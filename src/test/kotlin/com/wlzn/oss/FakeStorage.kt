package com.wlzn.oss

import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID

class FakeStorage : StorageClient {
    data class Stored(val bytes: ByteArray, val metadata: Map<String, String> = emptyMap())
    private data class Upload(val bucket: String, val key: String, val jobId: String, val parts: MutableMap<Int, ByteArray> = sortedMapOf())
    val objects = linkedMapOf<String, Stored>()
    private val uploads = mutableMapOf<String, Upload>()
    var uploadedParts = 0
    var afterPart: (() -> Unit)? = null
    var completeThenFail = false
    var failCopy = false
    var malformedRead = false
    var afterRead: (() -> Unit)? = null
    var deletes = 0
    var aborted = 0
    var lastReadOffset = -1L
    var conditionalComplete = false
    fun store(key: String, bytes: ByteArray, metadata: Map<String, String> = emptyMap()) { objects[key] = Stored(bytes, metadata) }
    private fun etag(bytes: ByteArray) = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    override fun close() {}
    override fun buckets() = listOf(BucketEntry("test-bucket"))
    override fun createBucket(name: String) {}
    override fun deleteBucket(name: String) {}
    override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean): ObjectPage = ObjectPage(objects.filterKeys { it.startsWith(prefix) && it != prefix }.map {
        ObjectEntry(it.key, it.value.bytes.size.toLong(), etag = etag(it.value.bytes), directory = it.key.endsWith('/'))
    })
    override fun head(bucket: String, key: String): ObjectDetails? = objects[key]?.let {
        ObjectDetails(it.bytes.size.toLong(), etag(it.bytes), Instant.EPOCH, userMetadata = it.metadata)
    }
    override fun read(bucket: String, key: String, offset: Long, etag: String?, expectedSize: Long?, length: Long?): InputStream {
        val bytes = objects.getValue(key).bytes
        require(etag == null || etag(bytes) == etag)
        lastReadOffset = offset
        val end = minOf(bytes.size.toLong(), offset + (length ?: bytes.size.toLong())).toInt()
        val result = bytes.copyOfRange(offset.toInt(), end)
        afterRead?.invoke()
        return ByteArrayInputStream(if (malformedRead) result + byteArrayOf(99) else result)
    }
    override fun put(bucket: String, key: String, input: InputStream, size: Long, contentType: String, forbidOverwrite: Boolean, expectedEtag: String?) {
        require(!forbidOverwrite || !objects.containsKey(key))
        if (expectedEtag != null) require(head(bucket, key)?.etag == expectedEtag)
        store(key, input.readBytes())
    }
    override fun replaceText(bucket: String, key: String, bytes: ByteArray, original: ObjectDetails) { require(head(bucket, key)?.etag == original.etag); store(key, bytes) }
    override fun delete(bucket: String, key: String) { objects.remove(key); deletes++ }
    override fun copy(bucket: String, key: String, targetBucket: String, targetKey: String, etag: String) {
        check(!failCopy) { "copy failed" }; require(head(bucket, key)?.etag == etag); objects[targetKey] = objects.getValue(key)
    }
    override fun signedUrl(bucket: String, key: String, duration: Duration) = "https://example.invalid/$bucket/$key"
    override fun acl(bucket: String, key: String) = ObjectAcl.PRIVATE
    override fun setAcl(bucket: String, key: String, acl: ObjectAcl) {}
    override fun updateMetadata(bucket: String, key: String, details: ObjectDetails) {}
    override fun restore(bucket: String, key: String) {}
    override fun multipartUploads(bucket: String, prefix: String, keyMarker: String?, uploadMarker: String?) = MultipartPage(emptyList())
    override fun beginUpload(bucket: String, key: String, contentType: String, jobId: String): String = UUID.randomUUID().toString().also { uploads[it] = Upload(bucket, key, jobId) }
    override fun parts(bucket: String, key: String, uploadId: String) = uploads.getValue(uploadId).parts.map { UploadPart(it.key, etag(it.value), it.value.size.toLong()) }
    override fun uploadPart(bucket: String, key: String, uploadId: String, number: Int, input: InputStream, size: Long): UploadPart {
        val bytes = input.readBytes(); require(bytes.size.toLong() == size)
        uploads.getValue(uploadId).parts[number] = bytes; uploadedParts++
        afterPart?.invoke()
        return UploadPart(number, etag(bytes), size)
    }
    override fun completeUpload(bucket: String, key: String, uploadId: String, parts: List<UploadPart>, forbidOverwrite: Boolean, expectedEtag: String?) {
        conditionalComplete = forbidOverwrite
        require(!forbidOverwrite || !objects.containsKey(key))
        val upload = uploads.remove(uploadId)!!
        val output = java.io.ByteArrayOutputStream()
        parts.forEach { output.write(upload.parts.getValue(it.number)) }
        store(key, output.toByteArray(), mapOf("wlzn-transfer-id" to upload.jobId))
        if (completeThenFail) throw java.io.IOException("simulated lost complete response")
    }
    override fun abortUpload(bucket: String, key: String, uploadId: String) { uploads.remove(uploadId); aborted++ }
}
