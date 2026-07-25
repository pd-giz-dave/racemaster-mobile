package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.hasRealEntries
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.di.appContainer
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

// null (no activity signal at all — a Mule-pulled label never seen, or a local race with no
// history yet) is never stale, only a genuine timestamp older than the cutoff counts.
internal fun isSkippedAsStale(lastTouchedAtMillis: Long?, maxAgeDays: Int): Boolean {
    if (lastTouchedAtMillis == null) return false
    val cutoffMillis = System.currentTimeMillis() - maxAgeDays.days.inWholeMilliseconds
    return lastTouchedAtMillis < cutoffMillis
}

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
    bibsModeRepository: BibsModeRepository,
) : ViewModel() {

    val historyItems: StateFlow<List<HistoryItemUi>> = combine(
        raceRepository.observeAllRaces(),
        muleRepository.sourceSummaries,
        muleRepository.raceLabelLastTouchedAtMillis,
        muleRepository.serverSyncMaxAgeDays,
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
                // Two flows per race — its own current Bibs segment (to know if Bibs has real
                // activity, needed alongside the race's own Time-mode field to tell "active"
                // apart from "merely selected", same distinction RaceRepository.deleteRace's
                // own backstop makes) and its own last-activity timestamp (for staleness — a
                // local race's own real history, not any Mule-inbox bookkeeping; see
                // isSkippedAsStale's own doc).
                combine(
                    races.map { race ->
                        combine(
                            bibsModeRepository.observeCurrentSegmentEntries(race.id),
                            raceRepository.observeLastActivityAtMillis(race.id),
                        ) { bibsEntries, lastActivityAtMillis ->
                            HistoryItemUi.LocalRace(
                                id = race.id,
                                label = race.label,
                                createdByDeviceName = race.createdByDeviceName,
                                isActive = isRaceActive(race.timeModeStartedAtMillis, bibsEntries.hasRealEntries(), race.cpModeStartedAtMillis),
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
                RaceHistoryViewModel(container.raceRepository, container.muleRepository, container.bibsModeRepository)
            }
        }
    }
}
