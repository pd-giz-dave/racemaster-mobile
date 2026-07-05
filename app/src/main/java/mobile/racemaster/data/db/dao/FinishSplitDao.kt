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

    @Query("UPDATE finish_splits SET note = :note WHERE id = :splitId")
    suspend fun updateNote(splitId: Long, note: String?)

    @Query("DELETE FROM finish_splits WHERE raceId = :raceId")
    suspend fun deleteAllForRace(raceId: Long)

    // One-shot (not a Flow) snapshot for Mule's BLE pull: it needs a fixed list to serialize
    // and stream out, not a live subscription.
    @Query("SELECT * FROM finish_splits WHERE raceId = :raceId AND syncedAtMillis IS NULL ORDER BY id")
    suspend fun getUnsyncedForRace(raceId: Long): List<FinishSplitEntity>

    // Every split for the race, synced or not — used when (re-)sending the *full* set to the
    // server rather than just the delta, so a deleted/corrupted server-side file gets fully
    // reconstructed on the next push instead of only receiving whatever's changed since.
    @Query("SELECT * FROM finish_splits WHERE raceId = :raceId ORDER BY id")
    suspend fun getAllForRace(raceId: Long): List<FinishSplitEntity>

    @Query("SELECT COUNT(*) FROM finish_splits WHERE raceId = :raceId AND syncedAtMillis IS NULL")
    fun observeUnsyncedCountForRace(raceId: Long): Flow<Int>

    // Most recent time a Mule pulled (and ack'd) any of this race's splits — distinct from
    // Mule's own "pushed to the server" bookkeeping, which lives on the Mule device instead.
    @Query("SELECT MAX(syncedAtMillis) FROM finish_splits WHERE raceId = :raceId")
    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?>

    // Keyed by recordUuid, not local id: that's the identifier Mule's BLE ack carries back.
    @Query("UPDATE finish_splits SET syncedAtMillis = :syncedAtMillis WHERE recordUuid IN (:recordUuids)")
    suspend fun markSynced(recordUuids: List<String>, syncedAtMillis: Long)
}