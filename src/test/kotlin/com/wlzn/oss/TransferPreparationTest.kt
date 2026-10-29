package com.wlzn.oss

import com.wlzn.oss.model.ObjectEntry
import com.wlzn.oss.model.TransferStatus
import com.wlzn.oss.storage.StorageOperations
import com.wlzn.oss.transfer.CheckpointStore
import com.wlzn.oss.transfer.TransferControl
import com.wlzn.oss.transfer.TransferEngine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TransferPreparationTest {
    @get:Rule val temporary = TemporaryFolder(java.io.File(System.getProperty("java.io.tmpdir")).canonicalFile)
    private fun download(storage: FakeStorage, root: Path, selection: List<ObjectEntry>) =
        StorageOperations.prepareDownload(storage, "demo", "bucket", "remote/", selection, root)

    @Test fun `existing download directory is used directly without another same named folder`() {
        val root = temporary.newFolder("Downloads").toPath()
        val storage = FakeStorage().apply { store("remote/report.txt", byteArrayOf(1, 2)) }
        val plan = download(storage, root, listOf(ObjectEntry("remote/report.txt")))
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(root.resolve("report.txt").toString(), plan.jobs.single().localPath)
        val engine = TransferEngine(CheckpointStore(temporary.root.toPath().resolve("checkpoints")))
        assertEquals(TransferStatus.COMPLETED, engine.run(storage, plan.jobs.single(), "demo", TransferControl()) { _, _ -> })
        assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(root.resolve("report.txt")))
        assertFalse(Files.exists(root.resolve("Downloads")))
    }

    @Test fun `unrelated files and existing directories do not count as overwrite conflicts`() {
        val root = temporary.newFolder("target").toPath()
        Files.write(root.resolve("unrelated.txt"), byteArrayOf(9))
        Files.createDirectories(root.resolve("photos"))
        val storage = FakeStorage().apply { store("remote/photos/a.jpg", byteArrayOf(1)); store("remote/photos/", byteArrayOf()) }
        val plan = download(storage, root, listOf(ObjectEntry("remote/photos/", directory = true)))
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(setOf(root.resolve("photos/a.jpg").toString(), root.resolve("photos").toString()), plan.jobs.map { it.localPath }.toSet())
        assertTrue(plan.jobs.none { it.overwrite })
    }

    @Test fun `download detects only actual nested file collisions`() {
        val root = temporary.newFolder("target").toPath()
        Files.createDirectories(root.resolve("photos"))
        Files.write(root.resolve("photos/a.jpg"), byteArrayOf(9))
        val storage = FakeStorage().apply { store("remote/photos/a.jpg", byteArrayOf(1)); store("remote/photos/b.jpg", byteArrayOf(2)) }
        val plan = download(storage, root, listOf(ObjectEntry("remote/photos/", directory = true)))
        assertEquals(setOf("remote/photos/a.jpg"), plan.conflicts)
        assertEquals(2, plan.jobs.size)
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(root.resolve("photos/a.jpg")))
    }

    @Test fun `directory downloads keep remote nesting and empty directories`() {
        val root = temporary.newFolder("target").toPath()
        val storage = FakeStorage().apply { store("remote/photos/nested/a.jpg", byteArrayOf(1)); store("remote/photos/empty/", byteArrayOf()) }
        val plan = download(storage, root, listOf(ObjectEntry("remote/photos/", directory = true)))
        val engine = TransferEngine(CheckpointStore(temporary.root.toPath().resolve("checkpoints")))
        plan.jobs.forEach { assertEquals(TransferStatus.COMPLETED, engine.run(storage, it, "demo", TransferControl()) { _, _ -> }) }
        assertTrue(Files.isRegularFile(root.resolve("photos/nested/a.jpg")))
        assertTrue(Files.isDirectory(root.resolve("photos/empty")))
    }

    @Test fun `files blocking destination folders fail before transfer`() {
        val root = temporary.newFolder("target").toPath()
        Files.write(root.resolve("photos"), byteArrayOf(9))
        val storage = FakeStorage().apply { store("remote/photos/a.jpg", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { download(storage, root, listOf(ObjectEntry("remote/photos/", directory = true))) }
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(root.resolve("photos")))
    }

    @Test fun `cloud file and descendant path conflicts fail the whole plan before creating local files`() {
        val root = temporary.newFolder("target").toPath()
        val storage = FakeStorage().apply { store("remote/photos", byteArrayOf(1)); store("remote/photos/a.jpg", byteArrayOf(2)) }
        assertThrows(IllegalArgumentException::class.java) {
            download(storage, root, listOf(ObjectEntry("remote/photos"), ObjectEntry("remote/photos/", directory = true)))
        }
        assertThrows(IllegalArgumentException::class.java) { download(storage, root, listOf(ObjectEntry("remote/", directory = true))) }
        Files.list(root).use { assertEquals(0L, it.count()) }
    }

    @Test fun `cloud file and empty folder cannot map to the same local name`() {
        val root = temporary.newFolder("target").toPath()
        val storage = FakeStorage().apply { store("remote/photos", byteArrayOf(1)); store("remote/photos/", byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            download(storage, root, listOf(ObjectEntry("remote/photos"), ObjectEntry("remote/photos/", directory = true)))
        }
        Files.list(root).use { assertEquals(0L, it.count()) }
    }

    @Test fun `new local collision after preflight is still skipped without overwriting`() {
        val root = temporary.newFolder("target").toPath()
        val storage = FakeStorage().apply { store("remote/a.txt", byteArrayOf(1)) }
        val plan = download(storage, root, listOf(ObjectEntry("remote/a.txt")))
        assertTrue(plan.conflicts.isEmpty())
        Files.write(root.resolve("a.txt"), byteArrayOf(9))
        val engine = TransferEngine(CheckpointStore(temporary.root.toPath().resolve("checkpoints")))
        assertEquals(TransferStatus.SKIPPED, engine.run(storage, plan.jobs.single(), "demo", TransferControl()) { _, _ -> })
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(root.resolve("a.txt")))
    }

    @Test fun `upload preflight ignores folder markers and only asks about matching object keys`() {
        val photos = temporary.newFolder("photos").toPath()
        Files.write(photos.resolve("a.jpg"), byteArrayOf(1))
        Files.write(photos.resolve("b.jpg"), byteArrayOf(2))
        val storage = FakeStorage().apply { store("remote/photos/", byteArrayOf()); store("remote/unrelated.txt", byteArrayOf(9)) }
        val first = StorageOperations.prepareUpload(storage, "demo", "bucket", "remote/", listOf(photos))
        assertTrue(first.conflicts.isEmpty())
        assertTrue(first.jobs.none { it.overwrite })
        storage.store("remote/photos/a.jpg", byteArrayOf(9))
        val second = StorageOperations.prepareUpload(storage, "demo", "bucket", "remote/", listOf(photos))
        assertEquals(setOf("remote/photos/a.jpg"), second.conflicts)
        assertArrayEquals(byteArrayOf(9), storage.objects.getValue("remote/photos/a.jpg").bytes)
    }

    @Test fun `upload of existing empty folder marker is not a file overwrite`() {
        val folder = temporary.newFolder("empty").toPath()
        val storage = FakeStorage().apply { store("remote/empty/", byteArrayOf()) }
        val plan = StorageOperations.prepareUpload(storage, "demo", "bucket", "remote/", listOf(folder))
        assertTrue(plan.conflicts.isEmpty())
        assertEquals("remote/empty/", plan.jobs.single().key)
    }
}
