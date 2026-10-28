package com.wlzn.oss

import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageOperations
import com.wlzn.oss.storage.StorageErrors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class StorageOperationsTest {
    @get:Rule val temporary = TemporaryFolder(java.io.File(System.getProperty("java.io.tmpdir")).canonicalFile)

    @Test fun `unexpected transfer diagnostics retain code locations without exception secrets`() {
        val error = NullPointerException("secret-value-and-signed-url").apply { initCause(IllegalStateException("session-token-value")) }
        val diagnostic = StorageErrors.diagnostic(error)
        assertTrue(diagnostic.contains("NullPointerException")); assertTrue(diagnostic.contains("StorageOperationsTest"))
        assertFalse(diagnostic.contains("secret-value")); assertFalse(diagnostic.contains("session-token-value"))
        assertTrue(StorageErrors.describe(error).contains("插件内部异常"))
        assertFalse(StorageErrors.describe(error).contains("secret-value"))
    }

    @Test fun `download rejects traversal absolute paths drive names and ambiguous segments`() {
        val root = temporary.root.toPath()
        listOf("../escape", "/absolute", "a/../../escape", "a\\evil", "C:/windows", "a//b", "a/./b").forEach {
            assertThrows(IllegalArgumentException::class.java) { StorageOperations.safeDownloadPath(root, it) }
        }
        assertEquals(root.resolve("正常目录/文件.txt"), StorageOperations.safeDownloadPath(root, "正常目录/文件.txt"))
    }

    @Test fun `download rejects symlink outside selected directory`() {
        val root = temporary.newFolder("root").toPath(); val outside = temporary.newFolder("outside").toPath()
        Files.createSymbolicLink(root.resolve("link"), outside)
        assertThrows(IllegalArgumentException::class.java) { StorageOperations.safeDownloadPath(root, "link/private.txt") }
    }

    @Test fun `failed copy cannot delete source objects`() {
        val storage = FakeStorage().apply { failCopy = true; store("source/a.txt", byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) {
            StorageOperations.copyOrMove(storage, "bucket", listOf(ObjectEntry("source/", directory = true)), "", "bucket", "target/", true, false)
        }
        assertEquals(0, storage.deletes); assertTrue(storage.objects.containsKey("source/a.txt"))
    }

    @Test fun `target conflict is checked before batch mutation`() {
        val storage = FakeStorage().apply { store("a", byteArrayOf(1)); store("target/a", byteArrayOf(2)) }
        assertThrows(IllegalArgumentException::class.java) {
            StorageOperations.copyOrMove(storage, "bucket", listOf(ObjectEntry("a")), "", "bucket", "target/", false, false)
        }
        assertArrayEquals(byteArrayOf(2), storage.objects.getValue("target/a").bytes)
    }

    @Test fun `recursive copy into own subtree is rejected`() {
        val storage = FakeStorage()
        assertThrows(IllegalArgumentException::class.java) {
            StorageOperations.copyOrMove(storage, "bucket", listOf(ObjectEntry("folder/", directory = true)), "", "bucket", "folder/sub/", false, false)
        }
    }

    @Test fun `range response validation refuses ignored and wrong ranges`() {
        assertThrows(StorageOperationException::class.java) { StorageOperations.validateReadResponse(200, null, 10, 5, 10, 5) }
        assertThrows(StorageOperationException::class.java) { StorageOperations.validateReadResponse(206, "bytes 0-4/10", 5, 5, 10, 5) }
        assertThrows(StorageOperationException::class.java) { StorageOperations.validateReadResponse(206, "bytes 5-9/12", 5, 5, 10, 5) }
        StorageOperations.validateReadResponse(206, "bytes 5-9/10", 5, 5, 10, 5)
        StorageOperations.validateReadResponse(200, null, 10, 0, 10)
    }

    @Test fun `upload plans nested and empty folders without flattening`() {
        val root = temporary.newFolder("photos").toPath()
        Files.createDirectories(root.resolve("empty")); Files.write(root.resolve("a.txt"), byteArrayOf(1))
        val result = StorageOperations.uploadFiles(listOf(root), "uploads/")
        assertEquals(setOf("uploads/photos/empty/", "uploads/photos/a.txt"), result.map { it.second }.toSet())
    }

    @Test fun `upload refuses collisions and symlinks`() {
        val first = temporary.newFolder("one").toPath().resolve("a.txt"); Files.write(first, byteArrayOf(1))
        val second = temporary.newFolder("two").toPath().resolve("a.txt"); Files.write(second, byteArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) { StorageOperations.uploadFiles(listOf(first, second), "") }
        val link = temporary.root.toPath().resolve("link"); Files.createSymbolicLink(link, first)
        assertThrows(IllegalArgumentException::class.java) { StorageOperations.uploadFiles(listOf(link), "") }
    }

    @Test fun `credentials are redacted and profiles reject credential-bearing endpoints`() {
        assertFalse(StorageCredentials("private-key", "private-secret", "token").toString().contains("private-secret"))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProfile(name = "bad", endpoint = "https://user:password@example.com", region = "cn-hangzhou").validate()
        }
        ConnectionProfile(name = "AWS", provider = Provider.AMAZON_S3, region = "us-east-1").validate()
    }
}
