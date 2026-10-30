package com.wlzn.oss.storage

import com.aliyun.oss.OSS
import com.aliyun.oss.OSSException
import com.aliyun.oss.HttpMethod
import com.aliyun.oss.model.*
import com.wlzn.oss.model.*
import com.wlzn.oss.model.ObjectAcl
import java.io.InputStream
import java.time.Duration
import java.util.Date

class AliyunStorageClient(private val client: OSS) : StorageClient {
    private fun metadata(bucket: String, key: String): ObjectMetadata = client.getObjectMetadata(bucket, key).apply {
        // HTTP 头不区分大小写；SDK 对自定义元数据前缀的解析区分大小写，兼容代理规范化后的响应头。
        rawMetadata.entries.filter { it.key.startsWith("x-oss-meta-", true) }.toList().forEach {
            addUserMetadata(it.key.substring("x-oss-meta-".length).lowercase(), it.value.toString())
            removeHeader(it.key)
        }
    }
    override fun close() = client.shutdown()
    override fun buckets() = client.listBuckets().map { BucketEntry(it.name, it.region?.takeIf { region -> region.isNotBlank() } ?: it.location.orEmpty().removePrefix("oss-")) }
    override fun bucketRegion(bucket: String): String = try {
        client.getBucketLocation(bucket).removePrefix("oss-")
    } catch (e: OSSException) {
        // 旧端点可能以重定向错误返回真实端点；仅接受 OSS 官方域名，不跟随任意地址。
        val endpoint = e.errorFields?.get("Endpoint").orEmpty()
        val region = Regex("(?:${Regex.escape(bucket)}\\.)?oss-([a-z0-9-]+)\\.aliyuncs\\.com").matchEntire(endpoint)?.groupValues?.get(1)
        if (e.errorCode in setOf("PermanentRedirect", "AuthorizationHeaderMalformed", "IncorrectEndpoint") && region != null) region else throw e
    }
    override fun createBucket(name: String) { client.createBucket(CreateBucketRequest(name).apply { cannedACL = CannedAccessControlList.Private }) }
    override fun deleteBucket(name: String) { client.deleteBucket(name) }

