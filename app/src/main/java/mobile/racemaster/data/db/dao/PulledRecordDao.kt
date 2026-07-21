package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mobile.racemaster.data.db.entity.PulledRecordEntity
import kotlinx.coroutines.flow.Flow

// One row per distinct source race Mule has ever pulled from — the accumulated history
// view, not just what's currently sitting unsynced. Grouped by race label alone (not also by
// sourceDeviceRole): a Mule source now shows both Time and Bibs records for a race label
// together, like a local race's own history does, instead of as two separate list entries.
// Always excludes this device's own self-pulled rows (see the DAO queries below) — those are
// purely push-to-server bookkeeping, not something to show the operator as a distinct
// "source" when it's already visible as one of their own local races.
data class PulledSourceSummary(
    val sourceRaceLabel: String,
    // Whichever device's rows were pulled most recently within this group — in the
    // overwhelming common case a race label's Mule source is a single physical phone, so this
    // is what Race History shows as "From {name}" (mirroring a local race's own "From {name}").
    val deviceName: String,
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
    // Excludes :myDeviceId's own rows (MuleSyncEngine.pullSelfRecords tags them with this
    // device's own id purely so push-to-server can treat "self" as a sync candidate like any
    // other device) — without this filter, every local race would also show up a second time
    // here as a confusingly-duplicated "From Mule" entry.
    @Query(
        """
        SELECT sourceRaceLabel,
               MAX(pulledAtMillis) AS lastPulledAtMillis,
               (SELECT deviceName FROM pulled_records p2
                WHERE p2.sourceRaceLabel = p.sourceRaceLabel AND p2.sourceDeviceId != :myDeviceId
                ORDER BY p2.pulledAtMillis DESC LIMIT 1) AS deviceName
        FROM pulled_records p
        WHERE sourceDeviceId != :myDeviceId
        GROUP BY sourceRaceLabel
        ORDER BY lastPulledAtMillis DESC
        """,
    )
    fun observeSourceSummaries(myDeviceId: String): Flow<List<PulledSourceSummary>>

    // Also excludes :myDeviceId's own rows — see observeSourceSummaries's doc.
    @Query("SELECT * FROM pulled_records WHERE sourceRaceLabel = :sourceRaceLabel AND sourceDeviceId != :myDeviceId ORDER BY pulledAtMillis")
    fun observeForSource(sourceRaceLabel: String, myDeviceId: String): Flow<List<PulledRecordEntity>>

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

    // The delta-sync cutoff for the next pull from this specific device/race — null (treated
    // as "nothing pulled yet, request everything") the first time.
    @Query("SELECT MAX(lineNumber) FROM pulled_records WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :sourceRaceLabel")
    suspend fun getLastPulledLineNumber(sourceDeviceId: String, sourceRaceLabel: String): Long?

    // Re-homes every one of this device's own self-pulled rows (see
    // MuleSyncEngine.pullSelfRecords) from a race's old label to its new one after a rename
    // (see RaceRepository.updateRaceDetails) — sourceRaceLabel is otherwise frozen forever at
    // pull time, so without this a renamed race's history stays permanently split across two
    // label groups: everything pulled before the rename under the old label, everything after
    // under the new one. pushToServer groups by sourceRaceLabel and only ever pushes what a
    // fresh server status check says that *specific* label is still missing — since the new
    // label has never been seen by the server, retagging every row (already-synced ones
    // included) onto it is what makes the next push send this race's *complete* history under
    // its current name, rather than only whatever's new since the rename.
    @Query("UPDATE pulled_records SET sourceRaceLabel = :newLabel WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :oldLabel")
    suspend fun retagSourceRaceLabel(sourceDeviceId: String, oldLabel: String, newLabel: String)
}
