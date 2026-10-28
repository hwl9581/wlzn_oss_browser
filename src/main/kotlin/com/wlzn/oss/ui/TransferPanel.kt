package com.wlzn.oss.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.wlzn.oss.model.*
import com.wlzn.oss.transfer.TransferManager
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

class TransferPanel : JPanel(BorderLayout()), Disposable {
    private val manager = TransferManager.getInstance()
    private var records = emptyList<TransferRecord>()
    private val model = object : AbstractTableModel() {
        private val columns = arrayOf("状态", "方向", "账号", "Bucket / 对象", "进度", "大小", "说明")
        override fun getRowCount() = records.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val record = records[rowIndex]
            return when (columnIndex) {
                0 -> statusLabel(record.status)
                1 -> if (record.kind == TransferKind.UPLOAD) "上传" else "下载"
                2 -> record.profileName
                3 -> "${record.bucket}/${record.key}"
                4 -> if (record.totalBytes > 0) "${record.transferredBytes * 100 / record.totalBytes}%" else "—"
                5 -> formatSize(record.totalBytes)
                else -> record.message
            }
        }
    }
    private val table = JBTable(model).apply { autoCreateRowSorter = true; setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION) }
    private val timer = Timer(700) { refresh() }

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3))
        listOf("暂停" to { id: String -> manager.pause(id) }, "继续 / 重试" to { id: String -> manager.resume(id) },
            "取消并清理" to { id: String -> manager.cancel(id) }).forEach { (label, action) ->
            toolbar.add(JButton(label).apply { addActionListener {
                val selected = table.selectedRows.map { records[table.convertRowIndexToModel(it)] }
                if (label.startsWith("取消") && selected.isNotEmpty() && Messages.showYesNoDialog(
                        "取消所选 ${selected.size} 个任务并清理其未完成分片或本地临时文件？", "取消任务", Messages.getQuestionIcon()) != Messages.YES) return@addActionListener
                try { selected.forEach { action(it.id) }; refresh() }
                catch (e: IllegalArgumentException) { Messages.showWarningDialog(e.message.orEmpty(), "传输任务") }
            } })
        }
        toolbar.add(JButton("清除已完成").apply { addActionListener { manager.clearFinished(); refresh() } })
        toolbar.add(JLabel("最多同时传输 2 个文件 · 暂停后可恢复 · 重启后手动继续"))
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        refresh()
        timer.start()
    }

    private fun refresh() {
        val selected = table.selectedRows.map { records.getOrNull(table.convertRowIndexToModel(it))?.id }.filterNotNull().toSet()
        records = manager.records()
        model.fireTableDataChanged()
        records.forEachIndexed { index, record -> if (record.id in selected) {
            val view = table.convertRowIndexToView(index)
            if (view >= 0) table.addRowSelectionInterval(view, view)
        } }
    }
    override fun dispose() = timer.stop()
}

fun formatSize(size: Long): String = when {
    size >= 1024L * 1024 * 1024 -> "%.2f GiB".format(size.toDouble() / (1024L * 1024 * 1024))
    size >= 1024L * 1024 -> "%.2f MiB".format(size.toDouble() / (1024L * 1024))
    size >= 1024 -> "%.1f KiB".format(size.toDouble() / 1024)
    else -> "$size B"
}

private fun statusLabel(status: TransferStatus) = when (status) {
    TransferStatus.QUEUED -> "排队"
    TransferStatus.RUNNING -> "传输中"
    TransferStatus.PAUSED -> "已暂停"
    TransferStatus.COMPLETED -> "已完成"
    TransferStatus.FAILED -> "失败"
    TransferStatus.CANCELLED -> "已取消"
    TransferStatus.SKIPPED -> "已跳过"
}
