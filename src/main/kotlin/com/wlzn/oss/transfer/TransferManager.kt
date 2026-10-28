package com.wlzn.oss.transfer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import com.wlzn.oss.config.OssSettings
import com.wlzn.oss.config.PasswordSafeSecretStore
import com.wlzn.oss.config.ConnectionAccess
import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageClients
import com.wlzn.oss.storage.StorageErrors
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock

@Service(Service.Level.APP)
class TransferManager : Disposable {
    private val settings = OssSettings.getInstance()
    private val tasks = linkedMapOf<String, TransferRecord>()
    private val controls = ConcurrentHashMap<String, TransferControl>()
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("WLZN OSS transfers", 2)
    private val engine = TransferEngine(CheckpointStore(Path.of(PathManager.getSystemPath(), "wlzn-oss-browser", "checkpoints")))
    private val secrets = PasswordSafeSecretStore()
    private var lastPersisted = 0L
    @Volatile private var disposed = false

    init {
        settings.state.transfers.forEach { saved ->
            val record = saved.copy()
            if (record.status in setOf(TransferStatus.RUNNING, TransferStatus.QUEUED)) {
                record.status = TransferStatus.PAUSED
                record.message = "IDE 重启后已暂停；点击继续从断点恢复"
            }
            tasks[record.id] = record
        }
    }

    @Synchronized
    fun records(): List<TransferRecord> = tasks.values.map { it.copy() }.reversed()

    @Synchronized
    fun hasActive(profileId: String): Boolean = tasks.values.any {
        it.profileId == profileId && (it.status in setOf(TransferStatus.RUNNING, TransferStatus.QUEUED) || controls.containsKey(it.id))
    }

    @Synchronized
    fun hasPending(profileId: String): Boolean = tasks.values.any {
        it.profileId == profileId && it.status !in setOf(TransferStatus.COMPLETED, TransferStatus.CANCELLED, TransferStatus.SKIPPED)
    }

    fun enqueue(profile: ConnectionProfile, jobs: List<TransferRecord>) {
      ConnectionAccess.configurationLock.withLock { synchronized(this) {
        require(settings.profiles().any { it.id == profile.id && it.revision == profile.revision }) { "连接配置已变化，请重新连接后建立任务" }
        check(!disposed) { "传输管理器已关闭" }
        val active = tasks.values.filter { it.status in setOf(TransferStatus.QUEUED, TransferStatus.RUNNING, TransferStatus.PAUSED, TransferStatus.FAILED) }
        require(active.size + jobs.size <= 10_000) { "待处理传输任务超过 10000，请分批传输或清理已取消任务" }
        val targets = active.map { target(it) }.toMutableSet()
        jobs.forEach { require(targets.add(target(it))) { "该目标已有未完成任务，请先继续或取消：${it.key}" } }
        jobs.forEach { incoming ->
            val record = incoming.copy(profileId = profile.id, profileName = profile.name, routeIdentity = profile.routeIdentity())
            tasks[record.id] = record
            schedule(record.id)
        }
        persist(true)
      } }
    }

    @Synchronized
    fun resume(id: String) {
        val job = tasks[id] ?: return
        require(!controls.containsKey(id)) { "任务正在停止，请稍后继续" }
        require(job.status in setOf(TransferStatus.PAUSED, TransferStatus.FAILED)) { "只有暂停或失败任务可以继续" }
        val collision = tasks.values.any { it.id != id && target(it) == target(job) && it.status in setOf(TransferStatus.QUEUED, TransferStatus.RUNNING) }
        require(!collision) { "该目标正在被另一个任务传输" }
        job.status = TransferStatus.QUEUED
        job.message = "等待继续"
        schedule(id)
        persist(true)
    }

    @Synchronized
    fun pause(id: String) {
        val job = tasks[id] ?: return
        val control = controls[id] ?: return
        control.pause()
        if (job.status == TransferStatus.QUEUED) {
            controls.remove(id, control)
            job.status = TransferStatus.PAUSED
            job.message = "已暂停"
        } else job.message = "正在暂停，已完成分片会保留"
        persist(true)
    }

