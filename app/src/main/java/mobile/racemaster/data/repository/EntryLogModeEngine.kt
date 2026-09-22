package mobile.racemaster.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode

// The Clock marker's fixed split number, outside the normal 1,2,3... sequence — shared by both
// Bibs' and CP's own start (see BibsModeRepository.startBibsMode/CpModeRepository.startCpMode),
// so it doesn't consume either mode's own display counter (see NO_SPLIT_ACTIONS' own doc for the
// same idea applied to RETIRE).
const val CLOCK_SPLIT_NUMBER = 0

// Root row kinds that must never be edited or undone through the generic path — Undo/Edit
// guards below key off this set, keyed off the ROOT row (never the target/echo) so the guard
// holds even if a bug elsewhere let an echo's displayed action drift from its root. None of
// these are ever actually reachable here anyway (all excluded from the live view's own fold
// input — see HistoryFold.currentSegmentRows), but listed for the same belt-and-braces reason.
private val NON_EDITABLE_ROOT_ACTIONS = setOf(
    HistoryAction.RESET, HistoryAction.UNDO,
    HistoryAction.MODE_START, HistoryAction.LOCATION, HistoryAction.NEW_RACE, HistoryAction.PING,
)

// RETIRE never crosses the timing point at all (Bibs or CP), so it gets no splitNumber and
// doesn't consume the shared counter — see HistoryLineEntity.splitNumber's own doc. PASS, by
// contrast, DOES get one: even though a CP checkpoint is never paired with a Time Mode device the
// way Bibs Mode's finish line is, so a CP row's splitNumber never aligns with any real timing
// split, CP Mode still wants it as a plain running count of "how many have passed since
// Start/Reset" (see CpModeRepository's own doc) — exactly the same counter mechanism Bibs Mode
// already used for FINISH, just repurposed here as a count rather than a split-time index. CLOCK
// is here too — the fixed Start marker never consumed the counter either (see
// CLOCK_SPLIT_NUMBER's own doc); undoing it must not decrement one (see Part 5's own doc on
// undoMostRecent's CLOCK branch below for why undoing Clock is now reachable at all).
private val NO_SPLIT_ACTIONS = setOf(HistoryAction.RETIRE, HistoryAction.CLOCK)

/** Per-mode wiring for the display-counter/started-at [RaceEntity] columns [EntryLogModeEngine]
 *  needs — one implementation per [HistoryMode] family (see `BibsModeRepository`/
 *  `CpModeRepository`), each a thin pass-through to that family's own dedicated [RaceDao]
 *  queries/[RaceEntity] fields. Kept separate from the engine itself since this is the one
 *  piece genuinely specific to each mode (different column names) — the same reason TIME/BIBS
 *  already each got a fully separate column pair rather than sharing one; CP follows that same
 *  established pattern instead of trying to share BIBS's columns. */
interface ModeProgressColumns {
    fun nextSplitOf(race: RaceEntity): Int
    suspend fun incrementCounter(raceId: Long)
    suspend fun decrementCounter(raceId: Long)
    suspend fun resetCounters(raceId: Long)

    // Relocation's own counter reset/restore/resume (see RaceRepository.recordModeStart and this
    // engine's own undoMostRecent LOCATION branch) — deliberately narrower than resetCounters,
    // which also clears startedAtMillis and would wrongly kick an in-progress mode back to
    // pre-Start state.
    suspend fun setCounterTo(raceId: Long, value: Int)

    // Undoing the mode's own fixed Clock/Start marker (see undoMostRecent's own CLOCK branch) —
    // timestamp-only, deliberately never touches the counter (see NO_SPLIT_ACTIONS' own doc).
    suspend fun clearStartedAt(raceId: Long)
}

/**
 * The mode-agnostic core behind `BibsModeRepository`/`CpModeRepository` — segmented,
 * LOCATION-anchored entry logging (see [HistoryFold]'s own doc) with append-only edit/undo
 * semantics (see [foldLatestVisible]), generalized over which [HistoryMode] family it's serving
 * and which [RaceEntity] columns track that family's own display counter/started-at state (see
 * [ModeProgressColumns]). Lifted verbatim from `BibsModeRepository`'s original method bodies,
 * parameterized rather than duplicated, so a third (or later, fourth) segmented-entry mode never
 * needs its own hand-copied twin of this logic.
 *
 * Deliberately does **not** include a "start" method: both Bibs and CP Mode start by inserting
 * a fixed Clock marker row (a permanent split #0 outside the normal sequence, consuming a real
 * line number but not the display counter — see [CLOCK_SPLIT_NUMBER]) *and* setting their own
 * [RaceEntity] started-at timestamp, but each writes to a different pair of columns
 * ([ModeProgressColumns] is only wired to one mode's own display counter, not its started-at
 * field) — different enough that forcing this into one shared method wouldn't actually remove
 * duplication, just hide it behind one signature. Each mode's own thin repository keeps its own
 * trivial start method instead (`BibsModeRepository.startBibsMode`/`CpModeRepository.startCpMode`).
 */
