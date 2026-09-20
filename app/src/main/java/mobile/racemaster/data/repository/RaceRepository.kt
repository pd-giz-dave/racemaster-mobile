package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.LineSyncDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.LineSyncEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class RaceRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    private val lineSyncDao: LineSyncDao,
    private val settingsRepository: SettingsRepository,
) {
    // The only place a race gets created from a manually-typed name now (Setup Race's offline/
    // manual branch; see adoptRaceLabel below for the online-pick path). course is always blank
    // (see buildRaceLabel — the segment is simply omitted), matching the dropped "course"
    // concept from phase 1.
    suspend fun startNewRace(
        name: String,
        location: String = "Finish",
        createdAtMillis: Long = System.currentTimeMillis(),
        deviceRole: String? = null,
        serverUrl: String? = null,
    ): Long =
        raceDao.insert(
            RaceEntity(
                name = name,
                location = location,
                label = buildRaceLabel(name, course = "", createdAtMillis),
                createdAtMillis = createdAtMillis,
                deviceRole = deviceRole,
                serverUrl = serverUrl,
                createdByDeviceName = settingsRepository.getOrCreateDeviceName(),
            ),
        )

    // Setup Race's online branch (see SetupRaceViewModel.pickAvailableRace): adopts an existing
    // server-side race label exactly, rather than reconstructing one from name+today's date the
    // way startNewRace does — the picked race may have been registered on an earlier date, and
    // this device must deposit its device file in that exact existing folder (TODO.md's phase 2:
    // "the device becomes that race and deposits its device file in the selected folder"), not a
    // fresh same-named one dated today. [raceLabel]'s own name portion (see RaceLabels.kt's
    // raceNameFromLabel) already carries any Seniors/Juniors suffix as plain text, same as a
    // manually-typed name would.
    suspend fun adoptRaceLabel(raceLabel: String, location: String): Long =
        raceDao.insert(
            RaceEntity(
                name = raceNameFromLabel(raceLabel),
                location = location,
                label = raceLabel,
                createdAtMillis = System.currentTimeMillis(),
                createdByDeviceName = settingsRepository.getOrCreateDeviceName(),
            ),
        )

    // Setup Race's own "this device is in the field" signal (see MuleRepository.announceRaceSetup)
    // — a single HistoryMode.ANY/HistoryAction.SETUP marker, written right after the race is
    // created/adopted, before any mode has been chosen. Follows the exact same
    // read-nextLineNumber/insert/increment shape TimeModeRepository.startStopwatch's own
    // MODE_START row uses, so this row consumes a real, permanent, never-reused lineNumber like
    // every other row — no synthetic/reserved-only numbering scheme needed. mode = ANY (not one
    // of TIME/BIBS/CP) is what keeps it out of every mode's own live current-segment queries for
    // free (those are SQL-filtered on `mode = :thisMode`, see HistoryLineDao.observeCurrentSegment)
    // without needing an action-based exclusion list the way MODE_START needs one within its own
    // shared family. It's still a completely real, synced, permanent row — it flows through
    // RaceRepository.getHistorySinceLineNumber/observeLastActivityAtMillis (both raceId-scoped,
    // not mode-scoped) exactly like any other, which is what lets MuleRepository.pushToServer and
    // PeripheralSyncService's own pull-serving pick it up with no transport-specific code of their
    // own — see HistoryAction.SETUP's own doc.
    suspend fun recordSetupMarker(raceId: Long, timestampMillis: Long = System.currentTimeMillis()) {
        val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
        historyLineDao.insert(
            HistoryLineEntity(
                raceId = raceId,
                mode = HistoryMode.ANY,
                action = HistoryAction.SETUP,
                bibNumber = null,
                splitNumber = null,
                lineNumber = race.nextLineNumber,
                note = race.location,
                timestampMillis = timestampMillis,
            ),
        )
        raceDao.incrementLineNumber(raceId)
    }

    // The date portion of the label is rebuilt from the race's original createdAtMillis, not
    // the edit time — the date is always auto-derived and fixed once the race is created.
    // name/location genuinely can change here now — RaceDetailsScreen only locks them once the
    // race has actually started a mode (see its own identityFieldsEnabled doc); before that, no
    // history can possibly exist for this race yet (every mode's own startXxxMode is what both
    // sets its *ModeStartedAtMillis and inserts its first history row, in the same transaction),
    // so nothing anywhere could already be referencing the old label. `course` is always blank
    // (the concept was dropped in phase 1), so the label is always rebuilt with one. serverUrl is
    // untouched here — it's not on this screen (see RaceDao.updateDetails). No Mule-inbox
    // retagging needed on a rename (there used to be one here) — MuleRepository.pushToServer now
    // reads this race's own current label fresh from RaceEntity on every attempt rather than
    // tracking a separately-labeled mirrored copy, so a rename just takes effect on the very next
    // push with nothing else to keep in sync. location is deliberately NOT part of the label (see
    // RaceEntity.location's own doc) — a change here just takes effect the same way, on the next
    // record this device pushes.
    suspend fun updateRaceDetails(raceId: Long, name: String, location: String) {
        val race = raceDao.getById(raceId) ?: return
        val label = buildRaceLabel(name, course = "", race.createdAtMillis)
        raceDao.updateDetails(raceId, name, location, label)
    }

    // Phase 3's adoption trigger: a device that broadcast a temporary, manually-typed race name
    // (because it had no server reachable at Setup Race time — see SetupRaceViewModel's offline
    // branch) is being told, via a targeted BLE progress delivery, which real server-side race it
    // actually belongs to (see PeripheralSyncService.handleProgressPayload for where this is
    // called). Rewrites the existing row in place — same [raceId], same
    // SettingsRepository.activeRaceId pointer, same history — to [raceLabel]'s own identity
    // instead of creating a new race the way [adoptRaceLabel] (Setup Race's online-pick path)
    // does: unlike that path, this one must never lose already-recorded history, and
    // [HistoryLineEntity.raceId] is a stable Room FK, never derived from name/label, so nothing
    // downstream needs migrating — every screen already observes this race reactively off
    // [raceId] via Room Flows, so the new identity reaches all of them on their very next
    // emission. [raceLabel]'s own name portion (see [raceNameFromLabel] — the same helper
    // [adoptRaceLabel] already uses) becomes this race's new name; location is left exactly as
    // it was (this device's own physical station, unrelated to which race it now records). A
    // no-op if [raceId] no longer exists (defensive — the race this delivery was addressed to
    // could in principle have been deleted between the delivery being cached and this running).
    suspend fun adoptRaceIdentity(raceId: Long, raceLabel: String) {
        val race = raceDao.getById(raceId) ?: return
        raceDao.updateDetails(raceId, raceNameFromLabel(raceLabel), race.location, raceLabel)
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

    // Promotes [newRaceId] to the device's own active race, first deleting whatever race is
    // being switched away from if it's pure clutter — a row Setup Race created (or the operator
    // navigated away from) that was never actually started in any mode, so it has, by
    // construction, zero real history worth keeping around to clog up Race History. Judged by
    // whether this race has ever recorded a single history line (observeLastActivityAtMillis
    // returning null) — since the "course" concept is gone (phase 1), there's no field left on
    // the entity that could tell "an abandoned placeholder" apart from "a real race with a full
    // history" any other way. A race that WAS started but
    // is merely Stopped-not-Reset (see isRaceActive) still has real history, so it's correctly
    // never swept up here — that's exactly the race Race History's own "Resume" action exists to
    // switch back to later. Every setActiveRaceId call site should route through here rather
    // than calling it directly, so this cleanup applies uniformly regardless of why the switch
    // is happening.
    suspend fun switchActiveRace(newRaceId: Long) {
        val oldRaceId = settingsRepository.activeRaceId.first()
        if (oldRaceId != null && oldRaceId != newRaceId) {
            val hasHistory = observeLastActivityAtMillis(oldRaceId).first() != null
            if (!hasHistory) deleteRace(oldRaceId)
        }
        settingsRepository.setActiveRaceId(newRaceId)
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
    // Must do exactly what an in-context Reset does for each mode that's actually active — a
    // RESET marker row in that mode's own history (consuming a permanent line number), then the
    // same DAO reset query clearing its display counter/started/stopped columns — not just the
    // bare column clear this used to do. Force-resetting a race is otherwise indistinguishable,
    // from Race History's later read of it, from that race simply never having been reset at
    // all: no boundary marker ever separated "the unfinished segment" this was meant to clear
    // from whatever came after, which is exactly the "was this genuinely redone, or is this
    // stale leftover data" ambiguity a real Reset's own marker exists to resolve (see
    // EntryLogModeEngine.reset's own doc). Scoped to only the modes actually started (unlike
    // before, which reset all three unconditionally as a harmless no-op) specifically so this
    // doesn't insert a spurious Reset line into a mode's history that was never even used for
    // this race. Deliberately does not delete the race itself, matching deleteRace's own
    // two-step design: this only clears whatever's blocking isRaceActive, leaving the operator
    // to explicitly delete afterward via the normal confirmation dialog.
    suspend fun forceResetActiveModes(raceId: Long) {
        val race = raceDao.getById(raceId) ?: return
        if (race.timeModeStartedAtMillis != null) insertResetMarkerAndReset(raceId, HistoryMode.TIME)
        if (race.bibsModeStartedAtMillis != null) insertResetMarkerAndReset(raceId, HistoryMode.BIBS)
        if (race.cpModeStartedAtMillis != null) insertResetMarkerAndReset(raceId, HistoryMode.CP)
    }

    private suspend fun insertResetMarkerAndReset(raceId: Long, mode: HistoryMode, resetAtMillis: Long = System.currentTimeMillis()) {
        val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
        historyLineDao.insert(
            HistoryLineEntity(
                raceId = raceId,
                mode = mode,
                action = HistoryAction.RESET,
                bibNumber = null,
                splitNumber = null,
                lineNumber = race.nextLineNumber,
                note = null,
                timestampMillis = resetAtMillis,
            ),
        )
        raceDao.incrementLineNumber(raceId)
        when (mode) {
            HistoryMode.TIME -> raceDao.resetTimeMode(raceId)
            HistoryMode.BIBS -> raceDao.resetBibsMode(raceId)
            HistoryMode.CP -> raceDao.resetCpMode(raceId)
            // Never actually reachable — forceResetActiveModes (this function's only caller)
            // only ever passes TIME/BIBS/CP, gated on that mode's own *ModeStartedAtMillis.
            // HistoryMode.ANY never starts (it's Setup Race's own one-off marker, not a
            // recording mode an operator can reset) — see HistoryMode.ANY's own doc.
            HistoryMode.ANY -> error("HistoryMode.ANY is never an active mode to reset")
        }
    }

    // The "This Race"/Relocate screen's own write path (RaceDetailsViewModel.save, only once the
    // race is active — before that, a location edit is still just a plain field overwrite via
    // updateRaceDetails, nothing recorded yet to segment). Writes one HistoryAction.LOCATION
    // marker PER currently-active mode — mirrors forceResetActiveModes' own per-mode-conditional
    // loop exactly, and for the same reason: mode-scoped, never HistoryMode.ANY, since
    // observeCurrentSegment's own query filters `mode = :mode` and an ANY-scoped row would be
    // invisible to every mode's own live segment/duplicate-detection. Wrapped in one transaction
    // so a crash mid-loop (relocating a race active in more than one mode) can't leave some
    // modes' markers written and others not.
    suspend fun relocateActiveModes(raceId: Long, newLocation: String) {
        db.withTransaction {
            val race = raceDao.getById(raceId) ?: return@withTransaction
            if (race.timeModeStartedAtMillis != null) insertLocationMarkerAndReset(raceId, HistoryMode.TIME, newLocation)
            if (race.bibsModeStartedAtMillis != null) insertLocationMarkerAndReset(raceId, HistoryMode.BIBS, newLocation)
            if (race.cpModeStartedAtMillis != null) insertLocationMarkerAndReset(raceId, HistoryMode.CP, newLocation)
            raceDao.updateLocationOnly(raceId, newLocation)
        }
    }

    // Mirrors insertResetMarkerAndReset's own 3-step shape (insert marker consuming a real
    // lineNumber, increment, reset that mode's own counter) with two differences: the marker
    // carries the new location (in `note`) plus what to restore on undo (priorSplitCounter/
    // previousLocation — see HistoryLineEntity's own doc for why these need their own dedicated
    // columns rather than reusing splitNumber), and the "reset" is the narrower
    // setXModeNextSplit(raceId, 1) rather than resetXMode, which would also wrongly clear
    // started/stoppedAtMillis mid-recording (see RaceDao's own doc on those queries).
    private suspend fun insertLocationMarkerAndReset(raceId: Long, mode: HistoryMode, newLocation: String, relocatedAtMillis: Long = System.currentTimeMillis()) {
        val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
        val priorSplitCounter = when (mode) {
            HistoryMode.TIME -> race.timeModeNextSplit
            HistoryMode.BIBS -> race.bibsModeNextSplit
            HistoryMode.CP -> race.cpModeNextSplit
            HistoryMode.ANY -> error("HistoryMode.ANY is never an active mode to relocate")
        }
        historyLineDao.insert(
            HistoryLineEntity(
                raceId = raceId,
                mode = mode,
                action = HistoryAction.LOCATION,
                bibNumber = null,
                splitNumber = null,
                lineNumber = race.nextLineNumber,
                note = newLocation,
                timestampMillis = relocatedAtMillis,
                priorSplitCounter = priorSplitCounter,
                previousLocation = race.location,
            ),
        )
        raceDao.incrementLineNumber(raceId)
        when (mode) {
            HistoryMode.TIME -> raceDao.setTimeModeNextSplit(raceId, 1)
            HistoryMode.BIBS -> raceDao.setBibsModeNextSplit(raceId, 1)
            HistoryMode.CP -> raceDao.setCpModeNextSplit(raceId, 1)
            HistoryMode.ANY -> error("HistoryMode.ANY is never an active mode to relocate")
        }
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
    // Time/Bibs/CP are mutually exclusive for a given race's current segment — only one of the
    // three may be started at once, the same interlock this originally only enforced between
    // Bibs and CP. Checks every other mode generically (via isModeStarted) rather than a fixed
    // pair of if-checks, so a 4th recording mode would only ever need adding to AppMode.entries
    // for this to already cover it.
    suspend fun blockedModeSwitchReason(raceId: Long, targetMode: AppMode): String? {
        val race = raceDao.getById(raceId) ?: return null
        val conflicting = AppMode.entries.firstOrNull { it != targetMode && isModeStarted(it, race) }
            ?: return null
        return "${conflicting.displayName()} still has an active race — Stop and Reset it before " +
            "switching to ${targetMode.displayName()}."
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

    // Mode-agnostic: a batch of confirmed lineNumbers is inherently already scoped to whatever
    // was actually sent, regardless of mode — see PeripheralSyncService.markSynced (a BLE ack
    // from a genuinely different Mule) and MuleRepository.pushToServer (this device's own
    // self-push, confirmed once the server's own status check reflects it — not merely handed
    // off locally, unlike the old self-mirrored-copy design).
    suspend fun markHistorySyncedByLineNumber(raceId: Long, lineNumbers: List<Long>, syncedAtMillis: Long = System.currentTimeMillis()) {
        if (lineNumbers.isEmpty()) return
        historyLineDao.markSynced(raceId, lineNumbers, syncedAtMillis)
    }

    // See PeripheralSyncService.backfillSinkAck's own doc. Inclusive of sinceLineNumber itself.
    suspend fun unsyncedLineNumbersUpTo(raceId: Long, sinceLineNumber: Long): List<Long> =
        historyLineDao.getUnsyncedLineNumbersUpTo(raceId, sinceLineNumber)

    // Per-line "synced to" feedback for a local race — see LineSyncEntity's own doc for what
    // isSink actually means (the red/orange/green threshold), and for why targetId/targetName
    // still only ever names the immediate hop that told this device, even for a confirmation
    // that arrived via a downstream device's own relayed sinkConfirmedOrigins.
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
