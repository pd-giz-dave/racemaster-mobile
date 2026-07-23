package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mobile.racemaster.data.db.entity.PulledRecordEntity
import kotlinx.coroutines.flow.Flow

// One row per distinct (source race, source device) pair Mule has ever pulled from — the
// accumulated history view, not just what's currently sitting unsynced. Grouped by race label
// AND sourceDeviceId (not also by sourceDeviceRole): a single physical phone that's run both
// Time and Bibs mode for the same race label still shows as one combined source, like a local
// race's own history does — but two genuinely different phones that happen to share a race
// label (e.g. a Time phone and a Bibs phone both working the same physical race under the same
// name/course/date) now show as two separate sources, each under its own device's name.
// Records pulled from different devices must never be merged into one source just because they
// share a race label. Always excludes this device's own self-pulled rows (see the DAO queries
// below) — those are purely push-to-server bookkeeping, not something to show the operator as
// a distinct "source" when it's already visible as one of their own local races.
data class PulledSourceSummary(
    val sourceRaceLabel: String,
    val sourceDeviceId: String,
    // This device's own name — since a group is now scoped to a single sourceDeviceId, this is
    // simply that device's most recently-used name (it can change if the operator renames the
    // device mid-race). Drives Race History's "From {name}" entry for this source.
    val deviceName: String,
    val lastPulledAtMillis: Long,
)

@Dao
interface PulledRecordDao {
    // IGNORE + the unique index on recordUuid makes re-pulling the same record from a
    // phone (e.g. after a dropped connection retry) a no-op rather than a duplicate row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<PulledRecordEntity>)

    // Once pulled, a record sticks around until either synced-and-forgotten-about or
    // explicitly deleted via deleteForSource (see that query's own doc) — so absent an
    // operator deletion, this is a genuine running history across every race Mule has ever
    // visited, not just the current one. Excludes :myDeviceId's own rows
    // (MuleSyncEngine.pullSelfRecords tags them with this device's own id purely so
    // push-to-server can treat "self" as a sync candidate like any other device) — without
    // this filter, every local race would also show up a second time here as a
    // confusingly-duplicated "From Mule" entry.
    @Query(
        """
        SELECT sourceRaceLabel,
               sourceDeviceId,
               MAX(pulledAtMillis) AS lastPulledAtMillis,
               (SELECT deviceName FROM pulled_records p2
                WHERE p2.sourceRaceLabel = p.sourceRaceLabel AND p2.sourceDeviceId = p.sourceDeviceId
                ORDER BY p2.pulledAtMillis DESC LIMIT 1) AS deviceName
        FROM pulled_records p
        WHERE sourceDeviceId != :myDeviceId
        GROUP BY sourceRaceLabel, sourceDeviceId
        ORDER BY lastPulledAtMillis DESC
        """,
    )
    fun observeSourceSummaries(myDeviceId: String): Flow<List<PulledSourceSummary>>

    // Scoped to one specific device's rows (the sourceDeviceId a PulledSourceSummary above
    // named), not just the race label — a race label alone can be shared by more than one
    // genuinely different device (see PulledSourceSummary's doc), and mixing their rows back
    // together here would silently re-merge exactly what observeSourceSummaries just kept
    // apart. Ordered by lineNumber (the source device's own true chronological sequence), not
    // pulledAtMillis (the order Mule happened to pull them in, which can genuinely differ — an
    // edit-echo or undo-marker pulled in a later batch than its own root, or records arriving
    // out of order across more than one pull) — Mule Source Detail must show the same
    // chronology a local race's own Race History does (see RaceHistoryDetailScreen's own
    // `sortedBy { it.lineNumber }`).
    @Query("SELECT * FROM pulled_records WHERE sourceRaceLabel = :sourceRaceLabel AND sourceDeviceId = :sourceDeviceId ORDER BY lineNumber")
    fun observeForSource(sourceRaceLabel: String, sourceDeviceId: String): Flow<List<PulledRecordEntity>>

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

    @Query("UPDATE pulled_records SET syncedAtMillis = :syncedAtMillis, syncedTargetName = :targetName WHERE recordUuid IN (:recordUuids)")
    suspend fun markSynced(recordUuids: List<String>, syncedAtMillis: Long, targetName: String? = null)

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

    // Unlike a local race (a device's own recorded history, and the only copy of it — see
    // RaceRepository.deleteRace's own doc for why that one is guarded against an active race),
    // a Mule source is purely a relayed copy: the real ground truth still lives on the
    // originating device. Deleting it here is always safe to offer, at any time, including
    // mid-race — getLastPulledLineNumber falls back to "nothing pulled yet" for this
    // (sourceDeviceId, sourceRaceLabel) pair the moment its rows are gone, so if that device is
    // still around, the very next pull just re-requests its entire history from scratch rather
    // than a delta. Deleting a source with rows that were pulled but never yet pushed to the
    // server does lose that data for good if the source device is no longer reachable (out of
    // range, powered off, race already over) — an operator's call to make, not something this
    // query second-guesses.
    @Query("DELETE FROM pulled_records WHERE sourceRaceLabel = :sourceRaceLabel AND sourceDeviceId = :sourceDeviceId")
    suspend fun deleteForSource(sourceRaceLabel: String, sourceDeviceId: String)
}
