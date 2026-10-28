package com.wlzn.oss.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.*
import com.wlzn.oss.model.*
import com.wlzn.oss.transfer.TransferManager
import java.awt.BorderLayout
import javax.swing.*
import kotlin.concurrent.withLock

class OssSettingsConfigurable : Configurable {
    private val profiles = mutableListOf<ConnectionProfile>()
    private val pending = mutableMapOf<String, CredentialUpdate>()
    private val model = DefaultListModel<ConnectionProfile>()
    private val list = JBList(model)
    private val secrets: SecretStore = PasswordSafeSecretStore()

    override fun getDisplayName() = "WLZN OSS Browser"

    override fun createComponent(): JComponent {
        reset()
        val rows = ToolbarDecorator.createDecorator(list)
            .setAddAction { edit(null) }
            .setEditAction { list.selectedValue?.let { edit(it) } }
            .setRemoveAction {
                list.selectedValue?.let { selected ->
                    if (Messages.showYesNoDialog("删除连接「${selected.name}」及其已保存凭据？", "删除连接", Messages.getQuestionIcon()) == Messages.YES) {
                        profiles.removeAll { it.id == selected.id }
                        pending.remove(selected.id)
                        refreshModel()
                    }
                }
            }.disableUpDownActions().createPanel()
        return JPanel(BorderLayout(0, 12)).apply {
            add(JLabel("多账号配置 · 支持阿里云 OSS 与 Amazon S3"), BorderLayout.NORTH)
            add(rows, BorderLayout.CENTER)
            add(JLabel("<html>密钥仅保存到 IDEA PasswordSafe；不写入项目。编辑时留空保留原凭据。<br>保存后，在右侧 OSS Browser 工具窗口点击“连接”。</html>"), BorderLayout.SOUTH)
        }
    }

