package com.wlzn.oss.model

import java.net.URI
import java.time.Instant
import java.util.UUID

enum class Provider(val label: String) {
    ALIYUN_OSS("阿里云 OSS"), AMAZON_S3("Amazon S3");
    override fun toString() = label
}

data class ConnectionProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var provider: Provider = Provider.ALIYUN_OSS,
    var endpoint: String = "",
    var region: String = "",
    var bucket: String = "",
    var prefix: String = "",
    var pathStyle: Boolean = false,
    var revision: Long = 0
) {
    override fun toString() = "$name · ${provider.label}"

    fun validate() {
        require(name.isNotBlank()) { "连接名称不能为空" }
        require(region.isBlank() || region.matches(Regex("[a-z0-9-]+"))) { "Region 格式无效，例如 cn-hangzhou 或 us-east-1；可留空自动识别" }
        if (endpoint.isNotBlank()) {
            val uri = URI(endpointUrl())
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null &&
                uri.rawQuery == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/")) {
                "Endpoint 只填写 http(s)://主机[:端口]，不要包含 Bucket、路径或凭据"
            }
            if (provider == Provider.AMAZON_S3) {
                require(!Regex("[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+\\.amazonaws\\.com(?:\\.cn)?").matches(uri.host.lowercase())) {
                    "填写的是 AWS 区域域名，缺少 S3 服务前缀；请清空 Endpoint 自动选择，或填写 https://s3.<region>.amazonaws.com（中国区以 .com.cn 结尾）"
                }
            }
        }
    }

    fun initialRegion(): String = region.ifBlank {
        val host = endpointUrl().takeIf { it.isNotBlank() }?.let { URI(it).host }.orEmpty()
        val pattern = if (provider == Provider.ALIYUN_OSS) "oss-([a-z0-9-]+?)(?:-internal)?\\.aliyuncs\\.com"
            else "s3[.-]([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?"
        Regex(pattern).matchEntire(host)?.groupValues?.get(1)
            ?: if (provider == Provider.ALIYUN_OSS) "cn-hangzhou" else "us-east-1"
    }

    fun endpointUrl(): String = endpoint.trim().let {
        if (it.isEmpty() || it.startsWith("https://") || it.startsWith("http://")) it.trimEnd('/') else "https://${it.trimEnd('/')}"
    }

    fun routeIdentity(): String = listOf(provider.name, endpointUrl(), region, pathStyle.toString()).joinToString("|")
}

class StorageCredentials(val accessKeyId: String, val accessKeySecret: String, val sessionToken: String = "") {
    override fun toString() = "StorageCredentials([REDACTED])"
}

data class BucketEntry(val name: String, val region: String = "") {
    override fun toString() = name
}

data class ObjectEntry(
    val key: String,
    val size: Long = 0,
    val modified: Instant? = null,
    val etag: String = "",
    val storageClass: String = "",
    val directory: Boolean = false
) {
    fun displayName(prefix: String): String = key.removePrefix(prefix).removeSuffix("/")
}

data class ObjectPage(val entries: List<ObjectEntry>, val nextToken: String? = null)
data class ObjectDetails(
    val size: Long,
    val etag: String,
    val modified: Instant?,
    val contentType: String = "application/octet-stream",
    val cacheControl: String = "",
    val contentDisposition: String = "",
    val contentEncoding: String = "",
    val contentLanguage: String = "",
    val userMetadata: Map<String, String> = emptyMap(),
    val storageClass: String = ""
)

enum class ObjectAcl(val label: String) {
    DEFAULT("继承 Bucket（OSS）"), PRIVATE("私有"), PUBLIC_READ("公共读"),
    PUBLIC_READ_WRITE("公共读写（OSS）"), CUSTOM("自定义 ACL");
    override fun toString() = label
}

data class UploadPart(val number: Int, val etag: String, val size: Long)
data class MultipartEntry(val key: String, val uploadId: String, val initiated: Instant?)
data class MultipartPage(val entries: List<MultipartEntry>, val nextKey: String? = null, val nextUploadId: String? = null)

enum class TransferKind { UPLOAD, DOWNLOAD }
enum class TransferStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, SKIPPED }

data class TransferRecord(
    var id: String = UUID.randomUUID().toString(),
    var profileId: String = "",
    var profileName: String = "",
    var routeIdentity: String = "",
    var kind: TransferKind = TransferKind.UPLOAD,
    var bucket: String = "",
    var key: String = "",
    var localPath: String = "",
    var overwrite: Boolean = false,
    var status: TransferStatus = TransferStatus.QUEUED,
    var totalBytes: Long = 0,
    var transferredBytes: Long = 0,
    var message: String = "等待传输",
    var createdAt: Long = System.currentTimeMillis()
)

class StorageOperationException(message: String) : RuntimeException(message)
