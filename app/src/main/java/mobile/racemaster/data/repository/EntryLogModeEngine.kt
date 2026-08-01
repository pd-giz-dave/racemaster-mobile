package mobile.racemaster.data.repository

import androidx.room.withTransaction
import java.util.UUID
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

// The Clock marker's fixed split number, outside the normal 1,2,3... sequence — shared by both
// Bibs' and CP's own start (see BibsModeRepository.startBibsMode/CpModeRepository.startCpMode),
// so it doesn't consume either mode's own display counter (see NO_SPLIT_ACTIONS' own doc for the
// same idea applied to RETIRE/PASS).
const val CLOCK_SPLIT_NUMBER = 0

// Root row kinds that must never be edited or undone through the generic path — Undo/Edit
// guards below key off this set, keyed off the ROOT row (never the target/echo) so the guard
// holds even if a bug elsewhere let an echo's displayed action drift from its root. MODE_START
// is never reachable here anyway (see observeCurrentSegmentEntries/undoMostRecent's own
// filtering — it's excluded from the live view entirely), but listed for the same
// belt-and-braces reason the other markers are.
private val NON_EDITABLE_ROOT_ACTIONS = setOf(HistoryAction.STOP, HistoryAction.RESET, HistoryAction.UNDO, HistoryAction.MODE_START)

// RETIRE never crosses the timing point at all (Bibs or CP), so it gets no splitNumber and
// doesn't consume the shared counter — see HistoryLineEntity.splitNumber's own doc. PASS, by
// contrast, DOES get one: even though a CP checkpoint is never paired with a Time Mode device
// the way Bibs Mode's finish line is, so a CP row's splitNumber never aligns with any real
// timing split, CP Mode still wants it as a plain running count of "how many have passed since
// Start/Reset" (see CpModeRepository's own doc) — exactly the same counter mechanism Bibs Mode
// already used for FINISH, just repurposed here as a count rather than a split-time index. STOP
// is here too (checked directly in stop() below, since it's never routed through recordEntry) —
// like Clock, Stop/Reset markers exist for the web app's own later boundary detection, not as
// something meant to occupy a slot in the operator-facing count.
private val NO_SPLIT_ACTIONS = setOf(HistoryAction.RETIRE, HistoryAction.STOP)

/** Per-mode wiring for the display-counter/stopped-at [RaceEntity] columns [EntryLogModeEngine]
 *  needs — one implementation per [HistoryMode] family (see `BibsModeRepository`/
 *  `CpModeRepository`), each a thin pass-through to that family's own dedicated [RaceDao]
 *  queries/[RaceEntity] fields. Kept separate from the engine itself since this is the one
 *  piece genuinely specific to each mode (different column names) — the same reason TIME/BIBS
 *  already each got a fully separate column pair rather than sharing one; CP follows that same
 *  established pattern instead of trying to share BIBS's columns. */
interface ModeProgressColumns {
    fun nextSplitOf(race: RaceEntity): Int
    fun stoppedAtOf(race: RaceEntity): Long?
    suspend fun incrementCounter(raceId: Long)
    suspend fun decrementCounter(raceId: Long)
    suspend fun setStoppedAt(raceId: Long, stoppedAtMillis: Long)
    suspend fun clearStoppedAt(raceId: Long)
    suspend fun resetCounters(raceId: Long)
}

/**
 * The mode-agnostic core behind `BibsModeRepository`/`CpModeRepository` — segmented
 * (Reset-bounded) entry logging with append-only edit/undo semantics (see [foldLatestVisible]),
 * generalized over which [HistoryMode] family it's serving and which [RaceEntity] columns track
 * that family's own display counter/stopped-at state (see [ModeProgressColumns]). Lifted
 * verbatim from `BibsModeRepository`'s original method bodies, parameterized rather than
 * duplicated, so a third (or later, fourth) segmented-entry mode never needs its own hand-copied
 * twin of this logic.
 *
 * Deliberately does **not** include a "start" method: both Bibs and CP Mode start by inserting
 * a fixed Clock marker row (a permanent, non-undoable split #0 outside the normal sequence,
 * consuming a real line number but not the display counter — see [CLOCK_SPLIT_NUMBER]) *and*
 * setting their own [RaceEntity] started-at timestamp, but each writes to a different pair of
 * columns ([ModeProgressColumns] is only wired to one mode's own display counter, not its
 * started-at field) — different enough that forcing this into one shared method wouldn't
 * actually remove duplication, just hide it behind one signature. Each mode's own thin
 * repository keeps its own trivial start method instead (`BibsModeRepository.startBibsMode`/
 * `CpModeRepository.startCpMode`).
 */
