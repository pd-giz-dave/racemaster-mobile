package mobile.racemaster.data.repository

import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.LineSyncDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.LineSyncEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class RaceRepository(
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    private val lineSyncDao: LineSyncDao,
    private val settingsRepository: SettingsRepository,
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
    // name/course/location genuinely can change here now — RaceDetailsScreen only locks them
    // once the race has actually started a mode (see its own identityFieldsEnabled doc); before
    // that, no history can possibly exist for this race yet (every mode's own startXxxMode is
    // what both sets its *ModeStartedAtMillis and inserts its first history row, in the same
    // transaction), so nothing anywhere could already be referencing the old label. Only
    // bibsRangeStart/bibsRangeCount are the sole fields a *started* race can still genuinely
    // change here. serverUrl is untouched here — it's not on this screen (see
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
        if (isRaceCurrentlyActive(raceId, this)) return
        if (raceDao.getById(raceId) == null) return
        lineSyncDao.deleteForRace(raceId)
        raceDao.deleteById(raceId)
        if (settingsRepository.activeRaceId.first() == raceId) {
            settingsRepository.clearActiveRaceId()
        }
    }

    // Lets an operator un-stick a race that RaceHistoryScreen's own caption says is still
    // "Active in X Mode", even when that mode's own screen no longer shows any sign of it.
    // settingsRepository.activeRaceId is a single, device-wide "currently selected race"
    // pointer, entirely independent of which race(s) still have an un-Reset startedAtMillis
    // sitting in the database (see isRaceActive's own doc) — once the operator has moved on to
    // a different race (a new one, or even just switched which existing one is current), an
    // older race's own stuck flag becomes unreachable via the normal per-mode Reset button,
    // since that button only ever acts on whichever race activeRaceId currently points to.
    // Confirmed in the field: a race showed "Active in Time Mode, can't be deleted" in Race
    // History while Time Mode's own screen showed no race at all — activeRaceId had since moved
    // to a different race, leaving the old one's timeModeStartedAtMillis with no in-context way
    // to reach it.
    //
    // Reuses the exact same DAO reset queries an in-context Reset already calls, unconditionally
    // for all three modes rather than checking which one is actually active first — resetting an
    // already-null field is a harmless no-op, the same idempotent-recheck-over-conditional
    // tradeoff already used elsewhere in this codebase (e.g. PeripheralSyncService.markSynced).
    // Deliberately does not delete the race itself, matching deleteRace's own two-step design:
    // this only clears whatever's blocking isRaceActive, leaving the operator to explicitly
    // delete afterward via the normal confirmation dialog.
    suspend fun forceResetActiveModes(raceId: Long) {
        raceDao.resetTimeMode(raceId)
        raceDao.resetBibsMode(raceId)
        raceDao.resetCpMode(raceId)
    }

    // Resolves a race label back to this device's own local race — see
    // MuleRepository.pushToServer's own self-push path.
    suspend fun getRaceByLabel(label: String): RaceEntity? = raceDao.getByLabel(label)

    // Bibs and CP are mutually exclusive for the same race — both are alternate ways of
    // logging the same physical station, so switching from one to the other while it still
    // holds live, un-reset activity would leave both writing independently into what's meant
    // to be one station's log. Requires the *other* of the two to be Stopped AND Reset first —
    // merely Stopped isn't enough, same "still counts as active" reasoning as [isRaceActive]
    // (bibsModeStartedAtMillis/cpModeStartedAtMillis only clear on Reset, not on Stop). Every
    // other switch (into or out of Time/Mule, or re-selecting the same mode) is always allowed.
    // Returns null when the switch is fine, or a message to show the operator when it isn't.
    // Called from ModePickerViewModel.selectModeForExistingRace, the one place a mode switch
    // for an already-active race actually happens.
    suspend fun blockedModeSwitchReason(raceId: Long, targetMode: AppMode): String? {
        if (targetMode != AppMode.BIBS && targetMode != AppMode.CP) return null
        val race = raceDao.getById(raceId) ?: return null
        if (targetMode == AppMode.BIBS && race.cpModeStartedAtMillis != null) {
            return "CP Mode still has an active race — Stop and Reset it before switching to Bibs Mode."
        }
        if (targetMode == AppMode.CP && race.bibsModeStartedAtMillis != null) {
            return "Bibs Mode still has an active race — Stop and Reset it before switching to CP Mode."
        }
        return null
    }

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

    // See PeripheralSyncService.backfillSinkAck's own doc. Inclusive of sinceLineNumber itself.
    suspend fun unsyncedRecordUuidsUpTo(raceId: Long, sinceLineNumber: Long): List<String> =
        historyLineDao.getUnsyncedRecordUuidsUpTo(raceId, sinceLineNumber)

    // Per-line "synced to" feedback for a local race — see LineSyncEntity's own doc for what
    // isSink actually means (the red/orange/green threshold), and for why targetId/targetName
    // still only ever names the immediate hop that told this device, even for a confirmation
    // that arrived via a downstream device's own relayed sinkConfirmedRecordUuids.
    fun observeLineSyncs(raceId: Long): Flow<List<LineSyncEntity>> = lineSyncDao.observeForRace(raceId)

    suspend fun recordLineSyncs(
        raceId: Long,
        lineNumbers: List<Long>,
        targetId: String,
        targetName: String,
        isSink: Boolean,
        syncedAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (lineNumbers.isEmpty()) return
        lineSyncDao.insertAll(
            lineNumbers.map {
                LineSyncEntity(raceId = raceId, lineNumber = it, targetId = targetId, targetName = targetName, syncedAtMillis = syncedAtMillis, isSink = isSink)
            },
        )
    }
}
