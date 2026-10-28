package com.wlzn.oss.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.wlzn.oss.config.ConnectionAccess
import com.wlzn.oss.config.OssSettings
import com.wlzn.oss.model.*
import com.wlzn.oss.storage.*
import com.wlzn.oss.transfer.TransferManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class OssBrowserPanel(private val project: Project) : JPanel(BorderLayout(0, 4)), Disposable {
    private val settings = OssSettings.getInstance()
    private val profileBox = JComboBox<ConnectionProfile>()
    private val bucketBox = JComboBox<String>().apply { isEditable = true; preferredSize = Dimension(230, 28) }
    private val path = JTextField(24)
    private val search = JTextField(16)
    private val status = JLabel(" 请先配置并连接账号")
    private val controls = mutableListOf<JButton>()
    private val operationControls = mutableListOf<JButton>()
    private var profile: ConnectionProfile? = null
    private var currentBucket = ""
    private var currentPrefix = ""
    private var listingToken: String? = null
    private var entries = emptyList<ObjectEntry>()
    private var updating = false
    private var busy = false
    private var generation = 0
    @Volatile private var disposed = false
    private val model = object : AbstractTableModel() {
        private val columns = arrayOf("名称", "大小", "修改时间", "存储类型")
        override fun getRowCount() = entries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries[rowIndex]
            return when (columnIndex) {
                0 -> entry.displayName(currentPrefix)
                1 -> if (entry.directory) "目录" else formatSize(entry.size)
                2 -> entry.modified?.let { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(it) }.orEmpty()
                else -> entry.storageClass
            }
        }
    }
    private val table = JBTable(model).apply {
        autoCreateRowSorter = true
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        rowHeight = 25
        emptyText.text = "连接账号后浏览对象；也可在设置中指定 Bucket 直接访问"
    }

    init {
        val header = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        header.add(JPanel(FlowLayout(FlowLayout.LEFT, 5, 3)).apply {
            add(JLabel("连接：")); profileBox.preferredSize = Dimension(280, 28); add(profileBox)
            add(button("连接") { connect() })
            add(button("设置") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "WLZN OSS Browser")
                reloadProfiles()
            })
        })
        header.add(JPanel(FlowLayout(FlowLayout.LEFT, 5, 3)).apply {
            add(JLabel("Bucket：")); add(bucketBox)
            add(button("打开") { openBucket() })
            add(operationButton("新建 Bucket") { createBucket() })
            add(operationButton("删除 Bucket") { deleteBucket() })
        })
        header.add(JPanel(FlowLayout(FlowLayout.LEFT, 5, 3)).apply {
            add(operationButton("上级") { load(StorageOperations.folder(currentPrefix.removeSuffix("/").substringBeforeLast('/', ""))) })
            add(JLabel("目录：")); add(path)
            add(operationButton("前往") { load(StorageOperations.folder(path.text)) })
            add(operationButton("刷新") { load(currentPrefix) })
        })
        header.add(JPanel(FlowLayout(FlowLayout.LEFT, 5, 3)).apply {
            add(JLabel("名称前缀：")); add(search)
            add(operationButton("搜索") { load(currentPrefix) })
            add(operationButton("下一页") { if (listingToken != null) load(currentPrefix, true) else setStatus("已到最后一页") })
        })
        header.add(JPanel(FlowLayout(FlowLayout.LEFT, 5, 3)).apply {
            add(operationButton("上传文件 / 目录") { chooseUpload() })
            add(operationButton("下载") { download() })
            add(operationButton("新建目录") { createFolder() })
            add(operationButton("预览 / 编辑") { preview() })
            add(operationButton("更多 ▾") { morePopup().show(itSource(), 0, 26) })
        })
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        table.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): java.awt.Component {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column)
                icon = if (entries.getOrNull(t.convertRowIndexToModel(row))?.directory == true) AllIcons.Nodes.Folder else AllIcons.FileTypes.Any_type
                return this
            }
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !busy) selected().singleOrNull()?.let { if (it.directory) load(it.key) else preview() }
            }
            override fun mousePressed(e: MouseEvent) { popup(e) }
            override fun mouseReleased(e: MouseEvent) { popup(e) }
            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger || busy || profile == null) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
                morePopup().show(table, e.x, e.y)
            }
        })
        table.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport) = !busy && profile != null && currentBucket.isNotBlank() && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                @Suppress("UNCHECKED_CAST")
                val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
                upload(files.map { it.toPath() }); return true
            }
        }
        profileBox.addActionListener { if (!updating) disconnect() }
        bucketBox.addActionListener { if (!updating && profile != null && !busy) openBucket() }
        path.addActionListener { if (!busy && profile != null) load(StorageOperations.folder(path.text)) }
        search.addActionListener { if (!busy && profile != null) load(currentPrefix) }
        reloadProfiles()
    }

    private fun itSource(): JComponent = controls.last()
    private fun button(text: String, action: () -> Unit): JButton = JButton(text).apply { addActionListener { action() }; controls.add(this) }
    private fun operationButton(text: String, action: () -> Unit): JButton = button(text, action).also { operationControls.add(it) }
    private fun selected(): List<ObjectEntry> = table.selectedRows.map { entries.getOrNull(table.convertRowIndexToModel(it)) }.filterNotNull()
    private fun setStatus(text: String) { status.text = " $text" }

    private fun reloadProfiles() {
        val previous = (profileBox.selectedItem as? ConnectionProfile)?.id ?: settings.selectedProfileId()
        updating = true
        profileBox.model = DefaultComboBoxModel(settings.profiles().toTypedArray())
        (0 until profileBox.itemCount).firstOrNull { profileBox.getItemAt(it).id == previous }?.let { profileBox.selectedIndex = it }
        updating = false
        disconnect()
    }

    private fun disconnect() {
        generation++
        profile = null
        currentBucket = ""; currentPrefix = ""; listingToken = null; entries = emptyList()
        updating = true; bucketBox.removeAllItems(); updating = false
        path.text = ""; search.text = ""; model.fireTableDataChanged()
        setBusy(false)
        setStatus(if (profileBox.itemCount == 0) "请先在 Settings → Tools → WLZN OSS Browser 添加连接" else "请选择连接并点击“连接”")
    }

    private fun connect() {
        val chosen = (profileBox.selectedItem as? ConnectionProfile)?.copy() ?: return
        background("连接 ${chosen.name}", {
            ConnectionAccess.open(chosen).use { client ->
                if (chosen.bucket.isNotBlank()) {
                    client.list(chosen.bucket, chosen.prefix)
                    listOf(BucketEntry(chosen.bucket))
                } else client.buckets()
            }
        }) { buckets ->
            profile = chosen
            settings.selectProfile(chosen.id)
            updating = true
            bucketBox.model = DefaultComboBoxModel(buckets.map { it.name }.toTypedArray())
            updating = false
            currentBucket = buckets.firstOrNull()?.name.orEmpty()
            setStatus("已连接 ${chosen.name} · ${chosen.provider.label} · ${chosen.region}")
            if (currentBucket.isNotEmpty()) load(StorageOperations.folder(chosen.prefix))
        }
    }

    private fun openBucket() {
        val value = bucketBox.editor.item?.toString()?.trim().orEmpty()
        if (value.isBlank()) return
        currentBucket = value
        search.text = ""
        load("")
    }

    private fun load(prefix: String, append: Boolean = false) {
        val connection = profile ?: return
        val bucket = currentBucket.takeIf { it.isNotBlank() } ?: return
        val token = if (append) listingToken else null
        val query = prefix + search.text
        background("列举对象", { ConnectionAccess.open(connection).use { it.list(bucket, query, token) } }) { page ->
            currentPrefix = prefix
            path.text = prefix
            entries = if (append) (entries + page.entries).distinctBy { it.key } else page.entries
            listingToken = page.nextToken
            model.fireTableDataChanged()
            setStatus("${connection.name} · $bucket/$prefix · 已加载 ${entries.size} 项${if (listingToken != null) "，还有下一页" else ""}")
        }
    }

    private fun createBucket() {
        val connection = profile ?: return
        val name = Messages.showInputDialog(project, "新 Bucket 名称（区域：${connection.region}；使用服务端默认安全策略）", "新建 Bucket", null)?.trim() ?: return
        if (name.isBlank()) return
        background("创建 Bucket", { ConnectionAccess.open(connection).use { it.createBucket(name) } }) {
            updating = true; bucketBox.addItem(name); bucketBox.selectedItem = name; updating = false
            currentBucket = name; load("")
        }
    }

    private fun deleteBucket() {
        val connection = profile ?: return
        val bucket = currentBucket.takeIf { it.isNotBlank() } ?: return
        val typed = Messages.showInputDialog(project, "删除 $bucket？仅允许删除空 Bucket，不会自动清空对象。\n输入完整 Bucket 名称确认：", "删除 Bucket", Messages.getWarningIcon()) ?: return
        if (typed != bucket) return
        background("删除 Bucket", { ConnectionAccess.open(connection).use { it.deleteBucket(bucket) } }) {
            updating = true; bucketBox.removeItem(bucket); updating = false
            currentBucket = ""; entries = emptyList(); model.fireTableDataChanged(); setStatus("Bucket 已删除")
        }
    }

    private fun chooseUpload() {
        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES; isMultiSelectionEnabled = true; dialogTitle = "选择上传文件或目录" }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) upload(chooser.selectedFiles.map { it.toPath() })
    }

    private fun upload(paths: List<Path>) {
        val connection = profile ?: return
        val bucket = currentBucket.takeIf { it.isNotBlank() } ?: return
        val prefix = currentPrefix
        val overwrite = overwriteChoice("上传到 ${connection.name} · $bucket/$prefix\n同名对象如何处理？") ?: return
        background("准备上传任务", { StorageOperations.uploadFiles(paths, prefix) }) { files ->
            try {
                TransferManager.getInstance().enqueue(connection, files.map { (file, key) -> TransferRecord(
                    profileId = connection.id, kind = TransferKind.UPLOAD, bucket = bucket, key = key, localPath = file.toAbsolutePath().toString(), overwrite = overwrite) })
                setStatus("已加入 ${files.size} 个上传任务，请查看“传输任务”页签")
            } catch (e: IllegalArgumentException) { Messages.showWarningDialog(project, e.message.orEmpty(), "无法建立上传任务") }
        }
    }

    private fun download() {
        val connection = profile ?: return
        val bucket = currentBucket
        val selection = selected().takeIf { it.isNotEmpty() } ?: return
        val prefix = currentPrefix
        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "选择下载目录" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val root = chooser.selectedFile.toPath().toAbsolutePath().normalize()
        val overwrite = overwriteChoice("下载所选 ${selection.size} 项及其子目录。\n本地同名文件如何处理？") ?: return
        background("准备下载任务", {
            ConnectionAccess.open(connection).use { client -> StorageOperations.expand(client, bucket, selection) { ProgressManager.checkCanceled() }.map { entry ->
                val local = StorageOperations.safeDownloadPath(root, entry.key.removePrefix(prefix))
                TransferRecord(profileId = connection.id, kind = TransferKind.DOWNLOAD, bucket = bucket, key = entry.key, localPath = local.toString(), overwrite = overwrite)
            } }
        }) { jobs ->
            try { TransferManager.getInstance().enqueue(connection, jobs); setStatus("已加入 ${jobs.size} 个下载任务，请查看“传输任务”页签") }
            catch (e: IllegalArgumentException) { Messages.showWarningDialog(project, e.message.orEmpty(), "无法建立下载任务") }
        }
    }

    private fun overwriteChoice(message: String): Boolean? = when (Messages.showYesNoCancelDialog(project, message, "同名文件处理", "覆盖", "跳过", "取消", Messages.getQuestionIcon())) {
        Messages.YES -> true; Messages.NO -> false; else -> null
    }

    private fun createFolder() {
        val name = Messages.showInputDialog(project, "在当前目录下创建文件夹（可用 / 分隔多层目录）：", "新建目录", null) ?: return
        if (name.isBlank() || name.startsWith('/') || name.contains('\\') || name.split('/').any { it == ".." || it == "." }) {
            Messages.showWarningDialog(project, "请输入有效的相对目录名", "新建目录"); return
        }
        operation("创建目录") { client, _, bucket, prefix ->
            val key = StorageOperations.folder(prefix + name)
            require(client.head(bucket, key) == null) { "目录已存在" }
            client.put(bucket, key, ByteArrayInputStream(byteArrayOf()), 0, "application/x-directory", true)
        }
    }

    private fun morePopup() = JPopupMenu().apply {
        listOf("刷新" to { load(currentPrefix) }, "复制路径" to { copyPaths() }, "临时分享链接" to { share() },
            "复制到…" to { copyOrMove(false) }, "移动到…" to { copyOrMove(true) }, "重命名" to { rename() },
            "元数据" to { metadata() }, "读写权限 ACL" to { acl() }, "解冻归档对象" to { restore() },
            "未完成分片" to { multipart() }, "删除所选" to { deleteObjects() }).forEach { (label, action) ->
            add(JMenuItem(label).apply { addActionListener { if (!busy) action() } })
        }
    }

    private fun copyPaths() {
        val scheme = if (profile?.provider == Provider.ALIYUN_OSS) "oss" else "s3"
        CopyPasteManager.getInstance().setContents(StringSelection(selected().joinToString("\n") { "$scheme://$currentBucket/${it.key}" }))
    }

    private fun share() {
        val entry = selected().singleOrNull()?.takeUnless { it.directory } ?: return
        val seconds = Messages.showInputDialog(project, "链接有效期（秒，1–604800；临时凭据过期会提前失效）：", "生成临时链接", null, "3600", null)?.toLongOrNull() ?: return
        if (seconds !in 1..604800) { Messages.showWarningDialog(project, "有效期必须为 1–604800 秒", "临时链接"); return }
        val connection = profile ?: return
        val bucket = currentBucket
        background("生成临时分享链接", { ConnectionAccess.open(connection).use { it.signedUrl(bucket, entry.key, Duration.ofSeconds(seconds)) } }) { url ->
            CopyPasteManager.getInstance().setContents(StringSelection(url))
            TextObjectDialog("临时链接（已复制）", url, false, "持有该链接即可在有效期内访问对象，请按需分享。").showAndGet()
        }
    }

    private fun copyOrMove(move: Boolean) {
        val selection = selected().takeIf { it.isNotEmpty() } ?: return
        val dialog = DestinationDialog(currentBucket, currentPrefix, move)
        if (!dialog.showAndGet()) return
        operation(if (move) "移动对象" else "复制对象") { client, _, bucket, prefix ->
            StorageOperations.copyOrMove(client, bucket, selection, prefix, dialog.bucket, dialog.prefix, move, dialog.overwrite) { ProgressManager.checkCanceled() }
        }
    }

    private fun rename() {
        val entry = selected().singleOrNull() ?: return
        val oldName = entry.displayName(currentPrefix)
        val name = Messages.showInputDialog(project, "新名称（复制成功后删除旧对象）：", "重命名", null, oldName, null) ?: return
        if (name.isBlank() || name in setOf(".", "..") || name.contains('/') || name.contains('\\') || name == oldName) return
        val connection = profile ?: return
        val bucket = currentBucket
        val target = currentPrefix + name + if (entry.directory) "/" else ""
        if (Messages.showYesNoDialog(project, "将 ${entry.key} 重命名为 $target？\n写前检查已存在目标并拒绝；服务端复制非原子，无法阻止其他客户端同时写入。", "确认重命名", Messages.getQuestionIcon()) != Messages.YES) return
        operation("重命名") { client, _, _, _ ->
            val sources = StorageOperations.expand(client, bucket, listOf(entry)) { ProgressManager.checkCanceled() }
            require(sources.none { it.size > StorageOperations.MAX_COPY_BYTES }) { "单对象超过 5 GiB，请使用云厂商工具重命名" }
            val targets = sources.map { source -> source to if (entry.directory) target + source.key.removePrefix(entry.key) else target }
            targets.forEach { require(client.head(bucket, it.second) == null) { "目标已存在：${it.second}" } }
            targets.forEach { (source, destination) -> ProgressManager.checkCanceled(); client.copy(bucket, source.key, bucket, destination, source.etag) }
            targets.forEach { (source, _) ->
                ProgressManager.checkCanceled()
                require(client.head(bucket, source.key)?.etag == source.etag) { "源对象已变化，保留来源和已复制目标：${source.key}" }
                client.delete(bucket, source.key)
            }
        }
    }

    private fun deleteObjects() {
        val selection = selected().takeIf { it.isNotEmpty() } ?: return
        if (Messages.showYesNoDialog(project, "删除 ${profile?.name} · $currentBucket 下所选 ${selection.size} 项？\n目录将递归删除。请确认：\n" + selection.take(10).joinToString("\n") { it.key },
                "确认删除", "删除", "取消", Messages.getWarningIcon()) != Messages.YES) return
        operation("删除对象") { client, _, bucket, _ ->
            StorageOperations.expand(client, bucket, selection) { ProgressManager.checkCanceled() }.forEach {
                ProgressManager.checkCanceled(); client.delete(bucket, it.key)
            }
        }
    }

    private fun restore() {
        val selection = selected().filterNot { it.directory }.takeIf { it.isNotEmpty() } ?: return
        if (Messages.showYesNoDialog(project, "对 ${selection.size} 个归档对象提交解冻请求？解冻由云服务异步处理，可能产生费用。", "解冻归档对象", Messages.getQuestionIcon()) != Messages.YES) return
        operation("提交解冻请求") { client, _, bucket, _ -> selection.forEach { ProgressManager.checkCanceled(); client.restore(bucket, it.key) } }
    }

    private fun acl() {
        val entry = selected().singleOrNull()?.takeUnless { it.directory } ?: return
        val connection = profile ?: return
        val bucket = currentBucket
        background("读取 ACL", { ConnectionAccess.open(connection).use { it.acl(bucket, entry.key) } }) { existing ->
            val values = if (connection.provider == Provider.ALIYUN_OSS) listOf(ObjectAcl.DEFAULT, ObjectAcl.PRIVATE, ObjectAcl.PUBLIC_READ, ObjectAcl.PUBLIC_READ_WRITE)
                else listOf(ObjectAcl.PRIVATE, ObjectAcl.PUBLIC_READ)
            val chosen = JOptionPane.showInputDialog(this, "当前：$existing\n选择新权限（S3 公共访问阻止策略可能禁止修改）：", "对象 ACL", JOptionPane.QUESTION_MESSAGE,
                null, values.toTypedArray(), existing) as? ObjectAcl ?: return@background
            if (Messages.showYesNoDialog(project, "将 $bucket/${entry.key} 的 ACL 改为 $chosen？", "确认权限变更", Messages.getWarningIcon()) == Messages.YES)
                operation("修改 ACL") { client, _, _, _ -> client.setAcl(bucket, entry.key, chosen) }
        }
    }

    private fun multipart() {
        val connection = profile ?: return
        val bucket = currentBucket
        val prefix = currentPrefix
        background("列举未完成分片", { ConnectionAccess.open(connection).use { client ->
            val result = mutableListOf<MultipartEntry>()
            var key: String? = null; var upload: String? = null
            do {
                ProgressManager.checkCanceled()
                val page = client.multipartUploads(bucket, prefix, key, upload)
                result.addAll(page.entries)
                require(result.size <= StorageOperations.MAX_BATCH) { "分片任务过多，请进入更具体目录" }
                require(page.nextKey == null || page.nextKey != key || page.nextUploadId != upload) { "分片分页游标未前进" }
                key = page.nextKey; upload = page.nextUploadId
            } while (key != null)
            result
        } }) { parts ->
            if (parts.isEmpty()) { Messages.showInfoMessage(project, "当前目录没有未完成分片", "分片管理"); return@background }
            val dialog = MultipartDialog(parts)
            if (dialog.showAndGet() && Messages.showYesNoDialog(project, "终止所选 ${dialog.selected.size} 个分片任务？对应上传将不能继续。", "确认清理", Messages.getWarningIcon()) == Messages.YES)
                operation("清理未完成分片") { client, _, _, _ -> dialog.selected.forEach { ProgressManager.checkCanceled(); client.abortUpload(bucket, it.key, it.uploadId) } }
        }
    }

    private fun metadata() {
        val entry = selected().singleOrNull()?.takeUnless { it.directory } ?: return
        val connection = profile ?: return
        val bucket = currentBucket
        background("读取元数据", { ConnectionAccess.open(connection).use { it.head(bucket, entry.key) ?: throw StorageOperationException("对象不存在") } }) { details ->
            val values = linkedMapOf<String, Any>("contentType" to details.contentType, "cacheControl" to details.cacheControl,
                "contentDisposition" to details.contentDisposition, "contentEncoding" to details.contentEncoding,
                "contentLanguage" to details.contentLanguage, "userMetadata" to details.userMetadata)
            val dialog = TextObjectDialog("元数据 · ${entry.key}", GsonBuilder().setPrettyPrinting().create().toJson(values), true,
                "${formatSize(details.size)} · ETag ${details.etag} · 保存通过服务端自复制完成")
            if (!dialog.showAndGet()) return@background
            try {
                val json = JsonParser.parseString(dialog.text).asJsonObject
                require(json.keySet() == values.keys) { "只允许修改列出的元数据字段" }
                fun value(key: String) = json.get(key).asString.also { require(!it.contains('\r') && !it.contains('\n')) { "HTTP 元数据不能含换行" } }
                val userMetadata = json.getAsJsonObject("userMetadata").entrySet().associate { (key, value) ->
                    require(key.matches(Regex("[a-zA-Z0-9_-]+"))) { "自定义元数据键只支持字母、数字、下划线和连字符" }
                    key to value.asString.also { require(!it.contains('\r') && !it.contains('\n')) { "元数据值不能含换行" } }
                }
                require(userMetadata.entries.sumOf { it.key.toByteArray().size + it.value.toByteArray().size } <= 2048) { "自定义元数据总长度不能超过 2 KiB" }
                val edited = details.copy(contentType = value("contentType"), cacheControl = value("cacheControl"), contentDisposition = value("contentDisposition"),
                    contentEncoding = value("contentEncoding"), contentLanguage = value("contentLanguage"), userMetadata = userMetadata)
                operation("保存元数据") { client, _, _, _ -> client.updateMetadata(bucket, entry.key, edited) }
            } catch (e: Exception) { Messages.showWarningDialog(project, e.message ?: "元数据 JSON 无效", "元数据") }
        }
    }

    private data class Preview(val bytes: ByteArray, val details: ObjectDetails, val image: BufferedImage?)
    private fun preview() {
        val entry = selected().singleOrNull() ?: return
        if (entry.directory) { load(entry.key); return }
        val connection = profile ?: return
        val bucket = currentBucket
        background("读取预览", { ConnectionAccess.open(connection).use { client ->
            val details = client.head(bucket, entry.key) ?: throw StorageOperationException("对象不存在")
            require(details.size <= StorageOperations.MAX_PREVIEW_BYTES) { "预览限制为 5 MiB，请下载大文件后查看" }
            val bytes = client.read(bucket, entry.key, etag = details.etag, expectedSize = details.size).use { it.readNBytes((StorageOperations.MAX_PREVIEW_BYTES + 1).toInt()) }
            require(bytes.size <= StorageOperations.MAX_PREVIEW_BYTES) { "对象超过预览大小上限" }
            require(bytes.size.toLong() == details.size) { "预览内容不完整，未打开编辑器，请重新读取" }
            val image = if (details.contentType.startsWith("image/")) decodeImage(bytes) else null
            Preview(bytes, details, image)
        } }) { preview ->
            if (preview.image != null) {
                val image = preview.image
                val factor = minOf(1.0, 900.0 / image.width, 650.0 / image.height)
                val icon = ImageIcon(if (factor < 1) image.getScaledInstance((image.width * factor).toInt(), (image.height * factor).toInt(), java.awt.Image.SCALE_SMOOTH) else image)
                object : DialogWrapper(project) {
                    init { title = entry.key; init() }
                    override fun createCenterPanel(): JComponent = JBScrollPane(JLabel(icon))
                }.showAndGet()
                return@background
            }
            val text = try {
                require(!preview.bytes.contains(0))
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(preview.bytes)).toString()
            } catch (_: Exception) { Messages.showInfoMessage(project, "该对象是二进制或非 UTF-8 文本，请下载后查看", "无法预览"); return@background }
            val note = if (connection.provider == Provider.AMAZON_S3) "UTF-8 文本 · 保存使用 ETag 条件写入，远端变化时会拒绝"
                else "UTF-8 文本 · OSS 保存前核对 ETag；OSS 不提供原子条件写入，仍可能覆盖同时发生的修改"
            val dialog = TextObjectDialog(entry.key, text, true, note)
            if (!dialog.showAndGet() || dialog.text == text) return@background
            val bytes = dialog.text.toByteArray(Charsets.UTF_8)
            if (Messages.showYesNoDialog(project, "将编辑后的内容保存到 ${connection.name} · $bucket/${entry.key}？", "确认保存", Messages.getWarningIcon()) != Messages.YES) return@background
            operation("保存文本") { client, _, _, _ ->
                val current = client.head(bucket, entry.key)
                require(current?.etag == preview.details.etag) { "远端对象已修改，请重新打开后编辑" }
                client.replaceText(bucket, entry.key, bytes, preview.details)
            }
        }
    }

    private fun decodeImage(bytes: ByteArray): BufferedImage? = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.input = input
            require(reader.getWidth(0).toLong() * reader.getHeight(0) <= 20_000_000) { "图片像素过大，请下载后查看" }
            reader.read(0)
        } finally { reader.dispose() }
    }

    private fun operation(title: String, action: (StorageClient, ConnectionProfile, String, String) -> Unit) {
        val connection = profile?.copy() ?: return
        val bucket = currentBucket
        val prefix = currentPrefix
        background(title, { ConnectionAccess.open(connection).use { action(it, connection, bucket, prefix) } }) {
            load(prefix)
        }
    }

    private fun <T> background(title: String, action: () -> T, success: (T) -> Unit) {
        val request = ++generation
        val connectionSnapshot = (profile ?: profileBox.selectedItem as? ConnectionProfile)?.copy()
        setBusy(true)
        setStatus("$title…")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var result: T? = null
            override fun run(indicator: ProgressIndicator) { result = action() }
            override fun onSuccess() {
                if (!alive(request)) return
                if (connectionSnapshot != null && settings.profiles().none { it.id == connectionSnapshot.id && it.revision == connectionSnapshot.revision }) {
                    disconnect()
                    setStatus("连接配置已变化，请重新连接")
                    return
                }
                setBusy(false)
                @Suppress("UNCHECKED_CAST")
                success(result as T)
            }
            override fun onThrowable(error: Throwable) {
                if (!alive(request)) return
                val message = StorageErrors.describe(error)
                setStatus("$title：$message")
                Messages.showErrorDialog(project, "$message\n\n批量操作可能已部分完成，请刷新核对。", title)
            }
            override fun onCancel() { if (alive(request)) setStatus("已取消 $title；批量操作请刷新核对已完成项") }
            override fun onFinished() { if (alive(request)) setBusy(false) }
        })
    }
    private fun alive(request: Int) = !disposed && !project.isDisposed && request == generation
    private fun setBusy(value: Boolean) {
        busy = value
        controls.forEach { it.isEnabled = !value }
        operationControls.forEach { it.isEnabled = !value && profile != null }
        table.isEnabled = !value && profile != null
        profileBox.isEnabled = !value
        bucketBox.isEnabled = !value && profile != null
        path.isEnabled = !value && profile != null
        search.isEnabled = !value && profile != null
    }
    override fun dispose() { disposed = true; generation++ }
}
