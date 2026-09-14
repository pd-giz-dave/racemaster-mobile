package mobile.racemaster.ui.racesetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer

/**
 * Backs the "Setup Race" screen — the one place a race gets created now (see TODO.md's phase 1:
 * the course/first-bib/runner-count concepts are dropped; a race is just a name — which may
 * carry its own Seniors/Juniors suffix as plain text, the same convention the web app already
 * uses for its own per-course race labels — and a location). Reached from Setup Device, before
 * any mode is selected, so the device file can reach the server (or a mule) as soon as setup is
 * done rather than waiting for the first split. Disabled while a race is already active, same
 * guard NameDeviceViewModel uses for renaming — creating a fresh race out from under one still
 * actively recording would silently abandon it as this device's active race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupRaceViewModel(
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
    private val muleRepository: MuleRepository,
) : ViewModel() {

    val hasActiveRace: StateFlow<Boolean> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(false)
            } else {
                raceRepository.observeRace(raceId).map { race ->
                    race != null && isRaceActive(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis, race.cpModeStartedAtMillis)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Every race name/location previously saved from this form, most-recent-first — lets each
    // field offer a history dropdown, same independent-field behavior as RaceDetailsScreen's
    // own fields used to have (picking one only ever fills that one field).
    val raceNameHistory: StateFlow<List<String>> = settingsRepository.raceNameHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locationHistory: StateFlow<List<String>> = settingsRepository.locationHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Creates a new race under [name]/[location], makes it this device's active race, and
     *  (best-effort — see MuleRepository.announceRaceSetup's own doc) announces it to the
     *  server immediately if already logged in, independent of any mode selection. */
    suspend fun save(name: String, location: String) {
        val trimmedName = name.trim()
        val trimmedLocation = location.trim()
        settingsRepository.addRaceNameToHistory(trimmedName)
        settingsRepository.addLocationToHistory(trimmedLocation)
        val newRaceId = raceRepository.startNewRace(trimmedName, course = "", location = trimmedLocation)
        raceRepository.switchActiveRace(newRaceId)
        raceRepository.getRace(newRaceId)?.label?.let { muleRepository.announceRaceSetup(it) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                SetupRaceViewModel(container.raceRepository, container.settingsRepository, container.muleRepository)
            }
        }
    }
}
