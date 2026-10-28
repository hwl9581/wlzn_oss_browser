package com.wlzn.oss.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageClient
import com.wlzn.oss.storage.StorageErrors
import com.wlzn.oss.config.OssSettings
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.*

/** 每次只列举展开目录的一页；不在连接时遍历所有 Bucket 或递归扫描对象。 */
internal class BucketTreePanel(private val project: Project, private val openClient: (ConnectionProfile) -> StorageClient,
    private val navigate: (String, String) -> Unit) : JPanel(BorderLayout()), Disposable {
    private class Location(val bucket: String, val prefix: String, val region: String = "") : DefaultMutableTreeNode() {
        var loaded = false
        var loading = false
        var nextToken: String? = null
        var request = 0
        init { add(DefaultMutableTreeNode("展开加载目录")) }
        override fun toString() = if (prefix.isEmpty()) bucket else prefix.removeSuffix("/").substringAfterLast('/').ifEmpty { "∅" }
    }
    private class More : DefaultMutableTreeNode("加载更多目录…")
    private class Retry(val message: String) : DefaultMutableTreeNode("加载失败，点击重试")
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        name = "oss.bucket-tree"; isRootVisible = false; showsRootHandles = true
        rowHeight = JBUI.scale(30)
        background = BrowserUi.chrome
        font = com.intellij.util.ui.UIUtil.getLabelFont().deriveFont(java.awt.Font.PLAIN)
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        emptyText.text = "连接账号后查看 Bucket"
    }
    private var profile: ConnectionProfile? = null
    private var generation = 0
    private var selecting = false
    private var disposed = false

    init {
        border = JBUI.Borders.empty(4, 4, 4, 0)
        background = BrowserUi.chrome
        minimumSize = JBUI.size(120, 0)
        add(JBScrollPane(tree).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            init { putClientProperty("html.disable", true) }
            override fun getTreeCellRendererComponent(t: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus)
                font = t.font
                backgroundNonSelectionColor = t.background
                icon = if (value is Location) { if (value.prefix.isEmpty()) AllIcons.Nodes.DataTables else AllIcons.Nodes.Folder } else null
                toolTipText = when (value) {
                    is Location -> "${value.bucket}/${value.prefix}${if (value.region.isNotBlank()) " · ${value.region}" else ""}"
                    is Retry -> value.message
                    else -> null
                }
                if (value !is Location && !selected) foreground = BrowserUi.muted
                return this
            }
        }
        ToolTipManager.sharedInstance().registerComponent(tree)
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                (event.path.lastPathComponent as? Location)?.takeIf { !it.loaded && !it.loading }?.let { load(it, false) }
            }
            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })
        tree.addTreeSelectionListener {
            if (selecting || !tree.isEnabled) return@addTreeSelectionListener
            when (val node = tree.lastSelectedPathComponent) {
                is Location -> navigate(node.bucket, node.prefix)
                is More -> (node.parent as? Location)?.let { load(it, true) }
                is Retry -> (node.parent as? Location)?.let { load(it, it.loaded) }
            }
        }
    }

    fun setConnection(connection: ConnectionProfile?, buckets: List<BucketEntry> = emptyList()) {
        generation++; profile = connection?.copy()
        selecting = true
        root.removeAllChildren()
        buckets.forEach { root.add(Location(it.name, "", it.region)) }
        model.reload(); tree.clearSelection()
        selecting = false
        tree.emptyText.text = if (connection == null) "连接账号后查看 Bucket" else "暂无 Bucket，可在管理菜单创建或打开"
    }

    fun addBucket(bucket: BucketEntry) {
        if (bucketNode(bucket.name) == null) model.insertNodeInto(Location(bucket.name, "", bucket.region), root, root.childCount)
    }

    fun removeBucket(bucket: String) { bucketNode(bucket)?.let { model.removeNodeFromParent(it) } }
    fun setNavigationEnabled(value: Boolean) { tree.isEnabled = value }

    fun selectLocation(bucket: String, prefix: String) {
        if (disposed) return
        selecting = true
        try {
            addBucket(BucketEntry(bucket))
            var node = bucketNode(bucket) ?: return
            var path = ""
            prefix.removeSuffix("/").takeIf { it.isNotEmpty() }?.split('/')?.forEach { segment ->
                path += "$segment/"
                val child = children(node).filterIsInstance<Location>().firstOrNull { it.prefix == path }
                    ?: Location(bucket, path).also { model.insertNodeInto(it, node, locationCount(node)) }
                node = child
            }
            val selection = TreePath(node.path)
            tree.selectionPath = selection
            tree.scrollPathToVisible(selection)
        } finally { selecting = false }
    }

    fun refreshLocation(bucket: String, prefix: String) {
        find(bucket, prefix)?.let { node ->
            node.request++; node.loading = false
            node.loaded = false; node.nextToken = null
            load(node, false)
        }
    }

    private fun load(node: Location, append: Boolean) {
        val connection = profile?.copy() ?: return
        if (node.loading || disposed) return
        val version = generation
        val request = ++node.request
        val token = if (append) node.nextToken else null
        node.loading = true
        children(node).filter { it !is Location }.forEach { model.removeNodeFromParent(it) }
        model.insertNodeInto(DefaultMutableTreeNode("正在加载…"), node, node.childCount)
        com.intellij.openapi.progress.ProgressManager.getInstance().run(object : Task.Backgroundable(project, "加载 ${node.bucket}/${node.prefix} 目录", true) {
            private var page: ObjectPage? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.checkCanceled()
                page = openClient(connection).use { it.list(node.bucket, node.prefix, token) }
                require(page?.nextToken == null || page?.nextToken != token) { "目录分页游标未前进" }
            }
            private fun alive() = !disposed && !project.isDisposed && generation == version && node.request == request && node.root === root &&
                OssSettings.getInstance().profiles().any { it.id == connection.id && it.revision == connection.revision }
            override fun onSuccess() {
                if (!alive()) return
                val result = page ?: return
                selecting = true
                try {
                    val selectedPrefix = (tree.lastSelectedPathComponent as? Location)?.takeIf { it.bucket == node.bucket }?.prefix
                    val folders = result.entries.filter { it.directory && it.key != node.prefix }
                    if (!append) children(node).filterIsInstance<Location>().filter { existing ->
                        folders.none { it.key == existing.prefix } &&
                            (selectedPrefix == null || !selectedPrefix.startsWith(existing.prefix))
                    }.forEach { model.removeNodeFromParent(it) }
                    children(node).filter { it !is Location }.forEach { model.removeNodeFromParent(it) }
                    val existing = children(node).filterIsInstance<Location>().associateBy { it.prefix }.toMutableMap()
                    folders.sortedBy { it.key }.forEach { entry ->
                        if (!existing.containsKey(entry.key)) {
                            val child = Location(node.bucket, entry.key)
                            model.insertNodeInto(child, node, locationCount(node))
                            existing[entry.key] = child
                        }
                    }
                    node.loaded = true; node.nextToken = result.nextToken
                    if (result.nextToken != null) model.insertNodeInto(More(), node, node.childCount)
                    model.nodeChanged(node)
                } finally { selecting = false }
            }
            override fun onThrowable(error: Throwable) {
                StorageErrors.logBrowserFailure(error)
                if (alive()) showRetry(StorageErrors.describe(error))
            }
            override fun onCancel() { if (alive()) showRetry("已取消加载，可点击重试") }
            private fun showRetry(message: String) {
                selecting = true
                try {
                    children(node).filter { it !is Location }.forEach { model.removeNodeFromParent(it) }
                    model.insertNodeInto(Retry(message), node, node.childCount)
                } finally { selecting = false }
            }
            override fun onFinished() { if (alive()) node.loading = false }
        })
    }

    private fun bucketNode(bucket: String) = children(root).filterIsInstance<Location>().firstOrNull { it.bucket == bucket }
    private fun children(node: DefaultMutableTreeNode) = (0 until node.childCount).map { node.getChildAt(it) as DefaultMutableTreeNode }
    private fun locationCount(node: DefaultMutableTreeNode) = children(node).count { it is Location }
    private fun find(bucket: String, prefix: String): Location? = root.depthFirstEnumeration().toList().filterIsInstance<Location>().firstOrNull { it.bucket == bucket && it.prefix == prefix }
    override fun dispose() { disposed = true; generation++; ToolTipManager.sharedInstance().unregisterComponent(tree) }
}
