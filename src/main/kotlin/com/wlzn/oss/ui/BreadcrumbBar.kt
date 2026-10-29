package com.wlzn.oss.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/** 只维护路径显示；云端导航仍由浏览面板执行。 */
internal class BreadcrumbBar(private val navigate: (String) -> Unit) : JPanel(CardLayout()) {
    val editor = JBTextField().apply { emptyText.text = "输入目录路径，按 Enter 前往" }
    private val crumbs = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false }
    private val scroll = JBScrollPane(crumbs, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
        border = JBUI.Borders.empty(); viewport.isOpaque = false; isOpaque = false
    }
    private var editing = false
    private var enabledNavigation = false

    init {
        minimumSize = JBUI.size(45, 28)
        preferredSize = JBUI.size(360, 28)
        add(scroll, "crumbs"); add(editor, "editor")
        editor.addActionListener { if (enabledNavigation) { editing = false; showCard(); navigate(editor.text) } }
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ESCAPE) { editing = false; showCard(); e.consume() } }
        })
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { revealCurrent() }
        })
        showPath("")
    }

    fun showPath(prefix: String) {
        editor.text = prefix
        crumbs.removeAll()
        fun crumb(text: String, destination: String) {
            crumbs.add(JButton(text).apply {
                putClientProperty("html.disable", true)
                border = JBUI.Borders.empty(4, 5); isContentAreaFilled = false; isBorderPainted = false
                maximumSize = preferredSize
                isEnabled = enabledNavigation; toolTipText = if (destination.isEmpty()) "Bucket 根目录" else "目录：$destination"
                addActionListener { navigate(destination) }
            })
        }
        crumb("/", "")
        var destination = ""
        prefix.removeSuffix("/").takeIf { it.isNotEmpty() }?.split('/')?.forEach { segment ->
            if (destination.isNotEmpty()) crumbs.add(BrowserUi.label(" / ").apply { maximumSize = preferredSize })
            destination += "$segment/"
            crumb(segment.ifEmpty { "∅" }, destination)
        }
        crumbs.add(Box.createHorizontalGlue())
        editing = false; showCard(); crumbs.revalidate(); crumbs.repaint()
        SwingUtilities.invokeLater { revealCurrent() }
    }

    fun editPath() {
        if (!enabledNavigation) return
        editing = !editing; showCard()
        if (editing) { editor.requestFocusInWindow(); editor.selectAll() }
    }

    fun setNavigationEnabled(value: Boolean) {
        enabledNavigation = value; editor.isEnabled = value
        crumbs.components.forEach { it.isEnabled = value }
    }

    private fun showCard() = (layout as CardLayout).show(this, if (editing) "editor" else "crumbs")
    private fun revealCurrent() {
        val width = maxOf(crumbs.preferredSize.width, scroll.viewport.extentSize.width)
        scroll.viewport.viewPosition = Point(maxOf(0, width - scroll.viewport.extentSize.width), 0)
    }
}