internal class EntryLogModeEngine(
    private val mode: HistoryMode,
    private val appMode: AppMode,
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    private val raceRepository: RaceRepository,
    private val columns: ModeProgressColumns,
) {
    // The live current-segment view — see HistoryFold.currentSegmentRows' own doc for exactly
    // what "current segment" now means (a LOCATION-anchored, walkable-by-Reset, resumable-by-
    // relocating-back union of one or more visits, no longer a single SQL RESET wall).
    fun observeCurrentSegmentEntries(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeAllForRaceAndMode(raceId, mode).map { raw -> currentSegment(raw) }

    private fun currentSegment(raw: List<HistoryLineEntity>): List<HistoryLineEntity> =
        currentSegmentRows(
            raw,
            { it.lineNumber },
            { it.refLineNumber },
            { it.note },
            { it.action == HistoryAction.LOCATION },
            { it.action == HistoryAction.RESET },
            { it.action == HistoryAction.UNDO },
            { it.action == HistoryAction.MODE_START || it.action == HistoryAction.NEW_RACE || it.action == HistoryAction.RESET || it.action == HistoryAction.PING },
        )

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = historyLineDao.observeUnsyncedCountForRace(raceId, mode)

    // For EditEntryScreen's own one-shot load-by-id on open — a dedicated screen (reached by
    // navigating, not composed inline over the live list) has no already-loaded row of its own
    // to read from, unlike the old inline editor which just filtered the live screen's own
    // uiState.entries.
    suspend fun getEntry(id: Long): HistoryLineEntity? = historyLineDao.getById(id)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = historyLineDao.observeLastSyncedAtMillis(raceId, mode)

    suspend fun recordEntry(
        raceId: Long,
        action: HistoryAction,
        bibNumber: Int?,
        note: String?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = if (action in NO_SPLIT_ACTIONS) {
                null
            } else {
                columns.nextSplitOf(race).also { columns.incrementCounter(raceId) }
            }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = mode,
                    action = action,
                    bibNumber = if (action in BIB_REQUIRED_ACTIONS) bibNumber else null,
                    splitNumber = splitNumber,
                    lineNumber = race.nextLineNumber,
                    note = note,
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    // splitNumber is assigned once at creation and is never touched here — it stays stable
    // across edits, exactly like Time Mode's own split-label editing never touches its number.
    //
    // Append-only: rather than mutating the existing row, this inserts a new "echo" row that
    // copies every field from the row currently being edited (crucially timestampMillis and
    // splitNumber) with only bibNumber/action/note changed — the original stays untouched in
    // the permanent history. refLineNumber is flattened to the ROOT row (never an intermediate
    // echo) so reconstructing "what's visible" only ever needs one level of grouping (see
    // HistoryFold). Refuses to edit a row whose ROOT is a Reset/Undo marker, and pins the
    // action to CLOCK if the root is a Clock row — defense-in-depth so either mode's own
    // Clock row's note-only edit path (see EditEntryScreen's own CLOCK branch) can never be
    // defeated by a direct updateEntry call with a different action.
    suspend fun updateEntry(id: Long, bibNumber: Int?, action: HistoryAction, note: String?) {
        db.withTransaction {
            val existing = historyLineDao.getById(id) ?: return@withTransaction
            val rootLineNumber = existing.refLineNumber ?: existing.lineNumber
            val root = historyLineDao.getByLineNumber(existing.raceId, rootLineNumber) ?: return@withTransaction
            if (root.action in NON_EDITABLE_ROOT_ACTIONS) return@withTransaction
            val effectiveAction = if (root.action == HistoryAction.CLOCK) HistoryAction.CLOCK else action
            val race = requireNotNull(raceDao.getById(existing.raceId)) { "Race ${existing.raceId} not found" }
            historyLineDao.insert(
                existing.copy(
                    id = 0,
                    lineNumber = race.nextLineNumber,
                    bibNumber = if (effectiveAction in BIB_REQUIRED_ACTIONS) bibNumber else null,
                    action = effectiveAction,
                    note = note,
                    refLineNumber = rootLineNumber,
                    syncedAtMillis = null,
                ),
            )
            raceDao.incrementLineNumber(existing.raceId)
        }
    }

    // Scoped to the current segment (see observeCurrentSegmentEntries above and
    // HistoryFold.currentSegmentRows) — a Reset marker (and everything it closed) is never
    // reachable here once that segment has been closed, with no separate guard needed for it.
    //
    // Append-only: rather than deleting the target row, this inserts an "undo marker"
    // (action = UNDO, refLineNumber = the target's ROOT) that HistoryFold treats as hiding its
    // whole logical entry — the original row(s) stay untouched in the permanent history. "Undo"
    // always targets the top of the FOLDED list (the most recent still-visible logical entry),
    // not literally the last-appended raw row, which could itself be an edit-echo of an OLDER
    // entry — undoing "the last thing that happened" means hiding that entry entirely, never
    // partially reverting an edit. Race-state side effects are keyed off the ROOT row's action,
    // never the target's, for the same robustness reason.
    suspend fun undoMostRecent(raceId: Long) {
        db.withTransaction {
            val raw = historyLineDao.getAllForRaceAndMode(raceId, mode)
            val current = currentSegment(raw)
            val target = current.firstOrNull() ?: return@withTransaction
            val rootLineNumber = target.refLineNumber ?: target.lineNumber
            val root = raw.first { it.lineNumber == rootLineNumber }
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = mode,
                    action = HistoryAction.UNDO,
                    // Copied from the root row purely so the undo marker itself can show which
                    // bib got undone — never treated as a real bib record itself, since UNDO
                    // isn't in BIB_REQUIRED_ACTIONS.
                    bibNumber = root.bibNumber,
                    splitNumber = root.splitNumber,
                    lineNumber = race.nextLineNumber,
                    note = null,
                    timestampMillis = System.currentTimeMillis(),
                    refLineNumber = rootLineNumber,
                ),
            )
            raceDao.incrementLineNumber(raceId)
            when {
                // Undoing the mode's own fixed Clock/Start marker now returns the screen to its
                // pre-Start state, matching Time Mode's own START branch — TODO.md's "undo last
                // in time mode includes the initial start, in bibs/cp mode it does not, make
                // them consistent - make bibs/cp like time". Timestamp-only clear (see
                // ModeProgressColumns.clearStartedAt's own doc) — the counter was never consumed
                // by Clock in the first place (see NO_SPLIT_ACTIONS), so nothing to restore there.
                root.action == HistoryAction.CLOCK -> columns.clearStartedAt(raceId)
                // A LOCATION root never merely "consumed one count" the way a real entry does —
                // its own forward write reset (or resumed — see RaceRepository.recordModeStart)
                // the counter, so undoing it must restore whatever the counter (and
                // RaceEntity.location) actually were beforehand, not decrement whatever they
                // happen to be now. Falling through to decrementCounter() here (as an unguarded
                // `else` would) would silently corrupt the counter.
                root.action == HistoryAction.LOCATION -> {
                    root.priorSplitCounter?.let { columns.setCounterTo(raceId, it) }
                    root.previousLocation?.let { raceDao.updateModeAndLocation(raceId, root.previousMode, it) }
                }
                // RETIRE never consumed the counter in the first place (see recordEntry/
                // NO_SPLIT_ACTIONS), so undoing one must not decrement it either.
                root.action !in NO_SPLIT_ACTIONS -> columns.decrementCounter(raceId)
            }
        }
    }

    // Resumes logging in place after Start is pressed again on an already-started, not-yet-reset
    // race — see each *ModeViewModel's own startXMode doc, and Race History's own "Resume" action
    // (RaceRepository.switchActiveRace) for the case where that race wasn't even this device's
    // active one any more. A no-op now that there's no separate stopped state left to clear (see
    // HistoryAction's own doc) — kept as a function, rather than removed outright, since each
    // *ModeViewModel's own already-started/not-yet-reset branch still calls it.
    suspend fun resume(raceId: Long) {
    }

    // Closes the current segment (see RaceRepository.closeCurrentSegment's own doc — one or more
    // RESET rows, walkable by pressing Reset again) and clears the display counter/started-at
    // back to their pre-start defaults. True means this closed the race's own very first
    // segment, reverting the device to "no race set up" — the caller announces this to the
    // server right away (see BibsModeViewModel/CpModeViewModel's own resetXMode wiring).
    suspend fun reset(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()): Boolean =
        raceRepository.closeCurrentSegment(raceId, mode, resetAtMillis)

    // Exposed so each mode's own thin start method (BibsModeRepository.startBibsMode/
    // CpModeRepository.startCpMode) can call the same defensive self-heal Time Mode's own
    // startStopwatch does before writing its Clock row — see RaceRepository.ensureOpenSegment's
    // own doc for what this guards against and why it's normally a no-op. [appMode] is threaded
    // through the constructor rather than each start method passing its own literal, so this
    // stays a single, un-duplicated call site.
    suspend fun ensureOpenSegment(raceId: Long) {
        raceRepository.ensureOpenSegment(raceId, appMode)
    }
}
