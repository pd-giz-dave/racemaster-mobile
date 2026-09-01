package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.isRaceStale
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.activeModeLabels
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.di.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface HistoryItemUi {
    // Always this device's own race — see RaceRepository.observeAllRaces, which only ever
    // returns races this installation itself created. Shown as "From {name} (self)" so it
    // reads consistently alongside a MuleSource entry's "From {name}" once both appear in the
    // same list. [isActive] is per isRaceActive — the centralized definition (see its own
    // doc) — not merely "this is the currently-selected race". RaceHistoryScreen disables
    // deleting it accordingly (see RaceRepository.deleteRace's own backstop, which uses the
    // same definition via isRaceCurrentlyActive).
    // [serverSyncSkippedAsStale] mirrors MuleRepository.pushToServer's own age cutoff for this
    // race, sourced from its own real activity (RaceRepository.observeLastActivityAtMillis),
    // not any sync bookkeeping — true once this race's history hasn't been touched recently
    // enough to still be checked against the server. false (never stale) for a race with no
    // history at all yet.
    data class LocalRace(
        val id: Long,
        val label: String,
        val createdByDeviceName: String,
        val isActive: Boolean,
        // Which mode(s) actually keep isActive true — see activeModeLabels' own doc for why
        // this is more than a formatting nicety: the operator may be looking at a screen for a
        // mode that's already fully reset, with no way to tell which *other* mode is really
        // still blocking deletion without this.
        val activeModeLabels: List<String>,
        val serverSyncSkippedAsStale: Boolean,
    ) : HistoryItemUi
    // A race pulled via Mule from a genuinely different physical device — this device's own
    // data is never staged into that same table at all (see PulledRecordEntity's own doc), so
    // this can never be just an echo of a LocalRace entry above. [sourceDeviceId] (not just
    // [raceLabel]) identifies this entry, since more than one physical device can share a race
    // label — see PulledSourceSummary's own doc.
    // [serverSyncSkippedAsStale] — see LocalRace's own doc above for the exact rule.
    data class MuleSource(
        val raceLabel: String,
        val sourceDeviceId: String,
        val deviceName: String,
        val serverSyncSkippedAsStale: Boolean,
    ) : HistoryItemUi
}

// Thin, directly-testable name for this screen's own display badge — see isRaceStale's own
// doc for the actual rule, shared with MuleRepository.pushToServer and PeripheralSyncService's
// relay-manifest serving so all three agree on exactly the same cutoff.
internal fun isSkippedAsStale(lastTouchedAtMillis: Long?, maxAgeDays: Int): Boolean =
    isRaceStale(lastTouchedAtMillis, maxAgeDays)

private data class HistorySources(
    val races: List<RaceEntity>,
    val sourceSummaries: List<PulledSourceSummary>,
    val lastTouchedAtMillis: Map<String, Long>,
    val maxAgeDays: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RaceHistoryViewModel(
    private val raceRepository: RaceRepository,
    private val muleRepository: MuleRepository,
) : ViewModel() {

    val historyItems: StateFlow<List<HistoryItemUi>> = combine(
        raceRepository.observeAllRaces(),
        muleRepository.sourceSummaries,
        muleRepository.raceLabelLastTouchedAtMillis,
        muleRepository.raceStaleAfterDays,
    ) { races, sourceSummaries, lastTouchedAtMillis, maxAgeDays ->
        HistorySources(races, sourceSummaries, lastTouchedAtMillis, maxAgeDays)
    }
        .flatMapLatest { (races, sourceSummaries, lastTouchedAtMillis, maxAgeDays) ->
            val muleItems = sourceSummaries.map {
                HistoryItemUi.MuleSource(
                    raceLabel = it.sourceRaceLabel,
                    sourceDeviceId = it.sourceDeviceId,
                    deviceName = it.deviceName,
                    serverSyncSkippedAsStale = isSkippedAsStale(lastTouchedAtMillis[it.sourceRaceLabel], maxAgeDays),
                )
            }
            if (races.isEmpty()) {
                flowOf(muleItems)
            } else {
                // One flow per race — its own last-activity timestamp (for staleness — a local
                // race's own real history, not any Mule-inbox bookkeeping; see
                // isSkippedAsStale's own doc). isActive itself is a plain read off the race's
                // own Time/Bibs/CP started-at fields (see isRaceActive), no separate query
                // needed for it.
                combine(
                    races.map { race ->
                        raceRepository.observeLastActivityAtMillis(race.id).map { lastActivityAtMillis ->
                            HistoryItemUi.LocalRace(
                                id = race.id,
                                label = race.label,
                                createdByDeviceName = race.createdByDeviceName,
                                isActive = isRaceActive(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis, race.cpModeStartedAtMillis),
                                activeModeLabels = activeModeLabels(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis, race.cpModeStartedAtMillis),
                                serverSyncSkippedAsStale = isSkippedAsStale(lastActivityAtMillis, maxAgeDays),
                            )
                        }
                    },
                ) { localRaces -> localRaces.toList() + muleItems }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Permanently erases a local race — see RaceRepository.deleteRace's own doc. Irreversible;
    // RaceHistoryScreen only calls this after its own confirmation dialog (and never offers it
    // at all for an active race — see HistoryItemUi.LocalRace.isActive).
    fun deleteRace(raceId: Long) {
        viewModelScope.launch { raceRepository.deleteRace(raceId) }
    }

    // See RaceRepository.forceResetActiveModes' own doc — un-sticks a race whose active mode(s)
    // are no longer reachable via that mode's own in-context Reset button, so it can then be
    // deleted through the normal deleteRace flow above.
    fun forceResetActiveModes(raceId: Long) {
        viewModelScope.launch { raceRepository.forceResetActiveModes(raceId) }
    }

    // See MuleRepository.deleteSource's own doc for why a Mule source (a relayed copy, not the
    // one true record of a race) is always deletable, with no active-race guard like deleteRace
    // above has.
    fun deleteMuleSource(raceLabel: String, sourceDeviceId: String) {
        viewModelScope.launch { muleRepository.deleteSource(raceLabel, sourceDeviceId) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RaceHistoryViewModel(container.raceRepository, container.muleRepository)
            }
        }
    }
}
