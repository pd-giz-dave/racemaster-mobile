package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode
import kotlinx.coroutines.flow.Flow

private class TimeProgressColumns(private val raceDao: RaceDao) : ModeProgressColumns {
    override fun nextSplitOf(race: RaceEntity) = race.timeModeNextSplit
    override suspend fun incrementCounter(raceId: Long) = raceDao.incrementTimeCounter(raceId)
    override suspend fun decrementCounter(raceId: Long) = raceDao.decrementTimeCounter(raceId)
    override suspend fun resetCounters(raceId: Long) = raceDao.resetTimeMode(raceId)
    override suspend fun setCounterTo(raceId: Long, value: Int) = raceDao.setTimeModeNextSplit(raceId, value)
    override suspend fun clearStartedAt(raceId: Long) = raceDao.clearTimeModeStartedAt(raceId)
}

// Root row kinds that must never be edited through updateNote, keyed off the ROOT row (never
// the target/echo) so the guard holds even if a bug elsewhere let an echo's displayed action
// drift from its root. Unlike EntryLogModeEngine's own NON_EDITABLE_ROOT_ACTIONS, this includes
// START: Time's own fixed Start marker is never editable at all (not even its note), whereas
// Bibs' equivalent Clock marker IS editable via the generic engine path (see
// EntryLogModeEngine.updateEntry's own CLOCK-pinning doc) — a deliberate asymmetry, not drift.
private val NON_EDITABLE_ROOT_ACTIONS = setOf(
    HistoryAction.START, HistoryAction.RESET, HistoryAction.UNDO,
    HistoryAction.MODE_START, HistoryAction.LOCATION, HistoryAction.NEW_RACE, HistoryAction.PING,
)

/** Time Mode's own thin wrapper around [EntryLogModeEngine] — every method here is a direct
 *  delegation except [startStopwatch] (genuinely Time-specific, same reason Bibs' own
 *  `startBibsMode` isn't part of the shared engine — see [EntryLogModeEngine]'s own doc) and
 *  [updateNote] (a narrower, note-only edit with its own editability rule — see
 *  [NON_EDITABLE_ROOT_ACTIONS]'s own doc for how it differs from the engine's generic
 *  [EntryLogModeEngine.updateEntry]). Public method names/signatures are unchanged from before
 *  this class was collapsed onto the engine, so nothing downstream needed to change. */
class TimeModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    raceRepository: RaceRepository,
) {
    private val engine = EntryLogModeEngine(
        HistoryMode.TIME, AppMode.TIME, db, raceDao, historyLineDao, raceRepository,
        TimeProgressColumns(raceDao), startMarkerAction = HistoryAction.START,
    )

    fun observeCurrentSegmentSplits(raceId: Long): Flow<List<HistoryLineEntity>> = engine.observeCurrentSegmentEntries(raceId)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = engine.observeUnsyncedCount(raceId)

    // For EditSplitScreen's own one-shot load-by-id on open — a dedicated screen (reached by
    // navigating, not composed inline over the live list) has no already-loaded row of its own
    // to read from, unlike the old inline editor which just filtered the live screen's own
    // uiState.splits.
    suspend fun getSplit(id: Long): HistoryLineEntity? = engine.getEntry(id)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = engine.observeLastSyncedAtMillis(raceId)

    // The start marker is a fixed split #0 outside the normal 1,2,3... sequence, so it
    // doesn't consume the display counter — it still consumes a permanent line number, same
    // as every other row. Its own MODE_START boundary marker (see that action's own doc) is
    // written earlier, up front, by RaceRepository.recordModeStart (Setup Race / Relocate) —
    // this only writes the real Start marker itself, the moment recording actually begins.
    // ensureOpenSegment is a defensive self-heal, normally a no-op — see its own doc — for the
    // one case where this mode's current location has no open segment left to write into
    // (pressing Start again right after fully Resetting it, without first Relocating away).
    suspend fun startStopwatch(raceId: Long, startedAtMillis: Long = System.currentTimeMillis()) {
        engine.ensureOpenSegment(raceId)
        db.withTransaction {
            val race = raceDao.getById(raceId) ?: return@withTransaction
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

    suspend fun recordSplit(raceId: Long, timestampMillis: Long = System.currentTimeMillis()) =
        engine.recordEntry(raceId, HistoryAction.SPLIT, bibNumber = null, note = null, timestampMillis)

    // Narrower than EntryLogModeEngine.updateEntry: only the note ever changes here (splitNumber/
    // action never do — Time has no bib/duplicate concept, and the UI never offers an action
    // picker for a split), so this stays its own small method rather than delegating. Append-only:
    // rather than mutating the existing row, this inserts a new "echo" row that copies every field
    // from the row currently being edited (crucially timestampMillis and splitNumber, so elapsed-
    // time math and display position never shift because of a note edit) with only the note
    // changed — the original stays untouched in the permanent history. refLineNumber is flattened
    // to the ROOT row (never an intermediate echo) so reconstructing "what's visible" only ever
    // needs one level of grouping (see HistoryFold). Refuses to edit a row whose ROOT is a
    // Start/Reset/Undo marker (see NON_EDITABLE_ROOT_ACTIONS) — the UI additionally never offers
    // this row for editing in the first place (see TimeModeScreen), this is the belt-and-braces
    // backstop.
    suspend fun updateNote(splitId: Long, note: String?) {
        val trimmed = note?.trim()?.ifBlank { null }
        db.withTransaction {
            val existing = historyLineDao.getById(splitId) ?: return@withTransaction
            val rootLineNumber = existing.refLineNumber ?: existing.lineNumber
            val root = historyLineDao.getByLineNumber(existing.raceId, rootLineNumber) ?: return@withTransaction
            if (root.action in NON_EDITABLE_ROOT_ACTIONS) return@withTransaction
            val race = raceDao.getById(existing.raceId) ?: return@withTransaction
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
    // back to their pre-start defaults.
    suspend fun resetStopwatch(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) = engine.reset(raceId, resetAtMillis)

    suspend fun undoMostRecent(raceId: Long) = engine.undoMostRecent(raceId)

    companion object {
        const val START_SPLIT_NUMBER = 0
    }
}
