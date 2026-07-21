package mobile.racemaster.ui.timemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.repository.hasRealEntries
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

data class FinishSplitUi(
    val id: Long,
    val splitNumber: Int,
    val action: HistoryAction,
    val elapsedMillis: Long,
    val note: String?,
    val synced: Boolean,
)

data class TimeModeUiState(
    val raceId: Long? = null,
    val raceLabel: String = "",
    val stopwatchStarted: Boolean = false,
    val stopwatchStopped: Boolean = false,
    val liveElapsedMillis: Long = 0L,
    val nextSplitNumber: Int = 1,
    val splits: List<FinishSplitUi> = emptyList(),
    val canUndo: Boolean = false,
    val raceInProgress: Boolean = false,
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    // Set from the race details screen — mirrors BibsModeUiState's fields exactly (form and
    // feedback wording are meant to be identical between the two modes), even though Time
    // Mode itself never actually reads firstBibNumber for anything.
    val firstBibNumber: Int? = null,
    val expectedRunnerCount: Int? = null,
    val finishedCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimeModeViewModel(
    private val timeModeRepository: TimeModeRepository,
    private val bibsModeRepository: BibsModeRepository,
    private val raceRepository: RaceRepository,
    settingsRepository: SettingsRepository,
    private val beeper: Beeper,
) : ViewModel() {

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<TimeModeUiState> = raceIdFlow
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(TimeModeUiState())
            } else {
                val muleStatusFlow = combine(
                    timeModeRepository.observeUnsyncedCount(raceId),
                    timeModeRepository.observeLastSyncedAtMillis(raceId),
                ) { unsyncedCount, lastSyncedAtMillis -> unsyncedCount to lastSyncedAtMillis }

                combine(
                    raceRepository.observeRace(raceId),
                    timeModeRepository.observeCurrentSegmentSplits(raceId),
                    bibsModeRepository.observeCurrentSegmentEntries(raceId),
                    tickerFlow,
                    muleStatusFlow,
                ) { race, splits, bibEntries, now, (unsyncedCount, lastSyncedAtMillis) ->
                    val startedAt = race?.timeModeStartedAtMillis
                    val stoppedAt = race?.timeModeStoppedAtMillis
                    val liveElapsed = when {
                        startedAt == null -> 0L
                        stoppedAt != null -> stoppedAt - startedAt
                        else -> now - startedAt
                    }
                    TimeModeUiState(
                        raceId = raceId,
                        raceLabel = race?.label.orEmpty(),
                        stopwatchStarted = startedAt != null,
                        stopwatchStopped = stoppedAt != null,
                        liveElapsedMillis = liveElapsed,
                        nextSplitNumber = race?.timeModeNextSplit ?: 1,
                        splits = splits.map {
                            FinishSplitUi(
                                id = it.id,
                                splitNumber = it.splitNumber,
                                action = it.action,
                                elapsedMillis = startedAt?.let { s -> it.timestampMillis - s } ?: 0L,
                                note = it.note,
                                synced = it.syncedAtMillis != null,
                            )
                        },
                        canUndo = splits.isNotEmpty(),
                        raceInProgress = isRaceInProgress(startedAt, stoppedAt, bibEntries.hasRealEntries(), race?.bibsModeStoppedAtMillis),
                        unsyncedCount = unsyncedCount,
                        lastSyncedAtMillis = lastSyncedAtMillis,
                        firstBibNumber = race?.bibsRangeStart,
                        expectedRunnerCount = race?.bibsRangeCount,
                        finishedCount = splits.count { it.splitNumber != 0 },
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
        viewModelScope.launch { timeModeRepository.undoMostRecent(raceId) }
    }

    fun updateNote(splitId: Long, note: String) {
        viewModelScope.launch { timeModeRepository.updateNote(splitId, note) }
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