    private fun edit(existing: ConnectionProfile?) {
        val dialog = ProfileDialog(existing, pending[existing?.id])
        if (!dialog.showAndGet()) return
        val profile = dialog.profile()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index < 0) profiles.add(profile) else profiles[index] = profile
        dialog.credentialUpdate()?.let { pending[profile.id] = it }
        refreshModel()
        list.setSelectedValue(profile, true)
    }

    private fun refreshModel() {
        model.clear()
        profiles.forEach { model.addElement(it) }
    }

    override fun isModified() = profiles != OssSettings.getInstance().profiles() || pending.isNotEmpty()

    override fun apply() {
        val settings = OssSettings.getInstance()
        val old = settings.profiles()
        val changed = (old.filter { previous -> profiles.none { it == previous } }.map { it.id } + pending.keys).toSet()
        if (changed.any { TransferManager.getInstance().hasActive(it) }) throw ConfigurationException("请先暂停该账号的传输任务，再修改连接或凭据")
        try {
            profiles.forEach { it.validate() }
            require(profiles.map { it.id }.distinct().size == profiles.size) { "连接 ID 重复" }
        } catch (e: IllegalArgumentException) { throw ConfigurationException(e.message ?: "连接参数无效") }

        var failure: String? = null
        val updates = pending.toMap()
        val removed = old.filter { previous -> profiles.none { it.id == previous.id } }.map { it.id }
        val newProfiles = profiles.map { profile ->
            val previous = old.firstOrNull { it.id == profile.id }
            profile.copy(revision = if (profile != previous || updates.containsKey(profile.id)) (previous?.revision ?: 0) + 1 else profile.revision)
        }
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
          ConnectionAccess.configurationLock.withLock {
            val backups = mutableMapOf<String, StorageCredentials?>()
            try {
                require(settings.profiles() == old) { "连接已被另一个设置窗口修改，请重新打开设置" }
                require(changed.none { TransferManager.getInstance().hasActive(it) }) { "连接有正在传输的任务，请先暂停" }
                old.forEach { previous ->
                    val next = newProfiles.firstOrNull { it.id == previous.id }
                    val update = updates[previous.id]
                    val changesIdentity = next == null || next.routeIdentity() != previous.routeIdentity() || update?.accessKeyId != null || update?.secret != null
                    require(!changesIdentity || !TransferManager.getInstance().hasPending(previous.id)) {
                        "「${previous.name}」还有未完成任务，请先取消并清理，再删除连接、更换密钥或修改区域；暂停后可单独更新 Session Token"
                    }
                }
                for (profile in newProfiles) {
                    val previous = secrets.read(profile.id)
                    val update = updates[profile.id]
                    val merged = if (update == null) previous else StorageCredentials(
                        update.accessKeyId ?: previous?.accessKeyId.orEmpty(),
                        update.secret ?: previous?.accessKeySecret.orEmpty(),
                        if (update.clearToken) "" else update.sessionToken ?: previous?.sessionToken.orEmpty())
                    require(merged != null && merged.accessKeyId.isNotBlank() && merged.accessKeySecret.isNotBlank()) { "请填写「${profile.name}」的 AccessKey 和 Secret" }
                    if (update != null) { backups[profile.id] = previous; secrets.write(profile.id, merged) }
                }
                removed.forEach { backups[it] = secrets.read(it); secrets.write(it, null) }
                settings.replaceProfiles(newProfiles)
            } catch (e: Exception) {
                failure = if (e is IllegalArgumentException) e.message else "安全存储不可用，请检查 IDEA 的 Passwords 设置后重试"
                backups.forEach { (id, backup) -> try { secrets.write(id, backup) } catch (_: Exception) { failure = "保存失败，部分安全存储回滚失败，请重新配置相关账号" } }
            }
          }
        }, "保存对象存储连接", false, null)
        failure?.let { throw ConfigurationException(it) }
        profiles.clear()
        profiles.addAll(newProfiles)
        refreshModel()
        pending.clear()
    }

    override fun reset() {
        profiles.clear()
        profiles.addAll(OssSettings.getInstance().profiles())
        pending.clear()
        refreshModel()
    }

    override fun disposeUIResources() { pending.clear(); profiles.clear(); model.clear() }

    private class CredentialUpdate(val accessKeyId: String?, val secret: String?, val sessionToken: String?, val clearToken: Boolean) {
        override fun toString() = "CredentialUpdate([REDACTED])"
    }

    private class ProfileDialog(private val existing: ConnectionProfile?, update: CredentialUpdate?) : DialogWrapper(true) {
        private val id = existing?.id ?: java.util.UUID.randomUUID().toString()
        private val name = JTextField(existing?.name.orEmpty(), 30)
        private val provider = JComboBox(Provider.entries.toTypedArray()).apply { selectedItem = existing?.provider ?: Provider.ALIYUN_OSS }
        private val endpoint = JTextField(existing?.endpoint.orEmpty(), 35)
        private val region = JTextField(existing?.region.orEmpty(), 25)
        private val bucket = JTextField(existing?.bucket.orEmpty(), 30)
        private val prefix = JTextField(existing?.prefix.orEmpty(), 30)
        private val pathStyle = JCheckBox("路径风格访问（兼容端点或本地测试）", existing?.pathStyle ?: false)
        private val accessKey = JPasswordField(update?.accessKeyId.orEmpty(), 30)
        private val secret = JPasswordField(update?.secret.orEmpty(), 30)
        private val token = JPasswordField(update?.sessionToken.orEmpty(), 30)
        private val clearToken = JCheckBox("移除原有临时 Token，切换为长期密钥", update?.clearToken ?: false)

        init { title = if (existing == null) "添加对象存储连接" else "编辑对象存储连接"; init() }

        fun profile() = ConnectionProfile(id, name.text.trim(), provider.selectedItem as Provider,
            endpoint.text.trim(), region.text.trim(), bucket.text.trim(), prefix.text, pathStyle.isSelected, existing?.revision ?: 0)

        fun credentialUpdate(): CredentialUpdate? {
            val key = String(accessKey.password).takeIf { it.isNotBlank() }
            val value = String(secret.password).takeIf { it.isNotBlank() }
            val session = String(token.password).takeIf { it.isNotBlank() }
            return if (key == null && value == null && session == null && !clearToken.isSelected) null else
                CredentialUpdate(key, value, session, clearToken.isSelected)
        }

        override fun createCenterPanel(): JComponent = panel {
            row("连接名称：") { cell(name).columns(COLUMNS_LARGE) }
            row("云服务：") { cell(provider) }
            row("Endpoint：") { cell(endpoint).columns(COLUMNS_LARGE).comment("OSS 必填；S3 留空使用 AWS 区域端点，例如 https://oss-cn-hangzhou.aliyuncs.com") }
            row("Region：") { cell(region).comment("OSS：cn-hangzhou；S3：us-east-1。填写 Bucket 的实际区域") }
            row("指定 Bucket：") { cell(bucket).comment("可选；填写后直接访问，无需列举账号下所有 Bucket 的权限") }
            row("起始目录：") { cell(prefix).comment("可选，例如 uploads/；用于初始定位，不替代服务端权限") }
            row { cell(pathStyle) }
            group("凭据（仅安全存储）") {
                row("AccessKey ID：") { cell(accessKey).columns(COLUMNS_LARGE) }
                row("AccessKey Secret：") { cell(secret).columns(COLUMNS_LARGE) }
                row("STS / Session Token：") { cell(token).columns(COLUMNS_LARGE) }
                row { cell(clearToken) }
                row { comment("编辑已有连接时留空保留原值。仅修改 Token 可用于更新临时会话；不会回显已保存的密钥。") }
            }
        }

        override fun doValidate(): ValidationInfo? = try {
            profile().validate()
            val update = credentialUpdate()
            if ((update?.accessKeyId == null) != (update?.secret == null)) ValidationInfo("修改长期凭据时必须同时填写 AccessKey ID 和 Secret")
            else if (existing == null && (update?.accessKeyId == null || update.secret == null)) ValidationInfo("新连接需要填写 AccessKey ID 和 Secret")
            else null
        } catch (e: Exception) { ValidationInfo(e.message ?: "连接参数无效") }
    }
}
