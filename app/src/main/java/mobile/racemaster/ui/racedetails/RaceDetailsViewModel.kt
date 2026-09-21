package mobile.racemaster.ui.racedetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the "Relocate" screen (formerly "This Race") — name/location/mode editor for the
 * device's already-created race (creation itself moved to Setup Race — see TODO.md's phase 1;
 * course/bib-range fields are gone entirely, not just moved). Name stays editable only until the
 * race has genuinely started a mode — locked read-only once [raceIsActive] is true, same as
 * always, since a race that's already recording history needs a different name to actually be a
 * new race instead (it's baked into the label's sync identity). Location and mode are different:
 * both are editable even while [raceIsActive], via [save] — this is the screen's whole new
 * purpose, see that function's own doc.
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

    private val modeSwitchErrorFlow = MutableStateFlow<String?>(null)
    val modeSwitchError: StateFlow<String?> = modeSwitchErrorFlow.asStateFlow()
    fun dismissModeSwitchError() { modeSwitchErrorFlow.value = null }

    // One save path now covers both the pre-race case (nothing recorded yet — name, location and
    // mode are all still a plain field overwrite) and the mid-race "Relocate" case (name is
    // locked, see RaceDetailsScreen's own nameFieldEnabled, but location and/or mode can still
    // move). A location/mode change writes a real LOCATION+MODE_START pair
    // (RaceRepository.recordModeStart) rather than a plain field overwrite — that's what gives
    // the move history, per-mode segment/counter-reset behavior, and undo (see
    // HistoryAction.LOCATION's own doc) — a bare field update would silently lose all of that.
    // A no-op on that part if neither actually changed, so re-pressing Save on an unmodified form
    // never writes a spurious, undo-able "relocated to the same place" event. Switching into a
    // different mode than the one being left is blocked (see
    // [RaceRepository.blockedModeSwitchReason]) only while the mode being left is still actually
    // recording (started, not yet stopped) — merely Stopped is enough to permit the switch,
    // same relaxed rule a pure location-only Relocate already gets for free — surfaced via
    // [modeSwitchError].
    // Returns true once the save actually applied (or there was nothing to apply), false if it
    // was blocked (see [modeSwitchError]) — the screen must only navigate away on true, otherwise
    // the blocked-switch dialog gets replaced by navigation before the operator ever sees it.
    suspend fun save(name: String, location: String, mode: AppMode): Boolean {
        val race = existingRace.value ?: return true
        val trimmedName = name.trim()
        val trimmedLocation = location.trim()
        val currentMode = race.mode?.let { raw -> runCatching { AppMode.valueOf(raw) }.getOrNull() }
        val locationOrModeChanged = trimmedLocation != race.location || mode != currentMode
        if (locationOrModeChanged) {
            val blockedReason = raceRepository.blockedModeSwitchReason(existingRaceId, mode)
            if (blockedReason != null) {
                modeSwitchErrorFlow.value = blockedReason
                return false
            }
        }
        if (!raceIsActive.value && trimmedName != race.name) {
            settingsRepository.addRaceNameToHistory(trimmedName)
            raceRepository.updateRaceDetails(existingRaceId, trimmedName, race.location)
        }
        if (locationOrModeChanged) {
            settingsRepository.addLocationToHistory(trimmedLocation)
            raceRepository.recordModeStart(existingRaceId, mode, trimmedLocation)
        }
        return true
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
