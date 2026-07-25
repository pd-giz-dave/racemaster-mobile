package mobile.racemaster.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer

/** AppBanner is composed once, outside RacemasterNavHost's back stack (see MainActivity), so
 *  this ViewModel's polling naturally runs for the whole app session regardless of which
 *  screen is showing — exactly what's needed for an always-visible header status. */
class AppBannerViewModel(
    serverStatusRepository: ServerStatusRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    // Overrides whatever the raw reachability poll reports with PAUSED whenever the operator
    // has turned server sync off from Mule Mode (MuleRepository.setServerSyncOff) — while
    // paused, no push is going to happen regardless of whether the server itself is reachable,
    // so "Online"/"Offline"/"Invalid server" would be misleading right now. UNKNOWN (no server
    // configured at all) is left alone — there's nothing to pause yet, same reasoning as why
    // the banner already renders blank for it (see AppBanner's own doc).
    val serverStatus: StateFlow<ServerStatusState> = combine(
        serverStatusRepository.state,
        settingsRepository.serverSyncOff,
    ) { state, syncOff ->
        if (syncOff && state.status != ServerStatus.UNKNOWN) state.copy(status = ServerStatus.PAUSED) else state
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), serverStatusRepository.state.value)

    init {
        serverStatusRepository.startPolling(viewModelScope, settingsRepository.serverBaseUrl)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                AppBannerViewModel(container.serverStatusRepository, container.settingsRepository)
            }
        }
    }
}
