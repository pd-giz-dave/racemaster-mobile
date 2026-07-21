package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mobile.racemaster.data.db.entity.LineSyncEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LineSyncDao {
    // REPLACE, not IGNORE: a re-ack from the same target should refresh the timestamp rather
    // than being silently dropped by the unique (raceId, lineNumber, targetId) index.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<LineSyncEntity>)

    @Query("SELECT * FROM line_syncs WHERE raceId = :raceId ORDER BY lineNumber")
    fun observeForRace(raceId: Long): Flow<List<LineSyncEntity>>

    // No ForeignKey/CASCADE on this table (see the entity's own doc) — unlike HistoryLineEntity,
    // deleting a race needs this explicit cleanup alongside RaceDao.deleteById to avoid leaving
    // orphaned rows behind.
    @Query("DELETE FROM line_syncs WHERE raceId = :raceId")
    suspend fun deleteForRace(raceId: Long)
}
