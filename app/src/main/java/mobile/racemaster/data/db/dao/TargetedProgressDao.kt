package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mobile.racemaster.data.db.entity.TargetedProgressEntity

@Dao
interface TargetedProgressDao {
    // REPLACE on the unique targetDeviceId index (not just the primary key) — a fresher delta
    // for a target already being held simply supersedes the old row, see the entity's own doc.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TargetedProgressEntity)

    @Query("SELECT * FROM targeted_progress WHERE targetDeviceId = :targetDeviceId")
    suspend fun getByTarget(targetDeviceId: String): TargetedProgressEntity?

    @Query("DELETE FROM targeted_progress WHERE targetDeviceId = :targetDeviceId")
    suspend fun deleteByTarget(targetDeviceId: String)
}
