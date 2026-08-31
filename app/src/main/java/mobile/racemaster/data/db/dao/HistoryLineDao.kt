package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryLineDao {
    // Full, permanent history across BOTH modes, for Race History's one true chronology —
    // replaces the old separate FinishSplitDao/BibEntryDao observeAllForRace queries (each of
    // which only ever saw its own table). Ordered by lineNumber (a strictly-increasing,
    // race-wide insertion sequence regardless of mode), not id — this is what makes a single
    // ORDER BY correct for both families, including the old fixed-Clock-row edge case
    // (BibEntryDao used to special-case this via `id DESC`; lineNumber already gives the same
    // guarantee since it's assigned in true insertion order too).
    @Query("SELECT * FROM history_lines WHERE raceId = :raceId ORDER BY lineNumber DESC")
    fun observeAllForRace(raceId: Long): Flow<List<HistoryLineEntity>>

    // Only the rows since the most recent reset marker for THIS mode (0/none if never reset)
    // — what a mode's own live screen shows, once folded (see HistoryFold) by its repository.
    @Query(
        """
        SELECT * FROM history_lines WHERE raceId = :raceId AND mode = :mode AND lineNumber >
            (SELECT COALESCE(MAX(lineNumber), 0) FROM history_lines
                WHERE raceId = :raceId AND mode = :mode AND action = :resetAction)
        ORDER BY lineNumber DESC
        """,
    )
    fun observeCurrentSegment(raceId: Long, mode: HistoryMode, resetAction: HistoryAction): Flow<List<HistoryLineEntity>>

    // One-shot (non-Flow) snapshot of the exact same rows as observeCurrentSegment — for use
    // inside a transactional Undo/Edit, which needs to fold the raw rows itself rather than
    // subscribe to a live Flow.
    @Query(
        """
        SELECT * FROM history_lines WHERE raceId = :raceId AND mode = :mode AND lineNumber >
            (SELECT COALESCE(MAX(lineNumber), 0) FROM history_lines
                WHERE raceId = :raceId AND mode = :mode AND action = :resetAction)
        ORDER BY lineNumber DESC
        """,
    )
    suspend fun getCurrentSegmentSnapshot(raceId: Long, mode: HistoryMode, resetAction: HistoryAction): List<HistoryLineEntity>

    // Fetches whatever row the UI is currently pointing at for an edit — may itself already
    // be an edit-echo, not necessarily the root.
    @Query("SELECT * FROM history_lines WHERE id = :id")
    suspend fun getById(id: Long): HistoryLineEntity?

    // Resolves a root row (an echo/undo-marker's refLineNumber always points here) so
    // Undo/Edit guards and race-state side effects can be keyed off the immutable original
    // rather than whatever the latest echo displays.
    @Query("SELECT * FROM history_lines WHERE raceId = :raceId AND lineNumber = :lineNumber")
    suspend fun getByLineNumber(raceId: Long, lineNumber: Long): HistoryLineEntity?

    @Insert
    suspend fun insert(entry: HistoryLineEntity): Long

    // Unscoped (both modes) — delta-sync snapshot spanning every segment of every mode this
    // device has recorded, past whatever the requester already has. Deliberately NOT scoped
    // to whichever AppMode screen happens to be showing: a mixed-mode race must still sync
    // everything it holds regardless of which mode the operator currently has open. Two
    // callers: a genuine BLE pull request from another Mule (PeripheralSyncService.
    // streamRecords), and MuleRepository.pushToServer building this device's own self-push
    // payload fresh on every tick — the two are otherwise identical from this query's own
    // point of view, since "what's new since line N" means the same thing either way.
    @Query("SELECT * FROM history_lines WHERE raceId = :raceId AND lineNumber > :sinceLineNumber ORDER BY lineNumber")
    suspend fun getSinceLineNumber(raceId: Long, sinceLineNumber: Long): List<HistoryLineEntity>

    @Query("SELECT COUNT(*) FROM history_lines WHERE raceId = :raceId AND mode = :mode AND syncedAtMillis IS NULL")
    fun observeUnsyncedCountForRace(raceId: Long, mode: HistoryMode): Flow<Int>

    // Most recent time this race's rows for THIS mode were confirmed synced somewhere — either
    // a genuinely different physical Mule acking a BLE pull (PeripheralSyncService.markSynced),
    // or (for this device's own self-push) MuleRepository.pushToServer's own server-status
    // check confirming a line actually landed, not merely being handed off locally.
    @Query("SELECT MAX(syncedAtMillis) FROM history_lines WHERE raceId = :raceId AND mode = :mode")
    fun observeLastSyncedAtMillis(raceId: Long, mode: HistoryMode): Flow<Long?>

    // The same signal as observeUnsyncedCountForRace/observeLastSyncedAtMillis above, but
    // unscoped to any one race or mode — every row this device has ever recorded, across every
    // race. Feeds Mule Mode's own aggregate status line (MuleRepository.unsyncedCount/
    // lastSyncedAtMillis), which needs to reflect this device's own outstanding pushes
    // alongside whatever it's holding for other devices, now that self-push builds its payload
    // fresh from this table each tick rather than staging a copy into pulled_records.
    @Query("SELECT COUNT(*) FROM history_lines WHERE syncedAtMillis IS NULL")
    fun observeUnsyncedCountAcrossAllRaces(): Flow<Int>

    @Query("SELECT MAX(syncedAtMillis) FROM history_lines")
    fun observeLastSyncedAtMillisAcrossAllRaces(): Flow<Long?>

    // How recently this race's own history was actually edited — used by
    // MuleRepository.pushToServer to decide whether a race with no pulled_records activity of
    // its own (i.e. every race, now that self-push no longer touches that table) is still
    // worth re-checking against the server, and by Race History to show a local race as "too
    // old for server sync" the same way a Mule-pulled source already does. Deliberately sourced
    // from the real data's own timestamps, not a sync-bookkeeping proxy.
    @Query("SELECT MAX(timestampMillis) FROM history_lines WHERE raceId = :raceId")
    fun observeLastActivityAtMillis(raceId: Long): Flow<Long?>

    // Keyed by recordUuid, not local id or mode: that's the identifier a BLE ack (or this
    // device's own self-push confirmation) carries back, and a batch of confirmed uuids is
    // inherently already scoped to whatever was actually sent, regardless of mode.
    @Query("UPDATE history_lines SET syncedAtMillis = :syncedAtMillis WHERE recordUuid IN (:recordUuids)")
    suspend fun markSynced(recordUuids: List<String>, syncedAtMillis: Long)

    // Everything for [raceId] up to and including [sinceLineNumber] this device's own
    // bookkeeping never got an explicit ack for — see PeripheralSyncService.backfillSinkAck's
    // own doc for why a request's own sinceLineNumber is itself proof the requester already has
    // everything up to and including it (the same inclusive line this protocol's own
    // getSinceLineNumber query — "lineNumber > :sinceLineNumber" — already treats as already
    // possessed), even absent an ack (a dropped connection between the puller durably storing
    // the data and it writing the ack back loses the ack, not the data).
    @Query("SELECT recordUuid FROM history_lines WHERE raceId = :raceId AND lineNumber <= :sinceLineNumber AND syncedAtMillis IS NULL")
    suspend fun getUnsyncedRecordUuidsUpTo(raceId: Long, sinceLineNumber: Long): List<String>

    // lineNumbers for a batch of confirmed recordUuids — used to attribute a confirmation to
    // specific history lines for per-line "synced to" bookkeeping (see LineSyncEntity).
    @Query("SELECT lineNumber FROM history_lines WHERE recordUuid IN (:recordUuids)")
    suspend fun getLineNumbersForUuids(recordUuids: List<String>): List<Long>
}
