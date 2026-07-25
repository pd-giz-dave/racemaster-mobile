package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import mobile.racemaster.data.db.entity.KnownDeviceEntity

@Dao
interface KnownDeviceDao {
    // REPLACE, not IGNORE — a device re-resolving must refresh its name (an operator can
    // rename a device between sessions) and lastSeenAtMillis, not stick with whatever was
    // first recorded.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: KnownDeviceEntity)

    @Query("SELECT * FROM known_devices ORDER BY lastSeenAtMillis DESC")
    fun observeAll(): Flow<List<KnownDeviceEntity>>

    // Always safe to call, whether or not deviceId is currently known here (e.g. an
    // unresolved BLE ghost's raw address was never actually upserted) — a DELETE matching no
    // row is a harmless no-op, same convention as PulledRecordDao.deleteForSource.
    @Query("DELETE FROM known_devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}
