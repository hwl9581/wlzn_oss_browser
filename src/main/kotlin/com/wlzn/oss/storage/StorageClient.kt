package com.wlzn.oss.storage

import com.wlzn.oss.model.*
import java.io.Closeable
import java.io.InputStream
import java.time.Duration

/** 只统一 OSS 与 S3 的实际共有操作；签名、请求和重试交给各官方 SDK。 */
interface StorageClient : Closeable {
    fun buckets(): List<BucketEntry>
    fun createBucket(name: String)
    fun deleteBucket(name: String)
    fun list(bucket: String, prefix: String, token: String? = null, recursive: Boolean = false): ObjectPage
    fun head(bucket: String, key: String): ObjectDetails?
    fun read(bucket: String, key: String, offset: Long = 0, etag: String? = null, expectedSize: Long? = null, length: Long? = null): InputStream
    fun put(bucket: String, key: String, input: InputStream, size: Long, contentType: String, forbidOverwrite: Boolean = false, expectedEtag: String? = null)
    fun replaceText(bucket: String, key: String, bytes: ByteArray, original: ObjectDetails)
    fun delete(bucket: String, key: String)
    fun copy(bucket: String, key: String, targetBucket: String, targetKey: String, etag: String)
    fun signedUrl(bucket: String, key: String, duration: Duration): String
    fun acl(bucket: String, key: String): ObjectAcl
    fun setAcl(bucket: String, key: String, acl: ObjectAcl)
    fun updateMetadata(bucket: String, key: String, details: ObjectDetails)
    fun restore(bucket: String, key: String)
    fun multipartUploads(bucket: String, prefix: String, keyMarker: String? = null, uploadMarker: String? = null): MultipartPage
    fun beginUpload(bucket: String, key: String, contentType: String, jobId: String): String
    fun parts(bucket: String, key: String, uploadId: String): List<UploadPart>
    fun uploadPart(bucket: String, key: String, uploadId: String, number: Int, input: InputStream, size: Long): UploadPart
    fun completeUpload(bucket: String, key: String, uploadId: String, parts: List<UploadPart>, forbidOverwrite: Boolean = false, expectedEtag: String? = null)
    fun abortUpload(bucket: String, key: String, uploadId: String)
}
