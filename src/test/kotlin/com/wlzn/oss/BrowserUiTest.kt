package com.wlzn.oss

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.wlzn.oss.config.OssSettings
import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageClient
import com.wlzn.oss.ui.*
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class BrowserUiTest : BasePlatformTestCase() {
    private lateinit var oldState: OssSettings.State
    private lateinit var connection: ConnectionProfile
    private var oldAsyncProperty: String? = null
    override fun setUp() {
        super.setUp()
        oldAsyncProperty = System.getProperty("intellij.progress.task.ignoreHeadless")
        System.setProperty("intellij.progress.task.ignoreHeadless", "true")
        oldState = OssSettings.getInstance().state
        connection = ConnectionProfile(name = "国内 OSS", region = "cn-hangzhou", prefix = "share/appconf/test/message/")
        OssSettings.getInstance().replaceProfiles(listOf(connection))
    }
    override fun tearDown() {
        try {
            PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            if (oldAsyncProperty == null) System.clearProperty("intellij.progress.task.ignoreHeadless") else System.setProperty("intellij.progress.task.ignoreHeadless", oldAsyncProperty!!)
            OssSettings.getInstance().loadState(oldState)
        } finally { super.tearDown() }
    }

    fun testBucketHierarchyAndBreadcrumbNavigateTheSameFileList() {
        val keys = AtomicReference(listOf("share/appconf/test/message/images/a.jpg", "share/appconf/test/message/default.jpg", "assets/logo.png"))
        val browser = OssBrowserPanel(project) { fixture(keys) }
        try {
            render(browser, "initial")
            invoke(browser, "连接")
            val table = components(browser).filterIsInstance<JTable>().single()
            waitFor("初始目录文件加载") { table.rowCount == 2 }
            val tree = components(browser).filterIsInstance<JTree>().single()
            waitFor("多级目录同步展开") { nodes(tree).any { it.toString() == "message" } }
            val images = nodes(tree).firstOrNull { it.toString() == "images" }
            if (images == null) {
                val message = nodes(tree).first { it.toString() == "message" }
                tree.expandPath(TreePath(message.path))
                waitFor("展开加载子目录") { nodes(tree).any { it.toString() == "images" } }
            }
            tree.selectionPath = TreePath(nodes(tree).first { it.toString() == "images" }.path)
            waitFor("点击目录定位右侧列表") { table.rowCount == 1 && table.getValueAt(0, 0) == "a.jpg" }
            val parent = components(browser).filterIsInstance<JButton>().first { it.text == "message" }
            parent.doClick()
            waitFor("面包屑回到父目录") { table.rowCount == 2 }
            keys.set(keys.get() + "share/appconf/test/message/new-folder/b.txt")
            invoke(browser, "刷新")
            waitFor("刷新文件及左树") { table.rowCount == 3 && nodes(tree).any { it.toString() == "new-folder" } }
        } finally { Disposer.dispose(browser) }
    }

    fun testRefreshInvalidatesSlowOlderDirectoryResponse() {
        val calls = AtomicInteger(); val started = CountDownLatch(1); val release = CountDownLatch(1); val oldFinished = AtomicBoolean()
        val storage = object : StorageClient by FakeStorage() {
            override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean): ObjectPage {
                if (calls.incrementAndGet() == 1) {
                    started.countDown(); release.await(5, TimeUnit.SECONDS); oldFinished.set(true)
                    return ObjectPage(listOf(ObjectEntry("stale/", directory = true)))
                }
                return ObjectPage(listOf(ObjectEntry("fresh/", directory = true)))
            }
        }
        val panel = BucketTreePanel(project, { storage }) { _, _ -> }
        try {
            panel.setConnection(connection, listOf(BucketEntry("test-bucket")))
            val tree = components(panel).filterIsInstance<JTree>().single()
            tree.expandPath(TreePath(nodes(tree).first { it.toString() == "test-bucket" }.path))
            waitFor("旧目录请求已开始") { started.count == 0L }
            panel.refreshLocation("test-bucket", "")
            waitFor("刷新响应先到达") { nodes(tree).any { it.toString() == "fresh" } }
            release.countDown()
            waitFor("旧请求已结束") { oldFinished.get() }
            PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            PlatformTestUtil.waitWithEventsDispatching("旧响应被忽略", { calls.get() >= 2 && nodes(tree).none { it.toString() == "stale" } }, 2000)
            assertTrue(nodes(tree).any { it.toString() == "fresh" })
        } finally { release.countDown(); Disposer.dispose(panel) }
    }

    fun testPaginatedRefreshRemovesDeletedDirectories() {
        val changed = AtomicBoolean()
        val storage = object : StorageClient by FakeStorage() {
            override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean) = if (token == null)
                ObjectPage(listOf(ObjectEntry(if (changed.get()) "new/" else "deleted/", directory = true)), "page2")
            else ObjectPage(listOf(ObjectEntry("kept/", directory = true)))
        }
        val panel = BucketTreePanel(project, { storage }) { _, _ -> }
        try {
            panel.setConnection(connection, listOf(BucketEntry("test-bucket")))
            val tree = components(panel).filterIsInstance<JTree>().single()
            tree.expandPath(TreePath(nodes(tree).first { it.toString() == "test-bucket" }.path))
            waitFor("第一页完成") { nodes(tree).any { it.toString() == "deleted" } }
            tree.selectionPath = TreePath(nodes(tree).first { it.toString() == "加载更多目录…" }.path)
            waitFor("第二页目录完成") { nodes(tree).any { it.toString() == "kept" } }
            changed.set(true); panel.refreshLocation("test-bucket", "")
            waitFor("分页刷新删除旧节点") { nodes(tree).any { it.toString() == "new" } && nodes(tree).none { it.toString() == "deleted" } }
            tree.selectionPath = TreePath(nodes(tree).first { it.toString() == "加载更多目录…" }.path)
            waitFor("刷新后可继续翻页") { nodes(tree).any { it.toString() == "kept" } }
        } finally { Disposer.dispose(panel) }
    }

    fun testDirectoryResponseIsIgnoredAfterProfileRevisionChanges() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = AtomicBoolean()
        val storage = object : StorageClient by FakeStorage() {
            override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean): ObjectPage {
                started.countDown(); release.await(5, TimeUnit.SECONDS); finished.set(true)
                return ObjectPage(listOf(ObjectEntry("old-account/", directory = true)))
            }
        }
        val panel = BucketTreePanel(project, { storage }) { _, _ -> }
        try {
            panel.setConnection(connection, listOf(BucketEntry("test-bucket")))
            val tree = components(panel).filterIsInstance<JTree>().single()
            tree.expandPath(TreePath(nodes(tree).first { it.toString() == "test-bucket" }.path))
            waitFor("请求已开始") { started.count == 0L }
            OssSettings.getInstance().replaceProfiles(listOf(connection.copy(revision = connection.revision + 1)))
            release.countDown(); waitFor("旧连接响应返回") { finished.get() }
            PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue(nodes(tree).none { it.toString() == "old-account" })
        } finally { release.countDown(); Disposer.dispose(panel) }
    }

    fun testTransferFiltersKeepProgressAndRowActionsAfterSorting() {
        val rows = sampleTransfers()
        val panel = TransferPanel { rows }
        try {
            render(panel, "transfers")
            val table = components(panel).filterIsInstance<JTable>().single()
            assertEquals(4, table.rowCount)
            table.rowSorter.toggleSortOrder(0)
            val row = (0 until table.rowCount).first { table.getValueAt(it, 0) == "onboarding-assets.zip" }
            val column = table.convertColumnIndexToView(3)
            assertTrue(table.editCellAt(row, column))
            assertTrue(components(table.editorComponent as Container).filterIsInstance<ActionLink>().any { it.text == "继续 / 重试" })
            table.cellEditor.cancelCellEditing()
            invoke(panel, "失败"); assertEquals(1, table.rowCount); assertEquals("failed.json", table.getValueAt(0, 0))
            invoke(panel, "进行中"); assertEquals(2, table.rowCount)
            invoke(panel, "已完成"); assertEquals(1, table.rowCount); assertEquals(100, table.getValueAt(0, table.convertColumnIndexToView(1)))
        } finally { Disposer.dispose(panel) }
    }

    fun testEditingAnOperationCellDoesNotFreezeProgressOrFilters() {
        val row = TransferRecord(key = "file.zip", status = TransferStatus.RUNNING, totalBytes = 100, transferredBytes = 10)
        val panel = TransferPanel { listOf(row.copy()) }
        try {
            render(panel, "editor-progress")
            val table = components(panel).filterIsInstance<JTable>().single()
            assertTrue(table.editCellAt(0, table.convertColumnIndexToView(3)))
            row.transferredBytes = 70
            waitFor("操作单元格编辑期间仍更新进度") { table.getValueAt(0, table.convertColumnIndexToView(1)) == 70 }
            invoke(panel, "失败"); assertEquals(0, table.rowCount); assertFalse(table.isEditing)
            invoke(panel, "全部"); assertEquals(1, table.rowCount)
        } finally { Disposer.dispose(panel) }
    }

    fun testNativeSidebarAndTransfersRenderAtWideAndNarrowSizes() {
        val keys = AtomicReference(listOf("share/appconf/test/message/images/a.jpg", "share/appconf/test/message/archive/history.json",
            "share/appconf/test/message/default.jpg", "share/appconf/test/message/banner.png", "share/appconf/test/message/message.json", "share/appconf/test/message/app-config.json"))
        val oldLaf = UIManager.getLookAndFeel(); val wasDark = !JBColor.isBright()
        val browser = OssBrowserPanel(project) { fixture(keys) }
        val transfers = TransferPanel { sampleTransfers() }
        try {
            invoke(browser, "连接")
            val table = components(browser).filterIsInstance<JTable>().single()
            waitFor("示例文件加载") { table.rowCount == 6 }
            table.setRowSelectionInterval((0 until table.rowCount).first { table.getValueAt(it, 0) == "default.jpg" }, (0 until table.rowCount).first { table.getValueAt(it, 0) == "default.jpg" })
            UIManager.setLookAndFeel("com.intellij.ide.ui.laf.darcula.DarculaLaf"); JBColor.setDark(true)
            com.intellij.ide.ui.LafManager.getInstance().defaultDarkLaf?.installTheme(UIManager.getLookAndFeelDefaults())
            com.intellij.openapi.util.IconLoader.activate()
            SwingUtilities.updateComponentTreeUI(browser); SwingUtilities.updateComponentTreeUI(transfers)
            render(browser, "browser-dark", 1100, 560); render(transfers, "transfers-dark", 1100, 520)
            render(browser, "browser-narrow", 560, 560); render(transfers, "transfers-narrow", 560, 520)
            assertTrue(components(browser).filterIsInstance<JSplitPane>().single().leftComponent.width >= 100)
            UIManager.setLookAndFeel(oldLaf); JBColor.setDark(wasDark)
            SwingUtilities.updateComponentTreeUI(browser); render(browser, "browser-light", 1100, 560)
        } finally {
            UIManager.setLookAndFeel(oldLaf); JBColor.setDark(wasDark)
            Disposer.dispose(browser); Disposer.dispose(transfers)
        }
    }

    private fun fixture(keys: AtomicReference<List<String>>): StorageClient = object : StorageClient by FakeStorage() {
        override fun buckets() = listOf(BucketEntry("test-bucket", "cn-hangzhou"), BucketEntry("assets-bucket", "cn-shanghai"))
        override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean): ObjectPage {
            val entries = keys.get().filter { it.startsWith(prefix) && it != prefix }.map { key ->
                val rest = key.removePrefix(prefix)
                if (!recursive && '/' in rest) ObjectEntry(prefix + rest.substringBefore('/') + "/", directory = true)
                else ObjectEntry(key, 131686, Instant.parse("2026-10-28T06:20:00Z"), storageClass = "Standard")
            }.distinctBy { it.key }.sortedWith(compareBy<ObjectEntry> { !it.directory }.thenBy { it.key })
            return ObjectPage(entries)
        }
    }

    private fun sampleTransfers() = listOf(
        TransferRecord(key = "share/app-resources.zip", bucket = "test-bucket", profileName = "国内 OSS", status = TransferStatus.RUNNING, totalBytes = 24800000, transferredBytes = 15400000),
        TransferRecord(key = "assets/onboarding-assets.zip", bucket = "test-bucket", profileName = "国内 OSS", kind = TransferKind.DOWNLOAD, status = TransferStatus.PAUSED, totalBytes = 12600000, transferredBytes = 9800000),
        TransferRecord(key = "message/default.jpg", bucket = "test-bucket", profileName = "国内 OSS", status = TransferStatus.COMPLETED, totalBytes = 131686, transferredBytes = 131686),
        TransferRecord(key = "message/failed.json", bucket = "test-bucket", profileName = "国内 OSS", status = TransferStatus.FAILED, message = "访问被拒绝，请检查账号权限和 Bucket 策略", totalBytes = 3200)
    )

    private fun invoke(component: JComponent, text: String) {
        val action = components(component).mapNotNull { ActionToolbar.findToolbarBy(it) }.distinct().flatMap { it.actionGroup.getChildren(null).toList() }
            .first { it.templatePresentation.text == text }
        val event = AnActionEvent.createEvent(DataContext.EMPTY_CONTEXT, action.templatePresentation.clone(), "WLZN.Test", ActionUiKind.TOOLBAR, null)
        action.update(event); assertTrue("Action disabled: $text", event.presentation.isEnabled)
        action.actionPerformed(event)
    }
    private fun nodes(tree: JTree) = (tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList().filterIsInstance<DefaultMutableTreeNode>()
    private fun waitFor(message: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching(message, condition, 7000)
    private fun components(container: Container): List<java.awt.Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
    private fun layout(container: Container) { container.invalidate(); container.doLayout(); container.components.filterIsInstance<Container>().forEach { layout(it) } }
    private fun render(component: JComponent, name: String, width: Int = 1100, height: Int = 560) {
        component.setSize(width, height); repeat(3) { layout(component) }
        components(component).filterIsInstance<JViewport>().forEach { it.dispatchEvent(java.awt.event.ComponentEvent(it, java.awt.event.ComponentEvent.COMPONENT_RESIZED)) }
        repeat(2) { layout(component) }
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { component.printAll(graphics) } finally { graphics.dispose() }
        ImageIO.write(image, "png", Path.of(System.getProperty("java.io.tmpdir"), "wlzn-oss-native-$name.png").toFile())
    }
}
