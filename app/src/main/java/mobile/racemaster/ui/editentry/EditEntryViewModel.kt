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
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.expectedBibsAtLocation
import mobile.racemaster.di.appContainer
import mobile.racemaster.ui.bibsmode.EVENT_PICKER_OPTIONS
import mobile.racemaster.util.parseMinutesSeconds

data class EditEntryUiState(
    val entry: HistoryLineEntity? = null,
    // False until the one-shot load-by-id completes — see EditSplitUiState's own doc for why.
    val loaded: Boolean = false,
    // The entry's race's own expected-bibs-at-this-location set (see
    // mobile.racemaster.data.repository.expectedBibsAtLocation), loaded alongside it — lets the
    // screen compute a live "not expected" warning as the operator types, same non-blocking
    // treatment Bibs Mode's own submit() now gives an unexpected bib rather than rejecting it.
    val expectedBibs: Set<Int> = emptySet(),
)

/** Backs [EditEntryScreen] — Bibs Mode's own entry editor, same as the inline `EditEntryPanel`
 *  it replaces was. (Used to be shared with CP Mode too, picking its own repository/action-type
 *  options via a `mode` arg — removed along with CP Mode itself, see TODO.md: "A
 *  rationalisation".) Loads the target entry by id once on open — this screen has no
 *  already-loaded row of its own to read from, unlike the old inline editor which just filtered
 *  the live screen's own uiState.entries. */
class EditEntryViewModel(
    private val entryId: Long,
    private val bibsModeRepository: BibsModeRepository,
    private val raceRepository: RaceRepository,
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    val availableTypes: List<HistoryAction> = EVENT_PICKER_OPTIONS

    private val stateFlow = MutableStateFlow(EditEntryUiState())
    val uiState: StateFlow<EditEntryUiState> = stateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            val entry = bibsModeRepository.getEntry(entryId)
            val race = entry?.raceId?.let { raceRepository.getRace(it) }
            val progressEntries = race?.let { progressRepository.getStored(it.id)?.entries }.orEmpty()
            stateFlow.value = EditEntryUiState(
                entry = entry,
                loaded = true,
                expectedBibs = expectedBibsAtLocation(progressEntries, race?.location.orEmpty()),
            )
        }
    }

    // An unexpected bib is flagged, not rejected — same non-blocking treatment
    // BibsModeViewModel's own submit() gives one (see
    // mobile.racemaster.data.repository.unexpectedBibWarning, which EditEntryScreen also uses to
    // show the live warning as the operator types). Only a genuinely missing bib still blocks
    // the save. Returns an error message to show inline, or null on success.
    suspend fun saveEntry(bibNumber: Int?, type: HistoryAction, note: String?): String? {
        if (type in BIB_REQUIRED_ACTIONS && bibNumber == null) return "Enter a bib number."
        bibsModeRepository.updateEntry(entryId, bibNumber, type, note)
        return null
    }

    // Bibs writes a Clock row on Start (see BibsModeRepository.startBibsMode), so
    // EditEntryScreen's CLOCK branch reaches this. Returns an error message on a bad parse
    // (mirroring BibsModeViewModel's old updateClockTime validation) or null on success.
    suspend fun saveClockTime(raw: String): String? {
        val canonical = parseMinutesSeconds(raw) ?: return "Enter a time as minutes and seconds, e.g. 5:30."
        bibsModeRepository.updateEntry(entryId, bibNumber = null, action = HistoryAction.CLOCK, note = canonical)
        return null
    }

    companion object {
        fun factory(entryId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                EditEntryViewModel(
                    entryId, container.bibsModeRepository,
                    container.raceRepository, container.progressRepository,
                )
            }
        }
    }
}
