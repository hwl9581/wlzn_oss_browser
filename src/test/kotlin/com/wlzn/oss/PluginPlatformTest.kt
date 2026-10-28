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
    fun testProfileDialogRecoversAfterInvalidOkAndNeverAcceptsMissingCredentials() {
        val dialog = OssSettingsConfigurable.ProfileDialog(null)
        try {
            // 平台 headless peer 不保留 rootPane，从校验关联的真实输入框找到原生表单。
            var form: Container = dialog.performValidateAll().single().component!!
            while (form.parent != null) form = form.parent
            val fields = components(form).filterIsInstance<JTextField>().associateBy { it.name }
            val ok = components(form).filterIsInstance<JButton>().first { it.text == "OK" }
            ok.doClick()
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertFalse(dialog.isOK); assertFalse(dialog.isDisposed)
            assertFalse(dialog.isOKActionEnabled)
            fields.getValue("connectionName").text = "多区域账号"
            fields.getValue("accessKeyId").text = "unit-test-key"
            // 缺少 Secret 时仍需阻断，不能通过一直启用 OK 来绕过校验。
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("缺少凭据仍必须校验失败", { dialog.performValidateAll().isNotEmpty() }, 2000)
            assertFalse(dialog.isOK)
            fields.getValue("accessKeySecret").text = "unit-test-secret"
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("修正输入后 OK 必须恢复", { dialog.isOKActionEnabled }, 5000)
            assertTrue(dialog.performValidateAll().isEmpty())
            assertEquals("", dialog.profile().region); assertEquals("", dialog.profile().endpoint)
            ok.doClick()
            assertTrue(dialog.isOK)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testEmptyRegionAndEndpointAllowedForBothCloudsAndManualEndpointRetained() {
        Provider.entries.forEach { provider ->
            val profile = ConnectionProfile(name = "自动区域", provider = provider)
            profile.validate()
            assertEquals("", profile.region); assertEquals("", profile.endpoint)
        }
        assertEquals("cn-shanghai", ConnectionProfile(name = "内网", endpoint = "oss-cn-shanghai-internal.aliyuncs.com").initialRegion())
        assertEquals("eu-west-1", ConnectionProfile(name = "S3", provider = Provider.AMAZON_S3, endpoint = "https://s3.eu-west-1.amazonaws.com").initialRegion())
    }

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
    private fun components(container: Container): List<java.awt.Component> = container.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
    private fun java.awt.Graphics2D.useGraphics(action: (java.awt.Graphics2D) -> Unit) { try { action(this) } finally { dispose() } }
}
