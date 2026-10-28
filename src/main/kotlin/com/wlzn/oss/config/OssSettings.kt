package com.wlzn.oss.config

import com.intellij.openapi.components.*
import com.wlzn.oss.model.ConnectionProfile
import com.wlzn.oss.model.TransferRecord

@Service(Service.Level.APP)
@State(name = "WlznOssBrowser", storages = [Storage(value = "wlzn-oss-browser.xml", roamingType = RoamingType.DISABLED)])
class OssSettings : PersistentStateComponent<OssSettings.State> {
    data class State(
        var profiles: MutableList<ConnectionProfile> = mutableListOf(),
        var transfers: MutableList<TransferRecord> = mutableListOf(),
        var selectedProfileId: String = ""
    )

    private var data = State()

    @Synchronized
    override fun getState() = State(data.profiles.map { it.copy() }.toMutableList(),
        data.transfers.map { it.copy() }.toMutableList(), data.selectedProfileId)

    @Synchronized
    override fun loadState(state: State) {
        data = State(state.profiles.map { it.copy() }.toMutableList(),
            state.transfers.map { it.copy() }.toMutableList(), state.selectedProfileId)
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
    fun saveTransfers(transfers: List<TransferRecord>) { data.transfers = transfers.map { it.copy() }.toMutableList() }

    companion object {
        fun getInstance(): OssSettings = service()
    }
}
