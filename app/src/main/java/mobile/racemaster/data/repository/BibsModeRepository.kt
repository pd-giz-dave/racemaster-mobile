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

private class BibsProgressColumns(private val raceDao: RaceDao) : ModeProgressColumns {
    override fun nextSplitOf(race: RaceEntity) = race.bibsModeNextSplit
    override fun stoppedAtOf(race: RaceEntity) = race.bibsModeStoppedAtMillis
    override suspend fun incrementCounter(raceId: Long) = raceDao.incrementBibsCounter(raceId)
    override suspend fun decrementCounter(raceId: Long) = raceDao.decrementBibsCounter(raceId)
    override suspend fun setStoppedAt(raceId: Long, stoppedAtMillis: Long) = raceDao.setBibsModeStoppedAt(raceId, stoppedAtMillis)
    override suspend fun clearStoppedAt(raceId: Long) = raceDao.clearBibsModeStoppedAt(raceId)
    override suspend fun resetCounters(raceId: Long) = raceDao.resetBibsMode(raceId)
}

/** Bibs Mode's own thin wrapper around [EntryLogModeEngine] — every method here is a direct
 *  delegation except [startBibsMode], which is genuinely Bibs-specific (see
 *  [EntryLogModeEngine]'s own doc for why "start" isn't part of the shared engine). Public
 *  method names/signatures are unchanged from before this class was generalized, so nothing
 *  downstream (BibsModeViewModel, AppContainer, ...) needed to change. */
class BibsModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
) {
    private val engine = EntryLogModeEngine(HistoryMode.BIBS, db, raceDao, historyLineDao, BibsProgressColumns(raceDao))

    fun observeCurrentSegmentEntries(raceId: Long): Flow<List<HistoryLineEntity>> = engine.observeCurrentSegmentEntries(raceId)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = engine.observeUnsyncedCount(raceId)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = engine.observeLastSyncedAtMillis(raceId)

    suspend fun getLineNumbersForUuids(recordUuids: List<String>): List<Long> = engine.getLineNumbersForUuids(recordUuids)

    // The Clock marker is a fixed split #0 outside the normal 1,2,3... sequence, so it doesn't
    // consume the display counter — it still consumes a permanent line number, same as every
    // other row. Deliberately a separate, explicit action rather than something race creation
    // (or a mode switch onto an already-active race — see ModePickerViewModel) does
    // automatically: this is what makes opening Bibs Mode side-effect-free to just look at,
    // exactly like Time Mode's own Start button — nothing is written until the operator
    // actually presses Start. (CP Mode's own start, by contrast, writes no row at all — see
    // CpModeRepository.startCpMode.) Also sets RaceEntity.bibsModeStartedAtMillis, exactly like
    // Time/CP's own start methods — the Clock row above is still written (it's a real logged
    // event, kept for the permanent record), but is no longer what "started" is derived from;
    // see that field's own doc for why.
    suspend fun startBibsMode(raceId: Long, startedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            raceDao.setBibsModeStartedAt(raceId, startedAtMillis)
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

    suspend fun recordEntry(raceId: Long, action: HistoryAction, bibNumber: Int?, note: String?, timestampMillis: Long = System.currentTimeMillis()) =
        engine.recordEntry(raceId, action, bibNumber, note, timestampMillis)

    suspend fun updateEntry(id: Long, bibNumber: Int?, action: HistoryAction, note: String?) = engine.updateEntry(id, bibNumber, action, note)

    suspend fun getEntry(id: Long): HistoryLineEntity? = engine.getEntry(id)

    suspend fun undoMostRecent(raceId: Long) = engine.undoMostRecent(raceId)

    suspend fun stopBibsMode(raceId: Long, stoppedAtMillis: Long = System.currentTimeMillis()) = engine.stop(raceId, stoppedAtMillis)

    suspend fun resetBibsMode(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) = engine.reset(raceId, resetAtMillis)

    companion object {
        const val CLOCK_SPLIT_NUMBER = 0
    }
}
