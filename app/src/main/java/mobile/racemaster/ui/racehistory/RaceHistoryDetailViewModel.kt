package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.repository.findDuplicateSplitRefsPerSegment
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ArchivedHistoryLineUi(
    val id: Long,
    val mode: HistoryMode,
    val action: HistoryAction,
    val bibNumber: Int?,
    val splitNumber: Int,
    val lineNumber: Long,
    // Only meaningful for a TIME-mode row (elapsed since its segment's most recent Start
    // marker) — 0 for a BIBS-mode row, which has no stopwatch of its own.
    val elapsedMillis: Long,
    val note: String?,
    // Only meaningful for a BIBS-mode row — empty for a TIME-mode row.
    val dupSplitRefs: List<Int>,
    val timestampMillis: Long,
    val synced: Boolean,
    val isUndoMarker: Boolean,
    val editedFromLineNumber: Long?,
    val syncedTo: List<String>,
)

data class RaceHistoryDetailUiState(
    val raceLabel: String = "",
    val deviceName: String = "",
    val lines: List<ArchivedHistoryLineUi> = emptyList(),
    val lastSyncedAtMillis: Long? = null,
)

class RaceHistoryDetailViewModel(
    raceId: Long,
    raceRepository: RaceRepository,
    timeModeRepository: TimeModeRepository,
    bibsModeRepository: BibsModeRepository,
) : ViewModel() {

    private val lastSyncedFlow = combine(
        timeModeRepository.observeLastSyncedAtMillis(raceId),
        bibsModeRepository.observeLastSyncedAtMillis(raceId),
    ) { timeSynced, bibsSynced -> maxOfNullable(timeSynced, bibsSynced) }

    val uiState: StateFlow<RaceHistoryDetailUiState> = combine(
        raceRepository.observeRace(raceId),
        raceRepository.observeHistory(raceId),
        lastSyncedFlow,
        raceRepository.observeLineSyncs(raceId),
    ) { race, rows, lastSyncedAtMillis, lineSyncs ->
        // Per-line "synced to" feedback (a BLE ack's device name, or "Server") — see
        // LineSyncEntity's doc for why this is deliberately simple bookkeeping, not a
        // multi-hop relay. The unique (raceId, lineNumber, targetId) index already dedups;
        // targetName is what's actually shown, since a raw deviceId isn't user-friendly.
        val syncedToByLine = lineSyncs.groupBy({ it.lineNumber }, { it.targetName })

        // Duplicate bib detection, scoped per Bibs segment (bounded by RESET markers) rather
        // than across the whole history — a bib legitimately reused in a later segment must
        // not be flagged against an earlier, already-reset-away segment (fixes a real bug:
        // this screen used to feed the full multi-segment history into one flat check).
        val dupRefs = findDuplicateSplitRefsPerSegment(rows.filter { it.mode == HistoryMode.BIBS })

        // A race's history can span more than one segment (each bounded by a Reset marker),
        // and each Time segment has its own Start time — so elapsed time can't be computed
        // off a single race-level timestamp once there's more than one segment. Walking the
        // full history in lineNumber order and tracking whichever Start marker was most
        // recently seen for a TIME-mode row gives every Time-mode row the correct elapsed time
        // relative to its own segment's start, regardless of how many resets (of either mode)
        // came before it. The `mode` check is what keeps this from ever picking up a Bibs
        // START row instead — see HistoryAction's own doc for why the two families can safely
        // share this action value.
        var segmentStartedAt: Long? = null
        val lines = rows.sortedBy { it.lineNumber }.map {
            if (it.mode == HistoryMode.TIME && it.action == HistoryAction.START) {
                segmentStartedAt = it.timestampMillis
            }
            ArchivedHistoryLineUi(
                id = it.id,
                mode = it.mode,
                action = it.action,
                bibNumber = it.bibNumber,
                splitNumber = it.splitNumber,
                lineNumber = it.lineNumber,
                elapsedMillis = if (it.mode == HistoryMode.TIME) segmentStartedAt?.let { s -> it.timestampMillis - s } ?: 0L else 0L,
                note = it.note,
                dupSplitRefs = dupRefs[it.id].orEmpty(),
                timestampMillis = it.timestampMillis,
                synced = it.syncedAtMillis != null,
                isUndoMarker = it.action == HistoryAction.UNDO,
                // The ROOT row's lineNumber — used both as the "edited from line #N" caption
                // on an edit-echo and as the target reference on an undo-marker's own
                // dedicated row (see RaceHistoryDetailScreen).
                editedFromLineNumber = it.refLineNumber,
                syncedTo = syncedToByLine[it.lineNumber].orEmpty(),
            )
        }
        RaceHistoryDetailUiState(
            raceLabel = race?.label.orEmpty(),
            deviceName = race?.createdByDeviceName.orEmpty(),
            lines = lines,
            lastSyncedAtMillis = lastSyncedAtMillis,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RaceHistoryDetailUiState())

    companion object {
        // Time Mode and Bibs Mode each track their own "last pulled by a Mule" timestamp
        // independently — this race's overall status is whichever happened more recently.
        private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

        fun factory(raceId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RaceHistoryDetailViewModel(
                    raceId,
                    container.raceRepository,
                    container.timeModeRepository,
                    container.bibsModeRepository,
                )
            }
        }
    }
}
