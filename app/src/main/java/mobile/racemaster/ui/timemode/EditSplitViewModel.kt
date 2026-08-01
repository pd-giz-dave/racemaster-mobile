package mobile.racemaster.ui.timemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.di.appContainer

data class EditSplitUiState(
    val splitNumber: Int? = null,
    val elapsedMillis: Long = 0L,
    val note: String? = null,
    // False until the one-shot load-by-id completes — the screen keeps the Note field/Save
    // disabled until then rather than briefly showing (and letting the operator edit) blank
    // defaults for a split that hasn't actually loaded yet.
    val loaded: Boolean = false,
)

/** Backs [EditSplitScreen] — a dedicated screen reached by navigating (see Routes.editSplit),
 *  not composed inline over the live splits list the way this editor used to be. Loads the
 *  target split by id once on open (this screen has no already-loaded row of its own to read
 *  from, unlike the old inline editor which just filtered the live screen's own
 *  uiState.splits) and computes its elapsed time the same way TimeModeViewModel's own mapping
 *  does — relative to the owning race's timeModeStartedAtMillis. */
class EditSplitViewModel(
    private val splitId: Long,
    private val timeModeRepository: TimeModeRepository,
    private val raceRepository: RaceRepository,
) : ViewModel() {

    private val stateFlow = MutableStateFlow(EditSplitUiState())
    val uiState: StateFlow<EditSplitUiState> = stateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            val split = timeModeRepository.getSplit(splitId) ?: return@launch
            val startedAt = raceRepository.getRace(split.raceId)?.timeModeStartedAtMillis
            stateFlow.value = EditSplitUiState(
                splitNumber = split.splitNumber,
                elapsedMillis = startedAt?.let { split.timestampMillis - it } ?: 0L,
                note = split.note,
                loaded = true,
            )
        }
    }

    suspend fun save(note: String) {
        timeModeRepository.updateNote(splitId, note)
    }

    companion object {
        fun factory(splitId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                EditSplitViewModel(splitId, container.timeModeRepository, container.raceRepository)
            }
        }
    }
}
