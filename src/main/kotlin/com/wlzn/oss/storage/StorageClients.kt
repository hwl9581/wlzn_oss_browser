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
    fun open(profile: ConnectionProfile, credentials: StorageCredentials): StorageClient {
        profile.validate()
        require(credentials.accessKeyId.isNotBlank() && credentials.accessKeySecret.isNotBlank()) { "请先在设置中保存 AccessKey 和 Secret" }
        return when (profile.provider) {
            Provider.ALIYUN_OSS -> {
                val configuration = ClientBuilderConfiguration().apply {
                    signatureVersion = SignVersion.V4
                    connectionTimeout = 10_000
                    socketTimeout = 30_000
                    maxConnections = 4
                    maxErrorRetry = 1
                    isSLDEnabled = profile.pathStyle
                }
                val provider = DefaultCredentialProvider(credentials.accessKeyId, credentials.accessKeySecret, credentials.sessionToken.ifBlank { null })
                AliyunStorageClient(OSSClientBuilder.create().endpoint(profile.endpointUrl())
                    .region(profile.region).credentialsProvider(provider).clientConfiguration(configuration).build())
            }
            Provider.AMAZON_S3 -> {
                val auth = StaticCredentialsProvider.create(if (credentials.sessionToken.isBlank())
                    AwsBasicCredentials.create(credentials.accessKeyId, credentials.accessKeySecret) else
                    AwsSessionCredentials.create(credentials.accessKeyId, credentials.accessKeySecret, credentials.sessionToken))
                val builder = S3Client.builder().region(Region.of(profile.region)).credentialsProvider(auth)
                    .forcePathStyle(profile.pathStyle)
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
