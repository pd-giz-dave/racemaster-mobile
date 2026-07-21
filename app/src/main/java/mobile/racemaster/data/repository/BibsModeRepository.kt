package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Root row kinds that must never be edited or undone through the generic path — Undo/Edit
// guards below key off this set, keyed off the ROOT row (never the target/echo) so the
// guard holds even if a bug elsewhere let an echo's displayed action drift from its root.
private val NON_EDITABLE_ROOT_ACTIONS = setOf(HistoryAction.STOP, HistoryAction.RESET, HistoryAction.UNDO)

class BibsModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
) {
    // Only the current segment (since the most recent Reset, if any) — for the live screen.
    // Folded (see HistoryFold): Undo/Edit no longer delete/mutate rows, they append an
    // undo-marker or edit-echo instead, so the raw DAO rows must be collapsed down to "one
    // row per still-visible logical entry" before the screen ever sees them.
    fun observeCurrentSegmentEntries(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeCurrentSegment(raceId, HistoryMode.BIBS, HistoryAction.RESET).map {
            foldLatestVisible(it, { e -> e.lineNumber }, { e -> e.refLineNumber }, { e -> e.action == HistoryAction.UNDO })
        }

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.BIBS)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.BIBS)

    // One-shot snapshot for self-pull into this device's own Mule inbox, not a live
    // subscription — this stays syncedAtMillis-driven (not lineNumber-based) since it's a
    // purely local "what's new to add to my own inbox" decision, independent of what any
    // remote puller has separately requested via delta.
    suspend fun getUnsyncedEntries(raceId: Long): List<HistoryLineEntity> = historyLineDao.getUnsyncedForRace(raceId, HistoryMode.BIBS)

    suspend fun markEntriesSyncedByUuid(recordUuids: List<String>, syncedAtMillis: Long = System.currentTimeMillis()) {
        if (recordUuids.isEmpty()) return
        historyLineDao.markSynced(recordUuids, syncedAtMillis)
    }

    // Resolves a batch of acked recordUuids back to their permanent lineNumbers — used to
    // attribute a BLE ack to specific history lines for per-line "synced to" bookkeeping.
    suspend fun getLineNumbersForUuids(recordUuids: List<String>): List<Long> =
        if (recordUuids.isEmpty()) emptyList() else historyLineDao.getLineNumbersForUuids(recordUuids)

    // The Clock marker is a fixed split #0 outside the normal 1,2,3... sequence, so it doesn't
    // consume the display counter — it still consumes a permanent line number, same as every
    // other row. Deliberately a separate, explicit action rather than something race creation
    // (or a mode switch onto an already-active race — see ModePickerViewModel) does
    // automatically: this is what makes opening Bibs Mode side-effect-free to just look at,
    // exactly like Time Mode's own Start button — nothing is written until the operator
    // actually presses Start.
    suspend fun startBibsMode(raceId: Long, startedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.BIBS,
                    action = HistoryAction.CLOCK,
                    bibNumber = null,
                    splitNumber = CLOCK_SPLIT_NUMBER,
                    lineNumber = race.nextLineNumber,
                    note = null,
                    timestampMillis = startedAtMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    suspend fun recordEntry(
        raceId: Long,
        action: HistoryAction,
        bibNumber: Int?,
        note: String?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = race.bibsModeNextSplit
            raceDao.incrementBibsCounter(raceId)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.BIBS,
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
    // HistoryFold). Refuses to edit a row whose ROOT is a Stop/Reset/Undo marker (closes a
    // pre-existing hole — the UI additionally never offers these rows for editing in the first
    // place, see BibsModeScreen; this is the belt-and-braces backstop), and pins the action to
    // CLOCK if the root is a Clock row — defense-in-depth so updateClockTime's CLOCK-only
    // note-edit path can never be defeated by a direct updateEntry call with a different action,
    // even though today's UI never attempts this.
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
    // whole logical entry — the original row(s) stay untouched in the permanent history.
    // "Undo" always targets the top of the FOLDED list (the most recent still-visible logical
    // entry), not literally the last-appended raw row, which could itself be an edit-echo of
    // an OLDER entry — undoing "the last thing that happened" means hiding that entry
    // entirely, never partially reverting an edit. Race-state side effects are keyed off the
    // ROOT row's action, never the target's, for the same robustness reason as
    // TimeModeRepository.undoMostRecent.
    suspend fun undoMostRecent(raceId: Long) {
        db.withTransaction {
            val raw = historyLineDao.getCurrentSegmentSnapshot(raceId, HistoryMode.BIBS, HistoryAction.RESET)
            val folded = foldLatestVisible(raw, { e -> e.lineNumber }, { e -> e.refLineNumber }, { e -> e.action == HistoryAction.UNDO })
            val target = folded.firstOrNull() ?: return@withTransaction
            val rootLineNumber = target.refLineNumber ?: target.lineNumber
            val root = raw.first { it.lineNumber == rootLineNumber }
            if (root.action == HistoryAction.CLOCK) return@withTransaction
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.BIBS,
                    action = HistoryAction.UNDO,
                    // Copied from the root row purely so the undo marker itself can show which
                    // bib got undone (see HistoryLineEntity.bibNumber) — never treated as a
                    // real bib record itself, since UNDO isn't in BIB_REQUIRED_ACTIONS.
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
                raceDao.clearBibsModeStoppedAt(raceId)
            }
            raceDao.decrementBibsCounter(raceId)
        }
    }

    suspend fun stopBibsMode(raceId: Long, stoppedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = race.bibsModeNextSplit
            raceDao.incrementBibsCounter(raceId)
            raceDao.setBibsModeStoppedAt(raceId, stoppedAtMillis)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.BIBS,
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
    // entry/marker for this race stays in the table untouched, only now excluded from the
    // live screen's current-segment view. Still resets the display counter/clock state to
    // their pre-start defaults, same as before. Leaves the new segment with no Clock row —
    // same as a freshly created race, this screen falls back to its Start button until the
    // operator presses it again (see BibsModeViewModel/Screen).
    suspend fun resetBibsMode(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.BIBS,
                    action = HistoryAction.RESET,
                    bibNumber = null,
                    splitNumber = race.bibsModeNextSplit,
                    lineNumber = race.nextLineNumber,
                    note = null,
                    timestampMillis = resetAtMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
            raceDao.resetBibsMode(raceId)
        }
    }

    companion object {
        const val CLOCK_SPLIT_NUMBER = 0
    }
}
