package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mobile.racemaster.data.db.entity.PulledRecordEntity
import kotlinx.coroutines.flow.Flow

// One row per distinct (source race, source device) pair Mule has ever pulled from a genuinely
// different physical device — the accumulated history view, not just what's currently sitting
// unsynced. This device's own data is never staged in here at all (see PulledRecordEntity's own
// doc): MuleRepository.pushToServer builds this device's own self-push payload fresh from its
// real HistoryLineEntity rows on every attempt instead, so there's never a second, driftable
// copy of it to keep in sync. Grouped by race label AND sourceDeviceId: a single physical phone
// that's run both Time and Bibs mode for the same race label still shows as one combined
// source, like a local race's own history does — but two genuinely different phones that
// happen to share a race label (e.g. a Time phone and a Bibs
// phone both working the same physical race under the same name/course/date) show as two
// separate sources, each under its own device's name. Records pulled from different devices
// must never be merged into one source just because they share a race label.
data class PulledSourceSummary(
    val sourceRaceLabel: String,
    val sourceDeviceId: String,
    // This device's own name — since a group is now scoped to a single sourceDeviceId, this is
    // simply that device's most recently-used name (it can change if the operator renames the
    // device mid-race). Drives Race History's "From {name}" entry for this source.
    val deviceName: String,
    val lastPulledAtMillis: Long,
)

// One row per distinct race label this Mule has ever pulled a genuinely different device's data
// for — mirrors exactly how MuleRepository.pushToServer's own staleness check decides whether a
// pulled-from-others race label is still worth checking against the server, so Race History can
// show that same "skipped as too old" state for a Mule source. A local race's own staleness
// uses a separate signal instead (HistoryLineDao.observeLastActivityAtMillis) — its own history
// is real data with its own timestamps, not something this table has any visibility into.
data class RaceLabelActivity(val sourceRaceLabel: String, val lastTouchedAtMillis: Long)

@Dao
interface PulledRecordDao {
    // IGNORE + the unique index on recordUuid makes re-pulling the same record from a
    // phone (e.g. after a dropped connection retry) a no-op rather than a duplicate row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<PulledRecordEntity>)

    // Once pulled, a record sticks around until either synced-and-forgotten-about or
    // explicitly deleted via deleteForSource (see that query's own doc) — so this is a genuine
    // running history across every other device's race Mule has ever visited, not just the
    // current one.
    @Query(
        """
        SELECT sourceRaceLabel,
               sourceDeviceId,
               MAX(pulledAtMillis) AS lastPulledAtMillis,
               (SELECT deviceName FROM pulled_records p2
                WHERE p2.sourceRaceLabel = p.sourceRaceLabel AND p2.sourceDeviceId = p.sourceDeviceId
                ORDER BY p2.pulledAtMillis DESC LIMIT 1) AS deviceName
        FROM pulled_records p
        GROUP BY sourceRaceLabel, sourceDeviceId
        ORDER BY lastPulledAtMillis DESC
        """,
    )
    fun observeSourceSummaries(): Flow<List<PulledSourceSummary>>

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

    // Purely a relayed copy of a genuinely different device's data — the real ground truth
    // still lives on the originating device. Deleting it here is always safe to offer, at any
    // time, including mid-race — getLastPulledLineNumber falls back to "nothing pulled yet" for
    // this (sourceDeviceId, sourceRaceLabel) pair the moment its rows are gone, so if that
    // device is still around, the very next pull just re-requests its entire history from
    // scratch rather than a delta. Deleting a source with rows that were pulled but never yet
    // pushed to the server does lose that data for good if the source device is no longer
    // reachable (out of range, powered off, race already over) — an operator's call to make,
    // not something this query second-guesses.
    @Query("DELETE FROM pulled_records WHERE sourceRaceLabel = :sourceRaceLabel AND sourceDeviceId = :sourceDeviceId")
    suspend fun deleteForSource(sourceRaceLabel: String, sourceDeviceId: String)

    // See RaceLabelActivity's own doc.
    @Query("SELECT sourceRaceLabel, MAX(pulledAtMillis) AS lastTouchedAtMillis FROM pulled_records GROUP BY sourceRaceLabel")
    fun observeLastTouchedByRaceLabel(): Flow<List<RaceLabelActivity>>
}
