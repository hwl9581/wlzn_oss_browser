package com.wlzn.oss.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.wlzn.oss.config.OssSettings
import com.wlzn.oss.model.TransferKind
import java.nio.file.Files
import java.nio.file.Path

/** 复用 IDEA 的主题文件选择器，上传与下载目录分别记忆；不使用“另存为”拼接目录名。 */
internal object LocalTransferChooser {
    fun descriptor(kind: TransferKind): FileChooserDescriptor =
        (if (kind == TransferKind.UPLOAD) FileChooserDescriptorFactory.multiFilesOrDirs() else FileChooserDescriptorFactory.singleDir()).apply {
            title = if (kind == TransferKind.UPLOAD) "选择上传文件或目录" else "选择下载目录"
            description = if (kind == TransferKind.UPLOAD) "所选文件和目录上传到当前云端目录" else "内容直接下载到所选目录，保留云端子目录结构"
            isForcedToUseIdeaFileChooser = true
        }

    fun initialDirectory(settings: OssSettings, kind: TransferKind): VirtualFile? {
        val saved = settings.transferDirectory(kind).takeIf { it.isNotBlank() } ?: return null
        var directory = try { Path.of(saved).toAbsolutePath().normalize() } catch (_: IllegalArgumentException) { return null }
        while (!Files.isDirectory(directory)) directory = directory.parent ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
    }

    fun rememberSelection(settings: OssSettings, kind: TransferKind, paths: List<Path>) {
        val selected = paths.firstOrNull()?.toAbsolutePath()?.normalize() ?: return
        val directory = if (Files.isDirectory(selected)) selected else selected.parent ?: return
        settings.rememberTransferDirectory(kind, directory.toString())
    }
}
