package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.FinishSplitDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.FinishSplitEntity
import kotlinx.coroutines.flow.Flow

class TimeModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val finishSplitDao: FinishSplitDao,
) {
    fun observeSplits(raceId: Long): Flow<List<FinishSplitEntity>> = finishSplitDao.observeForRace(raceId)

    // The start marker is a fixed split #0 outside the normal 1,2,3... sequence, so it
    // doesn't consume the counter.
    suspend fun startStopwatch(raceId: Long, startedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            raceDao.setTimeModeStartedAt(raceId, startedAtMillis)
            finishSplitDao.insert(
                FinishSplitEntity(
                    raceId = raceId,
                    splitNumber = START_SPLIT_NUMBER,
                    timestampMillis = startedAtMillis,
                    label = START_LABEL,
                ),
            )
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
            finishSplitDao.insert(
                FinishSplitEntity(
                    raceId = raceId,
                    splitNumber = splitNumber,
                    timestampMillis = stoppedAtMillis,
                    label = STOP_LABEL,
                ),
            )
        }
    }

    suspend fun recordSplit(raceId: Long, timestampMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = race.timeModeNextSplit
            raceDao.incrementTimeCounter(raceId)
            finishSplitDao.insert(
                FinishSplitEntity(raceId = raceId, splitNumber = splitNumber, timestampMillis = timestampMillis),
            )
        }
    }

    suspend fun updateLabel(splitId: Long, label: String?) {
        finishSplitDao.updateLabel(splitId, label?.trim()?.ifBlank { null })
    }

    // Wipes every split (including the Start/Stop markers) and the counter/clock state for
    // this race, returning it to exactly the pre-Start state so the same race can be re-run.
    suspend fun resetStopwatch(raceId: Long) {
        db.withTransaction {
            finishSplitDao.deleteAllForRace(raceId)
            raceDao.resetTimeMode(raceId)
        }
    }

    // Undoing the start/stop markers reverts the corresponding race state so the operator
    // isn't left stuck: undoing "Stop" resumes the live clock, undoing "Start" (only
    // reachable once every real split has also been undone) returns to the Start screen.
    suspend fun deleteMostRecent(raceId: Long) {
        db.withTransaction {
            val latest = finishSplitDao.getLatest(raceId) ?: return@withTransaction
            finishSplitDao.delete(latest)
            when (latest.label) {
                START_LABEL -> raceDao.clearTimeModeStartedAt(raceId)
                STOP_LABEL -> {
                    raceDao.clearTimeModeStoppedAt(raceId)
                    raceDao.decrementTimeCounter(raceId)
                }
                else -> raceDao.decrementTimeCounter(raceId)
            }
        }
    }

    companion object {
        const val START_SPLIT_NUMBER = 0
        const val START_LABEL = "Start"
        const val STOP_LABEL = "Stop"
    }
}