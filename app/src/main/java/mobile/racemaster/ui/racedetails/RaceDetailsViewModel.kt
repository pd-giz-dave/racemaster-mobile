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
 * Backs the "Relocate" screen (formerly "This Race") — name+location editor for the device's
 * already-created race (creation itself moved to Setup Race — see TODO.md's phase 1;
 * course/bib-range fields are gone entirely, not just moved). Name stays editable only until the
 * race has genuinely started a mode — locked read-only once [raceIsActive] is true, same as
 * always, since a race that's already recording history needs a different name to actually be a
 * new race instead (it's baked into the label's sync identity). Location is different: it's
 * editable even while [raceIsActive], via [relocate] — this is the screen's whole new purpose,
 * see that function's own doc.
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

    // Whether CP Mode specifically is among the currently-active modes on this race — the one
    // piece of "which mode(s) are active" this screen actually needs, to know whether to enforce
    // isValidCpLocation on a relocation the same way CpModeScreen already enforces it at its own
    // Start button. No new query: existingRace already carries cpModeStartedAtMillis.
    val cpModeActive: StateFlow<Boolean> = existingRace.map { it?.cpModeStartedAtMillis != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Every race name/location previously saved from this form (or Setup Race), so this form's
    // own fields can offer the same history dropdown.
    val raceNameHistory: StateFlow<List<String>> = settingsRepository.raceNameHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val locationHistory: StateFlow<List<String>> = settingsRepository.locationHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The pre-race path — nothing's been recorded yet, so a name/location edit is still just a
    // plain field overwrite, exactly as before this screen gained its relocate capability.
    suspend fun save(name: String, location: String) {
        val trimmedName = name.trim()
        val trimmedLocation = location.trim()
        settingsRepository.addRaceNameToHistory(trimmedName)
        settingsRepository.addLocationToHistory(trimmedLocation)
        raceRepository.updateRaceDetails(existingRaceId, trimmedName, trimmedLocation)
    }

    // The mid-race path — name is locked (the screen never offers it for editing once active,
    // see RaceDetailsScreen's own nameFieldEnabled), so only location moves. Writes a real
    // LOCATION marker (RaceRepository.relocateActiveModes) rather than a plain field overwrite —
    // that's what gives the move history, per-mode segment/counter-reset behavior, and undo (see
    // HistoryAction.LOCATION's own doc) — a bare updateRaceDetails call would silently lose all
    // of that. A no-op if the location wasn't actually changed, so re-pressing Save on an
    // unmodified field never writes a spurious, undo-able "relocated to the same place" event.
    suspend fun relocate(location: String) {
        val trimmedLocation = location.trim()
        if (trimmedLocation == existingRace.value?.location) return
        settingsRepository.addLocationToHistory(trimmedLocation)
        raceRepository.relocateActiveModes(existingRaceId, trimmedLocation)
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
