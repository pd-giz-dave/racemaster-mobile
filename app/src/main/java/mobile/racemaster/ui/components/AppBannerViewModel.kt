package mobile.racemaster.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer

/** AppBanner is composed once, outside RacemasterNavHost's back stack (see MainActivity), so
 *  this ViewModel's polling naturally runs for the whole app session regardless of which
 *  screen is showing — exactly what's needed for an always-visible header status. */
class AppBannerViewModel(
    serverStatusRepository: ServerStatusRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val serverStatus = serverStatusRepository.state

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
