package com.wlzn.oss

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageClients
import com.wlzn.oss.storage.RegionalStorageClient
import com.wlzn.oss.transfer.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** 通过真实官方 SDK 请求本机 HTTP 服务，核对签名、请求和响应解析；不访问云账号。 */
class StorageSdkContractTest {
    private data class Request(val method: String, val path: String, val query: String, val headers: Map<String, String>) {
        fun header(name: String) = headers[name.lowercase()]
    }

    private class Endpoint(val provider: Provider) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        var ignoreRange = false
        var versioned = false
        var denyBucketHead = false
        var locationRedirect = false
        var emptyPartListing = false
        var missingObjectHead = false
        var truncatedParts = false
        val remoteRegion get() = if (provider == Provider.ALIYUN_OSS) "cn-shanghai" else "us-west-2"
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { handle(it) }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)

        private fun handle(exchange: HttpExchange) {
            val request = Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery.orEmpty(),
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") })
            requests.add(request)
            exchange.requestBody.use { it.readBytes() }
            val prefix = if (provider == Provider.ALIYUN_OSS) "x-oss" else "x-amz"
            val query = request.query.split('&').map { it.substringBefore('=') }.toSet()
            var status = 200
            var body = ""
            exchange.responseHeaders.add("$prefix-request-id", "unit-request")
            exchange.responseHeaders.add("ETag", "\"abc\"")
            exchange.responseHeaders.add("Content-Type", "application/xml")
            when {
                request.method == "HEAD" && request.path.trim('/').split('/').size == 1 -> {
                    exchange.responseHeaders.add("x-amz-bucket-region", remoteRegion)
                    if (denyBucketHead) status = 403
                }
                "location" in query -> if (locationRedirect) {
                    status = 301
                    body = "<Error><Code>PermanentRedirect</Code><Message>Use the bucket endpoint</Message><RequestId>unit-request</RequestId><HostId>unit-host</HostId><Endpoint>remote-bucket.oss-$remoteRegion.aliyuncs.com</Endpoint></Error>"
                } else body = "<LocationConstraint>oss-$remoteRegion</LocationConstraint>"
                request.method == "HEAD" -> {
                    if (missingObjectHead) { status = 404; exchange.responseHeaders.add("x-oss-error-code", "NoSuchKey") }
                    exchange.responseHeaders.set("Content-Length", "3")
                    exchange.responseHeaders.set("Content-Type", "text/plain")
                    exchange.responseHeaders.add("Last-Modified", "Tue, 27 Oct 2026 00:00:00 GMT")
                    exchange.responseHeaders.add("Cache-Control", "max-age=60")
                    exchange.responseHeaders.add("$prefix-meta-owner", "unit-owner")
                    exchange.responseHeaders.add("$prefix-storage-class", if (provider == Provider.ALIYUN_OSS) "IA" else "STANDARD_IA")
                }
                "versioning" in query -> body = "<VersioningConfiguration>${if (versioned) "<Status>Enabled</Status>" else ""}</VersioningConfiguration>"
                "acl" in query && request.method == "GET" -> body = if (provider == Provider.ALIYUN_OSS)
                    "<AccessControlPolicy><Owner><ID>owner</ID><DisplayName>owner</DisplayName></Owner><AccessControlList><Grant>public-read</Grant></AccessControlList></AccessControlPolicy>"
                else """<AccessControlPolicy><Owner><ID>owner</ID><DisplayName>owner</DisplayName></Owner><AccessControlList>
                    <Grant><Grantee xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="CanonicalUser"><ID>owner</ID></Grantee><Permission>FULL_CONTROL</Permission></Grant>
                    <Grant><Grantee xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="Group"><URI>http://acs.amazonaws.com/groups/global/AllUsers</URI></Grantee><Permission>READ</Permission></Grant>
                    </AccessControlList></AccessControlPolicy>"""
                "list-type" in query -> body = """<ListBucketResult><Name>unit-bucket</Name><Prefix></Prefix><MaxKeys>500</MaxKeys><KeyCount>2</KeyCount><IsTruncated>true</IsTruncated><NextContinuationToken>page2</NextContinuationToken>
                    <Contents><Key>a.txt</Key><LastModified>2026-10-27T00:00:00.000Z</LastModified><ETag>"abc"</ETag><Size>3</Size><StorageClass>STANDARD</StorageClass></Contents><CommonPrefixes><Prefix>folder/</Prefix></CommonPrefixes></ListBucketResult>"""
                request.method == "GET" && request.path == "/" -> body = """<ListAllMyBucketsResult><Owner><ID>owner</ID><DisplayName>owner</DisplayName></Owner><Buckets><Bucket><Name>unit-bucket</Name><CreationDate>2026-10-27T00:00:00.000Z</CreationDate><Location>oss-cn-hangzhou</Location></Bucket></Buckets></ListAllMyBucketsResult>"""
                request.method == "POST" && "uploads" in query -> body = "<InitiateMultipartUploadResult><Bucket>unit-bucket</Bucket><Key>a.txt</Key><UploadId>unit-upload</UploadId></InitiateMultipartUploadResult>"
                request.method == "GET" && "uploadId" in query -> body = if (emptyPartListing)
                    "<ListPartsResult><Bucket>unit-bucket</Bucket><Key>a.txt</Key><UploadId>unit-upload</UploadId><MaxParts>1000</MaxParts><IsTruncated>$truncatedParts</IsTruncated></ListPartsResult>"
                else """<ListPartsResult><Bucket>unit-bucket</Bucket><Key>a.txt</Key><UploadId>unit-upload</UploadId><MaxParts>1000</MaxParts><IsTruncated>false</IsTruncated><NextPartNumberMarker>1</NextPartNumberMarker><Part><PartNumber>1</PartNumber><LastModified>2026-10-27T00:00:00.000Z</LastModified><ETag>"abc"</ETag><Size>3</Size></Part></ListPartsResult>"""
                request.method == "POST" && "uploadId" in query -> body = "<CompleteMultipartUploadResult><Location>$url/unit-bucket/a.txt</Location><Bucket>unit-bucket</Bucket><Key>a.txt</Key><ETag>\"abc\"</ETag></CompleteMultipartUploadResult>"
                request.method == "PUT" && request.header("$prefix-copy-source") != null -> body = "<CopyObjectResult><LastModified>2026-10-27T00:00:00.000Z</LastModified><ETag>\"abc\"</ETag></CopyObjectResult>"
                request.method == "GET" -> {
                    body = "abc"
                    val range = request.header("range")
                    if (range != null && !ignoreRange) {
                        val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                        val end = range.substringAfter('-').toIntOrNull() ?: 2
                        body = body.substring(start, end + 1)
                        status = 206
                        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/3")
                    }
                }
                request.method == "DELETE" -> status = 204
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (request.method == "HEAD" || status == 204 || bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (request.method != "HEAD" && status != 204) it.write(bytes) }
        }
    }

    private fun withClient(provider: Provider, action: (Endpoint, com.wlzn.oss.storage.StorageClient) -> Unit) {
        Endpoint(provider).use { endpoint ->
            val profile = ConnectionProfile(name = "SDK unit test", provider = provider, endpoint = endpoint.url,
                region = if (provider == Provider.ALIYUN_OSS) "cn-hangzhou" else "us-east-1", pathStyle = true)
            StorageClients.open(profile, StorageCredentials("unit-test-key", "unit-test-secret", "unit-test-session")).use { action(endpoint, it) }
        }
    }

    @Test fun `OSS SDK signs lists reads and rejects ignored ranges`() = readContract(Provider.ALIYUN_OSS)
    @Test fun `S3 SDK signs lists reads and rejects ignored ranges`() = readContract(Provider.AMAZON_S3)
    @Test fun `OSS automatically routes object and presigned requests to bucket region`() = regionalContract(Provider.ALIYUN_OSS)
    @Test fun `S3 automatically routes object and presigned requests using HeadBucket region`() = regionalContract(Provider.AMAZON_S3)
    private fun regionalContract(provider: Provider) {
        Endpoint(provider).use { endpoint ->
            val initial = if (provider == Provider.ALIYUN_OSS) "cn-hangzhou" else "us-east-1"
            val credentials = StorageCredentials("unit-test-key", "unit-test-secret")
            val profile = ConnectionProfile(name = "固定本机模拟端点", provider = provider, endpoint = endpoint.url, region = initial, pathStyle = true)
            val opened = mutableListOf<String>()
            val catalogue = StorageClients.open(profile, credentials)
            RegionalStorageClient(catalogue, initial, mutableMapOf()) { region ->
                opened.add(region)
                StorageClients.open(profile.copy(region = region), credentials)
            }.use { client ->
                client.list("remote-bucket", "")
                assertEquals(listOf(endpoint.remoteRegion), opened)
                assertTrue(endpoint.requests.last().header("authorization").orEmpty().contains("/${endpoint.remoteRegion}/"))
                val signed = java.net.URLDecoder.decode(client.signedUrl("remote-bucket", "a.txt", Duration.ofMinutes(5)), Charsets.UTF_8)
                assertTrue(signed.contains("/${endpoint.remoteRegion}/"))
                client.head("remote-bucket", "a.txt")
                val discoveries = endpoint.requests.count { "location" in it.query || (it.method == "HEAD" && it.path == "/remote-bucket") }
                assertEquals(1, discoveries)
                assertEquals(initial, profile.region)
                assertFalse(endpoint.requests.any { it.method == "GET" && it.path == "/" })
            }
        }
    }

    @Test fun `S3 discovers bucket region from denied HeadBucket without guessing`() = withClient(Provider.AMAZON_S3) { endpoint, client ->
        endpoint.denyBucketHead = true
        assertEquals(endpoint.remoteRegion, client.bucketRegion("remote-bucket"))
    }
    @Test fun `OSS discovers bucket region from official redirect endpoint`() = withClient(Provider.ALIYUN_OSS) { endpoint, client ->
        endpoint.locationRedirect = true
        assertEquals(endpoint.remoteRegion, client.bucketRegion("remote-bucket"))
    }
    @Test fun `OSS new multipart upload accepts empty part listing without next marker`() = withClient(Provider.ALIYUN_OSS) { endpoint, client ->
        endpoint.emptyPartListing = true
        val id = client.beginUpload("unit-bucket", "a.txt", "image/jpeg", "new-upload")
        assertTrue(client.parts("unit-bucket", "a.txt", id).isEmpty())
    }
    @Test fun `OSS truncated part listing with no next marker fails clearly without null pointer`() = brokenPagination(Provider.ALIYUN_OSS)
    @Test fun `S3 truncated part listing with no next marker fails clearly without loop`() = brokenPagination(Provider.AMAZON_S3)
    private fun brokenPagination(provider: Provider) = withClient(provider) { endpoint, client ->
        endpoint.emptyPartListing = true; endpoint.truncatedParts = true
        val failure = assertThrows(IllegalArgumentException::class.java) { client.parts("unit-bucket", "a.txt", "unit-upload") }
        assertTrue(failure.message.orEmpty().contains("分页游标"))
    }

    @Test fun `OSS new image upload runs engine and SDK end to end with initially empty parts`() = newUpload(Provider.ALIYUN_OSS)
    @Test fun `S3 new image upload runs engine and SDK end to end with initially empty parts`() = newUpload(Provider.AMAZON_S3)
    private fun newUpload(provider: Provider) = withClient(provider) { endpoint, client ->
        endpoint.emptyPartListing = true; endpoint.missingObjectHead = true
        val root = java.nio.file.Files.createTempDirectory(java.io.File(System.getProperty("java.io.tmpdir")).canonicalFile.toPath(), "wlzn-sdk-upload-")
        try {
            val file = java.nio.file.Files.write(root.resolve("default.jpg"), byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte()))
            val job = TransferRecord(profileId = "sdk-test", routeIdentity = "local-contract", bucket = "unit-bucket", key = "share/appconf/test/message/default.jpg", localPath = file.toString())
            val progress = mutableListOf<Pair<Long, Long>>()
            val result = TransferEngine(CheckpointStore(root.resolve("checkpoints"))).run(client, job, "unit-account", TransferControl()) { done, total -> progress.add(done to total) }
            assertEquals(TransferStatus.COMPLETED, result)
            assertEquals(0L to 6L, progress.first()); assertEquals(6L to 6L, progress.last())
            assertTrue(endpoint.requests.any { it.method == "PUT" && "partNumber=" in it.query })
            assertTrue(endpoint.requests.any { it.method == "POST" && "uploadId=" in it.query })
            assertNull(CheckpointStore(root.resolve("checkpoints")).read(job.id))
        } finally {
            java.nio.file.Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach { path -> java.nio.file.Files.delete(path) } }
        }
    }
    private fun readContract(provider: Provider) = withClient(provider) { endpoint, client ->
        assertEquals("unit-bucket", client.buckets().single().name)
        val page = client.list("unit-bucket", "")
        assertEquals(listOf("folder/", "a.txt"), page.entries.map { it.key }); assertEquals("page2", page.nextToken)
        val details = client.head("unit-bucket", "a.txt")!!
        assertEquals(3L, details.size); assertEquals("abc", details.etag); assertEquals("unit-owner", details.userMetadata["owner"])
        assertEquals("abc", client.read("unit-bucket", "a.txt", expectedSize = 3).use { String(it.readBytes()) })
        assertEquals("bc", client.read("unit-bucket", "a.txt", 1, "abc", 3, 2).use { String(it.readBytes()) })
        endpoint.ignoreRange = true
        assertThrows(StorageOperationException::class.java) { client.read("unit-bucket", "a.txt", 1, "abc", 3, 2).close() }
        val prefix = if (provider == Provider.ALIYUN_OSS) "x-oss" else "x-amz"
        assertTrue(endpoint.requests.all { it.header("authorization").orEmpty().contains("unit-test-key") })
        assertTrue(endpoint.requests.all { it.header("$prefix-security-token") == "unit-test-session" })
        val signed = client.signedUrl("unit-bucket", "space 中文.txt", Duration.ofMinutes(5))
        assertTrue(signed.contains("Signature", ignoreCase = true)); assertFalse(signed.contains("unit-test-secret"))
    }

    @Test fun `OSS SDK preserves metadata ACL class and conditional multipart completion`() = writeContract(Provider.ALIYUN_OSS)
    @Test fun `S3 SDK preserves metadata ACL class and conditional multipart completion`() = writeContract(Provider.AMAZON_S3)
    private fun writeContract(provider: Provider) = withClient(provider) { endpoint, client ->
        val prefix = if (provider == Provider.ALIYUN_OSS) "x-oss" else "x-amz"
        client.put("unit-bucket", "new.txt", "abc".byteInputStream(), 3, "text/plain", true)
        val put = endpoint.requests.last()
        assertEquals(if (provider == Provider.ALIYUN_OSS) "true" else "*", put.header(if (provider == Provider.ALIYUN_OSS) "x-oss-forbid-overwrite" else "if-none-match"))
        assertEquals(ObjectAcl.PUBLIC_READ, client.acl("unit-bucket", "a.txt"))
        val original = client.head("unit-bucket", "a.txt")!!
        client.replaceText("unit-bucket", "a.txt", "new".toByteArray(), original)
        val edit = endpoint.requests.last()
        assertEquals("unit-owner", edit.header("$prefix-meta-owner"))
        assertEquals(if (provider == Provider.ALIYUN_OSS) "IA" else "STANDARD_IA", edit.header("$prefix-storage-class"))
        if (provider == Provider.ALIYUN_OSS) assertEquals("public-read", edit.header("x-oss-object-acl"))
        else { assertEquals("\"abc\"", edit.header("if-match")); assertTrue(edit.header("x-amz-grant-read").orEmpty().contains("AllUsers")) }
        client.copy("unit-bucket", "a.txt", "unit-bucket", "b.txt", "abc")
        val copy = endpoint.requests.last()
        assertNotNull(copy.header("$prefix-copy-source-if-match"))
        assertEquals(if (provider == Provider.ALIYUN_OSS) "IA" else "STANDARD_IA", copy.header("$prefix-storage-class"))
        client.updateMetadata("unit-bucket", "a.txt", original.copy(userMetadata = mapOf("new" to "value")))
        assertEquals("value", endpoint.requests.last().header("$prefix-meta-new"))
        val before = endpoint.requests.count { it.method == "PUT" }
        assertThrows(IllegalArgumentException::class.java) { client.updateMetadata("unit-bucket", "a.txt", original.copy(etag = "stale")) }
        assertEquals(before, endpoint.requests.count { it.method == "PUT" })
        val id = client.beginUpload("unit-bucket", "a.txt", "text/plain", "job")
        assertEquals("unit-upload", id)
        val part = client.uploadPart("unit-bucket", "a.txt", id, 1, "abc".byteInputStream(), 3)
        assertEquals(1, client.parts("unit-bucket", "a.txt", id).single().number)
        client.completeUpload("unit-bucket", "a.txt", id, listOf(part), true)
        assertEquals(if (provider == Provider.ALIYUN_OSS) "true" else "*", endpoint.requests.last().header(if (provider == Provider.ALIYUN_OSS) "x-oss-forbid-overwrite" else "if-none-match"))
        client.abortUpload("unit-bucket", "a.txt", id)
        if (provider == Provider.ALIYUN_OSS) {
            endpoint.versioned = true
            assertThrows(IllegalArgumentException::class.java) { client.put("unit-bucket", "blocked", "abc".byteInputStream(), 3, "text/plain", true) }
            assertFalse(endpoint.requests.any { it.path.endsWith("/blocked") })
        }
    }
}
