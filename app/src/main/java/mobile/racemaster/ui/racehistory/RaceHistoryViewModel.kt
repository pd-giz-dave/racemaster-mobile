package mobile.racemaster.ui.racehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.mule.StoredProgress
import mobile.racemaster.data.mule.isRaceStale
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.activeModeLabels
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.settings.SettingsRepository
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
        // Total rows this race has ever written (RaceRepository.observeEntryCount) — shown as
        // "N entries from <device>" rather than a bare "From <device>".
        val entryCount: Int = 0,
        // This race's own most recent confirmed-synced moment, across every mode
        // (RaceRepository.observeLastSyncedAtMillis) — mirrors what Race History's own detail
        // screen already shows, one level up on the list row itself.
        val lastSyncedAtMillis: Long? = null,
        // Whether this race is the device's own current SettingsRepository.activeRaceId — a
        // race can be [isActive] (still has an un-Reset started mode) without being this any
        // more, e.g. after Setup Race created a fresh one while an older, still-un-Reset race sat
        // un-touched (see RaceRepository.switchActiveRace's own doc). That's exactly the "moved
        // on without resetting the old one, still runners on course there" case the "Resume"
        // action (see resumeRace below) exists to recover — offered only for a race that's
        // [isActive] but NOT this one, since resuming the already-current race is just what
        // pressing Start does. Defaults false so existing test call sites that construct this
        // directly don't need updating.
        val isCurrentActiveRace: Boolean = false,
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
    // Race-wide progress/bib-allocation data received for [raceId] (over BLE from the racemaster
    // web app, or fetched directly over HTTP) — see ProgressRepository's own doc. Independent of
    // whether a LocalRace entry for the same raceId still exists (ProgressEntity is deliberately
    // not foreign-keyed against RaceEntity — see its own doc), so this can outlive a deleted
    // race, or exist with no matching LocalRace entry at all. Always deletable (RaceHistoryScreen
    // offers no active-race-style guard for it, unlike LocalRace) — it's a received snapshot, not
    // this device's own live recording.
    data class ProgressFile(
        val raceId: Long,
        val raceLabel: String,
        val raceName: String,
        val generatedAt: String,
        val entryCount: Int,
    ) : HistoryItemUi
}

// Thin, directly-testable name for this screen's own display badge — see isRaceStale's own
// doc for the actual rule, shared with MuleRepository.pushToServer and PeripheralSyncService's
// relay-manifest serving so all three agree on exactly the same cutoff.
internal fun isSkippedAsStale(lastTouchedAtMillis: Long?, maxAgeDays: Int): Boolean =
    isRaceStale(lastTouchedAtMillis, maxAgeDays)

// The bulk-delete counterpart to this screen's own per-item delete gating: a LocalRace is only
// swept up while active (RaceRepository.deleteRace's own isRaceCurrentlyActive backstop would
// silently no-op it anyway, same as single-item delete already defers to — see deleteAllStale's
// own doc for why bulk delete doesn't route an active race through force-reset the way the
// single-item delete button does); a MuleSource has no such guard, same as its own single-item
// delete.
internal fun HistoryItemUi.isStaleAndDeletable(): Boolean = when (this) {
    is HistoryItemUi.LocalRace -> serverSyncSkippedAsStale && !isActive
    is HistoryItemUi.MuleSource -> serverSyncSkippedAsStale
    // Not swept up by "Delete stale" — a progress file has no per-race activity timestamp of
    // its own to judge staleness by (it's a received snapshot, not something this device keeps
    // touching), and it's cheap enough (one small Room row) that there's no real clutter cost
    // to leaving that decision to its own individual delete button instead.
    is HistoryItemUi.ProgressFile -> false
}

// What RaceHistoryScreen's "Delete stale" confirmation dialog shows — split by kind since the
// two have genuinely different consequences (a race's history is gone for good; a Mule source
// is just a local copy, safely re-pullable). Pulled out as a pure function, like
// isSkippedAsStale above, so it's directly testable without the ViewModel's full Flow graph.
internal data class StaleDeletionSummary(val localRaceCount: Int, val muleSourceCount: Int) {
    val total: Int get() = localRaceCount + muleSourceCount
}

internal fun staleDeletionSummary(items: List<HistoryItemUi>): StaleDeletionSummary {
    val deletable = items.filter { it.isStaleAndDeletable() }
    return StaleDeletionSummary(
        localRaceCount = deletable.count { it is HistoryItemUi.LocalRace },
        muleSourceCount = deletable.count { it is HistoryItemUi.MuleSource },
    )
}

