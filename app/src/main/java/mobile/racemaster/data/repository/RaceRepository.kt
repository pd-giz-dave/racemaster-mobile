package mobile.racemaster.data.repository

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
        // The offered course menu (see RaceEntity.courses' own doc) — empty only for the
        // pending, course-less row RaceDetailsScreen itself no longer creates without one
        // (courses is required there), kept optional here purely so resolveCourseRace's own
        // cloned-sibling call site reads naturally alongside every other cloned template field.
        courses: List<String> = emptyList(),
    ): Long =
        raceDao.insert(
            RaceEntity(
                name = name,
                course = course,
                courses = courses,
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
    // name/courses/location genuinely can change here now — RaceDetailsScreen only locks them
    // once the race has actually started a mode (see its own identityFieldsEnabled doc); before
    // that, no history can possibly exist for this race yet (every mode's own startXxxMode is
    // what both sets its *ModeStartedAtMillis and inserts its first history row, in the same
    // transaction), so nothing anywhere could already be referencing the old label. `course`
    // itself is deliberately NOT a parameter here any more — it's no longer something this
    // screen edits (see RaceEntity.course's own doc: it's chosen at Start time instead), so the
    // label is rebuilt from the race's own already-stored course, passed straight through
    // unchanged. Only bibsRangeStart/bibsRangeCount are the sole fields a *started* race can
    // still genuinely change here. serverUrl is untouched here — it's not on this screen (see
    // RaceDao.updateDetails). No Mule-inbox retagging needed on a rename (there used to be one
    // here) — MuleRepository.pushToServer now reads this race's own current label fresh from
    // RaceEntity on every attempt rather than tracking a separately-labeled mirrored copy, so a
    // rename just takes effect on the very next push with nothing else to keep in sync. location
    // is deliberately NOT part of the label (see RaceEntity.location's own doc) — a change here
    // just takes effect the same way, on the next record this device pushes.
    suspend fun updateRaceDetails(
        raceId: Long,
        name: String,
        courses: List<String>,
        location: String,
        bibsRangeStart: Int?,
        bibsRangeCount: Int?,
    ) {
        val race = raceDao.getById(raceId) ?: return
        val label = buildRaceLabel(name, race.course, race.createdAtMillis)
        raceDao.updateDetails(raceId, name, race.course, location, label, courses, bibsRangeStart, bibsRangeCount)
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
        }
    }

    // Resolves a race label back to this device's own local race — see
    // MuleRepository.pushToServer's own self-push path.
    suspend fun getRaceByLabel(label: String): RaceEntity? = raceDao.getByLabel(label)

    // Resolves which race row a Start press for [chosenCourse] should actually record into —
    // see TODO.md's "Multi-mode Method" spec and RaceEntity.course/.courses' own docs. Course is
    // now picked at Start time rather than fixed on the race details form, so [currentRaceId]
    // (whichever race is already active for this mode/device — created course-less by
    // RaceDetailsScreen, or a previously-started sibling) may or may not already be the right
    // row for [chosenCourse]. Three outcomes, in order:
    //  1. A race already has today's label for this name+course — reused as-is, whether this
    //     device wrote it earlier today (a previous Bibs/Time/CP session for this same course,
    //     including via this exact [currentRaceId]) or another mode on THIS device wrote it
    //     first (RaceEntity already lets Time/Bibs/CP share one row's history — see
    //     RaceHistoryDetailViewModel's own doc). If [currentRaceId] is a still course-less
    //     pending row distinct from the one found, it's deleted (deleteRace is a no-op-safe
    //     backstop on anything active, but a pending row is by definition never active) rather
    //     than left behind as permanent clutter in Race History.
    //  2. [currentRaceId]'s own course is still unset (the very first Start on a freshly
    //     created race, and no sibling already claimed this label) — that row is claimed in
    //     place via RaceDao.setCourseAndLabel: no new row, nothing to clean up.
    //  3. Otherwise a new sibling row is created (via [startNewRace]), cloning
    //     [currentRaceId]'s own template fields (name, offered courses, location, bib range,
    //     server URL) — the operator never has to re-enter them just to swap course.
    // Returns the resolved race's id. The caller is responsible for promoting it to
    // [SettingsRepository.setActiveRaceId] and for either resuming (via its own resumeXMode)
    // when the resolved row has already recorded that mode before — this course was ended, not
    // reset, so logging should carry on exactly where it left off, no fresh segment — or
    // starting fresh (via startXMode) otherwise; this function only ever resolves WHICH row,
    // never mutates a mode's own started/stopped state.
    suspend fun resolveCourseRace(currentRaceId: Long, chosenCourse: String, deviceRole: String?): Long {
        val current = requireNotNull(raceDao.getById(currentRaceId)) { "Race $currentRaceId not found" }
        val label = buildRaceLabel(current.name, chosenCourse, System.currentTimeMillis())
        val existing = raceDao.getByLabel(label)
        if (existing != null) {
            if (current.course.isBlank() && current.id != existing.id) deleteRace(current.id)
            return existing.id
        }
        if (current.course.isBlank()) {
            raceDao.setCourseAndLabel(current.id, chosenCourse, label)
            return current.id
        }
        return cloneTemplate(current, chosenCourse, deviceRole)
    }

    // Creates a fresh, still course-less sibling row cloned from [raceId]'s own template
    // fields — see cloneTemplate's own doc — WITHOUT touching [raceId]'s own started/stopped/
    // counters or history at all. Backs the Reset confirmation's "End recording" choice
    // (StopOrResetButton/*ModeViewModel.endRecording): unlike Reset (which is for a new
    // operator's own practice attempt — wipes the current course's segment right now so it can
    // be redone from scratch), End recording is the normal way a course finishes — its data
    // stays exactly as recorded, permanently. Returns the new pending race's id; the caller
    // promotes it to [SettingsRepository.activeRaceId] so the mode screen naturally shows its
    // own pre-Start "START" button again, ready for a fresh Start-time course pick via
    // resolveCourseRace — a different course starts a genuine new segment, while the SAME
    // course (there are still runners out, or it was ended by mistake) resumes exactly where it
    // left off via *ModeRepository.resumeXMode (see resolveCourseRace's own doc) — no new
    // Start/Clock marker, no counter reset, Undo last still reaches back before the Stop.
    suspend fun endRecordingForCourse(raceId: Long, deviceRole: String?): Long {
        val current = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
        return cloneTemplate(current, course = "", deviceRole)
    }

    // Shared by resolveCourseRace's new-sibling path and endRecordingForCourse — a new row
    // under [source]'s own name, courses menu, location, bib range, and server URL, so neither
    // caller makes the operator re-enter them just to move on to another course (or none yet).
    private suspend fun cloneTemplate(source: RaceEntity, course: String, deviceRole: String?): Long =
        startNewRace(
            name = source.name,
            course = course,
            location = source.location,
            deviceRole = deviceRole,
            serverUrl = source.serverUrl,
            bibsRangeStart = source.bibsRangeStart,
            bibsRangeCount = source.bibsRangeCount,
            courses = source.courses,
        )

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
