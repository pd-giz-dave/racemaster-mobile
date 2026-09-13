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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the race details screen used both for creating a new race ([existingRaceId] null) and
 * editing an existing one ([existingRaceId] non-null) — same screen, same fields, for every mode
 * (Time Mode never actually uses the bib number for anything, but collects it anyway so both
 * forms stay identical). Name/courses/location stay editable until the race has genuinely
 * started a mode — RaceDetailsScreen locks them read-only only once [raceIsActive] is true (see
 * that flow's own doc and RaceRepository.updateRaceDetails' own doc for why that's safe); a
 * race that's already recording history needs a different name/location to actually be a new
 * race instead, since they're baked into the label's sync identity — courses (the offered menu,
 * not a single locked choice any more — see RaceEntity.courses' own doc) isn't itself part of
 * the label, but stays locked alongside them for simplicity/consistency rather than getting its
 * own separate rule. This ViewModel just writes back whatever the screen passes in either way —
 * it has no opinion of its own on which fields a given call site left unchanged. Server URL is
 * deliberately not part of this screen — it'll live under Mule Mode setup eventually,
 * device-wide rather than per-race.
 */
class RaceDetailsViewModel(
    val mode: AppMode,
    val existingRaceId: Long?,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val existingRace: StateFlow<RaceEntity?> = if (existingRaceId == null) {
        MutableStateFlow(null)
    } else {
        raceRepository.observeRace(existingRaceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    // Whether this race has genuinely started any mode (a real *ModeStartedAtMillis, not merely
    // "was created" or "was opened") — the one gate both identityFieldsEnabled below (via
    // RaceDetailsScreen) and canClearRace rely on. False for a brand-new, not-yet-saved race
    // (existingRace is a fixed null StateFlow in that case) — nothing to be active yet. Computed
    // reactively (not a one-shot check) so both derived flows update live if this race's state
    // changes while the form is open, same as NameDeviceViewModel.hasActiveRace already does.
    val raceIsActive: StateFlow<Boolean> = existingRace.map { race ->
        race != null && isRaceActive(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis, race.cpModeStartedAtMillis)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // "Clear race" (RaceDetailsScreen's second button, mirroring Setup Server's own "No
    // Server") is only ever offered for an existing race that isn't active — same definition
    // isRaceCurrentlyActive/RaceRepository.deleteRace already enforce.
    val canClearRace: StateFlow<Boolean> = raceIsActive.map { active -> existingRaceId != null && !active }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
     *  parity without ever needing them. [courses] is the offered course menu (RaceDetailsScreen's
     *  "Courses" field) — a brand-new race is created course-less (see RaceEntity.course's own
     *  doc); the actual course is only picked once a mode's own Start button is pressed (see
     *  RaceRepository.resolveCourseRace). */
    suspend fun save(name: String, courses: List<String>, location: String, bibsRangeStart: Int?, bibsRangeCount: Int?): Long {
        val trimmedName = name.trim()
        val trimmedCourses = courses.map { it.trim() }.distinct()
        val trimmedLocation = location.trim()
        settingsRepository.addRaceNameToHistory(trimmedName)
        trimmedCourses.forEach { settingsRepository.addCourseToHistory(it) }
        settingsRepository.addLocationToHistory(trimmedLocation)

        val raceId = existingRaceId
        return if (raceId != null) {
            raceRepository.updateRaceDetails(raceId, trimmedName, trimmedCourses, trimmedLocation, bibsRangeStart, bibsRangeCount)
            raceId
        } else {
            val newRaceId = raceRepository.startNewRace(
                trimmedName,
                course = "",
                location = trimmedLocation,
                deviceRole = mode.name,
                bibsRangeStart = bibsRangeStart,
                bibsRangeCount = bibsRangeCount,
                courses = trimmedCourses,
            )
            settingsRepository.setAppMode(mode)
            // Routed through RaceRepository (not settingsRepository directly) so an earlier
            // "New Race" attempt abandoned before ever picking a course — still sitting as the
            // active race, course-less — gets cleaned up now rather than lingering forever; see
            // RaceRepository.switchActiveRace's own doc.
            raceRepository.switchActiveRace(newRaceId)
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
                )
            }
        }
    }
}
