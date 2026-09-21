package mobile.racemaster.ui.racesetup

import android.app.Application
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
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.data.settings.SetupRaceDraft
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
import mobile.racemaster.util.hasInternetConnectivity

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
    // Application, not Context — see ViewModelFactorySupport.applicationContext's own doc for
    // why that's what keeps Lint's StaticFieldLeak check from flagging a ViewModel field here.
    private val context: Application,
) : ViewModel() {

    // One-shot, checked-once-on-entry connectivity hint for Scan Server's own enablement — same
    // non-live-subscription convention MuleServerSetupViewModel.hasInternetConnectivity already
    // uses (see NetworkStatus.hasInternetConnectivity's own doc for why a live subscription isn't
    // needed here either).
    fun hasInternetConnectivity(): Boolean = hasInternetConnectivity(context)

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

    // What the operator has typed/picked so far, surviving a navigate-away-and-back before Save
    // (e.g. via Setup Device) — see SetupRaceDraft's own doc. Nullable, not a plain
    // SetupRaceDraft() default (same reasoning as MuleServerSetupViewModel.draft): DataStore
    // reads are async, so the very first composition sees this StateFlow's own initial value
    // before the real stored draft has arrived. A non-null placeholder default here was a real,
    // confirmed bug — SetupRaceScreen's own draftSeeded guard would latch onto that placeholder
    // as if it were the real (possibly genuinely blank, for a fresh install) draft, locking out
    // the real value's own emission moments later, and then re-persist the placeholder's defaults
    // over it. null vs. a genuine-but-blank SetupRaceDraft are the only two states that must stay
    // distinguishable for the screen's own seed-exactly-once guard to wait for the real one.
    val draft: StateFlow<SetupRaceDraft?> = settingsRepository.setupRaceDraft
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveDraft(name: String, location: String, mode: AppMode?) {
        viewModelScope.launch { settingsRepository.saveSetupRaceDraft(name, location, mode) }
    }

    /** Reverts the persisted draft back to [name]/[location]/[mode] — SetupRaceScreen's own
     *  "Discard changes?" confirmation calls this with whatever the draft held when the screen
     *  was first opened this visit, undoing the live keystroke-by-keystroke persistence
     *  ([saveDraft] above) that would otherwise leave this visit's abandoned edits sitting in
     *  the draft as if they were the real baseline — the exact bug that made Save permanently
     *  stuck disabled on a later revisit (an unsaved-but-typed draft looked identical to an
     *  already-saved one, since both compare equal to themselves). A suspend function (not
     *  fire-and-forget like [saveDraft]) so the caller can await the write finishing before
     *  navigating away — [saveDraft]'s own viewModelScope.launch would otherwise race against
     *  this ViewModel being cleared the moment navigation pops this screen. */
    suspend fun revertDraft(name: String, location: String, mode: AppMode?) {
        settingsRepository.saveSetupRaceDraft(name, location, mode)
    }

    /** Creates a new race under [name]/[location]/[mode], makes it this device's active race,
     *  records its own initial LOCATION+MODE_START pair (see
     *  [RaceRepository.recordModeStart]'s own doc — this is what lets the web app see this
     *  device's station and mode as soon as it's set up, over either transport, not just once the
     *  first real split lands), and (best-effort — see MuleRepository.announceRaceSetup's own
     *  doc) announces it to the server immediately if already logged in. The draft is
     *  deliberately left as-is on success (see [draft]'s own doc) — it's a durable sticky
     *  default, not cleared per-race, the same way Setup Server's own draft only ever reverts on
     *  an explicit Cancel. Scanning the server for a recent race (see [scanServer]) only ever
     *  fills in [name] on this same screen — this is the one and only place anything is written. */
    suspend fun save(name: String, location: String, mode: AppMode) {
        val trimmedName = name.trim()
        val trimmedLocation = location.trim()
        settingsRepository.addRaceNameToHistory(trimmedName)
        settingsRepository.addLocationToHistory(trimmedLocation)
        val newRaceId = raceRepository.startNewRace(trimmedName, location = trimmedLocation)
        raceRepository.switchActiveRace(newRaceId)
        raceRepository.recordModeStart(newRaceId, mode, trimmedLocation)
        muleRepository.announceRaceSetup()
    }

    private val _availableRaces = MutableStateFlow<AvailableRacesState>(AvailableRacesState.NotChecked)
    val availableRaces: StateFlow<AvailableRacesState> = _availableRaces.asStateFlow()

    /** Setup Race's online branch: scans the server for this owner's own recent races (within
     *  [SettingsRepository.raceStaleAfterDays]), for the operator to pick from instead of typing
     *  a name manually — see [MuleRepository.getAvailableRaces]. Safe to call with no active
     *  network/login; [AvailableRacesState.Unavailable] is the graceful degrade-to-manual-entry
     *  outcome for every such case, not an error the screen needs to handle specially. Picking a
     *  race (see SetupRaceScreen's own `onPick`) only ever fills in the name field — [save] is
     *  the one and only place anything is actually written. */
    fun scanServer() {
        viewModelScope.launch {
            _availableRaces.value = AvailableRacesState.Loading
            val maxAgeDays = settingsRepository.raceStaleAfterDays.first()
            val races = muleRepository.getAvailableRaces(maxAgeDays)
            _availableRaces.value = if (races.isNullOrEmpty()) AvailableRacesState.Unavailable else AvailableRacesState.Found(races)
        }
    }

    /** Back to [AvailableRacesState.NotChecked] — dismissing the picker (Cancel on the dialog, or
     *  picking a race) without leaving a stale list behind for a later Scan Server press. */
    fun dismissAvailableRaces() {
        _availableRaces.value = AvailableRacesState.NotChecked
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                SetupRaceViewModel(
                    container.raceRepository, container.settingsRepository,
                    container.muleRepository, applicationContext(),
                )
            }
        }
    }
}
