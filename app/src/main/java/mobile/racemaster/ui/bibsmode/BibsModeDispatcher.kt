package mobile.racemaster.ui.bibsmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.ui.closemode.CloseModeScreen
import mobile.racemaster.ui.countmode.CountModeScreen

/** Routes.BIBS_MODE's own entry point (TODO.md: "amalgamate Bibs, Count and Close modes") —
 *  Count/Close no longer have their own AppMode value or route (see AppMode's own doc); Pen and
 *  Close are just two more Bibs locations now, so this is the one place that decides, purely
 *  from the active race's current location, which of the three already-existing screens to show
 *  (each gained a "Move" top-bar link of its own — see each screen's own doc — but is otherwise
 *  unchanged): [CountModeScreen] at "Pen", [CloseModeScreen] at "Close", or
 *  [BibsModeScreen] itself (its own keypad UI) anywhere else. Deliberately thin and read-only —
 *  it never constructs CountModeViewModel/CloseModeViewModel/BibsModeViewModel until the matching
 *  branch is actually entered, so switching location never starts more than one mode's own
 *  BLE/peer-sync work at a time. */
@Composable
fun BibsModeDispatcher(
    onHome: () -> Unit,
    onEditRace: (Long) -> Unit,
    onEditEntry: (Long) -> Unit,
    viewModel: BibsModeDispatcherViewModel = viewModel(factory = BibsModeDispatcherViewModel.Factory),
) {
    val location by viewModel.location.collectAsStateWithLifecycle()
    when {
        location.equals("Pen", ignoreCase = true) -> CountModeScreen(onHome = onHome, onEditRace = onEditRace)
        location.equals("Close", ignoreCase = true) -> CloseModeScreen(onHome = onHome, onEditRace = onEditRace)
        else -> BibsModeScreen(onHome = onHome, onEditRace = onEditRace, onEditEntry = onEditEntry)
    }
}

/** [BibsModeDispatcher]'s own tiny read-only backing state — just the active race's current
 *  location, nothing else, so resolving which screen to show never pulls in any of the three
 *  real mode ViewModels' own (much heavier) dependencies. */
@OptIn(ExperimentalCoroutinesApi::class)
class BibsModeDispatcherViewModel(
    settingsRepository: SettingsRepository,
    raceRepository: RaceRepository,
) : ViewModel() {
    val location: StateFlow<String> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) flowOf("") else raceRepository.observeRace(raceId).map { it?.location.orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                BibsModeDispatcherViewModel(container.settingsRepository, container.raceRepository)
            }
        }
    }
}
