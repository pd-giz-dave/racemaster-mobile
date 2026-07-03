package mobile.racemaster.data.repository

import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.flow.Flow

class RaceRepository(
    private val raceDao: RaceDao,
) {
    suspend fun startNewRace(label: String, createdAtMillis: Long = System.currentTimeMillis()): Long =
        raceDao.insert(RaceEntity(label = label, createdAtMillis = createdAtMillis))

    fun observeRace(id: Long): Flow<RaceEntity?> = raceDao.observeById(id)

    fun observeAllRaces(): Flow<List<RaceEntity>> = raceDao.observeAll()
}