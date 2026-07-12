package mobile.racemaster.ui.modepicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
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
import kotlinx.coroutines.launch

data class ActiveRaceStatus(
    val raceLabel: String,
    val currentModeLabel: String,
    val splitCount: Int,
    val bibCount: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ModePickerViewModel(
    private val raceRepository: RaceRepository,
    private val timeModeRepository: TimeModeRepository,
    private val bibsModeRepository: BibsModeRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val hasActiveRace: StateFlow<Boolean> = settingsRepository.activeRaceId
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Surfaces the in-progress race (if any) so the picker can tell the operator what's
    // still open and which mode to return to, rather than letting them lose track of it.
    val activeRaceStatus: StateFlow<ActiveRaceStatus?> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(null)
            } else {
                combine(
                    raceRepository.observeRace(raceId),
                    timeModeRepository.observeSplits(raceId),
                    bibsModeRepository.observeEntries(raceId),
                    settingsRepository.appMode,
                ) { race, splits, bibEntries, mode ->
                    if (race == null) return@combine null
                    val inProgress = isRaceInProgress(
                        race.timeModeStartedAtMillis,
                        race.timeModeStoppedAtMillis,
                        bibEntries.hasRealEntries(),
                        race.bibsModeStoppedAtMillis,
                    )
                    if (!inProgress) return@combine null
                    ActiveRaceStatus(
                        raceLabel = race.label,
                        currentModeLabel = mode.displayName(),
                        splitCount = splits.count { it.splitNumber != 0 },
                        bibCount = bibEntries.count { it.type != BibEntryType.CLOCK },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Mode switch when a race is already active — no new race needed. */
    fun selectModeForExistingRace(mode: AppMode, onComplete: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setAppMode(mode)
            onComplete()
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                ModePickerViewModel(
                    container.raceRepository,
                    container.timeModeRepository,
                    container.bibsModeRepository,
                    container.settingsRepository,
                )
            }
        }
    }
}

private fun AppMode?.displayName(): String = when (this) {
    AppMode.TIME -> "Time Mode"
    AppMode.BIBS -> "Bibs Mode"
    AppMode.MULE -> "Mule Mode"
    null -> ""
}