package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mobile.racemaster.data.db.entity.PulledRecordEntity
import kotlinx.coroutines.flow.Flow

// One row per distinct source device/race Mule has ever pulled from — the accumulated
// history view, not just what's currently sitting unsynced.
data class PulledSourceSummary(
    val sourceDeviceRole: String,
    val sourceRaceLabel: String,
    val totalCount: Int,
    val syncedCount: Int,
    val lastPulledAtMillis: Long,
)

@Dao
interface PulledRecordDao {
    // IGNORE + the unique index on recordUuid makes re-pulling the same record from a
    // phone (e.g. after a dropped connection retry) a no-op rather than a duplicate row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<PulledRecordEntity>)

    // Records are never deleted once pulled (only marked synced), so this is a genuine
    // running history across every race Mule has ever visited, not just the current one.
    @Query(
        """
        SELECT sourceDeviceRole, sourceRaceLabel,
               COUNT(*) AS totalCount,
               SUM(CASE WHEN syncedAtMillis IS NOT NULL THEN 1 ELSE 0 END) AS syncedCount,
               MAX(pulledAtMillis) AS lastPulledAtMillis
        FROM pulled_records
        GROUP BY sourceDeviceRole, sourceRaceLabel
        ORDER BY lastPulledAtMillis DESC
        """,
    )
    fun observeSourceSummaries(): Flow<List<PulledSourceSummary>>

    @Query(
        "SELECT * FROM pulled_records WHERE sourceDeviceRole = :sourceDeviceRole AND sourceRaceLabel = :sourceRaceLabel " +
            "ORDER BY pulledAtMillis",
    )
    fun observeForSource(sourceDeviceRole: String, sourceRaceLabel: String): Flow<List<PulledRecordEntity>>

    @Query("SELECT * FROM pulled_records WHERE syncedAtMillis IS NULL ORDER BY pulledAtMillis")
    suspend fun getUnsynced(): List<PulledRecordEntity>

    // Every held record, synced or not — used when (re-)sending the *full* set to the
    // server rather than just the delta, so a deleted/corrupted server-side file gets fully
    // reconstructed on the next push instead of only receiving whatever's new since.
    @Query("SELECT * FROM pulled_records ORDER BY pulledAtMillis")
    suspend fun getAll(): List<PulledRecordEntity>

    @Query("SELECT COUNT(*) FROM pulled_records WHERE syncedAtMillis IS NULL")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT MAX(syncedAtMillis) FROM pulled_records")
    fun observeLastSyncedAtMillis(): Flow<Long?>

    @Query("SELECT MAX(pulledAtMillis) FROM pulled_records")
    fun observeLastPulledAtMillis(): Flow<Long?>

    @Query("UPDATE pulled_records SET syncedAtMillis = :syncedAtMillis WHERE recordUuid IN (:recordUuids)")
    suspend fun markSynced(recordUuids: List<String>, syncedAtMillis: Long)
}
