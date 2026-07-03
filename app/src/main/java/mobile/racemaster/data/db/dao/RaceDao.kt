package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RaceDao {
    @Insert
    suspend fun insert(race: RaceEntity): Long

    @Query("SELECT * FROM races WHERE id = :id")
    suspend fun getById(id: Long): RaceEntity?

    @Query("SELECT * FROM races WHERE id = :id")
    fun observeById(id: Long): Flow<RaceEntity?>

    @Query("SELECT * FROM races ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<RaceEntity>>

    @Query("UPDATE races SET timeModeNextSplit = timeModeNextSplit + 1 WHERE id = :raceId")
    suspend fun incrementTimeCounter(raceId: Long)

    @Query("UPDATE races SET timeModeNextSplit = timeModeNextSplit - 1 WHERE id = :raceId")
    suspend fun decrementTimeCounter(raceId: Long)

    @Query("UPDATE races SET bibsModeNextSplit = bibsModeNextSplit + 1 WHERE id = :raceId")
    suspend fun incrementBibsCounter(raceId: Long)

    @Query("UPDATE races SET bibsModeNextSplit = bibsModeNextSplit - 1 WHERE id = :raceId")
    suspend fun decrementBibsCounter(raceId: Long)

    @Query("UPDATE races SET timeModeStartedAtMillis = :startedAtMillis WHERE id = :raceId")
    suspend fun setTimeModeStartedAt(raceId: Long, startedAtMillis: Long)

    @Query("UPDATE races SET timeModeStoppedAtMillis = :stoppedAtMillis WHERE id = :raceId")
    suspend fun setTimeModeStoppedAt(raceId: Long, stoppedAtMillis: Long)

    @Query("UPDATE races SET timeModeStartedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearTimeModeStartedAt(raceId: Long)

    @Query("UPDATE races SET timeModeStoppedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearTimeModeStoppedAt(raceId: Long)

    @Query(
        "UPDATE races SET timeModeNextSplit = 1, timeModeStartedAtMillis = NULL, " +
            "timeModeStoppedAtMillis = NULL WHERE id = :raceId",
    )
    suspend fun resetTimeMode(raceId: Long)

    @Query("UPDATE races SET bibsModeStoppedAtMillis = :stoppedAtMillis WHERE id = :raceId")
    suspend fun setBibsModeStoppedAt(raceId: Long, stoppedAtMillis: Long)

    @Query("UPDATE races SET bibsModeStoppedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearBibsModeStoppedAt(raceId: Long)

    @Query("UPDATE races SET bibsModeNextSplit = 1, bibsModeStoppedAtMillis = NULL WHERE id = :raceId")
    suspend fun resetBibsMode(raceId: Long)
}