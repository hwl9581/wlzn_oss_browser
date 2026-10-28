package com.wlzn.oss.config

import com.google.gson.Gson
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.wlzn.oss.model.StorageCredentials

interface SecretStore {
    fun read(profileId: String): StorageCredentials?
    fun write(profileId: String, credentials: StorageCredentials?)
}

/** 调用方必须在后台、无读锁时访问 PasswordSafe。凭据不进入持久化 State。 */
class PasswordSafeSecretStore : SecretStore {
    private val gson = Gson()
    private data class SecretPayload(val secret: String = "", val sessionToken: String = "")
    private fun attributes(id: String) = CredentialAttributes("WLZN OSS Browser/$id")

    override fun read(profileId: String): StorageCredentials? {
        val credentials = PasswordSafe.instance.get(attributes(profileId)) ?: return null
        val payload = gson.fromJson(credentials.getPasswordAsString() ?: return null, SecretPayload::class.java) ?: return null
        return StorageCredentials(credentials.userName.orEmpty(), payload.secret, payload.sessionToken)
    }

    override fun write(profileId: String, credentials: StorageCredentials?) {
        val value = credentials?.let {
            Credentials(it.accessKeyId, gson.toJson(SecretPayload(it.accessKeySecret, it.sessionToken)))
        }
        PasswordSafe.instance.set(attributes(profileId), value)
    }
}
