package com.wlzn.oss.config

import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageClient
import com.wlzn.oss.storage.StorageClients
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 只锁住连接元数据和凭据的快照读取/替换，不在锁中执行云端请求。 */
object ConnectionAccess {
    val configurationLock = ReentrantLock()

    fun credentials(profile: ConnectionProfile, checkRevision: Boolean = true): StorageCredentials = configurationLock.withLock {
        val current = OssSettings.getInstance().profiles().firstOrNull { it.id == profile.id }
            ?: throw StorageOperationException("连接已删除，请重新选择")
        require(current.routeIdentity() == profile.routeIdentity() && (!checkRevision || current.revision == profile.revision)) {
            "连接或凭据配置已变化，请重新连接"
        }
        PasswordSafeSecretStore().read(profile.id) ?: throw StorageOperationException("请先在设置中保存连接凭据")
    }

    fun open(profile: ConnectionProfile): StorageClient = StorageClients.open(profile, credentials(profile))
}
