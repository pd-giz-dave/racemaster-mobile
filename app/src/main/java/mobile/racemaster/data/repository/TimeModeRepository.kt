package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Root row kinds that must never be edited or undone through the generic path — Undo/Edit
// guards below key off this set, keyed off the ROOT row (never the target/echo) so the
// guard holds even if a bug elsewhere let an echo's displayed action drift from its root.
private val NON_EDITABLE_ROOT_ACTIONS = setOf(HistoryAction.START, HistoryAction.STOP, HistoryAction.RESET, HistoryAction.UNDO)

class TimeModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
) {
    // Only the current segment (since the most recent Reset, if any) — for the live screen.
    // Folded (see HistoryFold): Undo/Edit no longer delete/mutate rows, they append an
    // undo-marker or edit-echo instead, so the raw DAO rows must be collapsed down to "one
    // row per still-visible logical entry" before the screen ever sees them.
    fun observeCurrentSegmentSplits(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeCurrentSegment(raceId, HistoryMode.TIME, HistoryAction.RESET).map {
            foldLatestVisible(it, { s -> s.lineNumber }, { s -> s.refLineNumber }, { s -> s.action == HistoryAction.UNDO })
        }

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.TIME)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.TIME)

    // One-shot snapshot for self-pull into this device's own Mule inbox, not a live
    // subscription — this stays syncedAtMillis-driven (not lineNumber-based) since it's a
    // purely local "what's new to add to my own inbox" decision, independent of what any
    // remote puller has separately requested via delta.
    suspend fun getUnsyncedSplits(raceId: Long): List<HistoryLineEntity> = historyLineDao.getUnsyncedForRace(raceId, HistoryMode.TIME)

    suspend fun markSplitsSyncedByUuid(recordUuids: List<String>, syncedAtMillis: Long = System.currentTimeMillis()) {
        if (recordUuids.isEmpty()) return
        historyLineDao.markSynced(recordUuids, syncedAtMillis)
    }

    // Resolves a batch of acked recordUuids back to their permanent lineNumbers — used to
    // attribute a BLE ack to specific history lines for per-line "synced to" bookkeeping.
    suspend fun getLineNumbersForUuids(recordUuids: List<String>): List<Long> =
        if (recordUuids.isEmpty()) emptyList() else historyLineDao.getLineNumbersForUuids(recordUuids)

    // The start marker is a fixed split #0 outside the normal 1,2,3... sequence, so it
    // doesn't consume the display counter — it still consumes a permanent line number, same
    // as every other row.
    suspend fun startStopwatch(raceId: Long, startedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            raceDao.setTimeModeStartedAt(raceId, startedAtMillis)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.TIME,
                    action = HistoryAction.START,
                    splitNumber = START_SPLIT_NUMBER,
                    lineNumber = race.nextLineNumber,
                    timestampMillis = startedAtMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    // The stop marker consumes the next split number like a normal split, so it sorts
    // naturally as the last entry and undoes the same way a normal split would.
    suspend fun stopStopwatch(raceId: Long, stoppedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = race.timeModeNextSplit
            raceDao.incrementTimeCounter(raceId)
            raceDao.setTimeModeStoppedAt(raceId, stoppedAtMillis)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.TIME,
                    action = HistoryAction.STOP,
                    splitNumber = splitNumber,
                    lineNumber = race.nextLineNumber,
                    timestampMillis = stoppedAtMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    suspend fun recordSplit(raceId: Long, timestampMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = race.timeModeNextSplit
            raceDao.incrementTimeCounter(raceId)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.TIME,
                    action = HistoryAction.SPLIT,
                    splitNumber = splitNumber,
                    lineNumber = race.nextLineNumber,
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    // Append-only: rather than mutating the existing row, this inserts a new "echo" row that
    // copies every field from the row currently being edited (crucially timestampMillis and
    // splitNumber, so elapsed-time math and display position never shift because of a note
    // edit) with only the note changed — the original stays untouched in the permanent
    // history. refLineNumber is flattened to the ROOT row (never an intermediate echo) so
    // reconstructing "what's visible" only ever needs one level of grouping (see HistoryFold).
    // Refuses to edit a row whose ROOT is a Start/Stop/Reset/Undo marker — the UI additionally
    // never offers this row for editing in the first place (see TimeModeScreen), this is the
    // belt-and-braces backstop. Since markers now live in `action` (not `note`), there's no
    // longer any reserved-string collision to guard against here — note is purely free text.
    suspend fun updateNote(splitId: Long, note: String?) {
        val trimmed = note?.trim()?.ifBlank { null }
        db.withTransaction {
            val existing = historyLineDao.getById(splitId) ?: return@withTransaction
            val rootLineNumber = existing.refLineNumber ?: existing.lineNumber
            val root = historyLineDao.getByLineNumber(existing.raceId, rootLineNumber) ?: return@withTransaction
            if (root.action in NON_EDITABLE_ROOT_ACTIONS) return@withTransaction
            val race = requireNotNull(raceDao.getById(existing.raceId)) { "Race ${existing.raceId} not found" }
            historyLineDao.insert(
                existing.copy(
                    id = 0,
                    lineNumber = race.nextLineNumber,
                    note = trimmed,
                    refLineNumber = rootLineNumber,
                    recordUuid = UUID.randomUUID().toString(),
                    syncedAtMillis = null,
                ),
            )
            raceDao.incrementLineNumber(existing.raceId)
        }
    }

    // Inserts a Reset marker (consuming the current display-counter value + a permanent line
    // number, exactly like Stop already does) instead of deleting anything — every prior
    // split/marker for this race stays in the table untouched, only now excluded from the
    // live screen's current-segment view. Still resets the display counter/clock state to
    // their pre-start defaults, same as before.
    suspend fun resetStopwatch(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.TIME,
                    action = HistoryAction.RESET,
                    splitNumber = race.timeModeNextSplit,
                    lineNumber = race.nextLineNumber,
                    timestampMillis = resetAtMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
            raceDao.resetTimeMode(raceId)
        }
    }

    // Undoing the start/stop markers reverts the corresponding race state so the operator
    // isn't left stuck: undoing "Stop" resumes the live clock, undoing "Start" (only
    // reachable once every real split has also been undone) returns to the Start screen.
    // Scoped to the current segment: a Reset marker (and everything before it) is never
    // reachable here once a new segment has started, since it falls outside the folded
    // current-segment view's boundary the moment there's a newer row.
    //
    // Append-only: rather than deleting the target row, this inserts an "undo marker"
    // (action = UNDO, refLineNumber = the target's ROOT) that HistoryFold treats as hiding its
    // whole logical entry — the original row(s) stay untouched in the permanent history.
    // "Undo" always targets the top of the FOLDED list (the most recent still-visible logical
    // entry), not literally the last-appended raw row — that raw row could itself be an
    // edit-echo of an OLDER entry, and undoing "the last thing that happened" (an edit) is
    // defined as hiding that entry entirely, not partially reverting the edit. Repeated Undo
    // presses therefore peel the visible list from the top, one logical entry per press,
    // exactly like the old delete-based behavior did.
    //
    // Race-state side effects are keyed off the ROOT row's action, never the target's — the
    // root is structurally guaranteed immutable (only ever created by startStopwatch/
    // stopStopwatch/resetStopwatch), so this stays correct even if the target is itself an
    // edited echo whose displayed content no longer matches its original semantic.
    suspend fun undoMostRecent(raceId: Long) {
        db.withTransaction {
            val raw = historyLineDao.getCurrentSegmentSnapshot(raceId, HistoryMode.TIME, HistoryAction.RESET)
            val folded = foldLatestVisible(raw, { s -> s.lineNumber }, { s -> s.refLineNumber }, { s -> s.action == HistoryAction.UNDO })
            val target = folded.firstOrNull() ?: return@withTransaction
            val rootLineNumber = target.refLineNumber ?: target.lineNumber
            val root = raw.first { it.lineNumber == rootLineNumber }
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.TIME,
                    action = HistoryAction.UNDO,
                    splitNumber = root.splitNumber,
                    lineNumber = race.nextLineNumber,
                    timestampMillis = System.currentTimeMillis(),
                    refLineNumber = rootLineNumber,
                ),
            )
            raceDao.incrementLineNumber(raceId)
            when (root.action) {
                HistoryAction.START -> raceDao.clearTimeModeStartedAt(raceId)
                HistoryAction.STOP -> {
                    raceDao.clearTimeModeStoppedAt(raceId)
                    raceDao.decrementTimeCounter(raceId)
                }
                else -> raceDao.decrementTimeCounter(raceId)
            }
        }
    }

    companion object {
        const val START_SPLIT_NUMBER = 0
    }
}
