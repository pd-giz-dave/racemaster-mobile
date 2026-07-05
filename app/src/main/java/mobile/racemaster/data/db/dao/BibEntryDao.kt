package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType
import kotlinx.coroutines.flow.Flow

@Dao
interface BibEntryDao {
    // Ordered by id, not splitNumber: the fixed Clock row has splitNumber = 0 but the
    // lowest id, so it must sort last by insertion order to be treated as "latest" only
    // when it's genuinely the only row for the race.
    @Query("SELECT * FROM bib_entries WHERE raceId = :raceId ORDER BY id DESC")
    fun observeForRace(raceId: Long): Flow<List<BibEntryEntity>>

    @Query("SELECT * FROM bib_entries WHERE raceId = :raceId ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(raceId: Long): BibEntryEntity?

    @Insert
    suspend fun insert(entry: BibEntryEntity): Long

    @Delete
    suspend fun delete(entry: BibEntryEntity)

    @Query("UPDATE bib_entries SET bibNumber = :bibNumber, type = :type, note = :note WHERE id = :id")
    suspend fun update(id: Long, bibNumber: Int?, type: BibEntryType, note: String?)

    @Query("DELETE FROM bib_entries WHERE raceId = :raceId")
    suspend fun deleteAllForRace(raceId: Long)

    // One-shot (not a Flow) snapshot for Mule's BLE pull: it needs a fixed list to serialize
    // and stream out, not a live subscription.
    @Query("SELECT * FROM bib_entries WHERE raceId = :raceId AND syncedAtMillis IS NULL ORDER BY id")
    suspend fun getUnsyncedForRace(raceId: Long): List<BibEntryEntity>

    // Every entry for the race, synced or not — used when (re-)sending the *full* set to the
    // server rather than just the delta, so a deleted/corrupted server-side file gets fully
    // reconstructed on the next push instead of only receiving whatever's changed since.
    @Query("SELECT * FROM bib_entries WHERE raceId = :raceId ORDER BY id")
    suspend fun getAllForRace(raceId: Long): List<BibEntryEntity>

    @Query("SELECT COUNT(*) FROM bib_entries WHERE raceId = :raceId AND syncedAtMillis IS NULL")
    fun observeUnsyncedCountForRace(raceId: Long): Flow<Int>

    // Most recent time a Mule pulled (and ack'd) any of this race's entries — distinct from
    // Mule's own "pushed to the server" bookkeeping, which lives on the Mule device instead.
    @Query("SELECT MAX(syncedAtMillis) FROM bib_entries WHERE raceId = :raceId")
    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?>

    // Keyed by recordUuid, not local id: that's the identifier Mule's BLE ack carries back.
    @Query("UPDATE bib_entries SET syncedAtMillis = :syncedAtMillis WHERE recordUuid IN (:recordUuids)")
    suspend fun markSynced(recordUuids: List<String>, syncedAtMillis: Long)
}
