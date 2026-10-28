package com.wlzn.oss.storage

import com.wlzn.oss.model.*
import java.io.InputStream
import java.time.Duration

/** 区域是 Bucket 的属性；账号配置和传输断点身份不随访问区域变化。 */
internal class RegionalStorageClient(
    private val catalogue: StorageClient,
    private val initialRegion: String,
    private val locations: MutableMap<String, String>,
    private val openRegion: (String) -> StorageClient
) : StorageClient by catalogue {
    private val clients = linkedMapOf(initialRegion to catalogue)

    override fun bucketRegion(bucket: String): String = (locations[bucket] ?: catalogue.bucketRegion(bucket)).also {
        require(it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)+"))) { "服务端未返回有效的 Bucket 区域，请手动指定 Region 和 Endpoint" }
        locations[bucket] = it
    }

    private fun region(region: String) = clients.getOrPut(region) { openRegion(region) }
    private fun bucket(bucket: String) = region(bucketRegion(bucket))

    override fun buckets(): List<BucketEntry> = catalogue.buckets().also { buckets ->
        buckets.filter { it.region.isNotBlank() }.forEach { locations[it.name] = it.region }
    }

    override fun createBucket(name: String) { throw StorageOperationException("创建 Bucket 需要单独选择区域") }
    override fun createBucket(name: String, region: String) {
        require(region.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)+"))) { "请输入有效的创建区域" }
        this.region(region).createBucket(name)
        locations[name] = region
    }
    override fun deleteBucket(name: String) { bucket(name).deleteBucket(name); locations.remove(name) }
    override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean) = bucket(bucket).list(bucket, prefix, token, recursive)
    override fun head(bucket: String, key: String) = bucket(bucket).head(bucket, key)
    override fun read(bucket: String, key: String, offset: Long, etag: String?, expectedSize: Long?, length: Long?) = bucket(bucket).read(bucket, key, offset, etag, expectedSize, length)
    override fun put(bucket: String, key: String, input: InputStream, size: Long, contentType: String, forbidOverwrite: Boolean, expectedEtag: String?) =
        bucket(bucket).put(bucket, key, input, size, contentType, forbidOverwrite, expectedEtag)
    override fun replaceText(bucket: String, key: String, bytes: ByteArray, original: ObjectDetails) = bucket(bucket).replaceText(bucket, key, bytes, original)
    override fun delete(bucket: String, key: String) = bucket(bucket).delete(bucket, key)
    override fun copy(bucket: String, key: String, targetBucket: String, targetKey: String, etag: String) {
        require(bucketRegion(bucket) == bucketRegion(targetBucket)) { "当前暂不支持跨区域复制/移动，请使用同区域目标 Bucket；来源未删除" }
        bucket(bucket).copy(bucket, key, targetBucket, targetKey, etag)
    }
    override fun signedUrl(bucket: String, key: String, duration: Duration) = bucket(bucket).signedUrl(bucket, key, duration)
    override fun acl(bucket: String, key: String) = bucket(bucket).acl(bucket, key)
    override fun setAcl(bucket: String, key: String, acl: ObjectAcl) = bucket(bucket).setAcl(bucket, key, acl)
    override fun updateMetadata(bucket: String, key: String, details: ObjectDetails) = bucket(bucket).updateMetadata(bucket, key, details)
    override fun restore(bucket: String, key: String) = bucket(bucket).restore(bucket, key)
    override fun multipartUploads(bucket: String, prefix: String, keyMarker: String?, uploadMarker: String?) = bucket(bucket).multipartUploads(bucket, prefix, keyMarker, uploadMarker)
    override fun beginUpload(bucket: String, key: String, contentType: String, jobId: String) = bucket(bucket).beginUpload(bucket, key, contentType, jobId)
    override fun parts(bucket: String, key: String, uploadId: String) = bucket(bucket).parts(bucket, key, uploadId)
    override fun uploadPart(bucket: String, key: String, uploadId: String, number: Int, input: InputStream, size: Long) = bucket(bucket).uploadPart(bucket, key, uploadId, number, input, size)
    override fun completeUpload(bucket: String, key: String, uploadId: String, parts: List<UploadPart>, forbidOverwrite: Boolean, expectedEtag: String?) =
        bucket(bucket).completeUpload(bucket, key, uploadId, parts, forbidOverwrite, expectedEtag)
    override fun abortUpload(bucket: String, key: String, uploadId: String) = bucket(bucket).abortUpload(bucket, key, uploadId)
    override fun close() {
        var failure: Exception? = null
        clients.values.forEach { try { it.close() } catch (e: Exception) { failure = failure ?: e } }
        failure?.let { throw it }
    }
}
