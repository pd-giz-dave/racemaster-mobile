package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.toHistoryAction
import mobile.racemaster.data.repository.findDuplicateSplitRefsPerSegment
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MulePulledRecordUi(
    val recordUuid: String,
    val action: HistoryAction,
    val number: Int?,
    val splitNumber: Int,
    val lineNumber: Long,
    val elapsedMillis: Long,
    val note: String?,
    val synced: Boolean,
    // Mirrors a local race's own HistoryLineRow.syncedToLabel exactly (same PulledRecordEntity
    // column a local race's LineSyncEntity.targetName parallels) — see
    // PulledRecordEntity.syncedTargetName's own doc.
    val syncedToLabel: String?,
    // Per-record category signal (mirrors MuleRepository.pushToServer's own categorization):
    // SyncRecord.time is unconditionally non-null for every Time-category record (including
    // its markers) and unconditionally null for every Bibs-category record — a merged Mule
    // source can now hold both categories under one race label, so this decides per record
    // whether HistoryLineRow's elapsedMillis (vs its bib number) is the meaningful column.
    val isTimeRecord: Boolean,
    val isUndoMarker: Boolean,
    val editedFromLineNumber: Long?,
    // Only meaningful for a Bibs-category record — see findDuplicateSplitRefsPerSegment's own
    // doc for the rule. Computed the exact same way a local race's own Race History
    // (RaceHistoryDetailViewModel) computes it for its HistoryLineEntity rows, via that
    // function's generic extractor-lambda core — not a hand-duplicated copy of the rule.
    val dupSplitRefs: List<Int>,
)

data class MuleSourceDetailUiState(
    val raceLabel: String = "",
    // Whichever device's records were seen most recently — see PulledSourceSummary's doc for
    // why this is a single name in the overwhelming common case. Drives the "From {name}"
    // heading, matching a local race's own detail screen.
    val deviceName: String = "",
    val records: List<MulePulledRecordUi> = emptyList(),
)

class MuleSourceDetailViewModel(
    raceLabel: String,
    sourceDeviceId: String,
    muleRepository: MuleRepository,
) : ViewModel() {

    val uiState: StateFlow<MuleSourceDetailUiState> = muleRepository.observeRecordsForSource(raceLabel, sourceDeviceId)
        .map { records ->
            // Bibs-only, exactly like RaceHistoryDetailViewModel's own dup computation — a
            // Time record's action is never in BIB_REQUIRED_ACTIONS so including it here would
            // be harmless either way, but scoping to isTimeRecord == false keeps this an exact
            // mirror of the local-race path.
            val dupRefs = findDuplicateSplitRefsPerSegment(
                records.filter { it.record.time == null },
                lineNumberOf = { it.record.lineNumber },
                refLineNumberOf = { it.record.refLineNumber },
                isUndoMarker = { it.record.toHistoryAction() == HistoryAction.UNDO },
                isReset = { it.record.toHistoryAction() == HistoryAction.RESET },
                keyOf = { it.record.recordUuid },
                bibNumberOf = { it.record.number },
                actionOf = { it.record.toHistoryAction() },
                splitNumberOf = { it.record.splitNumber ?: 0 },
            )
            MuleSourceDetailUiState(
                raceLabel = raceLabel,
                deviceName = records.lastOrNull()?.deviceName.orEmpty(),
                records = records.map {
                    MulePulledRecordUi(
                        recordUuid = it.record.recordUuid,
                        action = it.record.toHistoryAction(),
                        number = it.record.number,
                        splitNumber = it.record.splitNumber ?: 0,
                        lineNumber = it.record.lineNumber,
                        elapsedMillis = parseElapsedClock(it.record.time),
                        note = it.record.note,
                        synced = it.syncedAtMillis != null,
                        syncedToLabel = it.syncedToLabel,
                        isTimeRecord = it.record.time != null,
                        isUndoMarker = it.record.toHistoryAction() == HistoryAction.UNDO,
                        editedFromLineNumber = it.record.refLineNumber,
                        dupSplitRefs = dupRefs[it.record.recordUuid].orEmpty(),
                    )
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MuleSourceDetailUiState())

    companion object {
        fun factory(raceLabel: String, sourceDeviceId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MuleSourceDetailViewModel(raceLabel, sourceDeviceId, appContainer().muleRepository)
            }
        }
    }
}

// Inverse of SyncRecordMapping's formatElapsedAsClock — the wire format carries elapsed time
// as an "HH:MM:SS.CC" string. Splits off the optional ".CC" suffix before parsing rather than
// naively splitting the whole string on ":" and requiring exactly 3 integer parts — a bare
// "SS.CC".toIntOrNull() returns null (Kotlin rejects decimals), which used to silently zero
// out every Mule-pulled Time record's displayed elapsed time once centiseconds were added.
// Also tolerates the older centiseconds-free "HH:MM:SS" format (no trailing "." component).
internal fun parseElapsedClock(time: String?): Long {
    val parts = time?.split(":") ?: return 0L
    if (parts.size != 3) return 0L
    val hours = parts[0].toIntOrNull() ?: return 0L
    val minutes = parts[1].toIntOrNull() ?: return 0L
    val secondsParts = parts[2].split(".")
    val seconds = secondsParts.getOrNull(0)?.toIntOrNull() ?: return 0L
    val centis = secondsParts.getOrNull(1)?.toIntOrNull() ?: 0
    return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + centis * 10L
}
