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
    // [serverSyncSkippedAsStale] mirrors MuleRepository.pushToServer's own age cutoff (see
    // MuleRepository.raceLabelLastTouchedAtMillis's doc) — true once this race's own
    // self-pulled Mule rows haven't been touched recently enough to still be checked against
    // the server, same as any other race label. false (never stale) for a race Mule has never
    // pulled from itself at all, which is a distinct, unrelated state (e.g. auto-sync/self-push
    // simply hasn't run yet), not "too old".
    data class LocalRace(
        val id: Long,
        val label: String,
        val createdByDeviceName: String,
        val isActive: Boolean,
        val serverSyncSkippedAsStale: Boolean,
    ) : HistoryItemUi
    // A race pulled via Mule from a genuinely different physical device — self-pulled rows are
    // excluded upstream (see PulledRecordDao), so this is never just an echo of a LocalRace
    // entry above. [sourceDeviceId] (not just [raceLabel]) identifies this entry, since more
    // than one physical device can share a race label — see PulledSourceSummary's own doc.
    // [serverSyncSkippedAsStale] — see LocalRace's own doc above for the exact rule.
    data class MuleSource(
        val raceLabel: String,
        val sourceDeviceId: String,
        val deviceName: String,
        val serverSyncSkippedAsStale: Boolean,
    ) : HistoryItemUi
}

// See MuleRepository.raceLabelLastTouchedAtMillis's own doc — a race label absent from
// [lastTouchedAtMillis] has never been through Mule's inbox at all (unrelated to staleness),
// so only a label that IS present but predates the cutoff counts as skipped.
internal fun isSkippedAsStale(raceLabel: String, lastTouchedAtMillis: Map<String, Long>, maxAgeDays: Int): Boolean {
    val lastTouched = lastTouchedAtMillis[raceLabel] ?: return false
    val cutoffMillis = System.currentTimeMillis() - maxAgeDays.days.inWholeMilliseconds
    return lastTouched < cutoffMillis
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
                    serverSyncSkippedAsStale = isSkippedAsStale(it.sourceRaceLabel, lastTouchedAtMillis, maxAgeDays),
                )
            }
            if (races.isEmpty()) {
                flowOf(muleItems)
            } else {
                // One flow per race (its own current Bibs segment, to know if Bibs has real
                // activity) — needed alongside the race's own Time-mode field to tell "active"
                // apart from "merely selected", same distinction RaceRepository.deleteRace's
                // own backstop makes.
                combine(
                    races.map { race ->
                        bibsModeRepository.observeCurrentSegmentEntries(race.id).map { bibsEntries ->
                            HistoryItemUi.LocalRace(
                                id = race.id,
                                label = race.label,
                                createdByDeviceName = race.createdByDeviceName,
                                isActive = isRaceActive(race.timeModeStartedAtMillis, bibsEntries.hasRealEntries()),
                                serverSyncSkippedAsStale = isSkippedAsStale(race.label, lastTouchedAtMillis, maxAgeDays),
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