internal class EntryLogModeEngine(
    private val mode: HistoryMode,
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    private val columns: ModeProgressColumns,
) {
    // Only the current segment (since the most recent Reset, if any) — for the live screen.
    // Folded (see HistoryFold): Undo/Edit no longer delete/mutate rows, they append an
    // undo-marker or edit-echo instead, so the raw DAO rows must be collapsed down to "one row
    // per still-visible logical entry" before the screen ever sees them. MODE_START rows are
    // filtered out before folding — they're a boundary marker for Race History/the web app (see
    // HistoryAction.MODE_START's own doc), never meant for the live screen at all.
    fun observeCurrentSegmentEntries(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeCurrentSegment(raceId, mode, HistoryAction.RESET).map {
            foldLatestVisible(
                it.filter { e -> e.action != HistoryAction.MODE_START },
                { e -> e.lineNumber },
                { e -> e.refLineNumber },
                { e -> e.action == HistoryAction.UNDO },
            )
        }

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = historyLineDao.observeUnsyncedCountForRace(raceId, mode)

    // For EditEntryScreen's own one-shot load-by-id on open — a dedicated screen (reached by
    // navigating, not composed inline over the live list) has no already-loaded row of its own
    // to read from, unlike the old inline editor which just filtered the live screen's own
    // uiState.entries.
    suspend fun getEntry(id: Long): HistoryLineEntity? = historyLineDao.getById(id)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = historyLineDao.observeLastSyncedAtMillis(raceId, mode)

    // Resolves a batch of acked recordUuids back to their permanent lineNumbers — used to
    // attribute a BLE ack to specific history lines for per-line "synced to" bookkeeping.
    suspend fun getLineNumbersForUuids(recordUuids: List<String>): List<Long> =
        if (recordUuids.isEmpty()) emptyList() else historyLineDao.getLineNumbersForUuids(recordUuids)

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
    // across edits, exactly like Time Mode's split-label editing never touches its number.
    //
    // Append-only: rather than mutating the existing row, this inserts a new "echo" row that
    // copies every field from the row currently being edited (crucially timestampMillis and
    // splitNumber) with only bibNumber/action/note changed — the original stays untouched in
    // the permanent history. refLineNumber is flattened to the ROOT row (never an intermediate
    // echo) so reconstructing "what's visible" only ever needs one level of grouping (see
    // HistoryFold). Refuses to edit a row whose ROOT is a Stop/Reset/Undo marker, and pins the
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
                    recordUuid = UUID.randomUUID().toString(),
                    syncedAtMillis = null,
                ),
            )
            raceDao.incrementLineNumber(existing.raceId)
        }
    }

    // Scoped to the current segment: a Reset marker (and everything before it) is never
    // reachable here once a new segment has started — Reset itself is therefore never
    // reachable to undo, with no separate guard needed for it.
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
            // MODE_START excluded, same as observeCurrentSegmentEntries — it must never become
            // an undo target (the operator can't even see it to know it's there).
            val raw = historyLineDao.getCurrentSegmentSnapshot(raceId, mode, HistoryAction.RESET)
                .filter { it.action != HistoryAction.MODE_START }
            val folded = foldLatestVisible(raw, { e -> e.lineNumber }, { e -> e.refLineNumber }, { e -> e.action == HistoryAction.UNDO })
            val target = folded.firstOrNull() ?: return@withTransaction
            val rootLineNumber = target.refLineNumber ?: target.lineNumber
            val root = raw.first { it.lineNumber == rootLineNumber }
            // Neither mode's own Start-time Clock marker can ever be undone — it's the fixed
            // split #0 anchor everything else in the segment is relative to.
            if (root.action == HistoryAction.CLOCK) return@withTransaction
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
            if (root.action == HistoryAction.STOP) {
                columns.clearStoppedAt(raceId)
            }
            // Neither RETIRE nor STOP ever consumed the counter in the first place (see
            // recordEntry/stop()/NO_SPLIT_ACTIONS), so undoing one must not decrement it either.
            if (root.action !in NO_SPLIT_ACTIONS) {
                columns.decrementCounter(raceId)
            }
        }
    }

    // Stop is a boundary marker for the web app's own later segmentation, not a real logged
    // entry — like a RETIRE row, it gets no splitNumber and doesn't consume the display counter
    // (see NO_SPLIT_ACTIONS' own doc), so the very next real entry (after a Reset) still gets
    // the number Stop would otherwise have taken.
    suspend fun stop(raceId: Long, stoppedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            columns.setStoppedAt(raceId, stoppedAtMillis)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = mode,
                    action = HistoryAction.STOP,
                    bibNumber = null,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    note = null,
                    timestampMillis = stoppedAtMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    // Inserts a Reset marker (consuming a permanent line number, same as every other row, but
    // — like Stop above — no splitNumber, since it's a boundary marker for the web app's own
    // later segmentation rather than a real logged entry) instead of deleting anything — every
    // prior entry/marker for this race stays in the table untouched, only now excluded from the
    // live screen's current-segment view. Still resets the display counter/stopped state to
    // their pre-start defaults via columns.resetCounters, same as before.
    suspend fun reset(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
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
            columns.resetCounters(raceId)
        }
    }
}
