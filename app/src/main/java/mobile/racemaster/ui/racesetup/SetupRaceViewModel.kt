package mobile.racemaster.ui.racesetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobile.racemaster.data.mule.AvailableRace
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer

/** Setup Race's online branch — see [SetupRaceViewModel.scanServer]/[SetupRaceViewModel.availableRaces]. */
sealed interface AvailableRacesState {
    /** Nothing scanned yet — the initial state, and what a manual Cancel/re-entry resets back to. */
    data object NotChecked : AvailableRacesState
    data object Loading : AvailableRacesState
    data class Found(val races: List<AvailableRace>) : AvailableRacesState
    /** Couldn't reach the server, not logged in, or reachable but genuinely nothing recent —
     *  see [MuleRepository.getAvailableRaces]'s own doc for why those aren't told apart here:
     *  either way the operator's only path forward is the manual name field below. */
    data object Unavailable : AvailableRacesState
}

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
    private val progressRepository: ProgressRepository,
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

    /** Creates a new race under [name]/[location], makes it this device's active race, records
     *  its own setup marker (see [RaceRepository.recordSetupMarker]'s own doc — this is what lets
     *  the web app see this device's location as soon as it's set up, over either transport, not
     *  just once the first real split lands), and (best-effort — see
     *  MuleRepository.announceRaceSetup's own doc) announces it to the server immediately if
     *  already logged in, independent of any mode selection. The manual (offline, or
     *  online-but-not-picking-a-scanned-race) path — see [pickAvailableRace] for the online
     *  branch that adopts an exact existing server race instead. */
    suspend fun save(name: String, location: String) {
        val trimmedName = name.trim()
        val trimmedLocation = location.trim()
        settingsRepository.addRaceNameToHistory(trimmedName)
        settingsRepository.addLocationToHistory(trimmedLocation)
        val newRaceId = raceRepository.startNewRace(trimmedName, location = trimmedLocation)
        raceRepository.switchActiveRace(newRaceId)
        raceRepository.recordSetupMarker(newRaceId)
        muleRepository.announceRaceSetup()
    }

    private val _availableRaces = MutableStateFlow<AvailableRacesState>(AvailableRacesState.NotChecked)
    val availableRaces: StateFlow<AvailableRacesState> = _availableRaces.asStateFlow()

    /** Setup Race's online branch (TODO.md's phase 2): scans the server for this owner's own
     *  recent races (within [SettingsRepository.raceStaleAfterDays]), for the operator to pick
     *  from instead of typing a name manually — see [MuleRepository.getAvailableRaces]. Safe to
     *  call with no active network/login; [AvailableRacesState.Unavailable] is the graceful
     *  degrade-to-manual-entry outcome for every such case, not an error the screen needs to
     *  handle specially. */
    fun scanServer() {
        viewModelScope.launch {
            _availableRaces.value = AvailableRacesState.Loading
            val maxAgeDays = settingsRepository.raceStaleAfterDays.first()
            val races = muleRepository.getAvailableRaces(maxAgeDays)
            _availableRaces.value = if (races.isNullOrEmpty()) AvailableRacesState.Unavailable else AvailableRacesState.Found(races)
        }
    }

    /** Back to [AvailableRacesState.NotChecked] — dismissing the picker (Cancel on the dialog)
     *  without picking anything, so a later Scan Server press starts fresh rather than
     *  re-showing a stale list. */
    fun dismissAvailableRaces() {
        _availableRaces.value = AvailableRacesState.NotChecked
    }

    /** Adopts [race] exactly — see [RaceRepository.adoptRaceLabel]'s own doc for why this can't
     *  just be [save] with a different name — makes it this device's active race, records its
     *  own setup marker (see [save]'s own doc), then immediately pulls whatever progress the
     *  server already has for it (race-id-then-progress sequencing: the local race row must
     *  exist first, since [ProgressRepository]'s own storage is keyed by local raceId, not
     *  raceLabel) and announces the device file the same way [save]'s manual path does. */
    suspend fun pickAvailableRace(race: AvailableRace, location: String) {
        val trimmedLocation = location.trim()
        settingsRepository.addLocationToHistory(trimmedLocation)
        val newRaceId = raceRepository.adoptRaceLabel(race.raceLabel, trimmedLocation)
        raceRepository.switchActiveRace(newRaceId)
        raceRepository.recordSetupMarker(newRaceId)
        val baseUrl = settingsRepository.serverBaseUrl.first()
        val token = settingsRepository.authToken.first()
        if (baseUrl != null && token != null) {
            progressRepository.refreshFromServer(baseUrl, token, newRaceId, race.raceLabel)
        }
        muleRepository.announceRaceSetup()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                SetupRaceViewModel(
                    container.raceRepository, container.settingsRepository,
                    container.muleRepository, container.progressRepository,
                )
            }
        }
    }
}