private data class HistorySources(
    val races: List<RaceEntity>,
    val sourceSummaries: List<PulledSourceSummary>,
    val lastTouchedAtMillis: Map<String, Long>,
    val maxAgeDays: Int,
    val progressFiles: List<StoredProgress>,
    val activeRaceId: Long?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RaceHistoryViewModel(
    private val raceRepository: RaceRepository,
    private val muleRepository: MuleRepository,
    private val progressRepository: ProgressRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val historyItems: StateFlow<List<HistoryItemUi>> = combine(
        raceRepository.observeAllRaces(),
        muleRepository.sourceSummaries,
        muleRepository.raceLabelLastTouchedAtMillis,
        muleRepository.raceStaleAfterDays,
        // Paired rather than added as the combine's own 6th argument — kotlinx coroutines'
        // typed combine() overloads only go up to 5, same reason BibsModeViewModel/
        // CpModeViewModel's own RaceContext combines already pair theirs.
        combine(progressRepository.observeStored(), settingsRepository.activeRaceId) { progressFiles, activeRaceId ->
            progressFiles to activeRaceId
        },
    ) { races, sourceSummaries, lastTouchedAtMillis, maxAgeDays, (progressFiles, activeRaceId) ->
        HistorySources(races, sourceSummaries, lastTouchedAtMillis, maxAgeDays, progressFiles, activeRaceId)
    }
        .flatMapLatest { (races, sourceSummaries, lastTouchedAtMillis, maxAgeDays, progressFiles, activeRaceId) ->
            val muleItems = sourceSummaries.map {
                HistoryItemUi.MuleSource(
                    raceLabel = it.sourceRaceLabel,
                    sourceDeviceId = it.sourceDeviceId,
                    deviceName = it.deviceName,
                    serverSyncSkippedAsStale = isSkippedAsStale(lastTouchedAtMillis[it.sourceRaceLabel], maxAgeDays),
                )
            }
            val progressItems = progressFiles.map {
                HistoryItemUi.ProgressFile(
                    raceId = it.raceId,
                    raceLabel = it.raceLabel,
                    raceName = it.raceName,
                    generatedAt = it.generatedAt,
                    entryCount = it.entries.size,
                )
            }
            if (races.isEmpty()) {
                flowOf(muleItems + progressItems)
            } else {
                // One flow per race — its own last-activity timestamp (for staleness — a local
                // race's own real history, not any Mule-inbox bookkeeping; see
                // isSkippedAsStale's own doc). isActive itself is a plain read off the race's
                // own Time/Bibs/CP started-at fields (see isRaceActive), no separate query
                // needed for it.
                combine(
                    races.map { race ->
                        combine(
                            raceRepository.observeLastActivityAtMillis(race.id),
                            raceRepository.observeEntryCount(race.id),
                            raceRepository.observeLastSyncedAtMillis(race.id),
                        ) { lastActivityAtMillis, entryCount, lastSyncedAtMillis ->
                            HistoryItemUi.LocalRace(
                                id = race.id,
                                label = race.label,
                                createdByDeviceName = race.createdByDeviceName,
                                isActive = isRaceActive(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis, race.cpModeStartedAtMillis),
                                activeModeLabels = activeModeLabels(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis, race.cpModeStartedAtMillis),
                                serverSyncSkippedAsStale = isSkippedAsStale(lastActivityAtMillis, maxAgeDays),
                                entryCount = entryCount,
                                lastSyncedAtMillis = lastSyncedAtMillis,
                                isCurrentActiveRace = race.id == activeRaceId,
                            )
                        }
                    },
                ) { localRaces -> localRaces.toList() + muleItems + progressItems }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Switches this device's active race back to [raceId] — see
    // RaceRepository.switchActiveRace's own doc — for the "go back to a previously stopped race
    // and restart it" recovery path (TODO.md's phase 1: an accidental Stop with runners still
    // out on course). Whichever mode this race was recording in then resumes exactly where it
    // left off the next time that mode's own Start button is pressed (see each *ModeViewModel's
    // startXMode — the same in-place resume check an already-current race's Start already goes
    // through). Offered on Race History's LocalRace rows only when isActive && !isCurrentActiveRace
    // (see that field's own doc).
    fun resumeRace(raceId: Long) {
        viewModelScope.launch { raceRepository.switchActiveRace(raceId) }
    }

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

    // See ProgressRepository.delete's own doc — always deletable, no active-race guard, same
    // reasoning as deleteMuleSource above.
    fun deleteProgress(raceId: Long) {
        viewModelScope.launch { progressRepository.delete(raceId) }
    }

    // Bulk counterpart to deleteRace/deleteMuleSource above, for RaceHistoryScreen's own
    // "Delete stale" action. Reads historyItems.value fresh at call time — not whatever
    // snapshot the confirmation dialog was opened against — so an item that changed state
    // (e.g. a race going active) while the dialog was up is decided correctly. An active
    // LocalRace is simply skipped rather than routed through forceResetActiveModes the way
    // the single-item delete button does — a bulk sweep isn't the moment to force-reset
    // someone's still-running race out from under them.
    fun deleteAllStale() {
        viewModelScope.launch {
            for (item in historyItems.value.filter { it.isStaleAndDeletable() }) {
                when (item) {
                    is HistoryItemUi.LocalRace -> raceRepository.deleteRace(item.id)
                    is HistoryItemUi.MuleSource -> muleRepository.deleteSource(item.raceLabel, item.sourceDeviceId)
                    // Never actually reached — isStaleAndDeletable() always returns false for
                    // one of these, so the filter above already excludes it; listed only so this
                    // stays an exhaustive `when` rather than needing an `else`.
                    is HistoryItemUi.ProgressFile -> Unit
                }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RaceHistoryViewModel(
                    container.raceRepository,
                    container.muleRepository,
                    container.progressRepository,
                    container.settingsRepository,
                )
            }
        }
    }
}
