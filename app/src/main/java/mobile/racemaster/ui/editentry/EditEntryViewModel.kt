package mobile.racemaster.ui.editentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.CpModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.di.appContainer
import mobile.racemaster.ui.bibsmode.CP_ACTION_OPTIONS
import mobile.racemaster.ui.bibsmode.EVENT_PICKER_OPTIONS
import mobile.racemaster.util.parseMinutesSeconds

data class EditEntryUiState(
    val entry: HistoryLineEntity? = null,
    // False until the one-shot load-by-id completes — see EditSplitUiState's own doc for why.
    val loaded: Boolean = false,
    // The entry's race's configured bib range, loaded alongside it — lets the screen compute a
    // live "not in range" warning (see mobile.racemaster.data.repository.rangeWarningMessage)
    // as the operator types, same non-blocking treatment Bibs/CP Mode's own submit() now gives
    // an out-of-range bib rather than rejecting it.
    val raceBibsRangeStart: Int? = null,
    val raceBibsRangeCount: Int? = null,
)

/** Backs [EditEntryScreen] — shared by Bibs and CP Mode, same as the inline `EditEntryPanel`
 *  it replaces was. [mode] picks which repository this reads/writes through and which
 *  action-type options it offers ([EVENT_PICKER_OPTIONS] vs [CP_ACTION_OPTIONS]), exactly the
 *  same per-mode split [mobile.racemaster.ui.racedetails.RaceDetailsViewModel] already handles
 *  for its own field set. Loads the
 *  target entry by id once on open — this screen has no already-loaded row of its own to read
 *  from, unlike the old inline editor which just filtered the live screen's own
 *  uiState.entries. */
class EditEntryViewModel(
    private val mode: AppMode,
    private val entryId: Long,
    private val bibsModeRepository: BibsModeRepository,
    private val cpModeRepository: CpModeRepository,
    private val raceRepository: RaceRepository,
) : ViewModel() {

    val availableTypes: List<HistoryAction> = if (mode == AppMode.BIBS) EVENT_PICKER_OPTIONS else CP_ACTION_OPTIONS

    private val stateFlow = MutableStateFlow(EditEntryUiState())
    val uiState: StateFlow<EditEntryUiState> = stateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            val entry = if (mode == AppMode.BIBS) bibsModeRepository.getEntry(entryId) else cpModeRepository.getEntry(entryId)
            val race = entry?.raceId?.let { raceRepository.getRace(it) }
            stateFlow.value = EditEntryUiState(
                entry = entry,
                loaded = true,
                raceBibsRangeStart = race?.bibsRangeStart,
                raceBibsRangeCount = race?.bibsRangeCount,
            )
        }
    }

    // An out-of-range bib is flagged, not rejected — same non-blocking treatment
    // BibsModeViewModel/CpModeViewModel's own submit() gives one (see
    // mobile.racemaster.data.repository.rangeWarningMessage, which EditEntryScreen also uses to
    // show the live warning as the operator types). Only a genuinely missing bib still blocks
    // the save. Returns an error message to show inline, or null on success.
    suspend fun saveEntry(bibNumber: Int?, type: HistoryAction, note: String?): String? {
        if (type in BIB_REQUIRED_ACTIONS && bibNumber == null) return "Enter a bib number."
        if (mode == AppMode.BIBS) {
            bibsModeRepository.updateEntry(entryId, bibNumber, type, note)
        } else {
            cpModeRepository.updateEntry(entryId, bibNumber, type, note)
        }
        return null
    }

    // Bibs-only in practice — CP never writes a Clock row, so EditEntryScreen's CLOCK branch
    // (the only caller of this) is simply never reached for a CP entry. Returns an error
    // message on a bad parse (mirroring BibsModeViewModel's old updateClockTime validation) or
    // null on success.
    suspend fun saveClockTime(raw: String): String? {
        val canonical = parseMinutesSeconds(raw) ?: return "Enter a time as minutes and seconds, e.g. 5:30."
        bibsModeRepository.updateEntry(entryId, bibNumber = null, action = HistoryAction.CLOCK, note = canonical)
        return null
    }

    companion object {
        fun factory(mode: AppMode, entryId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                EditEntryViewModel(mode, entryId, container.bibsModeRepository, container.cpModeRepository, container.raceRepository)
            }
        }
    }
}
