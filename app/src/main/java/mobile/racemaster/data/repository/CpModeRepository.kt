package mobile.racemaster.data.repository

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
 *  [BibsModeRepository], just wired to CP's own [RaceEntity] columns and [HistoryMode.CP]. The
 *  one genuine difference from Bibs is [startCpMode]: unlike Bibs' Clock-marker insert, CP has
 *  no marker row at all — starting is purely setting [RaceEntity.cpModeStartedAtMillis], which
 *  is what [mobile.racemaster.ui.cpmode.CpModeViewModel] reads back to decide whether to show
 *  the Start button or the entry keypad (see that field's own doc for why, unlike Bibs, this
 *  can't be derived from "any entries at all"). */
class CpModeRepository(
    db: RacemasterDatabase,
    private val raceDao: RaceDao,
    historyLineDao: HistoryLineDao,
) {
    private val engine = EntryLogModeEngine(HistoryMode.CP, db, raceDao, historyLineDao, CpProgressColumns(raceDao))

    fun observeCurrentSegmentEntries(raceId: Long): Flow<List<HistoryLineEntity>> = engine.observeCurrentSegmentEntries(raceId)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = engine.observeUnsyncedCount(raceId)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = engine.observeLastSyncedAtMillis(raceId)

    suspend fun getLineNumbersForUuids(recordUuids: List<String>): List<Long> = engine.getLineNumbersForUuids(recordUuids)

    suspend fun startCpMode(raceId: Long, startedAtMillis: Long = System.currentTimeMillis()) {
        raceDao.setCpModeStartedAt(raceId, startedAtMillis)
    }

    suspend fun recordEntry(raceId: Long, action: HistoryAction, bibNumber: Int?, note: String?, timestampMillis: Long = System.currentTimeMillis()) =
        engine.recordEntry(raceId, action, bibNumber, note, timestampMillis)

    suspend fun updateEntry(id: Long, bibNumber: Int?, action: HistoryAction, note: String?) = engine.updateEntry(id, bibNumber, action, note)

    suspend fun undoMostRecent(raceId: Long) = engine.undoMostRecent(raceId)

    suspend fun stopCpMode(raceId: Long, stoppedAtMillis: Long = System.currentTimeMillis()) = engine.stop(raceId, stoppedAtMillis)

    suspend fun resetCpMode(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) = engine.reset(raceId, resetAtMillis)
}
