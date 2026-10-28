package com.wlzn.oss.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class OssToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val browser = OssBrowserPanel(project)
        val transfers = TransferPanel()
        val factory = ContentFactory.getInstance()
        toolWindow.contentManager.addContent(factory.createContent(browser, "文件浏览", false).apply { setDisposer(browser) })
        toolWindow.contentManager.addContent(factory.createContent(transfers, "传输任务", false).apply { setDisposer(transfers) })
    }
}
