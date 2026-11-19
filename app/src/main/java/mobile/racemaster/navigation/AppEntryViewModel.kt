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
        val raceMissing = raceId != null && raceRepository.getRace(raceId) == null
        // A second, narrower case of the same drift: no race selected at all (raceId null,
        // not merely pointing nowhere), yet a leftover appMode from one that's since been
        // deleted — confirmed in the field (RaceRepository.deleteRace/requestDeleteRace used to
        // clear only activeRaceId, never appMode, see their own doc). Without this, the app
        // launches straight into that stale mode's own screen with no race ever set up, which no
        // mode screen actually guards against (RaceContext.race lands null, but nothing routes
        // back to Mode Picker). Those two call sites are fixed now too, but this check also
        // repairs a phone already stuck in the state (DataStore persists past any app update).
        val modeWithNoRace = raceId == null && settingsRepository.appMode.first() != null
        if (raceMissing || modeWithNoRace) {
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
