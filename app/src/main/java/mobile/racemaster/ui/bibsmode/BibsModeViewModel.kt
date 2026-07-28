package mobile.racemaster.ui.bibsmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.accountedForRecordCount
import mobile.racemaster.data.repository.countDuplicateExtras
import mobile.racemaster.data.repository.duplicateBibNumbers
import mobile.racemaster.data.repository.findDuplicateSplitRefs
import mobile.racemaster.data.repository.hasRealEntries
import mobile.racemaster.data.repository.isBibInLegalRange
import mobile.racemaster.data.repository.isRaceInProgress
import mobile.racemaster.data.repository.lineSyncState
import mobile.racemaster.data.repository.linesWithAnySync
import mobile.racemaster.data.repository.outstandingBibs
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
import mobile.racemaster.ui.components.EntryLogUi
import mobile.racemaster.util.Beeper
import mobile.racemaster.util.parseMinutesSeconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class RaceContext(
    val race: RaceEntity?,
    val entries: List<HistoryLineEntity>,
    val unsyncedCount: Int,
    val lastSyncedAtMillis: Long?,
    val linesWithAnySync: Set<Long>,
)

data class BibsModeUiState(
    val raceId: Long? = null,
    val raceLabel: String = "",
    val raceLocation: String = "",
    // Whether Bibs Mode has been started for this segment — false for a fresh race, a race
    // just switched into from a different mode, or one just Reset, in which case the screen
    // shows a Start button instead of the entry keypad/list (see BibsModeScreen). Sourced
    // directly from RaceEntity.bibsModeStartedAtMillis, same as CpModeUiState.started — see
    // that field's own doc for why its Clock marker's mere presence isn't what this is derived
    // from, even though Start still writes one.
    val started: Boolean = false,
    val currentDigits: String = "",
    val pendingEventType: HistoryAction = HistoryAction.FINISH,
    val nextSplitNumber: Int = 1,
    val dupCount: Int = 0,
    val entries: List<EntryLogUi> = emptyList(),
    val canSubmit: Boolean = false,
    val canUndo: Boolean = false,
    val stopped: Boolean = false,
    val raceInProgress: Boolean = false,
    val errorMessage: String? = null,
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    // Set from the race details screen — so the operator knows what to expect, and how many
    // are still outstanding.
    val firstBibNumber: Int? = null,
    val expectedRunnerCount: Int? = null,
    val finishedCount: Int = 0,
    // Only populated (and only meaningful) once few enough are left that listing them is
    // more useful than just the count — see BibsModeScreen.
    val outstandingBibs: List<Int> = emptyList(),
    // Distinct bib numbers involved in a duplicate log — empty (and hidden) when there are none.
    val duplicateBibNumbers: List<Int> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class BibsModeViewModel(
    private val bibsModeRepository: BibsModeRepository,
    private val raceRepository: RaceRepository,
    settingsRepository: SettingsRepository,
    private val beeper: Beeper,
) : ViewModel() {

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Eagerly held so submit()/updateEntry() can read the current bib range synchronously.
    private val raceFlow: StateFlow<RaceEntity?> = raceIdFlow
        .flatMapLatest { raceId -> if (raceId == null) flowOf(null) else raceRepository.observeRace(raceId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val digitsFlow = MutableStateFlow("")
    private val pendingTypeFlow = MutableStateFlow(HistoryAction.FINISH)
    private val errorFlow = MutableStateFlow<String?>(null)

    private val raceAndEntriesFlow = raceIdFlow.flatMapLatest { raceId ->
        if (raceId == null) {
            flowOf(RaceContext(null, emptyList(), 0, null, emptySet()))
        } else {
            combine(
                raceRepository.observeRace(raceId),
                bibsModeRepository.observeCurrentSegmentEntries(raceId),
                bibsModeRepository.observeUnsyncedCount(raceId),
                bibsModeRepository.observeLastSyncedAtMillis(raceId),
                raceRepository.observeLineSyncs(raceId),
            ) { race, entries, unsyncedCount, lastSyncedAtMillis, lineSyncs ->
                RaceContext(race, entries, unsyncedCount, lastSyncedAtMillis, linesWithAnySync(lineSyncs))
            }
        }
    }

    val uiState: StateFlow<BibsModeUiState> = combine(
        raceAndEntriesFlow,
        digitsFlow,
        pendingTypeFlow,
        errorFlow,
    ) { context, digits, pendingType, error ->
        val (race, entries, unsyncedCount, lastSyncedAtMillis, linesWithAnySync) = context
        val dupRefs = findDuplicateSplitRefs(entries)
        val needsBib = pendingType in BIB_REQUIRED_ACTIONS
        val outstanding = outstandingBibs(entries, race?.bibsRangeStart, race?.bibsRangeCount)
        BibsModeUiState(
            raceId = race?.id,
            raceLabel = race?.label.orEmpty(),
            raceLocation = race?.location.orEmpty(),
            started = race?.bibsModeStartedAtMillis != null,
            currentDigits = digits,
            pendingEventType = pendingType,
            nextSplitNumber = race?.bibsModeNextSplit ?: 1,
            dupCount = countDuplicateExtras(entries),
            entries = entries.map {
                EntryLogUi(
                    id = it.id,
                    bibNumber = it.bibNumber,
                    splitNumber = it.splitNumber,
                    type = it.action,
                    note = it.note,
                    dupSplitRefs = dupRefs[it.id].orEmpty(),
                    syncState = lineSyncState(it.syncedAtMillis, it.lineNumber in linesWithAnySync),
                )
            },
            canSubmit = race != null && race.bibsModeStoppedAtMillis == null && (if (needsBib) digits.isNotEmpty() else true),
            canUndo = entries.hasRealEntries(),
            stopped = race?.bibsModeStoppedAtMillis != null,
            raceInProgress = isRaceInProgress(
                race?.timeModeStartedAtMillis,
                race?.timeModeStoppedAtMillis,
                race?.bibsModeStartedAtMillis,
                race?.bibsModeStoppedAtMillis,
                race?.cpModeStartedAtMillis,
                race?.cpModeStoppedAtMillis,
            ),
            errorMessage = error,
            unsyncedCount = unsyncedCount,
            lastSyncedAtMillis = lastSyncedAtMillis,
            firstBibNumber = race?.bibsRangeStart,
            expectedRunnerCount = race?.bibsRangeCount,
            finishedCount = accountedForRecordCount(entries),
            outstandingBibs = outstanding,
            duplicateBibNumbers = duplicateBibNumbers(entries),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BibsModeUiState())

    fun startBibsMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.startBibsMode(raceId) }
    }

    fun onDigit(digit: Int) {
        if (digitsFlow.value.length >= MAX_BIB_DIGITS) return
        digitsFlow.value += digit.toString()
    }

    fun onBackspace() {
        digitsFlow.value = digitsFlow.value.dropLast(1)
    }

    fun onClear() {
        digitsFlow.value = ""
    }

    fun setPendingEventType(type: HistoryAction) {
        pendingTypeFlow.value = type
        if (type !in BIB_REQUIRED_ACTIONS) digitsFlow.value = ""
    }

    fun submit() {
        val raceId = raceIdFlow.value ?: return
        val type = pendingTypeFlow.value
        val needsBib = type in BIB_REQUIRED_ACTIONS
        val bib = if (needsBib) digitsFlow.value.toIntOrNull() ?: return else null
        if (needsBib) {
            val race = raceFlow.value ?: return
            if (!isBibInLegalRange(bib!!, race.bibsRangeStart, race.bibsRangeCount)) {
                errorFlow.value = rangeErrorMessage(bib, race)
                return
            }
        }
        viewModelScope.launch {
            bibsModeRepository.recordEntry(raceId, type, bib, note = null)
            digitsFlow.value = ""
            pendingTypeFlow.value = HistoryAction.FINISH
            beeper.beep()
        }
    }

    fun updateEntry(id: Long, bibNumber: Int?, type: HistoryAction, note: String?) {
        val needsBib = type in BIB_REQUIRED_ACTIONS
        if (needsBib) {
            val bib = bibNumber ?: run {
                errorFlow.value = "Enter a bib number."
                return
            }
            val race = raceFlow.value ?: return
            if (!isBibInLegalRange(bib, race.bibsRangeStart, race.bibsRangeCount)) {
                errorFlow.value = rangeErrorMessage(bib, race)
                return
            }
        }
        viewModelScope.launch { bibsModeRepository.updateEntry(id, bibNumber, type, note) }
    }

    fun updateClockTime(id: Long, rawInput: String) {
        val canonical = parseMinutesSeconds(rawInput)
        if (canonical == null) {
            errorFlow.value = "Enter a time as minutes and seconds, e.g. 5:30."
            return
        }
        viewModelScope.launch { bibsModeRepository.updateEntry(id, bibNumber = null, action = HistoryAction.CLOCK, note = canonical) }
    }

    fun dismissError() {
        errorFlow.value = null
    }

    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.undoMostRecent(raceId) }
    }

    fun stopBibsMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.stopBibsMode(raceId) }
    }

    fun resetBibsMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.resetBibsMode(raceId) }
    }

    override fun onCleared() {
        beeper.release()
    }

    private fun rangeErrorMessage(bib: Int, race: RaceEntity): String {
        val start = race.bibsRangeStart
        val count = race.bibsRangeCount
        val rangeText = if (start != null && count != null) "${start}–${start + count - 1}" else "unset"
        return "Bib $bib is outside the legal range $rangeText."
    }

    companion object {
        private const val MAX_BIB_DIGITS = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                BibsModeViewModel(
                    container.bibsModeRepository,
                    container.raceRepository,
                    container.settingsRepository,
                    Beeper(applicationContext()),
                )
            }
        }
    }
}
