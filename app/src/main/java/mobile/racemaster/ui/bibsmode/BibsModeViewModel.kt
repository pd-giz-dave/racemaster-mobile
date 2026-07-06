package mobile.racemaster.ui.bibsmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.BIB_REQUIRED_TYPES
import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType
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
import mobile.racemaster.data.repository.outstandingBibs
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
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
    val entries: List<BibEntryEntity>,
    val unsyncedCount: Int,
    val lastSyncedAtMillis: Long?,
)

data class BibEntryUi(
    val id: Long,
    val bibNumber: Int?,
    val splitNumber: Int,
    val type: BibEntryType,
    val note: String?,
    val dupSplitRefs: List<Int>,
    val synced: Boolean,
)

data class BibsModeUiState(
    val raceId: Long? = null,
    val raceLabel: String = "",
    val currentDigits: String = "",
    val pendingEventType: BibEntryType = BibEntryType.FINISH,
    val nextSplitNumber: Int = 1,
    val dupCount: Int = 0,
    val entries: List<BibEntryUi> = emptyList(),
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

    // Eagerly held so submit()/updateEntry() can read the current bib range synchronously.
    private val raceFlow: StateFlow<RaceEntity?> = raceIdFlow
        .flatMapLatest { raceId -> if (raceId == null) flowOf(null) else raceRepository.observeRace(raceId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val digitsFlow = MutableStateFlow("")
    private val pendingTypeFlow = MutableStateFlow(BibEntryType.FINISH)
    private val errorFlow = MutableStateFlow<String?>(null)

    private val raceAndEntriesFlow = raceIdFlow.flatMapLatest { raceId ->
        if (raceId == null) {
            flowOf(RaceContext(null, emptyList(), 0, null))
        } else {
            combine(
                raceRepository.observeRace(raceId),
                bibsModeRepository.observeEntries(raceId),
                bibsModeRepository.observeUnsyncedCount(raceId),
                bibsModeRepository.observeLastSyncedAtMillis(raceId),
            ) { race, entries, unsyncedCount, lastSyncedAtMillis ->
                RaceContext(race, entries, unsyncedCount, lastSyncedAtMillis)
            }
        }
    }

    val uiState: StateFlow<BibsModeUiState> = combine(
        raceAndEntriesFlow,
        digitsFlow,
        pendingTypeFlow,
        errorFlow,
    ) { context, digits, pendingType, error ->
        val (race, entries, unsyncedCount, lastSyncedAtMillis) = context
        val dupRefs = findDuplicateSplitRefs(entries)
        val needsBib = pendingType in BIB_REQUIRED_TYPES
        val outstanding = outstandingBibs(entries, race?.bibsRangeStart, race?.bibsRangeCount)
        BibsModeUiState(
            raceId = race?.id,
            raceLabel = race?.label.orEmpty(),
            currentDigits = digits,
            pendingEventType = pendingType,
            nextSplitNumber = race?.bibsModeNextSplit ?: 1,
            dupCount = countDuplicateExtras(entries),
            entries = entries.map {
                BibEntryUi(
                    id = it.id,
                    bibNumber = it.bibNumber,
                    splitNumber = it.splitNumber,
                    type = it.type,
                    note = it.note,
                    dupSplitRefs = dupRefs[it.id].orEmpty(),
                    synced = it.syncedAtMillis != null,
                )
            },
            canSubmit = race != null && race.bibsModeStoppedAtMillis == null && (if (needsBib) digits.isNotEmpty() else true),
            canUndo = entries.hasRealEntries(),
            stopped = race?.bibsModeStoppedAtMillis != null,
            raceInProgress = isRaceInProgress(
                race?.timeModeStartedAtMillis,
                race?.timeModeStoppedAtMillis,
                entries.hasRealEntries(),
                race?.bibsModeStoppedAtMillis,
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

    fun setPendingEventType(type: BibEntryType) {
        pendingTypeFlow.value = type
        if (type !in BIB_REQUIRED_TYPES) digitsFlow.value = ""
    }

    fun submit() {
        val raceId = raceIdFlow.value ?: return
        val type = pendingTypeFlow.value
        val needsBib = type in BIB_REQUIRED_TYPES
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
            pendingTypeFlow.value = BibEntryType.FINISH
            beeper.beep()
        }
    }

    fun updateEntry(id: Long, bibNumber: Int?, type: BibEntryType, note: String?) {
        val needsBib = type in BIB_REQUIRED_TYPES
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
        viewModelScope.launch { bibsModeRepository.updateEntry(id, bibNumber = null, type = BibEntryType.CLOCK, note = canonical) }
    }

    fun dismissError() {
        errorFlow.value = null
    }

    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.deleteMostRecent(raceId) }
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
