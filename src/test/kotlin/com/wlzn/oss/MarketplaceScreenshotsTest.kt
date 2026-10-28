package com.wlzn.oss

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.wlzn.oss.config.OssSettings
import com.wlzn.oss.model.*
import com.wlzn.oss.storage.StorageClient
import com.wlzn.oss.ui.OssBrowserPanel
import com.wlzn.oss.ui.TransferPanel
import java.awt.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.*

/** Public listing assets use only these invented fixtures, never the desktop IDE or PasswordSafe. */
class MarketplaceScreenshotsTest : BasePlatformTestCase() {
    fun testRenderPublicListingWithOnlyDemoData() {
        val settings = OssSettings.getInstance()
        val original = settings.state
        val originalLaf = UIManager.getLookAndFeel()
        val originalDark = !JBColor.isBright()
        val originalAsync = System.getProperty("intellij.progress.task.ignoreHeadless")
        val palette = mapOf(
            "Panel.background" to Color(0x2B2D30), "Table.background" to Color(0x1E1F22),
            "Table.foreground" to Color(0xDFE1E5), "Table.selectionBackground" to Color(0x2E436E),
            "Table.selectionForeground" to Color(0xFFFFFF), "TableHeader.background" to Color(0x2B2D30),
            "TableHeader.foreground" to Color(0xA8ADBD), "Label.foreground" to Color(0xDFE1E5),
            "TextField.background" to Color(0x1E1F22), "TextField.foreground" to Color(0xDFE1E5),
            "ComboBox.background" to Color(0x2B2D30), "ComboBox.foreground" to Color(0xDFE1E5),
            "Button.foreground" to Color(0xDFE1E5), "ToolWindow.Header.background" to Color(0x2B2D30)
        )
        val originalPalette = palette.keys.associateWith { UIManager.get(it) }
        val oss = ConnectionProfile(name = "OSS Demo", provider = Provider.ALIYUN_OSS, region = "cn-shanghai", prefix = "examples/website/")
        val s3 = ConnectionProfile(name = "S3 Demo", provider = Provider.AMAZON_S3, region = "us-east-1", prefix = "examples/website/")
        System.setProperty("intellij.progress.task.ignoreHeadless", "true")
        UIManager.setLookAndFeel("com.intellij.ide.ui.laf.darcula.DarculaLaf")
        JBColor.setDark(true)
        com.intellij.ide.ui.LafManager.getInstance().defaultDarkLaf?.installTheme(UIManager.getLookAndFeelDefaults())
        com.intellij.openapi.util.IconLoader.activate()
        palette.forEach { (key, value) -> UIManager.put(key, value) }
        try {
            for ((profile, file) in listOf(oss to "01-oss-browser.png", s3 to "02-s3-browser.png")) {
                settings.replaceProfiles(listOf(profile))
                val panel = OssBrowserPanel(project) { demoStorage(profile.provider) }
                try {
                    panel.setSize(1280, 590); layout(panel)
                    val connect = components(panel).mapNotNull { ActionToolbar.findToolbarBy(it) }.distinct()
                        .flatMap { it.actionGroup.getChildren(null).toList() }.first { it.templatePresentation.text == "连接" }
                    connect.actionPerformed(AnActionEvent.createEvent(DataContext.EMPTY_CONTEXT, connect.templatePresentation.clone(), "WLZN.Demo", ActionUiKind.TOOLBAR, null))
                    val table = components(panel).filterIsInstance<JTable>().single()
                    PlatformTestUtil.waitWithEventsDispatching("Demo listing loaded", { table.rowCount == 6 }, 7000)
                    table.setRowSelectionInterval(2, 2)
                    render(panel, file, if (profile.provider == Provider.ALIYUN_OSS) "Explore OSS buckets and folders" else "Bring Amazon S3 into your IDE",
                        "Bucket tree  /  Breadcrumb navigation  /  File previews  /  Native actions")
                } finally { Disposer.dispose(panel) }
            }
            val tasks = listOf(
                TransferRecord(key = "examples/demo-bundle.zip", bucket = "demo-assets", profileName = "OSS Demo", status = TransferStatus.RUNNING, totalBytes = 32000000, transferredBytes = 22080000),
                TransferRecord(key = "media/sample-video.mp4", bucket = "demo-media", profileName = "S3 Demo", kind = TransferKind.DOWNLOAD, status = TransferStatus.PAUSED, totalBytes = 48000000, transferredBytes = 12000000),
                TransferRecord(key = "images/welcome.png", bucket = "demo-assets", profileName = "OSS Demo", status = TransferStatus.COMPLETED, totalBytes = 256000, transferredBytes = 256000),
                TransferRecord(key = "documents/guide.pdf", bucket = "demo-documents", profileName = "S3 Demo", status = TransferStatus.FAILED, totalBytes = 2400000, message = "Demo task — ready to retry")
            )
            val transfers = TransferPanel { tasks }
            try { render(transfers, "03-transfer-tasks.png", "Keep transfers under control",
                "Resumable transfers  /  Progress tracking  /  Pause and resume  /  Retry and cancel")
            } finally { Disposer.dispose(transfers) }
        } finally {
            PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()
            settings.loadState(original)
            UIManager.setLookAndFeel(originalLaf); JBColor.setDark(originalDark)
            originalPalette.forEach { (key, value) -> UIManager.put(key, value) }
            if (originalAsync == null) System.clearProperty("intellij.progress.task.ignoreHeadless") else System.setProperty("intellij.progress.task.ignoreHeadless", originalAsync)
        }
    }

