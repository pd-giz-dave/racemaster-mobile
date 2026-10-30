package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.LineSyncDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.DELETED_RACE_NOTE
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.LineSyncEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.mule.isRaceStale
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.data.settings.toHistoryMode
import mobile.racemaster.data.settings.wireName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class RaceRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    private val lineSyncDao: LineSyncDao,
    private val settingsRepository: SettingsRepository,
) {
    // Fetches every row this race has ever written for [mode] and reduces it to the building
    // blocks every segment-aware operation below needs — see HistoryFold's own doc for what each
    // piece means. Computed fresh every call (no caching): this only ever runs inside a Start/
    // Relocate/Reset transaction, never on a hot path like recordEntry/recordSplit.
    private fun buildVisitsSnapshot(raw: List<HistoryLineEntity>, locationAware: Boolean): VisitsSnapshot {
        val resetTargetLineNumbers = resetTargets(raw, { it.refLineNumber }, { it.action == HistoryAction.RESET })
        val displayFiltered = raw.filterNot {
            it.action == HistoryAction.MODE_START || it.action == HistoryAction.NEW_RACE ||
                it.action == HistoryAction.RESET || it.action == HistoryAction.PING
        }
        val folded = foldLatestVisible(displayFiltered, { it.lineNumber }, { it.refLineNumber }, { it.action == HistoryAction.UNDO })
            .sortedBy { it.lineNumber }
        val allVisits = visits(folded, { it.lineNumber }, { it.note }, { it.action == HistoryAction.LOCATION })
        return VisitsSnapshot(raw, allVisits, resetTargetLineNumbers, locationAware)
    }

    private suspend fun visitsSnapshot(raceId: Long, mode: HistoryMode): VisitsSnapshot =
        buildVisitsSnapshot(historyLineDao.getAllForRaceAndMode(raceId, mode), locationAware = mode == HistoryMode.BIBS)

    private class VisitsSnapshot(
        val raw: List<HistoryLineEntity>,
        val allVisits: List<LocationVisit<HistoryLineEntity>>,
        val resetTargetLineNumbers: Set<Long>,
        val locationAware: Boolean,
    ) {
        val openVisits: List<LocationVisit<HistoryLineEntity>> get() = currentSegmentVisits(allVisits, resetTargetLineNumbers, locationAware)
    }

    // Every location this mode still has an un-Reset (reachable-by-relocating-back) segment at,
    // most-recently-visited first, including the race's own CURRENT location — see HistoryFold's
    // own "what does reset mean" doc. Resetting only ever closes the CURRENT segment (see
    // closeCurrentSegment above) — any OTHER location's own data is left fully intact, but with
    // nothing in the UI otherwise telling the operator it's still there or how to get back to it
    // except by retyping its exact name via Relocate (confirmed in the field as genuinely
    // confusing: reset Start after relocating there from Finish, and the screen just shows a
    // blank, enabled Start button with no hint Finish's own entries are still sitting un-reset).
    // Callers filter out whichever entry is the current location themselves (they already have
    // it) and feed the rest to recordModeStart, below, to jump straight there — same
    // resume-on-relocate-back codepath Relocate's own location field already uses, not a second
    // one.
    // Time Mode is never location-partitioned (see currentSegmentVisits' own locationAware doc) —
    // there's only ever one merged current segment for it, so "some OTHER location is still open"
    // is not a concept that applies; always empty for it.
    fun observeOpenLocations(raceId: Long, mode: HistoryMode): Flow<List<String>> {
        if (mode != HistoryMode.BIBS) return flowOf(emptyList())
        return historyLineDao.observeAllForRaceAndMode(raceId, mode).map { raw ->
            val snapshot = buildVisitsSnapshot(raw, locationAware = true)
            snapshot.allVisits
                .filterNot { it.locationLineNumber in snapshot.resetTargetLineNumbers }
                .asReversed()
                .mapNotNull { it.note }
                .distinct()
        }
    }

    // Called from each mode's own Reset action (TimeModeRepository.resetStopwatch/
    // EntryLogModeEngine.reset) — see HistoryAction's own "What does reset mean?" doc. Closes
    // every visit making up the *current* segment at once (there can be more than one once a
    // relocate-back has merged non-contiguous visits sharing the same location text back into
    // one live view — see recordModeStart's own resume doc below): each gets its own RESET row,
    // reusing the same single-target refLineNumber contract every other RESET row already uses,
    // rather than inventing a multi-target reference. A no-op (nothing written) if this mode
    // currently has no open segment at all (the Reset button should already be disabled in that
    // case; this is the defensive backstop). Resetting the race's own very first-ever segment is
    // no different from resetting any other — race.location and the device's activeRaceId both
    // stay exactly as they were (this device is never kicked back to "no race set up" purely by
    // Reset walk-back any more — see ensureOpenSegment below for why pressing Start right back
    // at this same location afterward self-heals into a genuinely fresh segment, counter back at
    // 1, rather than resuming the one just closed).
    suspend fun closeCurrentSegment(raceId: Long, mode: HistoryMode, closedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val open = visitsSnapshot(raceId, mode).openVisits
            if (open.isEmpty()) return@withTransaction
            for (visit in open) {
                val race = raceDao.getById(raceId) ?: return@withTransaction
                historyLineDao.insert(
                    HistoryLineEntity(
                        raceId = raceId,
                        mode = mode,
                        action = HistoryAction.RESET,
                        splitNumber = null,
                        lineNumber = race.nextLineNumber,
                        timestampMillis = closedAtMillis,
                        refLineNumber = visit.locationLineNumber,
                    ),
                )
                raceDao.incrementLineNumber(raceId)
            }
            when (mode) {
                HistoryMode.TIME -> raceDao.resetTimeMode(raceId)
                HistoryMode.BIBS -> raceDao.resetBibsMode(raceId)
            }
        }
    }

    // Self-healing counterpart to closeCurrentSegment above — called by each mode's own Start
    // action (TimeModeRepository.startStopwatch/EntryLogModeEngine's Bibs/CP start functions)
    // before writing its own Start/Clock row. Necessary because the unified, LOCATION-anchored
    // segment model (see HistoryFold's own doc) has no SQL-level fallback boundary any more: a
    // row written with no currently-open LOCATION visit to belong to would silently attribute
    // itself to whatever visit was last seen historically — which, right after a Reset, is
    // exactly the now-closed one, making the new row invisible forever. Normally a no-op (Setup
    // Race/Relocate already wrote a fresh, still-open LOCATION+MODE_START pair before Start is
    // ever reachable); only actually fires when the operator presses Start again immediately
    // after fully Resetting the current segment without first Relocating elsewhere — in which
    // case this simply re-asserts the race's current location, which recordModeStart's own
    // resume-or-fresh logic below correctly treats as "nothing resumable" (the just-closed visit
    // is excluded) and so writes a genuinely fresh LOCATION+MODE_START pair, starting a brand new
    // segment at the same station with the counter back at 1.
    suspend fun ensureOpenSegment(raceId: Long, mode: AppMode) {
        val race = raceDao.getById(raceId) ?: return
        val historyMode = mode.toHistoryMode()
        if (visitsSnapshot(raceId, historyMode).openVisits.isNotEmpty()) return
        recordModeStart(raceId, mode, race.location)
    }

    // The only place a race gets created now (Setup Race's own save — see SetupRaceViewModel.save;
    // scanning the server only ever fills in `name` there, it never adopts a race directly). The
    // label is the name verbatim (see buildRaceLabel — no date or course is ever appended: a name
    // inherited from the server, or already following its own naming convention, must never be
    // silently modified).
    // Setup Race's own entry point: one local race per label, so the same name set up again on
    // the same device (e.g. after resetting it right back to the beginning) continues that race's
    // history — one device file on the server, one Race History entry — rather than creating a
    // second same-label race that shadows it (confirmed in the field: the second one never synced,
    // and both showed as identically-named entries). A race gone stale (no activity within
    // [maxAgeDays], e.g. last year's run of the same event) is not continued — a fresh race is
    // created instead, whose own NEW_RACE marker supersedes the old data on the server.
    suspend fun startOrContinueRace(name: String, location: String, maxAgeDays: Int): Long {
        val existing = raceDao.getByLabel(buildRaceLabel(name))
            ?.takeIf { it.id !in historyLineDao.observePendingDeleteRaceIds().first() }
        if (existing != null && !isRaceStale(historyLineDao.observeLastActivityAtMillis(existing.id).first(), maxAgeDays)) {
            return existing.id
        }
        return startNewRace(name, location = location)
    }

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
                label = buildRaceLabel(name),
                createdAtMillis = createdAtMillis,
                deviceRole = deviceRole,
                serverUrl = serverUrl,
                createdByDeviceName = settingsRepository.getOrCreateDeviceName(),
            ),
        )

    // Setup Race's own "this device is in the field, recording this mode at this station" write
    // (SetupRaceViewModel.save), and Relocate's own write when the operator
    // changes station and/or mode mid-race (RaceDetailsViewModel.save) — one function covers
    // both, since both need exactly the same pair: a HistoryAction.LOCATION marker (this
    // device's new station, in `note`) immediately followed by a HistoryAction.MODE_START marker
    // (the explicit mode, in `note` — see SyncRecord's own doc for why neither travels via
    // bibNumber/splitTime any more), both scoped to [mode], each consuming a real, permanent line
    // number. RaceEntity.mode/location are updated to match in the same transaction — a race
    // records at most one mode at a time now, replacing the old separate
    // HistoryMode.ANY/HistoryAction.SETUP marker (mode not yet known at Setup Race time) plus a
    // Relocate that could only ever touch location, never mode.
    //
    // previousMode/previousLocation on the LOCATION row let a later Undo restore exactly what
    // this call is about to overwrite (see EntryLogModeEngine/TimeModeRepository's own
    // LOCATION-undo branch) — for a brand-new race (Setup Race's own call), that's simply this
    // race's just-created defaults (mode = null, location = "Finish"), so undoing a race's very
    // first LOCATION+MODE_START pair correctly leaves it back in "no mode chosen yet" state.
    // priorSplitCounter is [mode]'s own counter as it stood immediately before this call resets
    // it to 1 (or resumes it — see below) — scoped to the NEW mode (not whichever was active
    // before), since this LOCATION row itself is mode-scoped to [mode] and only ever
    // visible/undoable from that mode's own screen.
    //
    // Relocating to a location this mode has already visited, and that visit hasn't since been
    // individually Reset, resumes it instead of starting fresh (TODO.md: "relocating back to some
    // previous location... must pick up where it left off... the mode screen should look like it
    // was when they left") — see HistoryFold's own doc for the location-grouped "visits" this is
    // built on. The counter resumes at one past the highest split/bib number actually used across
    // every one of that location's own still-open visits (falling back to 1 automatically when
    // there were none — the Clock/Start marker's own fixed split-0 counts toward that max but
    // never pushes it past 0 on its own); a genuinely new location, or one whose only prior
    // visit(s) were Reset away, always starts at 1, exactly as before this existed. Either way a
    // fresh LOCATION row is still written — "which visits are current" is entirely reconstructed
    // at read time from matching `note` text (see HistoryFold.currentSegmentVisits), never by
    // reusing an old LOCATION row — so an *interleaved* different location relocated through in
    // between (e.g. Finish -> CP1 -> Finish) is unaffected: its own visit(s) sit untouched, with
    // their own counter progressing independently of whatever Finish resumes to here.
    suspend fun recordModeStart(raceId: Long, mode: AppMode, location: String, timestampMillis: Long = System.currentTimeMillis()) {
        // Time is never location-aware (TODO.md's "A rationalisation") — forced to "Finish" once,
        // here, at the single place a LOCATION row/race.location is ever written, rather than
        // overriding every later reader of it. Ignores whatever the caller actually passed (a
        // lingering Bibs-mode value sitting in Setup Race's own hidden-for-Time draft, or free
        // text still typed into Relocate's location field while Time is selected) — so the stored
        // value is simply correct from the moment it's written, and everything downstream (local
        // display, server/Mule sync) sees it exactly like any other row, with nothing special to
        // know about Time.
        val effectiveLocation = if (mode == AppMode.TIME) "Finish" else location
        db.withTransaction {
            val historyMode = mode.toHistoryMode()
            var race = raceDao.getById(raceId) ?: return@withTransaction
            val resumeCounter = if (race.mode == null) {
                null
            } else {
                val snapshot = visitsSnapshot(raceId, historyMode)
                // Bibs only resumes a visit sharing this exact location's own note (location is
                // what partitions it); Time ignores location entirely (TODO.md's "A
                // rationalisation") and always continues from whatever's still open, regardless
                // of where it was recorded — relocating mid-race never restarts its own counter.
                val resumable = snapshot.allVisits.filter {
                    (historyMode != HistoryMode.BIBS || it.note == effectiveLocation) && it.locationLineNumber !in snapshot.resetTargetLineNumbers
                }
                if (resumable.isEmpty()) null else (resumable.flatMap { it.rows }.mapNotNull { it.splitNumber }.maxOrNull() ?: 0) + 1
            }
            // A brand-new race's own genuine first-ever call (Setup Race, never a later Relocate
            // — see HistoryAction.NEW_RACE's own doc) gets one extra marker line ahead of the
            // LOCATION/MODE_START pair below: the signal every sync recipient (server, a Mule's
            // own pull cache, the web app) needs to tell "this is a fresh race, discard whatever
            // you already hold for this device under this label" apart from "just another delta
            // for a race you already know about" — the two are otherwise indistinguishable once a
            // deleted-and-recreated race happens to reuse an identical label.
            if (race.mode == null) {
                historyLineDao.insert(
                    HistoryLineEntity(
                        raceId = raceId,
                        mode = historyMode,
                        action = HistoryAction.NEW_RACE,
                        bibNumber = null,
                        splitNumber = null,
                        lineNumber = race.nextLineNumber,
                        note = null,
                        timestampMillis = timestampMillis,
                    ),
                )
                raceDao.incrementLineNumber(raceId)
                race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            }
            val priorSplitCounter = when (historyMode) {
                HistoryMode.TIME -> race.timeModeNextSplit
                HistoryMode.BIBS -> race.bibsModeNextSplit
            }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = historyMode,
                    action = HistoryAction.LOCATION,
                    bibNumber = null,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    note = effectiveLocation,
                    timestampMillis = timestampMillis,
                    priorSplitCounter = priorSplitCounter,
                    previousLocation = race.location,
                    previousMode = race.mode,
                ),
            )
            raceDao.incrementLineNumber(raceId)
            val raceAfterLocation = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = historyMode,
                    action = HistoryAction.MODE_START,
                    bibNumber = null,
                    splitNumber = null,
                    lineNumber = raceAfterLocation.nextLineNumber,
                    note = mode.wireName(),
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
            val nextSplit = resumeCounter ?: 1
            when (historyMode) {
                HistoryMode.TIME -> raceDao.setTimeModeNextSplit(raceId, nextSplit)
                HistoryMode.BIBS -> raceDao.setBibsModeNextSplit(raceId, nextSplit)
            }
            raceDao.updateModeAndLocation(raceId, mode.name, effectiveLocation)
        }
        // Keeps the device-wide SettingsRepository.appMode setting in sync with this race's own
        // mode — PeripheralSyncService's advertised mode byte and AppEntryViewModel's own
        // last-mode auto-forward both still key off this flat setting rather than needing to
        // reactively observe whichever race happens to be active; this is the one place that
        // now updates it, replacing ModePickerViewModel's old direct setAppMode call (mode is no
        // longer chosen there — see that screen's own doc).
        settingsRepository.setAppMode(mode)
        // Bibs is location-aware (TODO.md: "A rationalisation") — this is the one place location
        // ever changes (Setup Race and Relocate both funnel through here), so it's the one place
        // that seeds a fresh default action from it. Purely a default: the operator's own later
        // picks via Bibs' own selector simply overwrite this same setting afterward, same as
        // always — see defaultActionForLocation's own doc.
        if (mode == AppMode.BIBS) {
            settingsRepository.setBibsAutoSaveAction(defaultActionForLocation(location))
        }
    }

    // name/location genuinely can change here now — RaceDetailsScreen only locks them once the
    // race has actually started a mode (see its own identityFieldsEnabled doc); before that, no
    // history can possibly exist for this race yet (every mode's own startXxxMode is what both
    // sets its *ModeStartedAtMillis and inserts its first history row, in the same transaction),
    // so nothing anywhere could already be referencing the old label. The label is just the new
    // name verbatim (see buildRaceLabel — no date/course is ever appended). serverUrl is
    // untouched here — it's not on this screen (see RaceDao.updateDetails). No Mule-inbox
    // retagging needed on a rename (there used to be one here) — MuleRepository.pushToServer now
    // reads this race's own current label fresh from RaceEntity on every attempt rather than
    // tracking a separately-labeled mirrored copy, so a rename just takes effect on the very next
    // push with nothing else to keep in sync. location is deliberately NOT part of the label (see
    // RaceEntity.location's own doc) — a change here just takes effect the same way, on the next
    // record this device pushes.
    suspend fun updateRaceDetails(raceId: Long, name: String, location: String) {
        raceDao.getById(raceId) ?: return
        val label = buildRaceLabel(name)
        raceDao.updateDetails(raceId, name, location, label)
    }

    // Phase 3's adoption trigger: a device that broadcast a temporary, manually-typed race name
    // (because it had no server reachable at Setup Race time — see SetupRaceViewModel's offline
    // branch) is being told, via a targeted BLE progress delivery, which real server-side race it
    // actually belongs to (see PeripheralSyncService.handleProgressPayload for where this is
    // called). Rewrites the existing row in place — same [raceId], same
    // SettingsRepository.activeRaceId pointer, same history — to [raceLabel]'s own identity: unlike
    // creating a fresh race, this must never lose already-recorded history, and
    // [HistoryLineEntity.raceId] is a stable Room FK, never derived from name/label, so nothing
    // downstream needs migrating — every screen already observes this race reactively off
    // [raceId] via Room Flows, so the new identity reaches all of them on their very next
    // emission. [raceLabel]'s own name portion (see [raceNameFromLabel]) becomes this race's new
    // name; location is left exactly as it was (this device's own physical station, unrelated to
    // which race it now records). Also updates the persisted Setup Race draft's own name (only
    // name — location/mode are left as whatever the operator already has there) to the same value,
    // so a later reopen of Setup Race shows the web app's own confirmed name rather than whatever
    // was there before adoption — this is also what makes the device's own re-synced history (and
    // hence the web app's Devices list) visibly reflect the adoption back to the operator. A no-op
    // if [raceId] no longer exists (defensive — the race this delivery was addressed to could in
    // principle have been deleted between the delivery being cached and this running).
    suspend fun adoptRaceIdentity(raceId: Long, raceLabel: String) {
        val race = raceDao.getById(raceId) ?: return
        val newName = raceNameFromLabel(raceLabel)
        raceDao.updateDetails(raceId, newName, race.location, raceLabel)
        val draft = settingsRepository.setupRaceDraft.first()
        settingsRepository.saveSetupRaceDraft(newName, draft.location, draft.mode?.let { AppMode.valueOf(it) })
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

    // Deleting a race everywhere, not just on this phone (TODO.md: "if a self history file is
    // deleted ... ensure it gets flushed from everywhere"). The race's whole history is replaced by
    // one NEW_RACE row noted DELETED_RACE_NOTE — a new, newer generation that every recipient
    // already treats as "discard what you hold" (server, mules, web app), and which the server and
    // mules additionally use to refuse any older copy a lagging mule later resends. The row stays
    // until that tombstone has been acknowledged (see purgeConfirmedDeletes); until then Race
    // History shows it as pending delete. Numbering restarts at 1 like any recreated race — the
    // recipients' existing "line count went down" handling picks it up from scratch. Refuses a
    // race still active, same as deleteRace.
    suspend fun requestDeleteRace(raceId: Long, nowMillis: Long = System.currentTimeMillis()) {
        if (isRaceCurrentlyActive(raceId, this)) return
        val race = raceDao.getById(raceId) ?: return
        db.withTransaction {
            historyLineDao.deleteAllForRace(raceId)
            lineSyncDao.deleteForRace(raceId)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = race.mode?.let { AppMode.valueOf(it).toHistoryMode() } ?: HistoryMode.TIME,
                    action = HistoryAction.NEW_RACE,
                    bibNumber = null,
                    splitNumber = null,
                    lineNumber = 1L,
                    note = DELETED_RACE_NOTE,
                    timestampMillis = nowMillis,
                ),
            )
            raceDao.setNextLineNumber(raceId, 2L)
        }
        if (settingsRepository.activeRaceId.first() == raceId) settingsRepository.clearActiveRaceId()
    }

    fun observePendingDeleteRaceIds(): Flow<List<Long>> = historyLineDao.observePendingDeleteRaceIds()

    fun observePendingDeleteRaces(): Flow<List<RaceEntity>> =
        combine(raceDao.observeAll(), historyLineDao.observePendingDeleteRaceIds()) { races, ids ->
            val pending = ids.toSet()
            races.filter { it.id in pending }
        }

    // Finishes each pending delete once its tombstone has been acknowledged — confirmed on the
    // server (syncedAtMillis) or taken by a mule/the web app (a LineSync row), which then carries
    // it on. Also finishes one shadowed by a newer same-label race, whose own NEW_RACE supersedes
    // the tombstone everywhere anyway (and which MuleRepository.pushToServer pushes instead).
    suspend fun purgeConfirmedDeletes() {
        val pendingIds = historyLineDao.observePendingDeleteRaceIds().first()
        if (pendingIds.isEmpty()) return
        val races = raceDao.observeAll().first()
        for (id in pendingIds) {
            val race = races.firstOrNull { it.id == id } ?: continue
            val tombstone = historyLineDao.getByLineNumber(id, 1L)
            val acknowledged = tombstone?.syncedAtMillis != null || lineSyncDao.observeForRace(id).first().isNotEmpty()
            val shadowed = races.any { it.label == race.label && it.id != id && it.createdAtMillis >= race.createdAtMillis && it.id !in pendingIds }
            if (acknowledged || shadowed) deleteRace(id)
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
    // Must do exactly what an in-context Reset does for each mode that's actually active — see
    // closeCurrentSegment's own doc, reused directly here rather than hand-duplicated. Scoped to
    // only the modes actually started (unlike before, which reset all three unconditionally as a
    // harmless no-op) specifically so this doesn't insert a spurious Reset line into a mode's
    // history that was never even used for this race. Deliberately does not delete the race
    // itself, matching deleteRace's own two-step design: this only clears whatever's blocking
    // isRaceActive, leaving the operator to explicitly delete afterward via the normal
    // confirmation dialog.
    suspend fun forceResetActiveModes(raceId: Long) {
        val race = raceDao.getById(raceId) ?: return
        if (race.timeModeStartedAtMillis != null) closeCurrentSegment(raceId, HistoryMode.TIME)
        if (race.bibsModeStartedAtMillis != null) closeCurrentSegment(raceId, HistoryMode.BIBS)
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

    // Race History's own "N entries from <device>" line — see HistoryLineDao.observeEntryCount's
    // own doc.
    fun observeEntryCount(raceId: Long): Flow<Int> = historyLineDao.observeEntryCount(raceId)

    // Race History's own list-row "Last synced" line — see
    // HistoryLineDao.observeLastSyncedAtMillisForRace's own doc.
    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = historyLineDao.observeLastSyncedAtMillisForRace(raceId)

    // Mode-scoped one-shot version of the above — see MuleSyncEngine's own Ping-heartbeat loop,
    // the only caller: it needs to know how long ONE mode specifically has gone quiet, not the
    // race as a whole (a busy Bibs station shouldn't suppress Time's own heartbeat, or vice
    // versa).
    suspend fun lastActivityAtMillis(raceId: Long, mode: HistoryMode): Long? = historyLineDao.getLastActivityAtMillis(raceId, mode)

    // Writes one Ping heartbeat row — see HistoryAction.PING's own doc. Consumes a permanent
    // line number like every other row but touches no counter/started-at column and is excluded
    // from every mode-screen list (see HistoryFold.currentSegmentRows' own displayExcluded
    // filter).
    suspend fun recordPing(raceId: Long, mode: HistoryMode, timestampMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = raceDao.getById(raceId) ?: return@withTransaction
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = mode,
                    action = HistoryAction.PING,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

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
