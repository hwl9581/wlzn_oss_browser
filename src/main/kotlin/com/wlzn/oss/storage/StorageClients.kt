package com.wlzn.oss.storage

import com.aliyun.oss.ClientBuilderConfiguration
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.common.auth.DefaultCredentialProvider
import com.aliyun.oss.common.comm.SignVersion
import com.wlzn.oss.model.*
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

object StorageClients {
    private val hints = object : LinkedHashMap<String, MutableMap<String, String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<String, String>>) = size > 32
    }

    @Synchronized
    private fun locations(profile: ConnectionProfile, credentials: StorageCredentials): MutableMap<String, String> {
        val account = java.security.MessageDigest.getInstance("SHA-256").digest(credentials.accessKeyId.toByteArray()).joinToString("") { "%02x".format(it) }
        val key = "${profile.id}|${profile.revision}|${profile.routeIdentity()}|$account"
        return hints.getOrPut(key) { java.util.concurrent.ConcurrentHashMap() }
    }

    @Synchronized
    fun forgetRegions(profileId: String) { hints.keys.removeIf { it.startsWith("$profileId|") } }

    fun open(profile: ConnectionProfile, credentials: StorageCredentials): StorageClient {
        profile.validate()
        require(credentials.accessKeyId.isNotBlank() && credentials.accessKeySecret.isNotBlank()) { "请先在设置中保存 AccessKey 和 Secret" }
        val snapshot = profile.copy()
        val initial = snapshot.initialRegion()
        val catalogue = fixed(snapshot.copy(region = initial), credentials, snapshot.endpoint.isBlank())
        if (snapshot.endpoint.isNotBlank()) return catalogue
        return RegionalStorageClient(catalogue, initial, locations(snapshot, credentials)) { region ->
            fixed(snapshot.copy(region = region), credentials)
        }
    }

    private fun fixed(profile: ConnectionProfile, credentials: StorageCredentials, discoverRegions: Boolean = false): StorageClient {
        return when (profile.provider) {
            Provider.ALIYUN_OSS -> {
                val configuration = ClientBuilderConfiguration().apply {
                    signatureVersion = SignVersion.V4
                    connectionTimeout = 10_000
                    socketTimeout = 30_000
                    maxConnections = 4
                    maxErrorRetry = 1
                    isRedirectEnable = false
                    isSLDEnabled = profile.pathStyle
                }
                val provider = DefaultCredentialProvider(credentials.accessKeyId, credentials.accessKeySecret, credentials.sessionToken.ifBlank { null })
                AliyunStorageClient(OSSClientBuilder.create().endpoint(profile.endpointUrl().ifBlank { "https://oss-${profile.region}.aliyuncs.com" })
                    .region(profile.region).credentialsProvider(provider).clientConfiguration(configuration).build())
            }
            Provider.AMAZON_S3 -> {
                val auth = StaticCredentialsProvider.create(if (credentials.sessionToken.isBlank())
                    AwsBasicCredentials.create(credentials.accessKeyId, credentials.accessKeySecret) else
                    AwsSessionCredentials.create(credentials.accessKeyId, credentials.accessKeySecret, credentials.sessionToken))
                val builder = S3Client.builder().region(Region.of(profile.region)).credentialsProvider(auth)
                    .forcePathStyle(profile.pathStyle)
                    .crossRegionAccessEnabled(discoverRegions)
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(30)))
                val signer = S3Presigner.builder().region(Region.of(profile.region)).credentialsProvider(auth)
                    .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(profile.pathStyle).build())
                if (profile.endpoint.isNotBlank()) {
                    builder.endpointOverride(URI(profile.endpointUrl()))
                    signer.endpointOverride(URI(profile.endpointUrl()))
                }
                AmazonStorageClient(builder.build(), signer.build())
            }
        }
    }
}
