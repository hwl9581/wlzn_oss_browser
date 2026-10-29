package com.wlzn.oss

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.wlzn.oss.config.OssSettings
import com.wlzn.oss.model.TransferKind
import com.wlzn.oss.ui.LocalTransferChooser
import java.nio.file.Files
import java.nio.file.Path

class LocalTransferChooserTest : BasePlatformTestCase() {
    fun testChooserUsesIdeaThemeAndSelectsExistingDownloadDirectory() {
        val root = localRoot()
        val folder = Files.createDirectories(root.resolve("chosen-folder"))
        val jar = Files.write(root.resolve("sample.jar"), byteArrayOf(1))
        val vfs = LocalFileSystem.getInstance()
        val upload = LocalTransferChooser.descriptor(TransferKind.UPLOAD)
        assertTrue(upload.isForcedToUseIdeaFileChooser)
        assertTrue(upload.isChooseMultiple)
        assertTrue(upload.isSelectable(vfs.refreshAndFindFileByNioFile(jar)!!))
        assertTrue(upload.isSelectable(vfs.refreshAndFindFileByNioFile(folder)!!))
        assertFalse(upload.isChooseJarContents)
        val download = LocalTransferChooser.descriptor(TransferKind.DOWNLOAD)
        assertTrue(download.isForcedToUseIdeaFileChooser)
        assertFalse(download.isChooseFiles)
        assertTrue(download.isChooseFolders)
        assertFalse(download.isChooseMultiple)
        assertTrue(download.isSelectable(vfs.refreshAndFindFileByNioFile(folder)!!))
        assertFalse(download.isSelectable(vfs.refreshAndFindFileByNioFile(jar)!!))
    }

    fun testUploadAndDownloadDirectoriesPersistIndependentlyAndOldStateRemainsCompatible() {
        val root = localRoot()
        val upload = Files.createDirectories(root.resolve("upload"))
        val download = Files.createDirectories(root.resolve("download"))
        val source = Files.write(upload.resolve("sample.txt"), byteArrayOf(1))
        val settings = OssSettings()
        LocalTransferChooser.rememberSelection(settings, TransferKind.UPLOAD, listOf(source))
        LocalTransferChooser.rememberSelection(settings, TransferKind.DOWNLOAD, listOf(download))
        val restored = OssSettings().apply { loadState(XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), OssSettings.State::class.java)) }
        assertEquals(upload.toString(), restored.transferDirectory(TransferKind.UPLOAD))
        assertEquals(download.toString(), restored.transferDirectory(TransferKind.DOWNLOAD))
        assertEquals(upload, LocalTransferChooser.initialDirectory(restored, TransferKind.UPLOAD)!!.toNioPath())
        assertEquals(download, LocalTransferChooser.initialDirectory(restored, TransferKind.DOWNLOAD)!!.toNioPath())
        val old = OssSettings().apply { loadState(OssSettings.State()) }
        assertEquals("", old.transferDirectory(TransferKind.UPLOAD))
        assertEquals("", old.transferDirectory(TransferKind.DOWNLOAD))
    }

    fun testSelectedUploadFolderIsRememberedAndDeletedDirectoryFallsBackToExistingParent() {
        val folder = Files.createDirectories(localRoot().resolve("upload"))
        val settings = OssSettings()
        LocalTransferChooser.rememberSelection(settings, TransferKind.UPLOAD, listOf(folder))
        assertEquals(folder.toString(), settings.transferDirectory(TransferKind.UPLOAD))
        Files.delete(folder)
        assertEquals(folder.parent, LocalTransferChooser.initialDirectory(settings, TransferKind.UPLOAD)!!.toNioPath())
        settings.rememberTransferDirectory(TransferKind.DOWNLOAD, "bad\u0000path")
        assertNull(LocalTransferChooser.initialDirectory(settings, TransferKind.DOWNLOAD))
    }

    private fun localRoot(): Path {
        val root = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), "oss-chooser-")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        Disposer.register(testRootDisposable, Disposable { FileUtil.delete(root.toFile()) })
        return root
    }
}