    override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean): ObjectPage {
        val result = client.listObjectsV2(ListObjectsV2Request(bucket).apply {
            this.prefix = prefix
            continuationToken = token
            maxKeys = 500
            if (!recursive) delimiter = "/"
        })
        val entries = result.commonPrefixes.map { ObjectEntry(it, directory = true) } + result.objectSummaries
            .filter { it.key != prefix }.map {
                ObjectEntry(it.key, it.size, it.lastModified?.toInstant(), it.eTag.orEmpty(), it.storageClass.orEmpty(), it.key.endsWith('/'))
            }
        return ObjectPage(entries.distinctBy { it.key }.sortedWith(compareBy<ObjectEntry> { !it.directory }.thenBy { it.key }),
            result.nextContinuationToken?.takeIf { result.isTruncated })
    }

    override fun head(bucket: String, key: String): ObjectDetails? = try {
        metadata(bucket, key).let {
            ObjectDetails(it.contentLength, it.eTag.orEmpty(), it.lastModified?.toInstant(),
                it.contentType ?: "application/octet-stream", it.cacheControl.orEmpty(), it.contentDisposition.orEmpty(),
                it.contentEncoding.orEmpty(), it.rawMetadata["Content-Language"]?.toString().orEmpty(), it.userMetadata.orEmpty().toMap(),
                it.objectStorageClass?.toString().orEmpty())
        }
    } catch (e: OSSException) {
        if (e.errorCode == "NoSuchKey") null else throw e
    }

    override fun read(bucket: String, key: String, offset: Long, etag: String?, expectedSize: Long?, length: Long?): InputStream {
        val request = GetObjectRequest(bucket, key)
        if (offset > 0 || length != null) request.setRange(offset, length?.let { offset + it - 1 } ?: -1)
        if (!etag.isNullOrEmpty()) request.matchingETagConstraints = listOf(etag)
        val obj = client.getObject(request)
        try {
            StorageOperations.validateReadResponse(obj.response.statusCode,
                obj.objectMetadata.rawMetadata.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.toString(),
                obj.objectMetadata.contentLength, offset, expectedSize, length)
        } catch (e: Exception) { try { obj.close() } catch (_: Exception) { }; throw e }
        return object : java.io.FilterInputStream(obj.objectContent) {
            override fun close() = obj.close()
        }
    }

    private fun useForbidOverwrite(bucket: String, key: String, requested: Boolean): Boolean {
        if (!requested) return false
        val versioning = client.getBucketVersioning(bucket).status.orEmpty()
        if (versioning in setOf("Enabled", "Suspended")) {
            // 版本化 Bucket 忽略禁止覆盖请求头。沿用传输层的冲突选择，并在提交前再检查一次。
            // 此检查不是原子条件写；检查之后发生的并发写入仍可能被替换为本次上传。
            require(head(bucket, key) == null) { "上传目标已存在，未覆盖；请刷新后重新上传并选择覆盖或跳过" }
            return false
        }
        return true
    }

    override fun put(bucket: String, key: String, input: InputStream, size: Long, contentType: String, forbidOverwrite: Boolean, expectedEtag: String?) {
        val conditional = useForbidOverwrite(bucket, key, forbidOverwrite)
        val metadata = ObjectMetadata().apply { contentLength = size; this.contentType = contentType }
        client.putObject(PutObjectRequest(bucket, key, input, metadata).apply {
            if (conditional) addHeader("x-oss-forbid-overwrite", "true")
        })
    }

    override fun replaceText(bucket: String, key: String, bytes: ByteArray, original: ObjectDetails) {
        val current = metadata(bucket, key)
        require(current.eTag == original.etag) { "远端对象已修改，请重新打开" }
        val oldAcl = client.getObjectAcl(bucket, key).permission.toString()
        current.contentLength = bytes.size.toLong()
        current.userMetadata = current.userMetadata.filterKeys { it != "wlzn-transfer-id" }.toMutableMap()
        current.removeHeader("Content-MD5")
        client.putObject(PutObjectRequest(bucket, key, java.io.ByteArrayInputStream(bytes), current).apply { addHeader("x-oss-object-acl", oldAcl) })
    }

    override fun delete(bucket: String, key: String) { client.deleteObject(bucket, key) }

    override fun copy(bucket: String, key: String, targetBucket: String, targetKey: String, etag: String) {
        var permission = client.getObjectAcl(bucket, key).permission.toString()
        if (permission == "default" && bucket != targetBucket) permission = client.getBucketAcl(bucket).cannedACL.toString()
        val metadata = metadata(bucket, key)
        client.copyObject(CopyObjectRequest(bucket, key, targetBucket, targetKey).apply {
            matchingETagConstraints = listOf(etag)
            addHeader("x-oss-object-acl", permission)
            if (!metadata.serverSideEncryption.isNullOrBlank()) addHeader("x-oss-server-side-encryption", metadata.serverSideEncryption)
            if (!metadata.serverSideEncryptionKeyId.isNullOrBlank()) addHeader("x-oss-server-side-encryption-key-id", metadata.serverSideEncryptionKeyId)
            addHeader("x-oss-storage-class", metadata.objectStorageClass?.toString() ?: "Standard")
        })
    }

    override fun signedUrl(bucket: String, key: String, duration: Duration): String =
        client.generatePresignedUrl(GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET).apply {
            expiration = Date.from(java.time.Instant.now().plus(duration))
        }).toExternalForm()

    override fun acl(bucket: String, key: String): ObjectAcl = when (client.getObjectAcl(bucket, key).permission.toString()) {
        "default" -> ObjectAcl.DEFAULT
        "private" -> ObjectAcl.PRIVATE
        "public-read" -> ObjectAcl.PUBLIC_READ
        "public-read-write" -> ObjectAcl.PUBLIC_READ_WRITE
        else -> ObjectAcl.CUSTOM
    }

    override fun setAcl(bucket: String, key: String, acl: ObjectAcl) {
        val value = when (acl) {
            ObjectAcl.DEFAULT -> CannedAccessControlList.Default
            ObjectAcl.PRIVATE -> CannedAccessControlList.Private
            ObjectAcl.PUBLIC_READ -> CannedAccessControlList.PublicRead
            ObjectAcl.PUBLIC_READ_WRITE -> CannedAccessControlList.PublicReadWrite
            else -> throw StorageOperationException("请选择支持的 ACL")
        }
        client.setObjectAcl(bucket, key, value)
    }

    override fun updateMetadata(bucket: String, key: String, details: ObjectDetails) {
        val old = metadata(bucket, key)
        require(old.eTag == details.etag) { "元数据编辑期间对象已变化，请刷新后重试" }
        val metadata = old.apply {
            contentType = details.contentType
            cacheControl = details.cacheControl
            contentDisposition = details.contentDisposition
            contentEncoding = details.contentEncoding
            setHeader("Content-Language", details.contentLanguage)
            userMetadata = details.userMetadata.filterKeys { it != "wlzn-transfer-id" }.toMutableMap()
        }
        val oldAcl = client.getObjectAcl(bucket, key).permission
        client.copyObject(CopyObjectRequest(bucket, key, bucket, key).apply {
            newObjectMetadata = metadata
            matchingETagConstraints = listOf(details.etag)
            addHeader("x-oss-object-acl", oldAcl.toString())
        })
    }

    override fun restore(bucket: String, key: String) { client.restoreObject(bucket, key) }

    override fun multipartUploads(bucket: String, prefix: String, keyMarker: String?, uploadMarker: String?): MultipartPage {
        val result = client.listMultipartUploads(ListMultipartUploadsRequest(bucket).apply {
            this.prefix = prefix
            this.keyMarker = keyMarker
            uploadIdMarker = uploadMarker
            maxUploads = 500
        })
        return MultipartPage(result.multipartUploads.map { MultipartEntry(it.key, it.uploadId, it.initiated?.toInstant()) },
            result.nextKeyMarker?.takeIf { result.isTruncated }, result.nextUploadIdMarker?.takeIf { result.isTruncated })
    }

    override fun beginUpload(bucket: String, key: String, contentType: String, jobId: String): String =
        client.initiateMultipartUpload(InitiateMultipartUploadRequest(bucket, key).apply {
            objectMetadata = ObjectMetadata().apply {
                this.contentType = contentType
                addUserMetadata("wlzn-transfer-id", jobId)
            }
        }).uploadId

    override fun parts(bucket: String, key: String, uploadId: String): List<UploadPart> {
        val parts = mutableListOf<UploadPart>()
        var marker = 0
        do {
            val result = client.listParts(ListPartsRequest(bucket, key, uploadId).apply { if (marker > 0) partNumberMarker = marker; maxParts = 1000 })
            parts.addAll(result.parts.map { UploadPart(it.partNumber, it.eTag, it.size) })
            if (!result.isTruncated) break
            val next = result.nextPartNumberMarker
            require(next != null && next > marker && next <= 10000) { "OSS 分片分页游标缺失或未前进，已停止续传检查" }
            marker = next
        } while (true)
        return parts
    }

    override fun uploadPart(bucket: String, key: String, uploadId: String, number: Int, input: InputStream, size: Long): UploadPart {
        val result = client.uploadPart(UploadPartRequest(bucket, key, uploadId, number, input, size))
        return UploadPart(number, result.eTag, size)
    }

    override fun completeUpload(bucket: String, key: String, uploadId: String, parts: List<UploadPart>, forbidOverwrite: Boolean, expectedEtag: String?) {
        val conditional = useForbidOverwrite(bucket, key, forbidOverwrite)
        client.completeMultipartUpload(CompleteMultipartUploadRequest(bucket, key, uploadId,
            parts.map { PartETag(it.number, it.etag) }).apply { if (conditional) addHeader("x-oss-forbid-overwrite", "true") })
    }

    override fun abortUpload(bucket: String, key: String, uploadId: String) {
        client.abortMultipartUpload(AbortMultipartUploadRequest(bucket, key, uploadId))
    }
}
