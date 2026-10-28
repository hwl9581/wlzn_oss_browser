package com.wlzn.oss.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.wlzn.oss.model.MultipartEntry
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableModel

class DestinationDialog(bucket: String, prefix: String, move: Boolean) : DialogWrapper(true) {
    private val bucketField = JTextField(bucket, 30)
    private val prefixField = JTextField(prefix, 35)
    private val overwriteField = JCheckBox("允许覆盖写前检查时已存在的同名对象", false)
    val bucket: String get() = bucketField.text.trim()
    val prefix: String get() = prefixField.text
    val overwrite: Boolean get() = overwriteField.isSelected
    init { title = if (move) "移动到（复制成功后删除来源）" else "复制到"; init() }
    override fun createCenterPanel(): JComponent = panel {
        row("目标 Bucket：") { cell(bucketField) }
        row("目标目录：") { cell(prefixField) }
        row { cell(overwriteField) }
        row { comment("同账号 / 云服务与 Region 内操作。复制非原子：写前检查无法阻止其他客户端同时写入；失败后请刷新检查。") }
    }
    override fun doValidate() = if (bucket.isBlank()) ValidationInfo("目标 Bucket 不能为空", bucketField) else null
}

class TextObjectDialog(titleText: String, text: String, private val editable: Boolean, private val note: String = "") : DialogWrapper(true) {
    private val area = JTextArea(text, 28, 90).apply { isEditable = editable; tabSize = 2; caretPosition = 0 }
    val text: String get() = area.text
    init {
        title = titleText
        init()
        setOKButtonText(if (editable) "保存到云端" else "关闭")
        if (!editable) setCancelButtonText("关闭")
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        if (note.isNotEmpty()) add(JLabel(note), BorderLayout.NORTH)
        add(JBScrollPane(area), BorderLayout.CENTER)
    }
    override fun doValidate() = if (editable && text.toByteArray().size > 5 * 1024 * 1024)
        ValidationInfo("编辑内容超过 5 MiB，请改用上传文件", area) else null
}

class MultipartDialog(private val entries: List<MultipartEntry>) : DialogWrapper(true) {
    private val table = JBTable(object : DefaultTableModel(arrayOf("对象路径", "Upload ID", "开始时间"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }.apply { entries.forEach { addRow(arrayOf(it.key, it.uploadId, it.initiated?.toString().orEmpty())) } }).apply {
        autoCreateRowSorter = true
        preferredScrollableViewportSize = Dimension(850, 350)
    }
    val selected: List<MultipartEntry> get() = table.selectedRows.map { entries[table.convertRowIndexToModel(it)] }
    init { title = "未完成分片任务（${entries.size}）"; init(); setOKButtonText("终止所选分片") }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        add(JLabel("终止会删除已上传的分片，并使对应断点无法恢复；不会删除已完成对象。"), BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }
    override fun doValidate() = if (selected.isEmpty()) ValidationInfo("请选择需要清理的分片任务", table) else null
}
