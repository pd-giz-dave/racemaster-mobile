package mobile.racemaster.data.repository

import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.LineSyncDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.LineSyncEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class RaceRepository(
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    private val lineSyncDao: LineSyncDao,
    private val settingsRepository: SettingsRepository,
    private val bibsModeRepository: BibsModeRepository,
) {
    // bibsRangeStart/bibsRangeCount are collected on the race details form for Time Mode too
    // now (form parity with Bibs), even though Time itself never reads bibsRangeStart for
    // anything — it's inert data there, kept only so both modes' forms/feedback stay identical.
    suspend fun startNewRace(
        name: String,
        course: String,
        location: String = "Finish",
        createdAtMillis: Long = System.currentTimeMillis(),
        deviceRole: String? = null,
        serverUrl: String? = null,
        bibsRangeStart: Int? = null,
        bibsRangeCount: Int? = null,
    ): Long =
        raceDao.insert(
            RaceEntity(
                name = name,
                course = course,
                location = location,
                label = buildRaceLabel(name, course, createdAtMillis),
                createdAtMillis = createdAtMillis,
                deviceRole = deviceRole,
                serverUrl = serverUrl,
                bibsRangeStart = bibsRangeStart,
                bibsRangeCount = bibsRangeCount,
                createdByDeviceName = settingsRepository.getOrCreateDeviceName(),
            ),
        )

    // The date portion of the label is rebuilt from the race's original createdAtMillis, not
    // the edit time — the date is always auto-derived and fixed once the race is created.
    // bibsRangeStart/bibsRangeCount are only ever actually *changed* by the caller while the
    // race is still fresh (see RaceDetailsScreen) — otherwise it just writes back what was
    // already there. serverUrl is untouched here — it's not on this screen (see
    // RaceDao.updateDetails). No Mule-inbox retagging needed on a rename (there used to be one
    // here) — MuleRepository.pushToServer now reads this race's own current label fresh from
    // RaceEntity on every attempt rather than tracking a separately-labeled mirrored copy, so a
    // rename just takes effect on the very next push with nothing else to keep in sync. location
    // is deliberately NOT part of the label (see RaceEntity.location's own doc) — a change here
    // just takes effect the same way, on the next record this device pushes.
    suspend fun updateRaceDetails(
        raceId: Long,
        name: String,
        course: String,
        location: String,
        bibsRangeStart: Int?,
        bibsRangeCount: Int?,
    ) {
        val race = raceDao.getById(raceId) ?: return
        val label = buildRaceLabel(name, course, race.createdAtMillis)
        raceDao.updateDetails(raceId, name, course, location, label, bibsRangeStart, bibsRangeCount)
    }

    fun observeRace(id: Long): Flow<RaceEntity?> = raceDao.observeById(id)

    suspend fun getRace(id: Long): RaceEntity? = raceDao.getById(id)

    fun observeAllRaces(): Flow<List<RaceEntity>> = raceDao.observeAll()

    // Permanently erases a race and its full history — RaceDao.deleteById's own FK cascade
    // takes history_lines with it; line_syncs has no such cascade so is cleared explicitly
    // here too. Irreversible, gated behind RaceHistoryScreen's own confirmation dialog before
    // this is ever called. Refuses to delete the race only while it's active per
    // isRaceCurrentlyActive (the one centralized definition — see its own doc) — a race
    // that's merely selected/defined but never started, or one that's been stopped *and*
    // Reset, is fair game, same as changing the device name is. RaceHistoryScreen already
    // disables the delete action accordingly, this is the backstop that holds regardless of
    // how deleteRace ends up called.
    //
    // No Mule-inbox cleanup needed here (there used to be one) — this device's own data is
    // never mirrored into pulled_records at all, so there's nothing left behind to purge; see
    // PulledRecordEntity's own doc for why that mirroring was removed.
    //
    // Also clears settingsRepository.activeRaceId if it still points at this race (see
    // SettingsRepository.clearActiveRaceId's own doc) — a race is deletable here precisely
    // when it's stopped-and-Reset (or never started), which is exactly the state a race can
    // sit in while still being the operator's own selected one. Left uncleared, Time/Bibs
    // Mode's own activeRaceId-driven state keeps rendering a "race" with blank details (label
    // defaults to "") that still looks active enough to show Start/Log — and the moment an
    // action tries to write to it (e.g. TimeModeRepository.startStopwatch's own
    // requireNotNull(raceDao.getById(raceId))), it crashes (confirmed in the field).
    suspend fun deleteRace(raceId: Long) {
        if (isRaceCurrentlyActive(raceId, this, bibsModeRepository)) return
        if (raceDao.getById(raceId) == null) return
        lineSyncDao.deleteForRace(raceId)
        raceDao.deleteById(raceId)
        if (settingsRepository.activeRaceId.first() == raceId) {
            settingsRepository.clearActiveRaceId()
        }
    }

    // Resolves a race label back to this device's own local race — see
    // MuleRepository.pushToServer's own self-push path.
    suspend fun getRaceByLabel(label: String): RaceEntity? = raceDao.getByLabel(label)

    // Cross-mode facade: the only two places that need to see a race's Time AND Bibs rows
    // together, rather than through TimeModeRepository/BibsModeRepository's per-mode views.
    //
    // Full, permanent history across every segment and BOTH modes — Race History's one true
    // chronology (see RaceHistoryDetailViewModel), replacing what used to be two separately-
    // sorted "Bib entries"/"Time splits" lists.
    fun observeHistory(raceId: Long): Flow<List<HistoryLineEntity>> = historyLineDao.observeAllForRace(raceId)

    // Delta-sync snapshot — every row past the requester's already-known line number, spanning
    // every segment of BOTH modes this device has recorded. Deliberately not scoped to
    // whichever AppMode screen happens to be showing — a mixed-mode race must sync everything
    // it holds regardless of which mode the operator currently has open. Two callers: a
    // genuine BLE pull request from another Mule (PeripheralSyncService.streamRecords), and
    // this device's own self-push (MuleRepository.pushToServer) building its payload fresh on
    // every attempt instead of relying on a locally-staged copy.
    suspend fun getHistorySinceLineNumber(raceId: Long, sinceLineNumber: Long): List<HistoryLineEntity> =
        historyLineDao.getSinceLineNumber(raceId, sinceLineNumber)

    // How recently this race's own history was actually edited — used by
    // MuleRepository.pushToServer to decide whether a race with no recent activity is still
    // worth checking against the server (the same staleness rule a Mule-pulled source already
    // gets, just sourced from this device's own real data instead of a relay's own
    // bookkeeping), and by Race History to show a local race as "too old for server sync".
    fun observeLastActivityAtMillis(raceId: Long): Flow<Long?> = historyLineDao.observeLastActivityAtMillis(raceId)

    // Device-wide counterparts to TimeModeRepository/BibsModeRepository's own per-race
    // observeUnsyncedCount/observeLastSyncedAtMillis — every row this device has ever recorded,
    // across every race, not just whichever one happens to be active. Feeds Mule Mode's own
    // aggregate status line (MuleRepository.unsyncedCount/lastSyncedAtMillis), which needs to
    // reflect this device's own outstanding self-pushes alongside whatever it's separately
    // holding for other devices — now that self-push builds its payload fresh from
    // HistoryLineEntity each tick rather than staging a copy into pulled_records, that table
    // alone can no longer answer "how much of MY OWN data is still unsynced".
    val unsyncedHistoryCountAcrossAllRaces: Flow<Int> = historyLineDao.observeUnsyncedCountAcrossAllRaces()
    val lastHistorySyncedAtMillisAcrossAllRaces: Flow<Long?> = historyLineDao.observeLastSyncedAtMillisAcrossAllRaces()

    // Mode-agnostic: a batch of confirmed recordUuids is inherently already scoped to whatever
    // was actually sent, regardless of mode — see PeripheralSyncService.markSynced (a BLE ack
    // from a genuinely different Mule) and MuleRepository.pushToServer (this device's own
    // self-push, confirmed once the server's own status check reflects it — not merely handed
    // off locally, unlike the old self-mirrored-copy design).
    suspend fun markHistorySyncedByUuid(recordUuids: List<String>, syncedAtMillis: Long = System.currentTimeMillis()) {
        if (recordUuids.isEmpty()) return
        historyLineDao.markSynced(recordUuids, syncedAtMillis)
    }

    suspend fun getHistoryLineNumbersForUuids(recordUuids: List<String>): List<Long> =
        if (recordUuids.isEmpty()) emptyList() else historyLineDao.getLineNumbersForUuids(recordUuids)

    // Per-line "synced to" feedback for a local race — see LineSyncEntity's doc for why this
    // is deliberately simple bookkeeping, not a gossip/multi-hop relay.
    fun observeLineSyncs(raceId: Long): Flow<List<LineSyncEntity>> = lineSyncDao.observeForRace(raceId)

    suspend fun recordLineSyncs(
        raceId: Long,
        lineNumbers: List<Long>,
        targetId: String,
        targetName: String,
        syncedAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (lineNumbers.isEmpty()) return
        lineSyncDao.insertAll(
            lineNumbers.map {
                LineSyncEntity(raceId = raceId, lineNumber = it, targetId = targetId, targetName = targetName, syncedAtMillis = syncedAtMillis)
            },
        )
    }
}
