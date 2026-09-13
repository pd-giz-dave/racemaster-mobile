package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.mule.ProgressEntry
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProgressDetailUiState(
    val raceLabel: String = "",
    val raceName: String = "",
    val generatedAt: String = "",
    // Sorted by bibNumber — the server's own entries array has no guaranteed order of its own
    // (see racemaster's server/routes/mobile.js, which just writes back whatever order the
    // Progress tab's own rows were in), and a bib-ordered list is what an operator scanning for
    // a specific runner actually wants.
    val entries: List<ProgressEntry> = emptyList(),
    val loaded: Boolean = false,
)

// One-shot load, not a live Flow — see MuleSourceDetailViewModel's own precedent for why a
// dedicated detail screen reached by navigating (not composed inline over an already-loaded
// list) has no already-loaded row of its own to read from. Progress data changes rarely enough
// once received (see ProgressRepository's own doc: "refreshed regularly", not "streamed live")
// that this being a one-shot snapshot rather than an observed Flow is a non-issue in practice —
// navigating back into this screen re-loads it fresh either way.
class ProgressDetailViewModel(raceId: Long, private val progressRepository: ProgressRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ProgressDetailUiState())
    val uiState: StateFlow<ProgressDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = progressRepository.getStored(raceId)
            _uiState.value = ProgressDetailUiState(
                raceLabel = stored?.raceLabel.orEmpty(),
                raceName = stored?.raceName.orEmpty(),
                generatedAt = stored?.generatedAt.orEmpty(),
                entries = stored?.entries.orEmpty().sortedBy { it.bibNumber },
                loaded = true,
            )
        }
    }

    companion object {
        fun factory(raceId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProgressDetailViewModel(raceId, appContainer().progressRepository)
            }
        }
    }
}
