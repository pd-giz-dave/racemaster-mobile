package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.flow.Flow

private class CpProgressColumns(private val raceDao: RaceDao) : ModeProgressColumns {
    override fun nextSplitOf(race: RaceEntity) = race.cpModeNextSplit
    override fun stoppedAtOf(race: RaceEntity) = race.cpModeStoppedAtMillis
    override suspend fun incrementCounter(raceId: Long) = raceDao.incrementCpCounter(raceId)
    override suspend fun decrementCounter(raceId: Long) = raceDao.decrementCpCounter(raceId)
    override suspend fun setStoppedAt(raceId: Long, stoppedAtMillis: Long) = raceDao.setCpModeStoppedAt(raceId, stoppedAtMillis)
    override suspend fun clearStoppedAt(raceId: Long) = raceDao.clearCpModeStoppedAt(raceId)
    override suspend fun resetCounters(raceId: Long) = raceDao.resetCpMode(raceId)
}

/** CP Mode's own thin wrapper around [EntryLogModeEngine] — structurally identical to
 *  [BibsModeRepository], just wired to CP's own [RaceEntity] columns and [HistoryMode.CP],
 *  including [startCpMode]'s own Clock-marker insert (see [BibsModeRepository.startBibsMode]'s
 *  own doc — the same reasoning applies here): a start line the operator can see and, if
 *  needed, correct the time of, and the fixed split-#0 anchor every real Pass's own 1,2,3...
 *  sequence count (via cpModeNextSplit) counts up from — that sequence is what gives the
 *  operator a running "how many have passed since Start/Reset" figure, even though a
 *  checkpoint's own splitNumber has no timing meaning to align with the way Bibs Mode's FINISH
 *  does (see [EntryLogModeEngine]'s own NO_SPLIT_ACTIONS doc — RETIRE alone stays excluded,
 *  since it's never a "passed" event). Starting still separately sets
 *  [RaceEntity.cpModeStartedAtMillis], which is what
 *  [mobile.racemaster.ui.cpmode.CpModeViewModel] reads back to decide whether to show the Start
 *  button or the entry keypad (see that field's own doc for why this can't be derived from "any
 *  entries at all" the way Bibs' own started flag no longer is either). */
class CpModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
) {
    private val engine = EntryLogModeEngine(HistoryMode.CP, db, raceDao, historyLineDao, CpProgressColumns(raceDao))

    fun observeCurrentSegmentEntries(raceId: Long): Flow<List<HistoryLineEntity>> = engine.observeCurrentSegmentEntries(raceId)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = engine.observeUnsyncedCount(raceId)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = engine.observeLastSyncedAtMillis(raceId)

    suspend fun getLineNumbersForUuids(recordUuids: List<String>): List<Long> = engine.getLineNumbersForUuids(recordUuids)

    suspend fun startCpMode(raceId: Long, startedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            raceDao.setCpModeStartedAt(raceId, startedAtMillis)
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.CP,
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

    suspend fun recordEntry(raceId: Long, action: HistoryAction, bibNumber: Int?, note: String?, timestampMillis: Long = System.currentTimeMillis()) =
        engine.recordEntry(raceId, action, bibNumber, note, timestampMillis)

    suspend fun updateEntry(id: Long, bibNumber: Int?, action: HistoryAction, note: String?) = engine.updateEntry(id, bibNumber, action, note)

    suspend fun getEntry(id: Long): HistoryLineEntity? = engine.getEntry(id)

    suspend fun undoMostRecent(raceId: Long) = engine.undoMostRecent(raceId)

    suspend fun stopCpMode(raceId: Long, stoppedAtMillis: Long = System.currentTimeMillis()) = engine.stop(raceId, stoppedAtMillis)

    suspend fun resetCpMode(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) = engine.reset(raceId, resetAtMillis)
}
