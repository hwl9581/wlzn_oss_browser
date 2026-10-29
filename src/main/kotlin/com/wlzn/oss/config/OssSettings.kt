package com.wlzn.oss.config

import com.intellij.openapi.components.*
import com.wlzn.oss.model.ConnectionProfile
import com.wlzn.oss.model.TransferRecord
import com.wlzn.oss.model.TransferKind

@Service(Service.Level.APP)
@State(name = "WlznOssBrowser", storages = [Storage(value = "wlzn-oss-browser.xml", roamingType = RoamingType.DISABLED)])
class OssSettings : PersistentStateComponent<OssSettings.State> {
    data class State(
        var profiles: MutableList<ConnectionProfile> = mutableListOf(),
        var transfers: MutableList<TransferRecord> = mutableListOf(),
        var selectedProfileId: String = "",
        var lastUploadDirectory: String = "",
        var lastDownloadDirectory: String = ""
    )

    private var data = State()

    @Synchronized
    override fun getState() = State(data.profiles.map { it.copy() }.toMutableList(),
        data.transfers.map { it.copy() }.toMutableList(), data.selectedProfileId, data.lastUploadDirectory, data.lastDownloadDirectory)

    @Synchronized
    override fun loadState(state: State) {
        data = State(state.profiles.map { it.copy() }.toMutableList(),
            state.transfers.map { it.copy() }.toMutableList(), state.selectedProfileId, state.lastUploadDirectory, state.lastDownloadDirectory)
    }

    @Synchronized
    fun profiles(): List<ConnectionProfile> = data.profiles.map { it.copy() }

    @Synchronized
    fun replaceProfiles(profiles: List<ConnectionProfile>) {
        data.profiles = profiles.map { it.copy() }.toMutableList()
        if (data.profiles.none { it.id == data.selectedProfileId }) data.selectedProfileId = data.profiles.firstOrNull()?.id.orEmpty()
    }

    @Synchronized
    fun selectProfile(id: String) { data.selectedProfileId = id }

    @Synchronized
    fun selectedProfileId(): String = data.selectedProfileId

    @Synchronized
    fun transferDirectory(kind: TransferKind): String = if (kind == TransferKind.UPLOAD) data.lastUploadDirectory else data.lastDownloadDirectory

    @Synchronized
    fun rememberTransferDirectory(kind: TransferKind, directory: String) {
        if (kind == TransferKind.UPLOAD) data.lastUploadDirectory = directory else data.lastDownloadDirectory = directory
    }

    @Synchronized
    fun saveTransfers(transfers: List<TransferRecord>) { data.transfers = transfers.map { it.copy() }.toMutableList() }

    companion object {
        fun getInstance(): OssSettings = service()
    }
}
