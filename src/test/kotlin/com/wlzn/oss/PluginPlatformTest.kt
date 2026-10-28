package com.wlzn.oss

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.wlzn.oss.config.OssSettings
import com.wlzn.oss.config.OssSettingsConfigurable
import com.wlzn.oss.model.*
import com.wlzn.oss.ui.OssBrowserPanel
import com.wlzn.oss.ui.TransferPanel
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*

class PluginPlatformTest : BasePlatformTestCase() {
    fun testStateIsDefensivelyCopiedAndContainsNoCredentials() {
        val settings = OssSettings()
        val profile = ConnectionProfile(name = "S3 开发环境", provider = Provider.AMAZON_S3, region = "us-east-1")
        settings.replaceProfiles(listOf(profile))
        profile.name = "changed"
        assertEquals("S3 开发环境", settings.profiles().single().name)
        settings.state.profiles.single().name = "changed again"
        assertEquals("S3 开发环境", settings.profiles().single().name)
        val xml = com.intellij.openapi.util.JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))
        assertFalse(xml.contains("accessKey")); assertFalse(xml.contains("sessionToken")); assertFalse(xml.contains("Secret"))
        val restored = XmlSerializer.deserialize(com.intellij.openapi.util.JDOMUtil.load(xml), OssSettings.State::class.java)
        assertEquals(Provider.AMAZON_S3, restored.profiles.single().provider)
    }

    fun testSettingsAndBothToolWindowPanelsCreateAndDispose() {
        val settings = OssSettings.getInstance()
        val previous = settings.state
        settings.replaceProfiles(listOf(
            ConnectionProfile(name = "阿里云开发环境", endpoint = "oss-cn-hangzhou.aliyuncs.com", region = "cn-hangzhou"),
            ConnectionProfile(name = "AWS 开发环境", provider = Provider.AMAZON_S3, region = "us-east-1")
        ))
        val configurable = OssSettingsConfigurable()
        val browser = OssBrowserPanel(project)
        val transfers = TransferPanel()
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset(); assertFalse(configurable.isModified)
            val tabs = JTabbedPane().apply { addTab("文件浏览", browser); addTab("传输任务", transfers) }
            val wrapper = JPanel(BorderLayout()).apply { add(tabs); setSize(1200, 560) }
            layout(wrapper)
            val image = BufferedImage(1200, 560, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().useGraphics { wrapper.printAll(it) }
            ImageIO.write(image, "png", Path.of(System.getProperty("java.io.tmpdir"), "wlzn-oss-browser-preview.png").toFile())
            assertTrue(browser.componentCount > 0); assertTrue(transfers.componentCount > 0)
        } finally {
            configurable.disposeUIResources(); Disposer.dispose(browser); Disposer.dispose(transfers)
            settings.loadState(previous)
        }
    }

    private fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach { layout(it) } }
    private fun java.awt.Graphics2D.useGraphics(action: (java.awt.Graphics2D) -> Unit) { try { action(this) } finally { dispose() } }
}
