package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.settings.AppMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Root row kinds that must never be edited or undone through the generic path — Undo/Edit
// guards below key off this set, keyed off the ROOT row (never the target/echo) so the
// guard holds even if a bug elsewhere let an echo's displayed action drift from its root.
// None of these are ever actually reachable here anyway (all excluded from the live view's own
// fold input — see HistoryFold.currentSegmentRows), but listed for the same belt-and-braces
// reason.
private val NON_EDITABLE_ROOT_ACTIONS = setOf(
    HistoryAction.START, HistoryAction.RESET, HistoryAction.UNDO,
    HistoryAction.MODE_START, HistoryAction.LOCATION, HistoryAction.NEW_RACE, HistoryAction.PING,
)

class TimeModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    private val raceRepository: RaceRepository,
) {
    // The live current-segment view — see HistoryFold.currentSegmentRows' own doc for exactly
    // what "current segment" now means (a LOCATION-anchored, walkable-by-Reset, resumable-by-
    // relocating-back union of one or more visits, no longer a single SQL RESET wall).
    fun observeCurrentSegmentSplits(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeAllForRaceAndMode(raceId, HistoryMode.TIME).map { raw -> currentSegment(raw) }

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

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.TIME)

    // For EditSplitScreen's own one-shot load-by-id on open — a dedicated screen (reached by
    // navigating, not composed inline over the live list) has no already-loaded row of its own
    // to read from, unlike the old inline editor which just filtered the live screen's own
    // uiState.splits.
    suspend fun getSplit(id: Long): HistoryLineEntity? = historyLineDao.getById(id)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.TIME)

    // The start marker is a fixed split #0 outside the normal 1,2,3... sequence, so it
    // doesn't consume the display counter — it still consumes a permanent line number, same
    // as every other row. Its own MODE_START boundary marker (see that action's own doc) is
    // written earlier, up front, by RaceRepository.recordModeStart (Setup Race / Relocate) —
    // this only writes the real Start marker itself, the moment recording actually begins.
    // ensureOpenSegment is a defensive self-heal, normally a no-op — see its own doc — for the
    // one case where this mode's current location has no open segment left to write into
    // (pressing Start again right after fully Resetting it, without first Relocating away).
    suspend fun startStopwatch(raceId: Long, startedAtMillis: Long = System.currentTimeMillis()) {
        raceRepository.ensureOpenSegment(raceId, AppMode.TIME)
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
    // Refuses to edit a row whose ROOT is a Start/Reset/Undo marker — the UI additionally
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
                    syncedAtMillis = null,
                ),
            )
            raceDao.incrementLineNumber(existing.raceId)
        }
    }

    // Closes the current segment (see RaceRepository.closeCurrentSegment's own doc — one or more
    // RESET rows, walkable by pressing Reset again) and clears the display counter/started-at
    // back to their pre-start defaults. True means this closed the race's own very first
    // segment, reverting the device to "no race set up" — the caller announces this to the
    // server right away (see TimeModeViewModel.resetStopwatch).
    suspend fun resetStopwatch(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()): Boolean =
        raceRepository.closeCurrentSegment(raceId, HistoryMode.TIME, resetAtMillis)

    // Resumes logging in place after "End recording" picks this same course again — see
    // EntryLogModeEngine.resume's own doc (Bibs/CP's identical sibling). A no-op now that there's
    // no separate stopped state left to clear (see HistoryAction's own doc) — kept as a function,
    // rather than removed outright, since TimeModeViewModel.startStopwatch's own
    // already-started/not-yet-reset branch still calls it, matching the identical call sites Bibs
    // and CP keep for the same reason.
    suspend fun resumeStopwatch(raceId: Long) {
    }

    // Undoing the start marker reverts the corresponding race state so the operator isn't left
    // stuck: undoing "Start" (only reachable once every real split has also been undone)
    // returns to the Start screen. Scoped to the current segment (see observeCurrentSegmentSplits
    // above and HistoryFold.currentSegmentRows) — a Reset marker (and everything it closed) is
    // never reachable here once that segment has been closed.
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
    // Race-state side effects are keyed off the ROOT row's action, never the target's, for the
    // same robustness reason.
    suspend fun undoMostRecent(raceId: Long) {
        db.withTransaction {
            val raw = historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.TIME)
            val current = currentSegment(raw)
            val target = current.firstOrNull() ?: return@withTransaction
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
                // A LOCATION root never merely "consumed one count" — its own forward write
                // reset (or resumed — see RaceRepository.recordModeStart) the counter, so
                // undoing it must restore whatever the counter (and RaceEntity.location) actually
                // were beforehand, never decrementTimeCounter — that would silently corrupt it
                // (this was the bug in this `when` before this branch existed: LOCATION fell into
                // `else` and got wrongly decremented).
                HistoryAction.LOCATION -> {
                    root.priorSplitCounter?.let { raceDao.setTimeModeNextSplit(raceId, it) }
                    root.previousLocation?.let { raceDao.updateModeAndLocation(raceId, root.previousMode, it) }
                }
                else -> raceDao.decrementTimeCounter(raceId)
            }
        }
    }

    companion object {
        const val START_SPLIT_NUMBER = 0
    }
}
