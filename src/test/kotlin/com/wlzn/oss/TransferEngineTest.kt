package com.wlzn.oss

import com.wlzn.oss.model.*
import com.wlzn.oss.transfer.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TransferEngineTest {
    @get:Rule val temporary = TemporaryFolder(java.io.File(System.getProperty("java.io.tmpdir")).canonicalFile)
    private fun engine() = TransferEngine(CheckpointStore(temporary.root.toPath().resolve("checkpoints")))
    private fun source(size: Int): Path = temporary.newFile().toPath().also { Files.write(it, ByteArray(size) { index -> (index % 251).toByte() }) }
    private fun job(path: Path, kind: TransferKind = TransferKind.UPLOAD) = TransferRecord(profileId = "test", routeIdentity = "fake-route", kind = kind,
        bucket = "test-bucket", key = "folder/data.bin", localPath = path.toString())

    @Test fun `multipart upload completes with conditional no overwrite`() {
        val storage = FakeStorage(); val path = source(9 * 1024 * 1024); val job = job(path)
        assertEquals(TransferStatus.COMPLETED, engine().run(storage, job, "fingerprint", TransferControl()) { _, _ -> })
        assertArrayEquals(Files.readAllBytes(path), storage.objects.getValue(job.key).bytes)
        assertEquals(2, storage.uploadedParts); assertTrue(storage.conditionalComplete)
    }

    @Test fun `paused multipart resumes only remaining parts after restart`() {
        val storage = FakeStorage(); val path = source(9 * 1024 * 1024); val job = job(path); val control = TransferControl()
        storage.afterPart = { control.pause() }
        assertThrows(TransferStopped::class.java) { engine().run(storage, job, "account-A", control) { _, _ -> } }
        assertEquals(1, storage.uploadedParts)
        storage.afterPart = null
        assertEquals(TransferStatus.COMPLETED, engine().run(storage, job, "account-A", TransferControl()) { _, _ -> })
        assertEquals(2, storage.uploadedParts)
        assertArrayEquals(Files.readAllBytes(path), storage.objects.getValue(job.key).bytes)
    }

    @Test fun `changed source and changed credentials cannot reuse upload checkpoint`() {
        val storage = FakeStorage(); val path = source(9 * 1024 * 1024); val job = job(path); val control = TransferControl()
        storage.afterPart = { control.pause() }
        assertThrows(TransferStopped::class.java) { engine().run(storage, job, "A", control) { _, _ -> } }
        storage.afterPart = null
        assertThrows(IllegalArgumentException::class.java) { engine().run(storage, job, "B", TransferControl()) { _, _ -> } }
        Files.newOutputStream(path).use { it.write(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { engine().run(storage, job, "A", TransferControl()) { _, _ -> } }
        assertEquals(1, storage.uploadedParts)
    }

    @Test fun `cancel removes remote multipart and checkpoint`() {
        val storage = FakeStorage(); val job = job(source(9 * 1024 * 1024)); val control = TransferControl()
        storage.afterPart = { control.pause() }
        assertThrows(TransferStopped::class.java) { engine().run(storage, job, "A", control) { _, _ -> } }
        engine().discard(storage, job)
        assertEquals(1, storage.aborted)
        assertNull(CheckpointStore(temporary.root.toPath().resolve("checkpoints")).read(job.id))
    }

    @Test fun `lost complete response is reconciled using job metadata`() {
        val storage = FakeStorage().apply { completeThenFail = true }; val job = job(source(1024))
        assertEquals(TransferStatus.COMPLETED, engine().run(storage, job, "A", TransferControl()) { _, _ -> })
        assertEquals(job.id, storage.objects.getValue(job.key).metadata["wlzn-transfer-id"])
    }

    @Test fun `existing remote object is skipped without writing`() {
        val storage = FakeStorage(); val job = job(source(1024)); storage.store(job.key, byteArrayOf(9))
        assertEquals(TransferStatus.SKIPPED, engine().run(storage, job, "A", TransferControl()) { _, _ -> })
        assertEquals(0, storage.uploadedParts); assertArrayEquals(byteArrayOf(9), storage.objects.getValue(job.key).bytes)
    }

    @Test fun `destination changed during upload is preserved`() {
        val storage = FakeStorage(); val job = job(source(1024))
        storage.afterPart = { storage.store(job.key, byteArrayOf(99)) }
        assertThrows(IllegalArgumentException::class.java) { engine().run(storage, job, "A", TransferControl()) { _, _ -> } }
        assertArrayEquals(byteArrayOf(99), storage.objects.getValue(job.key).bytes)
    }

    @Test fun `download writes complete data and skips existing files by default`() {
        val storage = FakeStorage(); val path = temporary.root.toPath().resolve("download.bin"); val job = job(path, TransferKind.DOWNLOAD)
        val bytes = ByteArray(10 * 1024 * 1024) { (it % 127).toByte() }; storage.store(job.key, bytes)
        assertEquals(TransferStatus.COMPLETED, engine().run(storage, job, "A", TransferControl()) { _, _ -> })
        assertArrayEquals(bytes, Files.readAllBytes(path))
        assertEquals(8L * 1024 * 1024, storage.lastReadOffset)
        assertEquals(TransferStatus.SKIPPED, engine().run(storage, job.copy(id = java.util.UUID.randomUUID().toString()), "A", TransferControl()) { _, _ -> })
    }

    @Test fun `malformed download response never becomes completed on retry`() {
        val storage = FakeStorage().apply { malformedRead = true }; val path = temporary.root.toPath().resolve("bad.bin"); val job = job(path, TransferKind.DOWNLOAD)
        val bytes = byteArrayOf(1, 2, 3); storage.store(job.key, bytes)
        assertThrows(IllegalArgumentException::class.java) { engine().run(storage, job, "A", TransferControl()) { _, _ -> } }
        assertFalse(Files.exists(path))
        storage.malformedRead = false
        assertEquals(TransferStatus.COMPLETED, engine().run(storage, job, "A", TransferControl()) { _, _ -> })
        assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun `new local file appearing during download is not overwritten`() {
        val storage = FakeStorage(); val path = temporary.root.toPath().resolve("race.bin"); val job = job(path, TransferKind.DOWNLOAD)
        storage.store(job.key, byteArrayOf(1, 2, 3))
        storage.afterRead = { Files.write(path, byteArrayOf(99)) }
        assertThrows(IllegalArgumentException::class.java) { engine().run(storage, job, "A", TransferControl()) { _, _ -> } }
        assertArrayEquals(byteArrayOf(99), Files.readAllBytes(path))
    }

    @Test fun `file part stream reset rereads same bytes without loading entire file`() {
        val path = source(1024)
        FilePartInputStream(path, 100, 200, TransferControl()) { }.use { stream ->
            stream.mark(500); val first = stream.readNBytes(200); stream.reset()
            assertArrayEquals(first, stream.readBytes()); assertEquals(200, first.size)
            assertArrayEquals(Files.readAllBytes(path).copyOfRange(100, 300), first)
        }
    }

    @Test fun `checkpoint never stores secrets and is account bound`() {
        val store = CheckpointStore(temporary.root.toPath().resolve("private-checkpoints"))
        val checkpoint = TransferCheckpoint(jobId = java.util.UUID.randomUUID().toString(), identity = "account-route", credentialFingerprint = "hash-only")
        store.write(checkpoint)
        val text = Files.readString(temporary.root.toPath().resolve("private-checkpoints/${checkpoint.jobId}.json"))
        assertFalse(text.contains("accessKeySecret")); assertFalse(text.contains("sessionToken"))
        assertEquals("hash-only", store.read(checkpoint.jobId)?.credentialFingerprint)
    }
}
