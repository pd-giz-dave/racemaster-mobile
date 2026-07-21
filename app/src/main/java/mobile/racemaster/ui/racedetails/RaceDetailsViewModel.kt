package mobile.racemaster.ui.racedetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the race details screen used both for creating a new race ([existingRaceId] null) and
 * editing an existing one's name/course/runner count/first bib number — same screen, same
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
) : ViewModel() {

    val existingRace: StateFlow<RaceEntity?> = if (existingRaceId == null) {
        MutableStateFlow(null)
    } else {
        raceRepository.observeRace(existingRaceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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

    /** Creates a new race or updates the existing one's details, then arms it as the active
     *  race for new ones. Returns the resulting race id. Bibs Mode no longer needs a
     *  dedicated creation path — its Clock marker is deferred to an explicit Start button
     *  press (see BibsModeRepository.startBibsMode), so creation itself is identical to every
     *  other mode's. bibsRangeStart/Count are required in practice for BIBS (the form itself
     *  won't enable Create without them — see RaceDetailsScreen's countFieldsValid), but
     *  stay nullable here since Time Mode collects the same fields purely for form/feedback
     *  parity without ever needing them. */
    suspend fun save(name: String, course: String, bibsRangeStart: Int?, bibsRangeCount: Int?): Long {
        val trimmedName = name.trim()
        val trimmedCourse = course.trim()
        settingsRepository.addRaceNameToHistory(trimmedName)
        settingsRepository.addCourseToHistory(trimmedCourse)

        val raceId = existingRaceId
        return if (raceId != null) {
            raceRepository.updateRaceDetails(raceId, trimmedName, trimmedCourse, bibsRangeStart, bibsRangeCount)
            raceId
        } else {
            val newRaceId = raceRepository.startNewRace(
                trimmedName,
                trimmedCourse,
                deviceRole = mode.name,
                bibsRangeStart = bibsRangeStart,
                bibsRangeCount = bibsRangeCount,
            )
            settingsRepository.setAppMode(mode)
            settingsRepository.setActiveRaceId(newRaceId)
            newRaceId
        }
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
