package com.wlzn.oss.transfer

import com.google.gson.Gson
import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageClient
import com.wlzn.oss.storage.StorageOperations
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class TransferStopped(val cancelled: Boolean) : RuntimeException()
class TransferControl {
    private enum class Stop { PAUSED, CANCELLED }
    private val stop = AtomicReference<Stop?>(null)
    fun pause() { stop.compareAndSet(null, Stop.PAUSED) }
    fun cancel() { stop.set(Stop.CANCELLED) }
    fun check() { stop.get()?.let { throw TransferStopped(it == Stop.CANCELLED) }; if (Thread.currentThread().isInterrupted) throw TransferStopped(false) }
}

data class TransferCheckpoint(
    var jobId: String = "",
    var identity: String = "",
    var credentialFingerprint: String = "",
    var uploadId: String = "",
    var sourceHash: String = "",
    var sourceSize: Long = 0,
    var partSize: Long = 8L * 1024 * 1024,
    var etag: String = "",
    var destinationEtag: String? = null,
    var destinationStamp: String = "",
    var parts: MutableList<UploadPart> = mutableListOf()
)

class CheckpointStore(private val root: Path) {
    private val gson = Gson()
    private fun file(id: String): Path {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "任务 ID 无效" }
        return root.resolve("$id.json")
    }
    fun read(id: String): TransferCheckpoint? {
        val file = file(id)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(file) && Files.size(file) < 4 * 1024 * 1024) { "断点文件无效" }
        return Files.newBufferedReader(file).use { gson.fromJson(it, TransferCheckpoint::class.java) }
    }
    fun write(checkpoint: TransferCheckpoint) {
        StorageOperations.assertNoSymlinks(root)
        Files.createDirectories(root)
        try { Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------")) } catch (_: UnsupportedOperationException) { }
        val temporary = Files.createTempFile(root, "checkpoint-", ".tmp")
        try {
            try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")) } catch (_: UnsupportedOperationException) { }
            Files.writeString(temporary, gson.toJson(checkpoint))
            try { Files.move(temporary, file(checkpoint.jobId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file(checkpoint.jobId), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    fun delete(id: String) { Files.deleteIfExists(file(id)) }
}

class TransferEngine(private val checkpoints: CheckpointStore) {
    fun run(client: StorageClient, job: TransferRecord, credentialFingerprint: String, control: TransferControl,
            progress: (Long, Long) -> Unit): TransferStatus {
        control.check()
        return if (job.kind == TransferKind.UPLOAD) upload(client, job, credentialFingerprint, control, progress)
        else download(client, job, credentialFingerprint, control, progress)
    }

    private fun identity(job: TransferRecord) = listOf(job.profileId, job.routeIdentity, job.kind.name, job.bucket, job.key, job.localPath).joinToString("\n")
    private fun checkpoint(job: TransferRecord, fingerprint: String): TransferCheckpoint? = checkpoints.read(job.id)?.also {
        require(it.identity == identity(job) && it.credentialFingerprint == fingerprint) { "连接或凭据已变化，不能复用旧断点；请取消并新建任务" }
    }

    private fun upload(client: StorageClient, job: TransferRecord, fingerprint: String, control: TransferControl,
                       progress: (Long, Long) -> Unit): TransferStatus {
        val path = Path.of(job.localPath)
        StorageOperations.assertNoSymlinks(path)
        if (Files.isDirectory(path) && job.key.endsWith('/')) {
            if (!job.overwrite && client.head(job.bucket, job.key) != null) return TransferStatus.SKIPPED
            client.put(job.bucket, job.key, InputStream.nullInputStream(), 0, "application/x-directory", !job.overwrite)
            return TransferStatus.COMPLETED
        }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "本地文件不存在" }
        val size = Files.size(path)
        progress(0, size)
        var cp = checkpoint(job, fingerprint)
        val destination = client.head(job.bucket, job.key)
        if (cp != null && destination?.userMetadata?.get("wlzn-transfer-id") == job.id && destination.size == size) {
            checkpoints.delete(job.id); progress(size, size); return TransferStatus.COMPLETED
        }
        if (cp == null && destination != null && !job.overwrite) return TransferStatus.SKIPPED
        val sourceHash = sha256(path, control)
        if (cp != null) require(cp.sourceHash == sourceHash && cp.sourceSize == size) { "本地文件已变化，不能继续旧分片；请取消并新建任务" }
        if (size == 0L) {
            control.check()
            client.put(job.bucket, job.key, InputStream.nullInputStream(), 0, contentType(path), !job.overwrite, destination?.etag)
            progress(0, 0); return TransferStatus.COMPLETED
        }
        if (cp == null) {
            val partSize = maxOf(8L * 1024 * 1024, ((size + 9999) / 10000 + 1024 * 1024 - 1) / (1024 * 1024) * 1024 * 1024)
            require(partSize <= 5L * 1024 * 1024 * 1024) { "文件超过当前分片传输支持的大小" }
            cp = TransferCheckpoint(job.id, identity(job), fingerprint, sourceHash = sourceHash, sourceSize = size,
                partSize = partSize, destinationEtag = destination?.etag)
            cp.uploadId = client.beginUpload(job.bucket, job.key, contentType(path), job.id)
            try { checkpoints.write(cp) } catch (e: Exception) {
                try { client.abortUpload(job.bucket, job.key, cp.uploadId) } catch (_: Exception) { }
                throw e
            }
        }
        val count = ((size + cp.partSize - 1) / cp.partSize).toInt()
        require(cp.partSize >= 5L * 1024 * 1024 && count in 1..10000) { "断点分片参数无效" }
        val remote = client.parts(job.bucket, job.key, cp.uploadId).associateBy { it.number }
        // 仅复用本地已确认且与服务端 ETag/长度相符的分片，未确认的重传同一 partNumber。
        cp.parts = cp.parts.filter { part ->
            part.number in 1..count && remote[part.number]?.let { it.etag == part.etag && it.size == part.size } == true &&
                part.size == minOf(cp.partSize, size - (part.number - 1) * cp.partSize)
        }.distinctBy { it.number }.toMutableList()
        for (number in 1..count) {
            control.check()
            if (cp.parts.any { it.number == number }) continue
            val offset = (number - 1) * cp.partSize
            val length = minOf(cp.partSize, size - offset)
            val completed = cp.parts.sumOf { it.size }
            val part = FilePartInputStream(path, offset, length, control) { progress(completed + it, size) }.use {
                client.uploadPart(job.bucket, job.key, cp.uploadId, number, it, length)
            }
            cp.parts.add(part)
            checkpoints.write(cp)
            progress(cp.parts.sumOf { it.size }, size)
        }
        control.check()
        require(sha256(path, control) == sourceHash) { "上传期间本地文件被修改，保留断点但未合并，请取消后重新上传" }
        val currentDestination = client.head(job.bucket, job.key)
        require(currentDestination?.etag == cp.destinationEtag) { "目标对象在传输期间已变化，未覆盖；请刷新后新建任务" }
        try {
            client.completeUpload(job.bucket, job.key, cp.uploadId, cp.parts.sortedBy { it.number }, !job.overwrite, cp.destinationEtag)
        } catch (e: Exception) {
            val completed = try { client.head(job.bucket, job.key) } catch (_: Exception) { null }
            if (completed?.size != size || completed.userMetadata["wlzn-transfer-id"] != job.id) throw e
        }
        checkpoints.delete(job.id)
        progress(size, size)
        return TransferStatus.COMPLETED
    }

    private fun download(client: StorageClient, job: TransferRecord, fingerprint: String, control: TransferControl,
                         progress: (Long, Long) -> Unit): TransferStatus {
        val destination = Path.of(job.localPath).toAbsolutePath().normalize()
        StorageOperations.assertNoSymlinks(destination)
        if (job.key.endsWith('/')) { Files.createDirectories(destination); return TransferStatus.COMPLETED }
        val details = client.head(job.bucket, job.key) ?: throw StorageOperationException("云端对象不存在")
        var cp = checkpoint(job, fingerprint)
        if (cp == null && Files.exists(destination) && !job.overwrite) return TransferStatus.SKIPPED
        if (cp != null) require(cp.etag == details.etag && cp.sourceSize == details.size) { "云端对象已变化，不能拼接旧下载；请取消后重新下载" }
        if (cp == null) {
            cp = TransferCheckpoint(job.id, identity(job), fingerprint, sourceSize = details.size, etag = details.etag,
                destinationStamp = localStamp(destination))
            checkpoints.write(cp)
        }
        Files.createDirectories(destination.parent)
        val temporary = temporaryDownload(job)
        StorageOperations.assertNoSymlinks(temporary)
        var offset = if (Files.exists(temporary)) Files.size(temporary) else 0
        require(offset <= details.size) { "本地下载断点长度异常，请取消后重新下载" }
        progress(offset, details.size)
        while (offset < details.size) {
          val initialOffset = offset
          val rangeLength = minOf(8L * 1024 * 1024, details.size - offset)
          try {
          client.read(job.bucket, job.key, offset, details.etag, details.size, rangeLength).use { input ->
            FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    control.check()
                    val length = input.read(buffer)
                    if (length < 0) break
                    require(offset + length <= initialOffset + rangeLength) { "服务端返回的下载范围或长度不匹配，未替换目标文件" }
                    val bytes = ByteBuffer.wrap(buffer, 0, length)
                    while (bytes.hasRemaining()) output.write(bytes)
                    offset += length
                    progress(offset, details.size)
                }
                require(offset == initialOffset + rangeLength) { "下载响应不完整，未使用该片段" }
                output.force(true)
            }
        }
          } catch (error: Exception) {
            // 未完整结束的 HTTP 响应不能成为可信下载断点，回退到本次请求前的已知长度。
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(temporary))
                FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.truncate(initialOffset) }
            throw error
          }
        }
        if (details.size == 0L && !Files.exists(temporary)) Files.createFile(temporary)
        control.check()
        require(offset == details.size) { "下载不完整，保留断点供继续传输" }
        StorageOperations.assertNoSymlinks(destination)
        require(localStamp(destination) == cp.destinationStamp) { "本地目标已被修改，未覆盖；请另选目录或重新建立任务" }
        if (!job.overwrite || cp.destinationStamp == "absent") {
            try { Files.createLink(destination, temporary) }
            catch (exists: FileAlreadyExistsException) { throw exists }
            catch (_: UnsupportedOperationException) { throw StorageOperationException("目标文件系统无法安全提交禁止覆盖的下载，已保留完整断点；请选择支持硬链接的本地目录") }
            catch (_: java.io.IOException) { throw StorageOperationException("无法创建下载目标，已保留完整断点；请检查权限并使用支持硬链接的本地目录") }
            Files.delete(temporary)
        } else {
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
        }
        checkpoints.delete(job.id)
        progress(details.size, details.size)
        return TransferStatus.COMPLETED
    }

    fun discard(client: StorageClient, job: TransferRecord) {
        val cp = checkpoints.read(job.id)
        if (job.kind == TransferKind.UPLOAD && !cp?.uploadId.isNullOrEmpty()) {
            try { client.abortUpload(job.bucket, job.key, cp!!.uploadId) }
            catch (e: Exception) {
                val absent = (e is com.aliyun.oss.OSSException && e.errorCode == "NoSuchUpload") ||
                    (e is software.amazon.awssdk.services.s3.model.S3Exception && e.awsErrorDetails()?.errorCode() == "NoSuchUpload")
                if (!absent) throw e
            }
        }
        if (job.kind == TransferKind.DOWNLOAD) {
            val temporary = temporaryDownload(job)
            StorageOperations.assertNoSymlinks(temporary)
            Files.deleteIfExists(temporary)
        }
        checkpoints.delete(job.id)
    }

    private fun temporaryDownload(job: TransferRecord): Path {
        val destination = Path.of(job.localPath).toAbsolutePath().normalize()
        return destination.resolveSibling(".${destination.fileName}.wlzn-${job.id}.part")
    }
    private fun localStamp(path: Path) = if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) "absent" else
        "${Files.size(path)}:${Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()}"
    private fun contentType(path: Path) = Files.probeContentType(path) ?: "application/octet-stream"

    companion object {
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun sha256(path: Path, control: TransferControl): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) { control.check(); val length = input.read(buffer); if (length < 0) break; digest.update(buffer, 0, length) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** 可重读单个文件分片，供 SDK 的有限网络重试使用，不把整个分片载入内存。 */
class FilePartInputStream(path: Path, private val start: Long, private val length: Long,
                          private val control: TransferControl, private val progress: (Long) -> Unit) : InputStream() {
    private val channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    private var consumed = 0L
    private var marked = 0L
    init { channel.position(start) }
    override fun read(): Int { val bytes = ByteArray(1); return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255 }
    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        control.check()
        if (count == 0) return 0
        if (consumed >= length) return -1
        val read = channel.read(ByteBuffer.wrap(buffer, offset, minOf(count.toLong(), length - consumed).toInt()))
        if (read > 0) { consumed += read; progress(consumed) }
        return read
    }
    override fun markSupported() = true
    override fun mark(readlimit: Int) { marked = consumed }
    override fun reset() { consumed = marked; channel.position(start + marked); progress(consumed) }
    override fun close() = channel.close()
}
