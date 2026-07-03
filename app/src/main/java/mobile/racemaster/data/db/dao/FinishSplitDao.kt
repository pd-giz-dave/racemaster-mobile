package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import mobile.racemaster.data.db.entity.FinishSplitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FinishSplitDao {
    @Query("SELECT * FROM finish_splits WHERE raceId = :raceId ORDER BY splitNumber DESC")
    fun observeForRace(raceId: Long): Flow<List<FinishSplitEntity>>

    @Query("SELECT * FROM finish_splits WHERE raceId = :raceId ORDER BY splitNumber DESC LIMIT 1")
    suspend fun getLatest(raceId: Long): FinishSplitEntity?

    @Insert
    suspend fun insert(entry: FinishSplitEntity): Long

    @Delete
    suspend fun delete(entry: FinishSplitEntity)

    @Query("UPDATE finish_splits SET label = :label WHERE id = :splitId")
    suspend fun updateLabel(splitId: Long, label: String?)

    @Query("DELETE FROM finish_splits WHERE raceId = :raceId")
    suspend fun deleteAllForRace(raceId: Long)
}