package mobile.racemaster.ui.racedetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.hasRealEntries
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the race details screen used both for creating a new race ([existingRaceId] null) and
 * editing an existing one's name/course/location/runner count/first bib number — same screen, same
 * fields, for every mode (Time Mode never actually uses the bib number for anything, but
 * collects it anyway so both forms stay identical). These fields stay editable after creation
 * too, but only while the race is still "fresh" (no real splits/entries recorded yet — see
 * RaceDetailsScreen's `isFresh`); this ViewModel just writes back whatever the screen passes
 * in either way. Server URL is deliberately not part of this screen — it'll live under Mule
 * Mode setup eventually, device-wide rather than per-race.
 */
class RaceDetailsViewModel(
    val mode: AppMode,
    val existingRaceId: Long?,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
    bibsModeRepository: BibsModeRepository,
) : ViewModel() {

    val existingRace: StateFlow<RaceEntity?> = if (existingRaceId == null) {
        MutableStateFlow(null)
    } else {
        raceRepository.observeRace(existingRaceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    // "Clear race" (RaceDetailsScreen's second button, mirroring Setup Server's own "No
    // Server") is only ever offered for an existing race that isn't active — same definition
    // isRaceCurrentlyActive/RaceRepository.deleteRace already enforce, computed reactively here
    // (rather than a one-shot isRaceCurrentlyActive call) so the button updates live if this
    // race's state changes while the form is open, same as NameDeviceViewModel.hasActiveRace.
    val canClearRace: StateFlow<Boolean> = if (existingRaceId == null) {
        MutableStateFlow(false)
    } else {
        combine(
            existingRace,
            bibsModeRepository.observeCurrentSegmentEntries(existingRaceId),
        ) { race, bibsEntries ->
            race != null && !isRaceActive(race.timeModeStartedAtMillis, bibsEntries.hasRealEntries(), race.cpModeStartedAtMillis)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Every race name previously saved from this form (new or renamed) — see
    // RaceDetailsScreen's Race name field, which offers these for re-selection.
    val raceNameHistory: StateFlow<List<String>> = settingsRepository.raceNameHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Every course previously saved from this form — see RaceDetailsScreen's Course field.
    val courseHistory: StateFlow<List<String>> = settingsRepository.courseHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Every location previously saved from this form — see RaceDetailsScreen's Location field.
    val locationHistory: StateFlow<List<String>> = settingsRepository.locationHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Creates a new race or updates the existing one's details, then arms it as the active
     *  race for new ones. Returns the resulting race id. Bibs Mode no longer needs a
     *  dedicated creation path — its Clock marker is deferred to an explicit Start button
     *  press (see BibsModeRepository.startBibsMode), so creation itself is identical to every
     *  other mode's. bibsRangeStart/Count are required in practice for BIBS (the form itself
     *  won't enable Create without them — see RaceDetailsScreen's countFieldsValid), but
     *  stay nullable here since Time Mode collects the same fields purely for form/feedback
     *  parity without ever needing them. */
    suspend fun save(name: String, course: String, location: String, bibsRangeStart: Int?, bibsRangeCount: Int?): Long {
        val trimmedName = name.trim()
        val trimmedCourse = course.trim()
        val trimmedLocation = location.trim()
        settingsRepository.addRaceNameToHistory(trimmedName)
        settingsRepository.addCourseToHistory(trimmedCourse)
        settingsRepository.addLocationToHistory(trimmedLocation)

        val raceId = existingRaceId
        return if (raceId != null) {
            raceRepository.updateRaceDetails(raceId, trimmedName, trimmedCourse, trimmedLocation, bibsRangeStart, bibsRangeCount)
            raceId
        } else {
            val newRaceId = raceRepository.startNewRace(
                trimmedName,
                trimmedCourse,
                location = trimmedLocation,
                deviceRole = mode.name,
                bibsRangeStart = bibsRangeStart,
                bibsRangeCount = bibsRangeCount,
            )
            settingsRepository.setAppMode(mode)
            settingsRepository.setActiveRaceId(newRaceId)
            newRaceId
        }
    }

    // "Reset to no race" — permanently deletes this race (RaceRepository.deleteRace already
    // refuses if it's active, matching canClearRace above), then the screen exits exactly like
    // Cancel does; there's no saved raceId left to report back via onSaved. A no-op if
    // existingRaceId is null (nothing to clear on a brand-new, unsaved race).
    suspend fun clearRace() {
        existingRaceId?.let { raceRepository.deleteRace(it) }
    }

    companion object {
        fun factory(mode: AppMode, existingRaceId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RaceDetailsViewModel(
                    mode,
                    existingRaceId,
                    container.raceRepository,
                    container.settingsRepository,
                    container.bibsModeRepository,
                )
            }
        }
    }
}