    @Synchronized
    fun cancel(id: String) {
        val job = tasks[id] ?: return
        if (job.status in setOf(TransferStatus.COMPLETED, TransferStatus.CANCELLED, TransferStatus.SKIPPED)) return
        controls[id]?.let { it.cancel(); job.message = "正在取消并清理断点"; return }
        val control = TransferControl().apply { cancel() }
        controls[id] = control
        job.message = "等待清理断点及未完成分片"
        executor.submit { execute(id, control) }
    }

    @Synchronized
    fun clearFinished() {
        tasks.entries.removeIf { it.value.status in setOf(TransferStatus.COMPLETED, TransferStatus.CANCELLED, TransferStatus.SKIPPED) }
        persist(true)
    }

    private fun schedule(id: String) {
        val control = TransferControl()
        controls[id] = control
        executor.submit { execute(id, control) }
    }

    private fun execute(id: String, control: TransferControl) {
        val job = synchronized(this) {
            if (controls[id] !== control || disposed) return
            tasks[id]?.copy() ?: return
        }
        try {
            val profile = settings.profiles().firstOrNull { it.id == job.profileId }
                ?: throw StorageOperationException("连接配置已删除，请重新添加相同连接后再处理任务")
            require(profile.routeIdentity() == job.routeIdentity) { "连接区域或 Endpoint 已变化，请恢复原配置或新建任务" }
            val credentials = ConnectionAccess.credentials(profile, false)
            StorageClients.open(profile, credentials).use { client ->
                synchronized(this) { tasks[id]?.apply { status = TransferStatus.RUNNING; message = "正在传输" } }
                try {
                    val status = engine.run(client, job, TransferEngine.fingerprint(credentials.accessKeyId), control) { done, total ->
                        synchronized(this) { tasks[id]?.apply { transferredBytes = done; totalBytes = total }; persist() }
                    }
                    synchronized(this) { tasks[id]?.apply { this.status = status; message = if (status == TransferStatus.SKIPPED) "目标已存在，按选择跳过" else "传输完成" } }
                } catch (e: Exception) {
                    val stopped = if (e is TransferStopped) e else try { control.check(); null } catch (stop: TransferStopped) { stop }
                    if (stopped == null) throw e
                    if (stopped.cancelled) engine.discard(client, job)
                    synchronized(this) { tasks[id]?.apply {
                        status = if (stopped.cancelled) TransferStatus.CANCELLED else TransferStatus.PAUSED
                        message = if (stopped.cancelled) "已取消，断点已清理" else "已暂停，可继续"
                    } }
                }
            }
        } catch (e: Exception) {
            if (e !is IllegalArgumentException && e !is StorageOperationException &&
                e !is com.aliyun.oss.OSSException && e !is software.amazon.awssdk.services.s3.model.S3Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(TransferManager::class.java)
                    .warn("WLZN OSS transfer ${job.id} ${job.kind} failed\n${StorageErrors.diagnostic(e)}")
            }
            synchronized(this) { tasks[id]?.apply { status = TransferStatus.FAILED; message = StorageErrors.describe(e) } }
        } finally {
            controls.remove(id, control)
            synchronized(this) { persist(true) }
        }
    }

    private fun target(job: TransferRecord) = if (job.kind == TransferKind.DOWNLOAD) "local:${Path.of(job.localPath).toAbsolutePath().normalize()}"
        else "remote:${job.profileId}:${job.bucket}:${job.key}"

    private fun persist(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersisted < 2000) return
        lastPersisted = now
        val finished = tasks.values.filter { it.status in setOf(TransferStatus.COMPLETED, TransferStatus.CANCELLED, TransferStatus.SKIPPED) }.takeLast(500).map { it.id }.toSet()
        settings.saveTransfers(tasks.values.filter { it.status !in setOf(TransferStatus.COMPLETED, TransferStatus.CANCELLED, TransferStatus.SKIPPED) || it.id in finished })
    }

    override fun dispose() {
        disposed = true
        controls.values.forEach { it.pause() }
        synchronized(this) { persist(true) }
        executor.shutdownNow()
    }

    companion object { fun getInstance(): TransferManager = service() }
}
