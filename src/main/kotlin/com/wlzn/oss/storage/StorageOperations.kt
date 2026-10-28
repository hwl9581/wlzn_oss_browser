package com.wlzn.oss.storage

import com.wlzn.oss.model.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object StorageOperations {
    const val MAX_BATCH = 100_000
    const val MAX_PREVIEW_BYTES = 5L * 1024 * 1024
    const val MAX_COPY_BYTES = 5L * 1024 * 1024 * 1024

    fun validateReadResponse(status: Int, contentRange: String?, contentLength: Long, offset: Long, expectedSize: Long?, length: Long? = null) {
        val valid = if (offset == 0L && length == null) status == 200 && (expectedSize == null || expectedSize == contentLength) else {
            val match = contentRange?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
            val start = match?.groupValues?.get(1)?.toLongOrNull()
            val end = match?.groupValues?.get(2)?.toLongOrNull()
            val total = match?.groupValues?.get(3)?.toLongOrNull()
            val expectedEnd = if (length == null) total?.minus(1) else offset + length - 1
            status == 206 && start == offset && total != null && end != null && end == expectedEnd && contentLength == end - offset + 1 &&
                (expectedSize == null || expectedSize == total)
        }
        if (!valid) throw StorageOperationException("服务端返回的下载范围或长度不匹配，未使用该响应")
    }

    fun folder(prefix: String): String = if (prefix.isEmpty() || prefix.endsWith('/')) prefix else "$prefix/"

    fun objects(client: StorageClient, bucket: String, prefix: String, check: () -> Unit = {}): List<ObjectEntry> {
        val result = linkedMapOf<String, ObjectEntry>()
        var token: String? = null
        do {
            check()
            val page = client.list(bucket, prefix, token, true)
            page.entries.forEach { result[it.key] = it }
            require(result.size <= MAX_BATCH) { "单次操作超过 $MAX_BATCH 个对象，请缩小目录范围" }
            val next = page.nextToken
            require(next == null || next != token) { "服务端分页游标未前进" }
            token = next
        } while (token != null)
        return result.values.toList()
    }

    fun expand(client: StorageClient, bucket: String, selected: List<ObjectEntry>, check: () -> Unit = {}): List<ObjectEntry> {
        val result = linkedMapOf<String, ObjectEntry>()
        selected.forEach { entry ->
            check()
            if (entry.directory) {
                objects(client, bucket, folder(entry.key), check).forEach { result[it.key] = it }
                // 空目录占位对象也需要复制、下载建目录或删除。
                client.head(bucket, folder(entry.key))?.let { result[folder(entry.key)] = entry.copy(key = folder(entry.key), size = it.size, etag = it.etag) }
            } else result[entry.key] = entry
            require(result.size <= MAX_BATCH) { "单次操作超过 $MAX_BATCH 个对象" }
        }
        return result.values.toList()
    }

    fun copyOrMove(client: StorageClient, bucket: String, entries: List<ObjectEntry>, sourcePrefix: String,
                   targetBucket: String, targetPrefix: String, move: Boolean, overwrite: Boolean, check: () -> Unit = {}) {
        require(entries.isNotEmpty()) { "请选择文件或目录" }
        require(entries.none { it.directory && bucket == targetBucket && folder(targetPrefix).startsWith(folder(it.key)) }) {
            "不能复制或移动目录到自身或其子目录"
        }
        val objects = expand(client, bucket, entries, check)
        require(objects.none { it.size > MAX_COPY_BYTES }) { "服务端复制单文件上限为 5 GiB，请使用云厂商工具迁移大文件" }
        val targets = objects.map { it to folder(targetPrefix) + it.key.removePrefix(sourcePrefix) }
        require(targets.none { bucket == targetBucket && it.first.key == it.second }) { "源路径和目标路径相同" }
        // 先完整检查目标冲突，避免批量操作到一半才发现同名文件。
        if (!overwrite) targets.forEach { (_, key) ->
            check()
            require(client.head(targetBucket, key) == null) { "目标已存在：$key；请选择覆盖或换一个目录" }
        }
        val copied = mutableListOf<Pair<String, String>>()
        for ((entry, target) in targets) {
            check()
            val source = client.head(bucket, entry.key) ?: throw StorageOperationException("源对象已不存在：${entry.key}")
            client.copy(bucket, entry.key, targetBucket, target, source.etag)
            copied.add(entry.key to source.etag)
        }
        if (move) {
            for ((key, etag) in copied) {
                check()
                val current = client.head(bucket, key)
                require(current == null || current.etag == etag) { "源对象已被修改，已保留来源和已复制目标：$key" }
                if (current != null) client.delete(bucket, key)
            }
        }
    }

    fun safeDownloadPath(root: Path, relativeKey: String): Path {
        require(relativeKey.isNotEmpty() && !relativeKey.startsWith('/') && !relativeKey.contains('\\') && !relativeKey.contains('\u0000')) {
            "对象路径不能安全映射到本地文件：$relativeKey"
        }
        val segments = relativeKey.removeSuffix("/").split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." || it.contains(':') }) { "对象路径含不安全的本地路径片段：$relativeKey" }
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(relativeKey).normalize()
        require(target.startsWith(base) && target != base) { "下载路径超出目标目录" }
        assertNoSymlinks(base)
        assertNoSymlinks(target)
        return target
    }

    fun assertNoSymlinks(path: Path) {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "为避免写入目录之外，目标路径不能包含符号链接：$current" }
            current = current.parent
        }
    }

    fun uploadFiles(paths: List<Path>, prefix: String): List<Pair<Path, String>> {
        val result = linkedMapOf<String, Path>()
        fun add(file: Path, key: String) {
            require(result.putIfAbsent(key, file) == null) { "所选文件映射到相同对象路径：$key" }
            require(result.size <= MAX_BATCH) { "单次上传超过 $MAX_BATCH 个文件" }
        }
        paths.forEach { selected ->
            require(!Files.isSymbolicLink(selected)) { "请选择真实文件或目录，不支持上传符号链接" }
            if (Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
                Files.walk(selected).use { stream -> stream.forEach { child ->
                    require(!Files.isSymbolicLink(child)) { "目录中存在符号链接，请移除后重试：$child" }
                    if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        add(child, folder(prefix) + selected.fileName + "/" + selected.relativize(child).joinToString("/"))
                    } else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        Files.list(child).use { children ->
                            if (!children.findAny().isPresent) add(child, folder(prefix) + selected.fileName +
                                (if (child == selected) "" else "/" + selected.relativize(child).joinToString("/")) + "/")
                        }
                    }
                } }
            } else {
                require(Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) { "文件不存在：$selected" }
                add(selected, folder(prefix) + selected.fileName)
            }
        }
        return result.map { (key, file) -> file to key }
    }
}
