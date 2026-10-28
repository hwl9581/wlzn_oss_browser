package com.wlzn.oss.ui

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer

internal object BrowserUi {
    val muted: Color get() = UIUtil.getContextHelpForeground()
    val chrome: Color get() = JBColor.namedColor("ToolWindow.Header.background", JBColor(0xF7F8FA, 0x2B2D30))
    val blue: Color get() = JBColor.namedColor("Link.activeForeground", JBColor(0x3574F0, 0x6B9BFA))
    val green: Color get() = JBColor.namedColor("Component.successFocusColor", JBColor(0x287549, 0x73B48A))
    val amber: Color get() = JBColor.namedColor("Component.warningFocusColor", JBColor(0x986300, 0xD4AB68))
    val red: Color get() = JBColor.namedColor("Component.errorFocusColor", JBColor(0xBB4249, 0xE88989))

    fun action(text: String, icon: Icon? = null, showText: Boolean = true,
               enabled: () -> Boolean = { true }, perform: (AnActionEvent) -> Unit): AnAction =
        object : DumbAwareAction(text, text, icon) {
            init { templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, showText) }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun actionPerformed(e: AnActionEvent) { if (enabled()) perform(e) }
        }

    fun toolbar(place: String, target: JComponent, vararg actions: AnAction): ActionToolbar =
        ActionManager.getInstance().createActionToolbar(place, DefaultActionGroup(*actions), true).apply {
            setTargetComponent(target)
            layoutStrategy = ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY
            setMinimumButtonSize(JBUI.size(28, 28))
            component.border = JBUI.Borders.empty()
            component.isOpaque = false
            component.name = place
        }

    fun label(text: String = "") = JLabel(text).apply {
        foreground = muted; font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL).deriveFont(Font.PLAIN)
        putClientProperty("html.disable", true)
    }

    fun addCell(panel: JPanel, component: Component, column: Int, weight: Double = 0.0) {
        panel.add(component, GridBagConstraints().apply {
            gridx = column; gridy = 0; weightx = weight
            fill = if (weight > 0) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0, if (column == 0) 0 else 6, 0, 0)
        })
    }

    fun footer(left: JLabel, right: JLabel) = JPanel(BorderLayout(12, 0)).apply {
        border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(6, 12))
        add(left, BorderLayout.CENTER); add(right, BorderLayout.EAST)
    }

    fun styleTable(table: JBTable, rowHeight: Int) {
        table.rowHeight = JBUI.scale(rowHeight)
        table.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN)
        table.showHorizontalLines = false; table.showVerticalLines = false
        table.intercellSpacing = Dimension(0, 0)
        table.fillsViewportHeight = true
        table.tableHeader.reorderingAllowed = false
        table.tableHeader.preferredSize = JBUI.size(0, 30)
        table.tableHeader.font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL).deriveFont(Font.PLAIN)
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            init { putClientProperty("html.disable", true) }
            override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column)
                border = JBUI.Borders.empty(0, 12)
                return this
            }
        })
    }

    fun layoutCell(panel: JPanel, table: JTable, row: Int, column: Int) {
        panel.size = table.getCellRect(row, column, false).size
        panel.invalidate()
        panel.doLayout()
        panel.components.filterIsInstance<Container>().forEach { it.doLayout() }
    }
}
