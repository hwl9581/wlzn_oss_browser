package com.wlzn.oss.storage

import com.wlzn.oss.model.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.InputStream
import java.time.Duration

class AmazonStorageClient(private val client: S3Client, private val signer: S3Presigner) : StorageClient {
    private fun expires(raw: HeadObjectResponse) = raw.expiresString()?.takeIf { it.isNotBlank() }?.let {
        java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }
    override fun close() { try { client.close() } finally { signer.close() } }
    override fun buckets() = client.listBuckets().buckets().map { BucketEntry(it.name()) }
    override fun createBucket(name: String) {
        val region = client.serviceClientConfiguration().region().id()
        val request = CreateBucketRequest.builder().bucket(name)
        if (region != "us-east-1") request.createBucketConfiguration { it.locationConstraint(region) }
        client.createBucket(request.build())
    }
    override fun deleteBucket(name: String) { client.deleteBucket { it.bucket(name) } }

    override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean): ObjectPage {
        val request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(token).maxKeys(500)
        if (!recursive) request.delimiter("/")
        val result = client.listObjectsV2(request.build())
        val entries = result.commonPrefixes().map { ObjectEntry(it.prefix(), directory = true) } + result.contents()
            .filter { it.key() != prefix }.map {
                ObjectEntry(it.key(), it.size(), it.lastModified(), it.eTag().orEmpty().trim('"'), it.storageClassAsString().orEmpty(), it.key().endsWith('/'))
            }
        return ObjectPage(entries.distinctBy { it.key }.sortedWith(compareBy<ObjectEntry> { !it.directory }.thenBy { it.key }),
            result.nextContinuationToken()?.takeIf { result.isTruncated == true })
    }

    override fun head(bucket: String, key: String): ObjectDetails? = try {
        client.headObject { it.bucket(bucket).key(key) }.let {
            ObjectDetails(it.contentLength(), it.eTag().orEmpty().trim('"'), it.lastModified(), it.contentType() ?: "application/octet-stream",
                it.cacheControl().orEmpty(), it.contentDisposition().orEmpty(), it.contentEncoding().orEmpty(), it.contentLanguage().orEmpty(),
                it.metadata().toMap(), it.storageClassAsString().orEmpty())
        }
    } catch (e: S3Exception) { if (e.statusCode() == 404) null else throw e }

    override fun read(bucket: String, key: String, offset: Long, etag: String?, expectedSize: Long?, length: Long?): InputStream {
        val request = GetObjectRequest.builder().bucket(bucket).key(key)
        if (offset > 0 || length != null) request.range("bytes=$offset-${length?.let { offset + it - 1 } ?: ""}")
        if (!etag.isNullOrEmpty()) request.ifMatch("\"$etag\"")
        val stream = client.getObject(request.build())
        try {
            StorageOperations.validateReadResponse(stream.response().sdkHttpResponse().statusCode(), stream.response().contentRange(),
                stream.response().contentLength(), offset, expectedSize, length)
        } catch (e: Exception) { try { stream.close() } catch (_: Exception) { }; throw e }
        return stream
    }

    override fun put(bucket: String, key: String, input: InputStream, size: Long, contentType: String, forbidOverwrite: Boolean, expectedEtag: String?) {
        val request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
        if (forbidOverwrite) request.ifNoneMatch("*") else if (expectedEtag != null) request.ifMatch("\"$expectedEtag\"")
        client.putObject(request.build(), RequestBody.fromInputStream(input, size))
    }

    private fun grants(bucket: String, key: String): Map<Permission, String> {
        val result = client.getObjectAcl { it.bucket(bucket).key(key) }
        if (result.grants().all { it.grantee().id() == result.owner().id() }) return emptyMap()
        return result.grants().groupBy { it.permission() }.mapValues { (_, items) -> items.joinToString(",") { grant ->
            val grantee = grant.grantee()
            when {
                !grantee.id().isNullOrBlank() -> "id=\"${grantee.id()}\""
                !grantee.uri().isNullOrBlank() -> "uri=\"${grantee.uri()}\""
                else -> throw StorageOperationException("此对象包含无法保留的 ACL 授权，请使用 AWS 控制台操作")
            }
        } }
    }

    override fun replaceText(bucket: String, key: String, bytes: ByteArray, original: ObjectDetails) {
        val raw = client.headObject { it.bucket(bucket).key(key) }
        require(raw.eTag().trim('"') == original.etag) { "远端对象已修改，请重新打开" }
        val grants = grants(bucket, key)
        val request = PutObjectRequest.builder().bucket(bucket).key(key).ifMatch(raw.eTag())
            .contentType(raw.contentType()).cacheControl(raw.cacheControl()).contentDisposition(raw.contentDisposition())
            .contentEncoding(raw.contentEncoding()).contentLanguage(raw.contentLanguage()).expires(expires(raw))
            .metadata(raw.metadata().filterKeys { it != "wlzn-transfer-id" })
            .serverSideEncryption(raw.serverSideEncryption()).ssekmsKeyId(raw.ssekmsKeyId()).bucketKeyEnabled(raw.bucketKeyEnabled())
            .storageClass(raw.storageClass() ?: StorageClass.STANDARD)
        grants[Permission.FULL_CONTROL]?.let { request.grantFullControl(it) }
        grants[Permission.READ]?.let { request.grantRead(it) }
        grants[Permission.READ_ACP]?.let { request.grantReadACP(it) }
        grants[Permission.WRITE_ACP]?.let { request.grantWriteACP(it) }
        require(!grants.containsKey(Permission.WRITE)) { "此对象 ACL 不支持安全保留" }
        client.putObject(request.build(), RequestBody.fromBytes(bytes))
    }
    override fun delete(bucket: String, key: String) { client.deleteObject { it.bucket(bucket).key(key) } }

    override fun copy(bucket: String, key: String, targetBucket: String, targetKey: String, etag: String) {
        val raw = client.headObject { it.bucket(bucket).key(key) }
        val grants = grants(bucket, key)
        val request = CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(key)
            .destinationBucket(targetBucket).destinationKey(targetKey).copySourceIfMatch("\"$etag\"")
            .serverSideEncryption(raw.serverSideEncryption()).ssekmsKeyId(raw.ssekmsKeyId()).bucketKeyEnabled(raw.bucketKeyEnabled())
            .storageClass(raw.storageClass() ?: StorageClass.STANDARD)
        grants[Permission.FULL_CONTROL]?.let { request.grantFullControl(it) }
        grants[Permission.READ]?.let { request.grantRead(it) }
        grants[Permission.READ_ACP]?.let { request.grantReadACP(it) }
        grants[Permission.WRITE_ACP]?.let { request.grantWriteACP(it) }
        require(!grants.containsKey(Permission.WRITE)) { "此对象 ACL 不支持安全保留" }
        client.copyObject(request.build())
    }

    override fun signedUrl(bucket: String, key: String, duration: Duration): String = signer.presignGetObject(
        GetObjectPresignRequest.builder().signatureDuration(duration).getObjectRequest { it.bucket(bucket).key(key) }.build()
    ).url().toExternalForm()

    override fun acl(bucket: String, key: String): ObjectAcl {
        val result = client.getObjectAcl { it.bucket(bucket).key(key) }
        val publicGrants = result.grants().filter { it.grantee().uri() == "http://acs.amazonaws.com/groups/global/AllUsers" }
        if (publicGrants.any { it.permission() == Permission.WRITE || it.permission() == Permission.FULL_CONTROL }) return ObjectAcl.CUSTOM
        val hasCustom = result.grants().any {
            it.grantee().id() != result.owner().id() && it.grantee().uri() != "http://acs.amazonaws.com/groups/global/AllUsers"
        }
        return if (hasCustom) ObjectAcl.CUSTOM else if (publicGrants.any { it.permission() == Permission.READ }) ObjectAcl.PUBLIC_READ else ObjectAcl.PRIVATE
    }

    override fun setAcl(bucket: String, key: String, acl: ObjectAcl) {
        val value = when (acl) {
            ObjectAcl.PRIVATE -> ObjectCannedACL.PRIVATE
            ObjectAcl.PUBLIC_READ -> ObjectCannedACL.PUBLIC_READ
            else -> throw StorageOperationException("S3 支持私有或公共读；BucketOwnerEnforced 或公共访问阻止策略可能禁止修改 ACL")
        }
        client.putObjectAcl { it.bucket(bucket).key(key).acl(value) }
    }

    override fun updateMetadata(bucket: String, key: String, details: ObjectDetails) {
        val current = head(bucket, key) ?: throw StorageOperationException("对象不存在")
        require(current.etag == details.etag) { "元数据编辑期间对象已变化，请刷新后重试" }
        val raw = client.headObject { it.bucket(bucket).key(key) }
        val grants = grants(bucket, key)
        val request = CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(key).destinationBucket(bucket).destinationKey(key)
            .copySourceIfMatch("\"${details.etag}\"").metadataDirective(MetadataDirective.REPLACE)
            .metadata(details.userMetadata.filterKeys { it != "wlzn-transfer-id" }).contentType(details.contentType)
            .cacheControl(details.cacheControl).contentDisposition(details.contentDisposition)
            .contentEncoding(details.contentEncoding).contentLanguage(details.contentLanguage).expires(expires(raw))
            .serverSideEncryption(raw.serverSideEncryption()).ssekmsKeyId(raw.ssekmsKeyId()).bucketKeyEnabled(raw.bucketKeyEnabled())
            .storageClass(raw.storageClass() ?: StorageClass.STANDARD)
        grants[Permission.FULL_CONTROL]?.let { request.grantFullControl(it) }
        grants[Permission.READ]?.let { request.grantRead(it) }
        grants[Permission.READ_ACP]?.let { request.grantReadACP(it) }
        grants[Permission.WRITE_ACP]?.let { request.grantWriteACP(it) }
        require(!grants.containsKey(Permission.WRITE)) { "此对象 ACL 不支持安全保留" }
        client.copyObject(request.build())
    }

    override fun restore(bucket: String, key: String) {
        client.restoreObject { it.bucket(bucket).key(key).restoreRequest { r -> r.days(1).glacierJobParameters { p -> p.tier(Tier.STANDARD) } } }
    }

    override fun multipartUploads(bucket: String, prefix: String, keyMarker: String?, uploadMarker: String?): MultipartPage {
        val result = client.listMultipartUploads { it.bucket(bucket).prefix(prefix).keyMarker(keyMarker).uploadIdMarker(uploadMarker).maxUploads(500) }
        return MultipartPage(result.uploads().map { MultipartEntry(it.key(), it.uploadId(), it.initiated()) },
            result.nextKeyMarker()?.takeIf { result.isTruncated == true }, result.nextUploadIdMarker()?.takeIf { result.isTruncated == true })
    }

    override fun beginUpload(bucket: String, key: String, contentType: String, jobId: String): String = client.createMultipartUpload {
        it.bucket(bucket).key(key).contentType(contentType).metadata(mapOf("wlzn-transfer-id" to jobId))
    }.uploadId()

    override fun parts(bucket: String, key: String, uploadId: String): List<UploadPart> {
        val parts = mutableListOf<UploadPart>()
        var marker: Int? = null
        do {
            val result = client.listParts { it.bucket(bucket).key(key).uploadId(uploadId).partNumberMarker(marker).maxParts(1000) }
            parts.addAll(result.parts().map { UploadPart(it.partNumber(), it.eTag(), it.size()) })
            marker = result.nextPartNumberMarker()
        } while (result.isTruncated == true)
        return parts
    }

    override fun uploadPart(bucket: String, key: String, uploadId: String, number: Int, input: InputStream, size: Long): UploadPart {
        val result = client.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key).uploadId(uploadId).partNumber(number).build(),
            RequestBody.fromInputStream(input, size))
        return UploadPart(number, result.eTag(), size)
    }

    override fun completeUpload(bucket: String, key: String, uploadId: String, parts: List<UploadPart>, forbidOverwrite: Boolean, expectedEtag: String?) {
        client.completeMultipartUpload { it.bucket(bucket).key(key).uploadId(uploadId).apply {
            if (forbidOverwrite) ifNoneMatch("*") else if (expectedEtag != null) ifMatch("\"$expectedEtag\"")
        }.multipartUpload { upload ->
            upload.parts(parts.map { part -> CompletedPart.builder().partNumber(part.number).eTag(part.etag).build() })
        } }
    }

    override fun abortUpload(bucket: String, key: String, uploadId: String) {
        client.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(uploadId) }
    }
}
