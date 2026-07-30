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

// Root row kinds that must never be edited or undone through the generic path — Undo/Edit
// guards below key off this set, keyed off the ROOT row (never the target/echo) so the guard
// holds even if a bug elsewhere let an echo's displayed action drift from its root.
private val NON_EDITABLE_ROOT_ACTIONS = setOf(HistoryAction.STOP, HistoryAction.RESET, HistoryAction.UNDO)

// Actions with no corresponding Time Mode split to align with, so they neither get a
// splitNumber nor consume the shared counter — see HistoryLineEntity.splitNumber's own doc.
// RETIRE never crosses the timing point at all (Bibs or CP). PASS joins it here for the same
// underlying reason even though it IS a real checkpoint crossing: unlike Bibs Mode's FINISH
// (paired with a Time Mode device running at the very same finish line — see README), CP
// Mode's checkpoints are never paired with a Time Mode device of their own, so a CP row's
// splitNumber never actually aligned with anything to begin with.
private val NO_SPLIT_ACTIONS = setOf(HistoryAction.RETIRE, HistoryAction.PASS)

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
 * Deliberately does **not** include a "start" method: Bibs Mode starts by inserting a fixed
 * Clock marker row (a whole extra concept — a permanent, non-undoable split #0 outside the
 * normal sequence); CP Mode starts by setting a plain [RaceEntity.cpModeStartedAtMillis]
 * timestamp, with no marker row at all. These are different enough operations that forcing them
 * into one shared method wouldn't actually remove duplication, just hide two genuinely different
 * things behind one signature — each mode's own thin repository keeps its own trivial start
 * method instead (`BibsModeRepository.startBibsMode`/`CpModeRepository.startCpMode`).
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
    // per still-visible logical entry" before the screen ever sees them.
    fun observeCurrentSegmentEntries(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeCurrentSegment(raceId, mode, HistoryAction.RESET).map {
            foldLatestVisible(it, { e -> e.lineNumber }, { e -> e.refLineNumber }, { e -> e.action == HistoryAction.UNDO })
        }

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = historyLineDao.observeUnsyncedCountForRace(raceId, mode)

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
    // action to CLOCK if the root is a Clock row — defense-in-depth so a Bibs Clock row's
    // note-only edit path can never be defeated by a direct updateEntry call with a different
    // action; this branch is simply never reachable for a CP row, which never has a Clock root.
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
            val raw = historyLineDao.getCurrentSegmentSnapshot(raceId, mode, HistoryAction.RESET)
            val folded = foldLatestVisible(raw, { e -> e.lineNumber }, { e -> e.refLineNumber }, { e -> e.action == HistoryAction.UNDO })
            val target = folded.firstOrNull() ?: return@withTransaction
            val rootLineNumber = target.refLineNumber ?: target.lineNumber
            val root = raw.first { it.lineNumber == rootLineNumber }
            // Never reachable for a CP row (CP never writes a Clock row) — this guard only ever
            // actually fires for Bibs Mode's own fixed split #0 marker.
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
            // Neither RETIRE nor PASS ever consumed the counter in the first place (see
            // recordEntry/NO_SPLIT_ACTIONS), so undoing one must not decrement it either.
            if (root.action !in NO_SPLIT_ACTIONS) {
                columns.decrementCounter(raceId)
            }
        }
    }

    suspend fun stop(raceId: Long, stoppedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = columns.nextSplitOf(race)
            columns.incrementCounter(raceId)
            columns.setStoppedAt(raceId, stoppedAtMillis)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = mode,
                    action = HistoryAction.STOP,
                    bibNumber = null,
                    splitNumber = splitNumber,
                    lineNumber = race.nextLineNumber,
                    note = null,
                    timestampMillis = stoppedAtMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    // Inserts a Reset marker (consuming the current display-counter value + a permanent line
    // number, exactly like Stop already does) instead of deleting anything — every prior
    // entry/marker for this race stays in the table untouched, only now excluded from the live
    // screen's current-segment view. Still resets the display counter/stopped state to their
    // pre-start defaults via columns.resetCounters, same as before.
    suspend fun reset(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = mode,
                    action = HistoryAction.RESET,
                    bibNumber = null,
                    splitNumber = columns.nextSplitOf(race),
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
