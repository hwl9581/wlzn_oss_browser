package com.wlzn.oss.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.wlzn.oss.model.*
import com.wlzn.oss.transfer.TransferManager
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class TransferPanel @JvmOverloads internal constructor(
    private val recordsProvider: () -> List<TransferRecord> = { TransferManager.getInstance().records() }
) : JPanel(BorderLayout()), Disposable {
    private enum class Filter(val label: String) { ALL("全部"), ACTIVE("进行中"), COMPLETED("已完成"), FAILED("失败") }
    private var filter = Filter.ALL
    private var records = emptyList<TransferRecord>()
    private var allRecords = emptyList<TransferRecord>()
    private var disposed = false
    private val footer = BrowserUi.label()
    private val hint = BrowserUi.label("断点自动保存 · 可随时暂停")
    private val toolbars = mutableListOf<ActionToolbar>()
    private val model = object : AbstractTableModel() {
        private val columns = arrayOf("文件 / 目标位置", "进度", "状态", "操作")
        override fun getRowCount() = records.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 1) java.lang.Integer::class.java else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 3 &&
            records[rowIndex].status in setOf(TransferStatus.QUEUED, TransferStatus.RUNNING, TransferStatus.PAUSED, TransferStatus.FAILED)
        override fun getValueAt(row: Int, column: Int): Any = records[row].let { record ->
            when (column) { 0 -> fileName(record); 1 -> transferPercent(record); 2 -> statusLabel(record.status); else -> "" }
        }
    }
    private val table: JBTable = object : JBTable(model) {
        override fun getToolTipText(event: MouseEvent): String? = recordAt(rowAtPoint(event.point))?.let {
            "任务：${it.profileName} · ${it.bucket}/${it.key} · ${statusLabel(it.status)} · ${it.message}"
        }
    }.apply {
        name = "oss.transfers"; autoCreateRowSorter = true
        BrowserUi.styleTable(this, 58)
        putClientProperty("terminateEditOnFocusLost", true)
        emptyText.text = "还没有传输任务"
    }
    private val timer = Timer(700) { refresh() }

    init {
        val filters = Filter.entries.map { item ->
            object : ToggleAction(item.label), DumbAware {
                init { templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true) }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = filter == item
                override fun setSelected(e: AnActionEvent, state: Boolean) { if (state) { table.cellEditor?.cancelCellEditing(); filter = item; refresh() } }
            }
        }
        val filterToolbar = BrowserUi.toolbar("WLZN.OSS.TransferFilters", this, *filters.toTypedArray()).also { toolbars.add(it) }
        val operations = BrowserUi.toolbar("WLZN.OSS.TransferActions", this,
            BrowserUi.action("暂停全部", AllIcons.Actions.Pause, enabled = { allRecords.any { it.status in setOf(TransferStatus.RUNNING, TransferStatus.QUEUED) } }) {
                table.cellEditor?.cancelCellEditing()
                allRecords.filter { it.status in setOf(TransferStatus.RUNNING, TransferStatus.QUEUED) }.forEach { TransferManager.getInstance().pause(it.id) }; refresh()
            },
            BrowserUi.action("清除已完成", AllIcons.Actions.GC, enabled = { allRecords.any { finished(it.status) } }) {
                table.cellEditor?.cancelCellEditing()
                TransferManager.getInstance().clearFinished(); refresh()
            }).also { toolbars.add(it) }
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 8)
            add(filterToolbar.component, BorderLayout.CENTER); add(operations.component, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        table.columnModel.getColumn(0).cellRenderer = FileRenderer()
        table.columnModel.getColumn(1).cellRenderer = ProgressRenderer()
        table.columnModel.getColumn(2).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column)
                val record = recordAt(row) ?: return this
                if (!selected) foreground = statusColor(record.status)
                icon = when (record.status) {
                    TransferStatus.COMPLETED -> AllIcons.General.InspectionsOK
                    TransferStatus.FAILED -> AllIcons.General.Error
                    TransferStatus.PAUSED -> AllIcons.Actions.Pause
                    else -> null
                }
                border = JBUI.Borders.empty(0, 10)
                return this
            }
        }
        table.columnModel.getColumn(3).cellRenderer = TableCellRenderer { t, _, selected, _, row, column ->
            recordAt(row)?.let { operations(it, selected, false).apply { BrowserUi.layoutCell(this, t, row, column) } } ?: JLabel()
        }
        table.columnModel.getColumn(3).cellEditor = object : AbstractCellEditor(), TableCellEditor {
            override fun getCellEditorValue(): Any = ""
            override fun getTableCellEditorComponent(t: JTable, value: Any?, selected: Boolean, row: Int, column: Int): Component =
                recordAt(row)?.let { operations(it, true, true) } ?: JLabel()
        }
        val columns = (0..3).map { table.columnModel.getColumn(it) }
        listOf(440, 220, 100, 120).forEachIndexed { index, width -> columns[index].preferredWidth = JBUI.scale(width) }
        columns[0].minWidth = JBUI.scale(120); columns[1].minWidth = JBUI.scale(125); columns[3].minWidth = JBUI.scale(130)
        val scroll = JBScrollPane(table).apply { border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1, 0, 0, 0); setColumnHeaderView(table.tableHeader) }
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val showStatus = scroll.viewport.extentSize.width >= JBUI.scale(610)
                val hasStatus = (0 until table.columnCount).any { table.columnModel.getColumn(it).modelIndex == 2 }
                if (showStatus != hasStatus) {
                    table.cellEditor?.cancelCellEditing()
                    columns.forEach { table.removeColumn(it) }
                    listOf(0, 1, 3).toMutableList().apply { if (showStatus) add(2, 2) }.forEach { table.addColumn(columns[it]) }
                }
                table.setSize(scroll.viewport.extentSize.width, table.height); table.doLayout()
                hint.isVisible = this@TransferPanel.width >= JBUI.scale(610)
            }
        })
        add(scroll, BorderLayout.CENTER); add(BrowserUi.footer(footer, hint), BorderLayout.SOUTH)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2 && table.columnAtPoint(e.point).let { it >= 0 && table.convertColumnIndexToModel(it) != 3 }) showDetails() }
            override fun mousePressed(e: MouseEvent) { popup(e) }
            override fun mouseReleased(e: MouseEvent) { popup(e) }
            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
                val selection = selectedRecords()
                JPopupMenu().apply {
                    add(JMenuItem("任务详情").apply { isEnabled = selection.size == 1; addActionListener { showDetails() } })
                    add(JMenuItem("暂停所选").apply { isEnabled = selection.any { it.status in setOf(TransferStatus.RUNNING, TransferStatus.QUEUED) }; addActionListener {
                        selection.filter { it.status in setOf(TransferStatus.RUNNING, TransferStatus.QUEUED) }.forEach { runAction(it, "pause") }
                    } })
                    add(JMenuItem("继续 / 重试所选").apply { isEnabled = selection.any { it.status in setOf(TransferStatus.PAUSED, TransferStatus.FAILED) }; addActionListener {
                        selection.filter { it.status in setOf(TransferStatus.PAUSED, TransferStatus.FAILED) }.forEach { runAction(it, "resume") }
                    } })
                    add(JMenuItem("取消并清理所选").apply { isEnabled = selection.any { !finished(it.status) }; addActionListener { cancel(selection.filter { !finished(it.status) }) } })
                }.show(table, e.x, e.y)
            }
        })
        refresh(); timer.start()
    }

    private fun recordAt(viewRow: Int): TransferRecord? = viewRow.takeIf { it in 0 until table.rowCount }?.let { records.getOrNull(table.convertRowIndexToModel(it)) }
    private fun selectedRecords() = table.selectedRows.map { recordAt(it) }.filterNotNull()
    private fun operations(record: TransferRecord, selected: Boolean, interactive: Boolean) = JPanel(GridBagLayout()).apply {
        background = if (selected) table.selectionBackground else table.background
        val links = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
        add(links, GridBagConstraints().apply { weightx = 1.0; anchor = GridBagConstraints.WEST })
        if (finished(record.status)) links.add(BrowserUi.label(statusLabel(record.status))) else {
            val resume = record.status in setOf(TransferStatus.PAUSED, TransferStatus.FAILED)
            links.add(ActionLink(if (resume) "继续 / 重试" else "暂停").apply { if (interactive) addActionListener { runAction(record, if (resume) "resume" else "pause") } })
            links.add(ActionLink("取消").apply { if (interactive) addActionListener { cancel(listOf(record)) } })
        }
    }

    private fun runAction(record: TransferRecord, command: String) {
        if (disposed) return
        table.cellEditor?.stopCellEditing()
        try {
            if (command == "resume") TransferManager.getInstance().resume(record.id) else TransferManager.getInstance().pause(record.id)
            refresh()
        } catch (e: IllegalArgumentException) { Messages.showWarningDialog(e.message.orEmpty(), "传输任务") }
    }

    private fun cancel(selection: List<TransferRecord>) {
        table.cellEditor?.stopCellEditing()
        if (selection.isEmpty() || disposed) return
        if (Messages.showYesNoDialog("取消所选 ${selection.size} 个任务并清理其未完成分片或本地临时文件？", "取消任务", Messages.getQuestionIcon()) != Messages.YES) return
        selection.forEach { TransferManager.getInstance().cancel(it.id) }; refresh()
    }

    private fun showDetails() {
        val record = selectedRecords().singleOrNull() ?: return
        TextObjectDialog("传输任务详情", "${statusLabel(record.status)} · ${record.profileName}\n${record.bucket}/${record.key}\n本地：${record.localPath}\n${formatSize(record.transferredBytes)} / ${formatSize(record.totalBytes)}\n\n${record.message}", false).showAndGet()
    }

    private fun refresh() {
        if (disposed) return
        val selected = selectedRecords().map { it.id }.toSet()
        allRecords = recordsProvider()
        val updated = allRecords.filter { when (filter) {
            Filter.ALL -> true
            Filter.ACTIVE -> it.status in setOf(TransferStatus.QUEUED, TransferStatus.RUNNING, TransferStatus.PAUSED)
            Filter.COMPLETED -> finished(it.status)
            Filter.FAILED -> it.status == TransferStatus.FAILED
        } }
        // 进度变化时保留正在点击的编辑器，但继续更新绘制；状态/结构变化则关闭旧操作。
        val keepEditor = table.isEditing && records.map { it.id to it.status } == updated.map { it.id to it.status }
        if (!keepEditor) table.cellEditor?.cancelCellEditing()
        records = updated
        if (keepEditor) {
            table.repaint()
            updateFooter()
            return
        }
        model.fireTableDataChanged()
        records.forEachIndexed { index, record ->
            val view = table.convertRowIndexToView(index)
            if (view >= 0) {
                val height = JBUI.scale(if (record.status == TransferStatus.FAILED) 76 else 58)
                if (table.getRowHeight(view) != height) table.setRowHeight(view, height)
                if (record.id in selected) table.addRowSelectionInterval(view, view)
            }
        }
        table.emptyText.text = if (allRecords.isEmpty()) "还没有传输任务，上传或下载后会在这里显示" else "暂无${filter.label}任务"
        updateFooter()
    }

    private fun updateFooter() {
        footer.text = "${allRecords.count { it.status == TransferStatus.RUNNING }} 项传输中 · 最多同时传输 2 个文件"
        toolbars.forEach { it.updateActionsAsync() }
    }

    private inner class FileRenderer : JPanel(), TableCellRenderer {
        private val title = JLabel().apply { putClientProperty("html.disable", true); font = com.intellij.util.ui.UIUtil.getLabelFont().deriveFont(Font.PLAIN) }
        private val location = BrowserUi.label()
        private val error = BrowserUi.label()
        init { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(5, 12); add(title); add(Box.createVerticalStrut(JBUI.scale(4))); add(location); add(error) }
        override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            val record = recordAt(row) ?: return this
            background = if (selected) t.selectionBackground else t.background
            title.text = fileName(record); title.icon = if (record.kind == TransferKind.UPLOAD) AllIcons.Actions.Upload else AllIcons.Actions.Download
            title.foreground = if (selected) t.selectionForeground else t.foreground
            location.text = "${record.profileName} · ${record.bucket}/${record.key}"
            location.foreground = if (selected) t.selectionForeground else BrowserUi.muted
            error.isVisible = record.status == TransferStatus.FAILED
            error.text = record.message; error.foreground = if (selected) t.selectionForeground else BrowserUi.red
            BrowserUi.layoutCell(this, t, row, column)
            return this
        }
    }

    private inner class ProgressRenderer : JPanel(BorderLayout(0, 6)), TableCellRenderer {
        private val percent = BrowserUi.label()
        private val sizeLabel = BrowserUi.label()
        private val progress = object : JProgressBar(0, 100) {
            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = com.intellij.ui.JBColor.namedColor("ProgressBar.trackColor", com.intellij.ui.JBColor(0xE4E7ED, 0x35373D))
                    g.fillRoundRect(0, 0, width, height, height, height)
                    g.color = foreground
                    g.fillRoundRect(0, 0, (width * percentComplete).toInt(), height, height, height)
                } finally { g.dispose() }
            }
        }.apply { isBorderPainted = false; preferredSize = JBUI.size(10, 4); isStringPainted = false }
        private val labels = JPanel(BorderLayout()).apply { isOpaque = false; add(percent, BorderLayout.WEST); add(sizeLabel, BorderLayout.EAST) }
        init { border = JBUI.Borders.empty(12, 10); add(labels, BorderLayout.NORTH); add(progress, BorderLayout.SOUTH) }
        override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
            val record = recordAt(row) ?: return this
            val amount = transferPercent(record)
            background = if (selected) t.selectionBackground else t.background
            percent.text = if (record.status == TransferStatus.QUEUED) "排队中" else "$amount%"
            percent.foreground = if (selected) t.selectionForeground else t.foreground
            sizeLabel.text = if (t.getCellRect(row, column, false).width < JBUI.scale(200)) formatSize(record.totalBytes)
                else "${formatSize(record.transferredBytes)} / ${formatSize(record.totalBytes)}"
            sizeLabel.foreground = if (selected) t.selectionForeground else BrowserUi.muted
            progress.value = amount; progress.foreground = if (record.status == TransferStatus.PAUSED) BrowserUi.muted else statusColor(record.status)
            BrowserUi.layoutCell(this, t, row, column)
            return this
        }
    }

    override fun dispose() { disposed = true; timer.stop(); table.cellEditor?.cancelCellEditing() }
}

