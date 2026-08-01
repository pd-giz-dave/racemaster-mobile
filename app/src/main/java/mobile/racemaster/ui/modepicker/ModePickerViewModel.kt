package mobile.racemaster.ui.modepicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.CpModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.repository.isRaceInProgress
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val cpCount: Int,
    val isStopped: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ModePickerViewModel(
    private val raceRepository: RaceRepository,
    private val timeModeRepository: TimeModeRepository,
    private val bibsModeRepository: BibsModeRepository,
    private val cpModeRepository: CpModeRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val hasActiveRace: StateFlow<Boolean> = settingsRepository.activeRaceId
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Surfaces the active race (if any) so the picker can tell the operator what's still
    // open and which mode to return to, rather than letting them lose track of it. Stays
    // visible once Stopped — only Reset (see isRaceActive) clears it.
    val activeRaceStatus: StateFlow<ActiveRaceStatus?> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(null)
            } else {
                combine(
                    raceRepository.observeRace(raceId),
                    timeModeRepository.observeCurrentSegmentSplits(raceId),
                    bibsModeRepository.observeCurrentSegmentEntries(raceId),
                    cpModeRepository.observeCurrentSegmentEntries(raceId),
                    settingsRepository.appMode,
                ) { race, splits, bibEntries, cpEntries, mode ->
                    if (race == null) return@combine null
                    val active = isRaceActive(
                        race.timeModeStartedAtMillis,
                        race.bibsModeStartedAtMillis,
                        race.cpModeStartedAtMillis,
                    )
                    if (!active) return@combine null
                    val inProgress = isRaceInProgress(
                        race.timeModeStartedAtMillis,
                        race.timeModeStoppedAtMillis,
                        race.bibsModeStartedAtMillis,
                        race.bibsModeStoppedAtMillis,
                        race.cpModeStartedAtMillis,
                        race.cpModeStoppedAtMillis,
                    )
                    ActiveRaceStatus(
                        raceLabel = race.label,
                        currentModeLabel = mode.displayName(),
                        splitCount = splits.count { it.splitNumber != 0 },
                        bibCount = bibEntries.count { it.action != HistoryAction.CLOCK },
                        cpCount = cpEntries.count { it.action != HistoryAction.CLOCK },
                        isStopped = !inProgress,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val modeSwitchErrorFlow = MutableStateFlow<String?>(null)
    val modeSwitchError: StateFlow<String?> = modeSwitchErrorFlow.asStateFlow()

    /** Mode switch when a race is already active — no new race needed. Switching into Bibs
     *  Mode for a race started in a different mode lands on an empty Bibs segment, same as a
     *  freshly created one — its own Start button (see BibsModeScreen) is what begins it,
     *  nothing needed here. Blocked (see [RaceRepository.blockedModeSwitchReason]) rather than
     *  performed when Bibs and CP are being switched between each other and the one being left
     *  still has live, un-Reset activity. */
    fun selectModeForExistingRace(mode: AppMode, onComplete: () -> Unit) {
        viewModelScope.launch {
            val raceId = settingsRepository.activeRaceId.first()
            val blockedReason = raceId?.let { raceRepository.blockedModeSwitchReason(it, mode) }
            if (blockedReason != null) {
                modeSwitchErrorFlow.value = blockedReason
                return@launch
            }
            settingsRepository.setAppMode(mode)
            onComplete()
        }
    }

    fun dismissModeSwitchError() {
        modeSwitchErrorFlow.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                ModePickerViewModel(
                    container.raceRepository,
                    container.timeModeRepository,
                    container.bibsModeRepository,
                    container.cpModeRepository,
                    container.settingsRepository,
                )
            }
        }
    }
}

private fun AppMode?.displayName(): String = when (this) {
    AppMode.TIME -> "Time Mode"
    AppMode.BIBS -> "Bibs Mode"
    AppMode.CP -> "CP Mode"
    AppMode.MULE -> "Mule Mode"
    null -> ""
}