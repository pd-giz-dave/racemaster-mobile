package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.BibEntryDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType
import kotlinx.coroutines.flow.Flow

class BibsModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val bibEntryDao: BibEntryDao,
) {
    fun observeEntries(raceId: Long): Flow<List<BibEntryEntity>> = bibEntryDao.observeForRace(raceId)

    suspend fun recordEntry(
        raceId: Long,
        bibNumber: Int,
        type: BibEntryType,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val splitNumber = if (type == BibEntryType.RETIRE) {
                null
            } else {
                val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
                raceDao.incrementBibsCounter(raceId)
                race.bibsModeNextSplit
            }
            bibEntryDao.insert(
                BibEntryEntity(
                    raceId = raceId,
                    bibNumber = bibNumber,
                    type = type,
                    splitNumber = splitNumber,
                    timestampMillis = timestampMillis,
                ),
            )
        }
    }

    suspend fun deleteMostRecent(raceId: Long) {
        db.withTransaction {
            val latest = bibEntryDao.getLatest(raceId) ?: return@withTransaction
            bibEntryDao.delete(latest)
            if (latest.splitNumber != null) raceDao.decrementBibsCounter(raceId)
        }
    }
}