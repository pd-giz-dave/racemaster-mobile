package mobile.racemaster.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.hasRealEntries
import mobile.racemaster.data.repository.isRaceInProgress
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface StartDestinationState {
    data object Loading : StartDestinationState
    data class Ready(val mode: AppMode?) : StartDestinationState
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppEntryViewModel(
    settingsRepository: SettingsRepository,
    raceRepository: RaceRepository,
    bibsModeRepository: BibsModeRepository,
) : ViewModel() {

    val startDestinationState: StateFlow<StartDestinationState> = settingsRepository.appMode
        .map { mode -> StartDestinationState.Ready(mode) as StartDestinationState }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartDestinationState.Loading)

    // Drives the app-wide "block exit" back-press guard and screen-pinning: true whenever
    // the active race's clock is running or it has recorded bib data, regardless of which
    // screen is showing — except in Mule mode, which needs to run in the background/be
    // freely switched away from (it's a relay device visiting other phones, not something
    // that should ever be pinned or block the operator from leaving).
    val raceInProgress: StateFlow<Boolean> = settingsRepository.appMode
        .flatMapLatest { mode ->
            if (mode == AppMode.MULE) {
                flowOf(false)
            } else {
                settingsRepository.activeRaceId.flatMapLatest { raceId ->
                    if (raceId == null) {
                        flowOf(false)
                    } else {
                        combine(
                            raceRepository.observeRace(raceId),
                            bibsModeRepository.observeEntries(raceId),
                        ) { race, bibEntries ->
                            isRaceInProgress(
                                race?.timeModeStartedAtMillis,
                                race?.timeModeStoppedAtMillis,
                                bibEntries.hasRealEntries(),
                                race?.bibsModeStoppedAtMillis,
                            )
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                AppEntryViewModel(container.settingsRepository, container.raceRepository, container.bibsModeRepository)
            }
        }
    }
}