private fun finished(status: TransferStatus) = status in setOf(TransferStatus.COMPLETED, TransferStatus.CANCELLED, TransferStatus.SKIPPED)
private fun fileName(record: TransferRecord) = record.key.removeSuffix("/").substringAfterLast('/').ifEmpty { record.key }
internal fun transferPercent(record: TransferRecord): Int = if (record.status == TransferStatus.COMPLETED) 100 else
    if (record.totalBytes <= 0) 0 else (record.transferredBytes.toDouble() / record.totalBytes * 100).toInt().coerceIn(0, 100)
private fun statusColor(status: TransferStatus) = when (status) {
    TransferStatus.COMPLETED -> BrowserUi.green
    TransferStatus.FAILED -> BrowserUi.red
    TransferStatus.PAUSED -> BrowserUi.amber
    TransferStatus.RUNNING -> BrowserUi.blue
    else -> BrowserUi.muted
}
private fun statusLabel(status: TransferStatus) = when (status) {
    TransferStatus.QUEUED -> "排队中"; TransferStatus.RUNNING -> "传输中"; TransferStatus.PAUSED -> "已暂停"
    TransferStatus.COMPLETED -> "已完成"; TransferStatus.FAILED -> "失败"; TransferStatus.CANCELLED -> "已取消"; TransferStatus.SKIPPED -> "已跳过"
}
fun formatSize(size: Long): String = when {
    size >= 1024L * 1024 * 1024 -> "%.2f GiB".format(size.toDouble() / (1024L * 1024 * 1024))
    size >= 1024L * 1024 -> "%.2f MiB".format(size.toDouble() / (1024L * 1024))
    size >= 1024 -> "%.1f KiB".format(size.toDouble() / 1024)
    else -> "$size B"
}
