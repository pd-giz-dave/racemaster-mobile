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

    // One-shot (not a Flow) snapshot for Mule's BLE pull: it needs a fixed list to serialize
    // and stream out, not a live subscription. Scoped to one mode — this is the "what's new to
    // add to my own inbox" self-pull path, which each thin repository still owns separately.
    @Query("SELECT * FROM history_lines WHERE raceId = :raceId AND mode = :mode AND syncedAtMillis IS NULL ORDER BY id")
    suspend fun getUnsyncedForRace(raceId: Long, mode: HistoryMode): List<HistoryLineEntity>

    // Unscoped (both modes) — delta-sync snapshot for a Mule pull request, spanning every
    // segment of every mode this device has recorded. Deliberately NOT scoped to whichever
    // AppMode screen happens to be showing: a mixed-mode race must still sync everything it
    // holds over BLE regardless of which mode the operator currently has open.
    @Query("SELECT * FROM history_lines WHERE raceId = :raceId AND lineNumber > :sinceLineNumber ORDER BY lineNumber")
    suspend fun getSinceLineNumber(raceId: Long, sinceLineNumber: Long): List<HistoryLineEntity>

    @Query("SELECT COUNT(*) FROM history_lines WHERE raceId = :raceId AND mode = :mode AND syncedAtMillis IS NULL")
    fun observeUnsyncedCountForRace(raceId: Long, mode: HistoryMode): Flow<Int>

    // Most recent time a Mule pulled (and ack'd) any of this race's rows for THIS mode —
    // distinct from Mule's own "pushed to the server" bookkeeping, which lives on the Mule
    // device instead.
    @Query("SELECT MAX(syncedAtMillis) FROM history_lines WHERE raceId = :raceId AND mode = :mode")
    fun observeLastSyncedAtMillis(raceId: Long, mode: HistoryMode): Flow<Long?>

    // Keyed by recordUuid, not local id or mode: that's the identifier Mule's BLE ack carries
    // back, and a batch of acked uuids is inherently already scoped to whatever was actually
    // streamed out, regardless of mode.
    @Query("UPDATE history_lines SET syncedAtMillis = :syncedAtMillis WHERE recordUuid IN (:recordUuids)")
    suspend fun markSynced(recordUuids: List<String>, syncedAtMillis: Long)

    // lineNumbers for a batch of acked recordUuids — used to attribute a BLE ack to specific
    // history lines for per-line "synced to" bookkeeping (see LineSyncEntity).
    @Query("SELECT lineNumber FROM history_lines WHERE recordUuid IN (:recordUuids)")
    suspend fun getLineNumbersForUuids(recordUuids: List<String>): List<Long>
}
