package mobile.racemaster.ui.timemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.repository.buildRaceLabel
import mobile.racemaster.data.repository.isRaceInProgress
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
import mobile.racemaster.util.Beeper
import mobile.racemaster.util.tickerFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FinishSplitUi(val id: Long, val splitNumber: Int, val elapsedMillis: Long, val label: String?)

data class TimeModeUiState(
    val raceLabel: String = "",
    val stopwatchStarted: Boolean = false,
    val stopwatchStopped: Boolean = false,
    val liveElapsedMillis: Long = 0L,
    val splits: List<FinishSplitUi> = emptyList(),
    val canUndo: Boolean = false,
    val raceInProgress: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimeModeViewModel(
    private val timeModeRepository: TimeModeRepository,
    private val bibsModeRepository: BibsModeRepository,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
    private val beeper: Beeper,
) : ViewModel() {

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<TimeModeUiState> = raceIdFlow
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(TimeModeUiState())
            } else {
                combine(
                    raceRepository.observeRace(raceId),
                    timeModeRepository.observeSplits(raceId),
                    bibsModeRepository.observeEntries(raceId),
                    tickerFlow,
                ) { race, splits, bibEntries, now ->
                    val startedAt = race?.timeModeStartedAtMillis
                    val stoppedAt = race?.timeModeStoppedAtMillis
                    val liveElapsed = when {
                        startedAt == null -> 0L
                        stoppedAt != null -> stoppedAt - startedAt
                        else -> now - startedAt
                    }
                    TimeModeUiState(
                        raceLabel = race?.label.orEmpty(),
                        stopwatchStarted = startedAt != null,
                        stopwatchStopped = stoppedAt != null,
                        liveElapsedMillis = liveElapsed,
                        splits = splits.map {
                            FinishSplitUi(
                                id = it.id,
                                splitNumber = it.splitNumber,
                                elapsedMillis = startedAt?.let { s -> it.timestampMillis - s } ?: 0L,
                                label = it.label,
                            )
                        },
                        canUndo = splits.isNotEmpty(),
                        raceInProgress = isRaceInProgress(startedAt, stoppedAt, bibEntries.isNotEmpty()),
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimeModeUiState())

    fun startStopwatch() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { timeModeRepository.startStopwatch(raceId) }
    }

    // No debounce here by design: two taps in quick succession (two finishers crossing close
    // together) must always produce two distinct splits, never get merged into one.
    fun recordSplit() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch {
            timeModeRepository.recordSplit(raceId)
            beeper.beep()
        }
    }

    fun stopStopwatch() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { timeModeRepository.stopStopwatch(raceId) }
    }

    fun resetStopwatch() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { timeModeRepository.resetStopwatch(raceId) }
    }

    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { timeModeRepository.deleteMostRecent(raceId) }
    }

    fun updateSplitLabel(splitId: Long, label: String) {
        viewModelScope.launch { timeModeRepository.updateLabel(splitId, label) }
    }

    fun startNewRace(name: String) {
        if (uiState.value.raceInProgress) return
        viewModelScope.launch {
            val newRaceId = raceRepository.startNewRace(buildRaceLabel(name))
            settingsRepository.setActiveRaceId(newRaceId)
        }
    }

    override fun onCleared() {
        beeper.release()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                TimeModeViewModel(
                    container.timeModeRepository,
                    container.bibsModeRepository,
                    container.raceRepository,
                    container.settingsRepository,
                    Beeper(applicationContext()),
                )
            }
        }
    }
}