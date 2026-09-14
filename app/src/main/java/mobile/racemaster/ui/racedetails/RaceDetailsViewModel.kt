package mobile.racemaster.ui.racedetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the "This Race" screen — a rename-only editor for the device's already-created race
 * (creation itself moved to Setup Race — see TODO.md's phase 1; course/bib-range fields are
 * gone entirely, not just moved). Name/location stay editable until the race has genuinely
 * started a mode — locked read-only only once [raceIsActive] is true (see that flow's own doc
 * and RaceRepository.updateRaceDetails' own doc for why that's safe); a race that's already
 * recording history needs a different name/location to actually be a new race instead, since
 * the name is baked into the label's sync identity.
 */
class RaceDetailsViewModel(
    private val existingRaceId: Long,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val existingRace: StateFlow<RaceEntity?> = raceRepository.observeRace(existingRaceId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Computed reactively (not a one-shot check) so it updates live if this race's state
    // changes while the form is open, same as NameDeviceViewModel.hasActiveRace already does.
    val raceIsActive: StateFlow<Boolean> = existingRace.map { race ->
        race != null && isRaceActive(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis, race.cpModeStartedAtMillis)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Every race name/location previously saved from this form (or Setup Race), so this form's
    // own fields can offer the same history dropdown.
    val raceNameHistory: StateFlow<List<String>> = settingsRepository.raceNameHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locationHistory: StateFlow<List<String>> = settingsRepository.locationHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun save(name: String, location: String) {
        val trimmedName = name.trim()
        val trimmedLocation = location.trim()
        settingsRepository.addRaceNameToHistory(trimmedName)
        settingsRepository.addLocationToHistory(trimmedLocation)
        raceRepository.updateRaceDetails(existingRaceId, trimmedName, trimmedLocation)
    }

    companion object {
        fun factory(existingRaceId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RaceDetailsViewModel(existingRaceId, container.raceRepository, container.settingsRepository)
            }
        }
    }
}
