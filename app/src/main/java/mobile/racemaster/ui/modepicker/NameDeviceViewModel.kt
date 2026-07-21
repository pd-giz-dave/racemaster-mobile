package mobile.racemaster.ui.modepicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.hasRealEntries
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.repository.isRaceCurrentlyActive
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.util.generateDeviceName

/** This device's name (see [SettingsRepository.getOrCreateDeviceName]) tags every split/bib
 *  entry it records — auto-generated once ("clever-cricket"), freely renamable here — but
 *  never while the active race is active per [isRaceCurrentlyActive] (the centralized
 *  definition — see its own doc), so an active race never ends up with some of its history
 *  tagged under the old name and some under the new one. A race that's merely selected/defined
 *  but never started, or one that's been stopped *and* Reset, doesn't count as active for this
 *  purpose — but one that's merely been Stopped (not Reset) still does. */
@OptIn(ExperimentalCoroutinesApi::class)
class NameDeviceViewModel(
    private val settingsRepository: SettingsRepository,
    private val raceRepository: RaceRepository,
    private val bibsModeRepository: BibsModeRepository,
) : ViewModel() {

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasActiveRace: StateFlow<Boolean> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(false)
            } else {
                combine(
                    raceRepository.observeRace(raceId),
                    bibsModeRepository.observeCurrentSegmentEntries(raceId),
                ) { race, bibsEntries ->
                    race != null && isRaceActive(race.timeModeStartedAtMillis, bibsEntries.hasRealEntries())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Ensures a name exists (generating one on first-ever launch) so the field always
        // has something to prefill, rather than showing blank until the operator types.
        viewModelScope.launch { settingsRepository.getOrCreateDeviceName() }
    }

    // Just a suggestion into the text field, not persisted until Save is tapped — matches
    // the rest of this form's edit-then-save pattern rather than committing a rename the
    // operator hasn't confirmed yet.
    fun generateAnother(): String = generateDeviceName()

    fun save(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // Defense-in-depth backstop — NameDeviceScreen already disables editing entirely
            // while the active race is active, this just guards the actual mutation too.
            val raceId = settingsRepository.activeRaceId.first()
            if (raceId != null && isRaceCurrentlyActive(raceId, raceRepository, bibsModeRepository)) return@launch
            settingsRepository.setDeviceName(trimmed)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                NameDeviceViewModel(container.settingsRepository, container.raceRepository, container.bibsModeRepository)
            }
        }
    }
}
