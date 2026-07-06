package mobile.racemaster.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface StartDestinationState {
    data object Loading : StartDestinationState
    data class Ready(val mode: AppMode?) : StartDestinationState
}

class AppEntryViewModel(
    private val settingsRepository: SettingsRepository,
    private val raceRepository: RaceRepository,
) : ViewModel() {

    val startDestinationState: StateFlow<StartDestinationState> = flow {
        // A destructive Room migration wipes every race but not this DataStore-backed
        // pointer (separate storage) — validate it once at startup and fall back to no
        // active mode/race (and reset any Mule Mode session state pinned to it) rather than
        // resuming into a phantom one that crashes the moment an action button touches it.
        val raceId = settingsRepository.activeRaceId.first()
        if (raceId != null && raceRepository.getRace(raceId) == null) {
            settingsRepository.clearStaleSessionState()
        }
        emitAll(settingsRepository.appMode.map { mode -> StartDestinationState.Ready(mode) as StartDestinationState })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartDestinationState.Loading)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                AppEntryViewModel(container.settingsRepository, container.raceRepository)
            }
        }
    }
}
