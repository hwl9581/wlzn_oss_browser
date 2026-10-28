package com.wlzn.oss

import com.wlzn.oss.model.*
import com.wlzn.oss.storage.RegionalStorageClient
import com.wlzn.oss.storage.StorageClient
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration

class RegionalStorageClientTest {
    @Test fun `bucket catalogue hints route transfers and links without additional discovery`() {
        val east = FakeStorage(); val west = FakeStorage()
        east.store("east.txt", "east".toByteArray()); west.store("west.txt", "west".toByteArray())
        val opened = mutableListOf<String>(); var closed = 0
        val catalogue = object : StorageClient by east {
            override fun buckets() = listOf(BucketEntry("east-bucket", "east-1"), BucketEntry("west-bucket", "west-2"))
            override fun bucketRegion(bucket: String): String = error("catalogue hints should avoid discovery")
            override fun close() { closed++ }
        }
        RegionalStorageClient(catalogue, "east-1", mutableMapOf()) { region ->
            opened.add(region)
            object : StorageClient by west { override fun close() { closed++ } }
        }.use { client ->
            assertEquals(2, client.buckets().size)
            assertNotNull(client.head("east-bucket", "east.txt")); assertNull(client.head("east-bucket", "west.txt"))
            assertNotNull(client.head("west-bucket", "west.txt"))
            client.put("west-bucket", "new", "x".byteInputStream(), 1, "text/plain")
            assertTrue(west.objects.containsKey("new")); assertFalse(east.objects.containsKey("new"))
            val upload = client.beginUpload("west-bucket", "multi", "text/plain", "job")
            val part = client.uploadPart("west-bucket", "multi", upload, 1, "abc".byteInputStream(), 3)
            assertEquals(1, client.parts("west-bucket", "multi", upload).size)
            client.completeUpload("west-bucket", "multi", upload, listOf(part), true)
            assertArrayEquals("abc".toByteArray(), client.read("west-bucket", "multi").use { it.readBytes() })
            client.signedUrl("west-bucket", "multi", Duration.ofMinutes(5))
            assertThrows(IllegalArgumentException::class.java) { client.copy("east-bucket", "east.txt", "west-bucket", "target", "etag") }
            assertFalse(west.objects.containsKey("target")); assertEquals(0, east.deletes)
            assertEquals(listOf("west-2"), opened)
        }
        assertEquals(2, closed)
    }

    @Test fun `new bucket needs explicit region while account remains automatic`() {
        val created = mutableListOf<String>()
        val initial = FakeStorage()
        RegionalStorageClient(initial, "east-1", mutableMapOf()) { region ->
            object : StorageClient by FakeStorage() { override fun createBucket(name: String) { created.add("$region/$name") } }
        }.use { client ->
            assertThrows(StorageOperationException::class.java) { client.createBucket("new-bucket") }
            client.createBucket("new-bucket", "west-2")
            assertEquals(listOf("west-2/new-bucket"), created)
            assertEquals("west-2", client.bucketRegion("new-bucket"))
        }
    }

    @Test fun `unknown bucket discovery failure never falls back to initial region`() {
        var opened = false
        val catalogue = object : StorageClient by FakeStorage() {
            override fun bucketRegion(bucket: String): String = throw StorageOperationException("Access denied")
        }
        RegionalStorageClient(catalogue, "east-1", mutableMapOf()) { opened = true; FakeStorage() }.use { client ->
            assertThrows(StorageOperationException::class.java) { client.list("unknown", "") }
            assertFalse(opened)
        }
    }
}
