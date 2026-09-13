package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mobile.racemaster.data.db.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    // REPLACE, not IGNORE — a re-delivery of the same race's progress (a newer generatedAt, or
    // even an identical one re-sent) always supersedes whatever this device was already holding
    // for that raceId, same "last write wins" reasoning as LineSyncDao's own REPLACE.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProgressEntity)

    @Query("SELECT * FROM progress WHERE raceId = :raceId")
    suspend fun getByRaceId(raceId: Long): ProgressEntity?

    // Ordered most-recently-stored-first, matching RaceDao.observeAll's own ordering for the
    // same list (Race History shows both, and a consistent newest-first order across every
    // section there is what makes the page read as one coherent list rather than three
    // independently-sorted ones).
    @Query("SELECT * FROM progress ORDER BY storedAtMillis DESC")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE raceId = :raceId")
    suspend fun deleteByRaceId(raceId: Long)
}
