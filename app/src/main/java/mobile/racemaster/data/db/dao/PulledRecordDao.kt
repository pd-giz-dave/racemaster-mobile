package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
data class PulledSourceKey(val sourceDeviceId: String, val sourceRaceLabel: String)

data class PulledSourceSummary(
    val sourceRaceLabel: String,
    val sourceDeviceId: String,
    // This device's own name — since a group is now scoped to a single sourceDeviceId, this is
    // simply that device's most recently-used name (it can change if the operator renames the
    // device mid-race). Drives Race History's "From {name}" entry for this source.
    val deviceName: String,
    val lastPulledAtMillis: Long,
    // The highest lineNumber held for this source — what a mule-to-mule relay manifest reports
    // as this source's own DeviceInfo.lastLineNumber would, letting another mule pulling this
    // one apply the exact same delta-sync comparison it already uses for a direct leaf pull.
    val lastLineNumber: Long,
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
    // IGNORE + the unique index on (sourceDeviceId, sourceRaceLabel, lineNumber) makes
    // re-pulling the same record from a phone (e.g. after a dropped connection retry) a no-op
    // rather than a duplicate row.
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
               MAX(lineNumber) AS lastLineNumber,
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

    // Serves a mule-to-mule relay pull: everything this device holds for a given true origin,
    // after sinceLineNumber — the same delta-sync shape RaceRepository.getHistorySinceLineNumber
    // already serves for a device's own race, just backed by the pulled-inbox table instead. Not
    // reactive (suspend, not Flow) — this only ever runs once per incoming BLE pull request, not
    // observed continuously.
    @Query("SELECT * FROM pulled_records WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :sourceRaceLabel AND lineNumber > :sinceLineNumber ORDER BY lineNumber")
    suspend fun getRecordsSince(sourceDeviceId: String, sourceRaceLabel: String, sinceLineNumber: Long): List<PulledRecordEntity>

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

    // `internal`, not called directly — see markSynced below, which chunks against
    // SQLITE_MAX_IN_LIST_PARAMS before ever reaching this (see that constant's own doc — a
    // large IN-list here blew through SQLite's per-statement bound-parameter cap in the field).
    @Query(
        "UPDATE pulled_records SET syncedAtMillis = :syncedAtMillis, syncedTargetName = :targetName " +
            "WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :sourceRaceLabel AND lineNumber IN (:lineNumbers)",
    )
    suspend fun markSyncedChunk(sourceDeviceId: String, sourceRaceLabel: String, lineNumbers: List<Long>, syncedAtMillis: Long, targetName: String? = null)

    @Transaction
    suspend fun markSynced(sourceDeviceId: String, sourceRaceLabel: String, lineNumbers: List<Long>, syncedAtMillis: Long, targetName: String? = null) {
        for (chunk in lineNumbers.chunked(SQLITE_MAX_IN_LIST_PARAMS)) {
            markSyncedChunk(sourceDeviceId, sourceRaceLabel, chunk, syncedAtMillis, targetName)
        }
    }

    // Everything held for [sourceDeviceId]/[sourceRaceLabel] up to and including
    // [sinceLineNumber] that's never been confirmed reaching a sink — the relay-leg counterpart
    // to HistoryLineDao's own getUnsyncedLineNumbersUpTo; see
    // PeripheralSyncService.backfillSinkAck's own doc for why a relay pull's own sinceLineNumber
    // is itself proof of this, inclusive.
    @Query(
        "SELECT lineNumber FROM pulled_records WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :sourceRaceLabel " +
            "AND lineNumber <= :sinceLineNumber AND syncedAtMillis IS NULL",
    )
    suspend fun getUnsyncedLineNumbersUpTo(sourceDeviceId: String, sourceRaceLabel: String, sinceLineNumber: Long): List<Long>

    // The delta-sync cutoff for the next pull from this specific device/race — null (treated
    // as "nothing pulled yet, request everything") the first time.
    @Query("SELECT MAX(lineNumber) FROM pulled_records WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :sourceRaceLabel")
    suspend fun getLastPulledLineNumber(sourceDeviceId: String, sourceRaceLabel: String): Long?

    // Every lineNumber this device is holding on [sourceDeviceId]/[sourceRaceLabel]'s behalf
    // that's confirmed reaching a sink (syncedAtMillis set — see markSynced above) but hasn't
    // yet actually been told back to that source (confirmationRelayedAtMillis still null — see
    // markConfirmationRelayed below). Called right before acking a pull FROM that same source
    // device, so this device's own ack can piggyback these as one of AckPayload.sinkConfirmedOrigins
    // — the mechanism that lets a sink confirmation climb back up an N-hop mule chain to the
    // device that originally recorded these lines. See MuleRepository.pullFrom.
    //
    // Scoped to *un*relayed rows only, not every sink-confirmed row for this source — the ack
    // this piggybacks on must stay bounded by what's genuinely new (a "delta" — what this device
    // just learned from the server or a sink mule), not the source's entire ever-growing
    // confirmed history, since a large race (e.g. 300 runners) would otherwise re-transfer that
    // whole backlog every ~10s tick forever. Trusting confirmationRelayedAtMillis here is safe
    // specifically because PeripheralSyncService now defers its GATT write-response until
    // markSynced (which calls markConfirmationRelayed's sibling on the *receiving* end — see
    // that class's own doc) has genuinely finished, closing the fire-and-forget window that used
    // to let a confirmation get marked "told" on this side before the peripheral had actually
    // durably processed it.
    @Query(
        "SELECT lineNumber FROM pulled_records WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :sourceRaceLabel " +
            "AND syncedAtMillis IS NOT NULL AND confirmationRelayedAtMillis IS NULL",
    )
    suspend fun getUnrelayedSinkConfirmedLineNumbersForSource(sourceDeviceId: String, sourceRaceLabel: String): List<Long>

    // Marks [lineNumbers] (scoped to [sourceDeviceId]/[sourceRaceLabel] — a bare lineNumber
    // isn't unique across different sources) as told back to their source — called only once
    // the ack write that actually carried them has succeeded, which (thanks to
    // PeripheralSyncService's deferred GATT response — see its own doc) now genuinely means the
    // peripheral finished applying it, not just that the bytes arrived. Never called
    // optimistically before the write completes, so a failed/dropped write leaves these rows
    // eligible to be picked up and resent by getUnrelayedSinkConfirmedLineNumbersForSource on
    // the very next tick rather than lost.
    //
    // Only ever called by MuleRepository.pullFrom for a *direct* pull (the peripheral just acked
    // IS sourceDeviceId itself), never for a relay pull to some other intermediate mule — see
    // that call site's own doc. Confirmed in the field: two mules that both hold a copy of the
    // same leaf's data (and also pull from each other, e.g. via a mule-to-mule relay chain) can
    // each independently learn a confirmation and each try to relay it — if either one marked it
    // relayed after merely telling the *other* mule, the confirmation could get "used up" between
    // them and never reach the leaf that actually recorded the line, even though both mules'
    // own bookkeeping showed it as sink-confirmed.
    // `internal`, not called directly — see markConfirmationRelayed below, which chunks against
    // SQLITE_MAX_IN_LIST_PARAMS before ever reaching this (see that constant's own doc).
    @Query(
        "UPDATE pulled_records SET confirmationRelayedAtMillis = :relayedAtMillis " +
            "WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :sourceRaceLabel AND lineNumber IN (:lineNumbers)",
    )
    suspend fun markConfirmationRelayedChunk(sourceDeviceId: String, sourceRaceLabel: String, lineNumbers: List<Long>, relayedAtMillis: Long)

    @Transaction
    suspend fun markConfirmationRelayed(sourceDeviceId: String, sourceRaceLabel: String, lineNumbers: List<Long>, relayedAtMillis: Long) {
        for (chunk in lineNumbers.chunked(SQLITE_MAX_IN_LIST_PARAMS)) {
            markConfirmationRelayedChunk(sourceDeviceId, sourceRaceLabel, chunk, relayedAtMillis)
        }
    }

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

    // Sources held only as a deletion tombstone (the source device deleted that race — see
    // racemaster-mobile's RaceRepository.requestDeleteRace): kept so an older copy is refused,
    // but hidden from Race History. Matched on the stored JSON — a tombstone is the lone
    // NewRace row at lineNumber 1 whose note is "Deleted".
    @Query(
        "SELECT DISTINCT sourceDeviceId, sourceRaceLabel FROM pulled_records WHERE lineNumber = 1 " +
            "AND payloadJson LIKE '%\"action\":\"NewRace\"%' AND payloadJson LIKE '%\"note\":\"Deleted\"%'",
    )
    fun observeTombstonedSources(): Flow<List<PulledSourceKey>>

    // By the device NAME the server knows it as — see MuleRepository.pushToServer's handling of a
    // push the server refused as superseded (the server never learns a deviceId).
    @Query("DELETE FROM pulled_records WHERE sourceRaceLabel = :sourceRaceLabel AND deviceName = :deviceName")
    suspend fun deleteForDeviceName(sourceRaceLabel: String, deviceName: String)

    // The lowest-numbered row held for this source — a generation's opening NEW_RACE marker when
    // one is held (see MuleRepository.storePulledRecords' generation ordering).
    @Query("SELECT * FROM pulled_records WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel = :sourceRaceLabel ORDER BY lineNumber LIMIT 1")
    suspend fun getFirstForSource(sourceDeviceId: String, sourceRaceLabel: String): PulledRecordEntity?

    // Rows held for [sourceDeviceId] at [lineNumber] under any label other than
    // [sourceRaceLabel] — see MuleRepository.storePulledRecords' adopted-relabel cleanup.
    @Query(
        "SELECT * FROM pulled_records WHERE sourceDeviceId = :sourceDeviceId AND sourceRaceLabel != :sourceRaceLabel " +
            "AND lineNumber = :lineNumber",
    )
    suspend fun getAtLineNumberUnderOtherLabels(sourceDeviceId: String, sourceRaceLabel: String, lineNumber: Long): List<PulledRecordEntity>

    // See RaceLabelActivity's own doc.
    @Query("SELECT sourceRaceLabel, MAX(pulledAtMillis) AS lastTouchedAtMillis FROM pulled_records GROUP BY sourceRaceLabel")
    fun observeLastTouchedByRaceLabel(): Flow<List<RaceLabelActivity>>
}
