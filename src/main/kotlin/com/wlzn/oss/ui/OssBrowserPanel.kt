package com.wlzn.oss.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
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
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.wlzn.oss.config.ConnectionAccess
import com.wlzn.oss.config.OssSettings
import com.wlzn.oss.model.*
import com.wlzn.oss.storage.*
import com.wlzn.oss.transfer.TransferManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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

class OssBrowserPanel @JvmOverloads internal constructor(private val project: Project,
    private val openClient: (ConnectionProfile) -> StorageClient = ConnectionAccess::open) : JPanel(BorderLayout()), Disposable {
    private val settings = OssSettings.getInstance()
    private val profileBox = JComboBox<ConnectionProfile>()
    private val bucketTree = BucketTreePanel(project, openClient) { bucket, prefix -> navigateTo(bucket, prefix) }
    private val pathBar = BreadcrumbBar { navigateTo(currentBucket, StorageOperations.folder(it)) }
    private val searchField = object : SearchTextField(false) {
        override fun onFieldCleared() { if (ready()) load(currentPrefix) }
    }
    private val search = searchField.textEditor.apply { emptyText.text = "按名称前缀搜索"; name = "oss.search" }
    private val status = BrowserUi.label("请先配置并连接账号")
    private val summary = BrowserUi.label()
    private val connectionInfo = BrowserUi.label("未连接")
    private val toolbars = mutableListOf<ActionToolbar>()
    private val uploadButton = JButton("上传", AllIcons.Actions.Upload)
    private val nextButton = JButton("加载更多")
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
        override fun getColumnClass(column: Int): Class<*> = if (column in 1..2) java.lang.Long::class.java else String::class.java
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries[rowIndex]
            return when (columnIndex) {
                0 -> entry.displayName(currentPrefix)
                1 -> if (entry.directory) -1L else entry.size
                2 -> entry.modified?.toEpochMilli() ?: 0L
                else -> entry.storageClass
            }
        }
    }
    private val table = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? = rowAtPoint(event.point).takeIf { it >= 0 }
            ?.let { entries.getOrNull(convertRowIndexToModel(it))?.key?.let { key -> "对象：$key" } }
    }.apply {
        name = "oss.files"
        autoCreateRowSorter = true
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        BrowserUi.styleTable(this, 34)
    }

    init {
        val header = JPanel().apply { name = "oss.header"; layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val accountRow = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(6, 10); background = BrowserUi.chrome }
        profileBox.apply { name = "oss.account"; minimumSize = JBUI.size(65, 28); preferredSize = JBUI.size(220, 28); font = com.intellij.util.ui.UIUtil.getLabelFont().deriveFont(java.awt.Font.PLAIN) }
        BrowserUi.addCell(accountRow, BrowserUi.label("账号"), 0)
        BrowserUi.addCell(accountRow, profileBox, 1)
        BrowserUi.addCell(accountRow, toolbar("Connection", BrowserUi.action("连接", AllIcons.Actions.Execute, false,
            { !busy && profileBox.selectedItem != null }) { connect() }).component, 2)
        BrowserUi.addCell(accountRow, JPanel().apply { isOpaque = false; minimumSize = Dimension(0, 0) }, 3, 1.0)
        BrowserUi.addCell(accountRow, connectionInfo, 4)
        BrowserUi.addCell(accountRow, toolbar("Settings", BrowserUi.action("连接设置", AllIcons.General.Settings, false, { !busy }) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "WLZN OSS Browser"); reloadProfiles()
        }).component, 5)
        accountRow.border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), accountRow.border)
        val location = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(4, 10, 0, 10); background = com.intellij.util.ui.UIUtil.getTableBackground() }
        BrowserUi.addCell(location, toolbar("Parent", BrowserUi.action("上级目录", AllIcons.Actions.MoveUp, false,
            { ready() && currentPrefix.isNotEmpty() }) { navigateTo(currentBucket, StorageOperations.folder(currentPrefix.removeSuffix("/").substringBeforeLast('/', ""))) }).component, 0)
        BrowserUi.addCell(location, pathBar, 1, 0.7)
        BrowserUi.addCell(location, toolbar("Navigation",
            BrowserUi.action("编辑完整目录路径", AllIcons.Actions.Edit, false, { ready() }) { pathBar.editPath() },
            BrowserUi.action("刷新", AllIcons.Actions.Refresh, false, { ready() }) { refreshCurrent() }).component, 2)
        searchField.minimumSize = JBUI.size(110, 28); searchField.preferredSize = JBUI.size(205, 28)
        BrowserUi.addCell(location, searchField, 3, 0.3)
        header.add(location)
        uploadButton.apply {
            name = "oss.upload"; toolTipText = "上传文件或目录，也可以拖入文件列表"; margin = JBUI.insets(3, 10)
            putClientProperty(com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            addActionListener { chooseUpload() }
        }
        nextButton.apply { name = "oss.more-page"; isVisible = false; margin = JBUI.insets(3, 8); addActionListener { if (ready() && listingToken != null) load(currentPrefix, true) } }
        val fileActions = JPanel(BorderLayout(6, 0)).apply { border = JBUI.Borders.empty(3, 10, 6, 10); background = com.intellij.util.ui.UIUtil.getTableBackground() }
        val actions = toolbar("Files",
            BrowserUi.action("下载", AllIcons.Actions.Download, enabled = { ready() && selected().isNotEmpty() }) { download() },
            Separator.getInstance(),
            BrowserUi.action("新建目录", AllIcons.Actions.NewFolder, enabled = { ready() }) { createFolder() },
            BrowserUi.action("预览", AllIcons.Actions.Preview, enabled = { ready() && selected().singleOrNull()?.directory == false }) { preview() },
            BrowserUi.action("更多", AllIcons.Actions.MoreHorizontal, enabled = { ready() }) { showPopup(morePopup(), it) })
        fileActions.add(uploadButton, BorderLayout.WEST); fileActions.add(actions.component, BorderLayout.CENTER); fileActions.add(nextButton, BorderLayout.EAST)
        header.add(fileActions)
        val scroll = JBScrollPane(table).apply { border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0); setColumnHeaderView(table.tableHeader) }
        val files = JPanel(BorderLayout()).apply { minimumSize = JBUI.size(220, 0); add(header, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER) }
        val sidebar = JPanel(BorderLayout()).apply {
            background = BrowserUi.chrome
            minimumSize = JBUI.size(125, 0)
            add(JPanel(BorderLayout()).apply {
                background = BrowserUi.chrome
                border = JBUI.Borders.empty(4, 10, 4, 4)
                add(BrowserUi.label("BUCKETS"), BorderLayout.CENTER)
                add(toolbar("Buckets", BrowserUi.action("Bucket 管理", AllIcons.Actions.MoreHorizontal, false,
                    { !busy && profile != null }) { showPopup(bucketPopup(), it) }).component, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(bucketTree, BorderLayout.CENTER)
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, files).apply {
            name = "oss.split"; border = JBUI.Borders.empty(); dividerSize = JBUI.scale(1)
            dividerLocation = JBUI.scale(190); resizeWeight = 0.0; isContinuousLayout = true
        }
        add(accountRow, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        add(BrowserUi.footer(status, summary), BorderLayout.SOUTH)
        table.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            init { putClientProperty("html.disable", true) }
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): java.awt.Component {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column)
                icon = if (entries.getOrNull(t.convertRowIndexToModel(row))?.directory == true) AllIcons.Nodes.Folder else AllIcons.FileTypes.Any_type
                border = JBUI.Borders.empty(0, 12); iconTextGap = JBUI.scale(8)
                return this
            }
        }
        for (column in 1..3) table.columnModel.getColumn(column).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, col: Int): java.awt.Component {
                val entry = entries[t.convertRowIndexToModel(row)]
                val text = when (column) {
                    1 -> if (entry.directory) "—" else formatSize(entry.size)
                    2 -> entry.modified?.let { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(it) }.orEmpty()
                    else -> entry.storageClass
                }
                super.getTableCellRendererComponent(t, text, selected, focus, row, col)
                horizontalAlignment = if (column == 1) SwingConstants.RIGHT else SwingConstants.LEFT
                if (!selected) foreground = BrowserUi.muted
                border = JBUI.Borders.empty(0, 12)
                return this
            }
        }
        val columns = (0..3).map { table.columnModel.getColumn(it) }
        listOf(370, 90, 165, 100).forEachIndexed { index, width -> columns[index].preferredWidth = JBUI.scale(width) }
        columns[0].minWidth = JBUI.scale(120)
        columns[1].minWidth = JBUI.scale(100)
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val width = scroll.viewport.extentSize.width
                val wanted = listOf(0, 1) + (if (width >= JBUI.scale(520)) listOf(2) else emptyList()) + (if (width >= JBUI.scale(700)) listOf(3) else emptyList())
                val visible = (0 until table.columnCount).map { table.columnModel.getColumn(it).modelIndex }
                if (visible != wanted) { columns.forEach { table.removeColumn(it) }; wanted.forEach { table.addColumn(columns[it]) } }
                table.setSize(width, table.height); table.doLayout()
                scroll.viewport.viewPosition = java.awt.Point(0, scroll.viewport.viewPosition.y)
                connectionInfo.isVisible = this@OssBrowserPanel.width >= JBUI.scale(800)
            }
        })
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) updateSelection() }
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
                if (row < 0) table.clearSelection()
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
        search.addActionListener { if (!busy && profile != null) load(currentPrefix) }
        reloadProfiles()
    }

    private fun ready() = !disposed && !busy && profile != null && currentBucket.isNotBlank()
    private fun toolbar(place: String, vararg actions: com.intellij.openapi.actionSystem.AnAction) = BrowserUi.toolbar("WLZN.OSS.$place", this, *actions).also { toolbars.add(it) }
    private fun showPopup(menu: JPopupMenu, event: AnActionEvent) {
        val anchor = event.inputEvent?.component as? JComponent ?: this
        menu.show(anchor, 0, anchor.height)
    }
    private fun bucketPopup() = BrowserUi.popup("WLZN.OSS.BucketPopup", bucketTree, DefaultActionGroup().apply {
        add(BrowserUi.action("打开指定 Bucket…", AllIcons.Nodes.Folder, enabled = { !busy && profile != null }) { openBucket() })
        add(BrowserUi.action("新建 Bucket…", AllIcons.Actions.NewFolder, enabled = { !busy && profile != null }) { createBucket() })
        addSeparator()
        add(BrowserUi.action("删除空 Bucket…", AllIcons.General.Delete, enabled = { ready() }) { deleteBucket() })
    })
    private fun selected(): List<ObjectEntry> = table.selectedRows.map { entries.getOrNull(table.convertRowIndexToModel(it)) }.filterNotNull()
    private fun setStatus(text: String) { status.text = text; status.toolTipText = text }
    private fun updateSelection() {
        val selection = selected()
        summary.text = "${entries.size} 项" + if (selection.isNotEmpty()) " · 已选 ${selection.size} 项" else ""
        if (selection.isNotEmpty() && !busy) setStatus(if (selection.size == 1) selection.single().let { "${it.displayName(currentPrefix)} · ${if (it.directory) "目录" else formatSize(it.size)}" } else "已选择 ${selection.size} 项")
        toolbars.forEach { it.updateActionsAsync() }
    }

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
        bucketTree.setConnection(null)
        pathBar.showPath(""); search.text = ""; model.fireTableDataChanged()
        setBusy(false)
        setStatus(if (profileBox.itemCount == 0) "请先在 Settings → Tools → WLZN OSS Browser 添加连接" else "请选择连接并点击“连接”")
    }

    private fun connect() {
        val chosen = (profileBox.selectedItem as? ConnectionProfile)?.copy() ?: return
        background("连接 ${chosen.name}", {
            StorageClients.forgetRegions(chosen.id)
            openClient(chosen).use { client ->
                if (chosen.bucket.isNotBlank()) {
                    client.list(chosen.bucket, chosen.prefix)
                    listOf(BucketEntry(chosen.bucket))
                } else client.buckets()
            }
        }) { buckets ->
            profile = chosen
            settings.selectProfile(chosen.id)
            bucketTree.setConnection(chosen, buckets)
            currentBucket = buckets.firstOrNull()?.name.orEmpty()
            setStatus("已连接 ${chosen.name} · ${chosen.provider.label} · ${if (chosen.endpoint.isBlank()) "自动识别各 Bucket 区域" else chosen.initialRegion()}")
            if (currentBucket.isNotEmpty()) load(StorageOperations.folder(chosen.prefix))
        }
    }

    private fun openBucket() {
        val value = Messages.showInputDialog(project, "输入要访问的 Bucket 名称（无需列举全部 Bucket 的权限）：", "打开 Bucket", null, currentBucket, null)?.trim().orEmpty()
        if (value.isBlank()) return
        navigateTo(value, "")
    }

    private fun navigateTo(value: String, prefix: String) {
        if (busy || profile == null || value.isBlank()) return
        if (value != currentBucket) {
            entries = emptyList(); listingToken = null; currentPrefix = ""
            model.fireTableDataChanged(); pathBar.showPath("")
        }
        currentBucket = value
        search.text = ""
        load(prefix)
    }

    private fun load(prefix: String, append: Boolean = false) {
        val connection = profile ?: return
        val bucket = currentBucket.takeIf { it.isNotBlank() } ?: return
        val token = if (append) listingToken else null
        val query = prefix + search.text
        background("列举对象", { openClient(connection).use { it.list(bucket, query, token) } }) { page ->
            currentPrefix = prefix
            pathBar.showPath(prefix)
            bucketTree.selectLocation(bucket, prefix)
            entries = if (append) (entries + page.entries).distinctBy { it.key } else page.entries
            listingToken = page.nextToken
            model.fireTableDataChanged()
            nextButton.isVisible = listingToken != null
            updateSelection()
            updateEmptyText()
            setStatus("${connection.name} · $bucket/$prefix · 已加载 ${entries.size} 项${if (listingToken != null) "，还有下一页" else ""}")
        }
    }

    private fun createBucket() {
        val connection = profile ?: return
        val automatic = connection.endpoint.isBlank()
        val region = if (automatic) Messages.showInputDialog(project, "新 Bucket 所在区域（创建时需要明确选择，不影响账号访问其他区域）：", "创建区域", null, connection.initialRegion(), null)?.trim() ?: return else connection.initialRegion()
        if (!region.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)+"))) { Messages.showWarningDialog(project, "请输入有效区域，例如 cn-hangzhou / us-east-1", "创建区域"); return }
        val name = Messages.showInputDialog(project, "新 Bucket 名称（区域：$region；使用服务端默认安全策略）", "新建 Bucket", null)?.trim() ?: return
        if (name.isBlank()) return
        background("创建 Bucket", { openClient(connection).use { if (automatic) it.createBucket(name, region) else it.createBucket(name) } }) {
            bucketTree.addBucket(BucketEntry(name, region))
            navigateTo(name, "")
        }
    }

    private fun deleteBucket() {
        val connection = profile ?: return
        val bucket = currentBucket.takeIf { it.isNotBlank() } ?: return
        val typed = Messages.showInputDialog(project, "删除 $bucket？仅允许删除空 Bucket，不会自动清空对象。\n输入完整 Bucket 名称确认：", "删除 Bucket", Messages.getWarningIcon()) ?: return
        if (typed != bucket) return
        background("删除 Bucket", { openClient(connection).use { it.deleteBucket(bucket) } }) {
            bucketTree.removeBucket(bucket)
            currentBucket = ""; currentPrefix = ""; listingToken = null; entries = emptyList()
            model.fireTableDataChanged(); pathBar.showPath(""); updateSelection(); setStatus("Bucket 已删除")
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
            openClient(connection).use { client -> StorageOperations.expand(client, bucket, selection) { ProgressManager.checkCanceled() }.map { entry ->
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

    private fun morePopup() = BrowserUi.popup("WLZN.OSS.FilePopup", table, fileActions())

    private fun fileActions() = DefaultActionGroup().apply {
        val singleFile = { ready() && selected().singleOrNull()?.directory == false }
        val hasSelection = { ready() && selected().isNotEmpty() }
        add(BrowserUi.action("预览 / 编辑…", AllIcons.Actions.Preview, enabled = singleFile) { preview() })
        add(BrowserUi.action("下载…", AllIcons.Actions.Download, enabled = hasSelection) { download() })
        addSeparator()
        add(BrowserUi.action("复制路径", AllIcons.Actions.Copy, enabled = hasSelection) { copyPaths() })
        add(BrowserUi.action("临时分享链接…", AllIcons.Actions.Share, enabled = singleFile) { share() })
        addSeparator()
        add(BrowserUi.action("重命名…", AllIcons.Actions.Edit, enabled = { ready() && selected().size == 1 }) { rename() })
        add(BrowserUi.action("复制到…", AllIcons.Actions.Copy, enabled = hasSelection) { copyOrMove(false) })
        add(BrowserUi.action("移动到…", AllIcons.Actions.MoveTo2, enabled = hasSelection) { copyOrMove(true) })
        add(DefaultActionGroup("高级操作", true).apply {
            templatePresentation.icon = AllIcons.General.Settings
            add(BrowserUi.action("元数据…", AllIcons.Actions.Properties, enabled = singleFile) { metadata() })
            add(BrowserUi.action("读写权限 ACL…", AllIcons.General.Settings, enabled = singleFile) { acl() })
            addSeparator()
            add(BrowserUi.action("解冻归档对象…", AllIcons.Actions.Download, enabled = singleFile) { restore() })
            add(BrowserUi.action("未完成分片…", AllIcons.Actions.ListFiles, enabled = { ready() }) { multipart() })
        })
        addSeparator()
        add(BrowserUi.action("新建目录…", AllIcons.Actions.NewFolder, enabled = { ready() }) { createFolder() })
        add(BrowserUi.action("刷新", AllIcons.Actions.Refresh, enabled = { ready() }) { refreshCurrent() })
        addSeparator()
        add(BrowserUi.action("删除所选…", AllIcons.General.Delete, enabled = hasSelection) { deleteObjects() })
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
        background("生成临时分享链接", { openClient(connection).use { it.signedUrl(bucket, entry.key, Duration.ofSeconds(seconds)) } }) { url ->
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
        background("读取 ACL", { openClient(connection).use { it.acl(bucket, entry.key) } }) { existing ->
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
        background("列举未完成分片", { openClient(connection).use { client ->
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
        background("读取元数据", { openClient(connection).use { it.head(bucket, entry.key) ?: throw StorageOperationException("对象不存在") } }) { details ->
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
        background("读取预览", { openClient(connection).use { client ->
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
        background(title, { openClient(connection).use { action(it, connection, bucket, prefix) } }) {
            bucketTree.refreshLocation(bucket, prefix)
            load(prefix)
        }
    }

    private fun refreshCurrent() {
        if (!ready()) return
        bucketTree.refreshLocation(currentBucket, currentPrefix)
        load(currentPrefix)
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
                if (currentBucket.isNotBlank()) bucketTree.selectLocation(currentBucket, currentPrefix)
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
        uploadButton.isEnabled = ready()
        nextButton.isEnabled = ready() && listingToken != null
        nextButton.isVisible = listingToken != null
        table.isEnabled = ready()
        profileBox.isEnabled = !value
        bucketTree.setNavigationEnabled(!value && profile != null)
        pathBar.setNavigationEnabled(ready())
        search.isEnabled = !value && profile != null
        connectionInfo.text = if (value) "正在连接 / 读取…" else if (profile == null) "未连接" else "${if (profile?.endpoint.isNullOrBlank()) "自动区域" else profile?.initialRegion()} · 已连接"
        connectionInfo.foreground = if (profile != null && !value) BrowserUi.green else BrowserUi.muted
        updateEmptyText()
        toolbars.forEach { it.updateActionsAsync() }
    }

    private fun updateEmptyText() {
        table.emptyText.clear()
        when {
            busy -> table.emptyText.appendText("正在读取对象…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            profile == null -> {
                table.emptyText.appendText("连接存储账号，开始浏览文件", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                table.emptyText.appendSecondaryText("在上方选择账号并点击连接", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            }
            else -> {
                table.emptyText.appendText(if (search.text.isNotEmpty()) "没有匹配的文件" else "这个目录还没有文件", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                table.emptyText.appendSecondaryText(if (search.text.isNotEmpty()) "清空名称前缀后重试" else "点击“上传”或将文件拖到这里", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
            }
        }
    }
    override fun dispose() { disposed = true; generation++; bucketTree.dispose() }
}