    private fun demoStorage(provider: Provider): StorageClient = object : StorageClient by FakeStorage() {
        override fun buckets() = if (provider == Provider.ALIYUN_OSS)
            listOf(BucketEntry("demo-assets", "cn-shanghai"), BucketEntry("demo-documents", "ap-southeast-1"), BucketEntry("demo-backups", "cn-beijing"))
        else listOf(BucketEntry("demo-assets", "us-east-1"), BucketEntry("demo-documents", "eu-west-1"), BucketEntry("demo-backups", "ap-southeast-2"))
        override fun list(bucket: String, prefix: String, token: String?, recursive: Boolean): ObjectPage {
            val keys = listOf("examples/website/images/banner.png", "examples/website/fonts/demo.woff2", "examples/website/hero.png",
                "examples/website/manifest.json", "examples/website/README.md", "examples/website/release.zip", "documents/guide.pdf", "archives/sample.zip")
            val result = keys.filter { it.startsWith(prefix) && it != prefix }.map { key ->
                val rest = key.removePrefix(prefix)
                if (!recursive && '/' in rest) ObjectEntry(prefix + rest.substringBefore('/') + "/", directory = true)
                else ObjectEntry(key, when { key.endsWith(".zip") -> 4800000L; key.endsWith(".png") -> 262144L; else -> 4096L },
                    Instant.parse("2026-01-15T10:30:00Z"), storageClass = "Standard")
            }.distinctBy { it.key }.sortedWith(compareBy<ObjectEntry> { !it.directory }.thenBy { it.key })
            return ObjectPage(result)
        }
    }

    private fun render(component: JComponent, file: String, heading: String, subtitle: String) {
        val background = Color(0x111318)
        val wrapper = JPanel(BorderLayout(0, 24)).apply {
            this.background = background; border = BorderFactory.createEmptyBorder(35, 40, 24, 40)
            add(JPanel().apply {
                isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel("WLZN OSS BROWSER   ·   ALIBABA CLOUD OSS + AMAZON S3").apply { foreground = Color(0x8EAFF0); font = Font("SansSerif", Font.BOLD, 12) })
                add(Box.createVerticalStrut(14))
                add(JLabel(heading).apply { foreground = Color(0xF4F5F7); font = Font("SansSerif", Font.BOLD, 30) })
                add(Box.createVerticalStrut(12))
                add(JLabel(subtitle).apply { foreground = Color(0xA6ACB7); font = Font("SansSerif", Font.PLAIN, 14) })
            }, BorderLayout.NORTH)
            add(component, BorderLayout.CENTER)
            add(JLabel("DEMO DATA ONLY  •  All accounts, buckets, paths and tasks shown are fictional.").apply {
                foreground = Color(0x8C94A3); font = Font("SansSerif", Font.PLAIN, 12)
            }, BorderLayout.SOUTH)
        }
        wrapper.setSize(1400, 860)
        repeat(4) { layout(wrapper) }
        components(wrapper).filterIsInstance<JViewport>().forEach { it.dispatchEvent(java.awt.event.ComponentEvent(it, java.awt.event.ComponentEvent.COMPONENT_RESIZED)) }
        repeat(2) { layout(wrapper) }
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        val image = BufferedImage(1400, 860, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics -> try { wrapper.printAll(graphics) } finally { graphics.dispose() } }
        val output = Path.of("build/marketplace/screenshots")
        Files.createDirectories(output)
        ImageIO.write(image, "png", output.resolve(file).toFile())
        assertTrue(Files.size(output.resolve(file)) > 10000)
    }
    private fun components(container: Container): List<Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
    private fun layout(container: Container) { container.invalidate(); container.doLayout(); container.components.filterIsInstance<Container>().forEach { layout(it) } }
}
