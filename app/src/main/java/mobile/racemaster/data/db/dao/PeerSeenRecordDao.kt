package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity

@Dao
interface PeerSeenRecordDao {
    // No uniqueness constraint to IGNORE against — unlike PulledRecordDao's insertAll, a peer's
    // own (originDeviceId, lineNumber) can legitimately be merged more than once (a reconnect
    // re-reporting the same already-held lines is harmless, just a few redundant rows); the
    // union views below collapse duplicates by real-world key (beaconNumber/bibNumber) at read
    // time instead of relying on insert-time dedup — see PeerSeenRecordEntity's own doc.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<PeerSeenRecordEntity>)

    // The highest lineNumber already merged for this specific origin — the resume cursor
    // PeerSyncRepository compares a freshly-pulled peer's own lastLineNumber against, the exact
    // same delta-sync shape MuleRepository.lastPulledLineNumber already uses for the ordinary
    // relay-inbox path.
    @Query("SELECT MAX(lineNumber) FROM peer_seen_records WHERE originDeviceId = :originDeviceId")
    suspend fun getLastMergedLineNumber(originDeviceId: String): Long?

    // Every beacon any peer in this group has reported as COUNT_SEEN — the Count Mode union
    // view's own peer half (see CountModeRepository's local-only equivalent). Distinct beacon
    // numbers only; which specific peer(s) reported it is a separate, UI-only lookup
    // (observeForBeacon below) rather than carried here, since the common case (a plain "has
    // this been counted by anyone" check) never needs it.
    @Query(
        "SELECT DISTINCT beaconNumber FROM peer_seen_records " +
            "WHERE raceGroupId = :raceGroupId AND mode = :mode AND beaconNumber IS NOT NULL",
    )
    fun observeSeenBeaconNumbers(raceGroupId: String, mode: HistoryMode): Flow<List<Int>>

    // Every bib any peer in this group has reported — the Bibs/Finish union view's own peer
    // half, feeding the existing unexpectedBibWarning warn-don't-block convention (see the
    // design doc's "Merge into local state" section).
    @Query(
        "SELECT DISTINCT bibNumber FROM peer_seen_records " +
            "WHERE raceGroupId = :raceGroupId AND mode = :mode AND bibNumber IS NOT NULL",
    )
    fun observeSeenBibNumbers(raceGroupId: String, mode: HistoryMode): Flow<List<Int>>

    // Every peer-reported row for one specific beacon, newest first — feeds a UI detail ("seen
    // by brave-reef at 14:32") and BibValidation's extended cross-device duplicate check.
    @Query(
        "SELECT * FROM peer_seen_records WHERE raceGroupId = :raceGroupId AND mode = :mode " +
            "AND beaconNumber = :beaconNumber ORDER BY timestampMillis DESC",
    )
    fun observeForBeacon(raceGroupId: String, mode: HistoryMode, beaconNumber: Int): Flow<List<PeerSeenRecordEntity>>

    @Query(
        "SELECT * FROM peer_seen_records WHERE raceGroupId = :raceGroupId AND mode = :mode " +
            "AND bibNumber = :bibNumber ORDER BY timestampMillis DESC",
    )
    fun observeForBib(raceGroupId: String, mode: HistoryMode, bibNumber: Int): Flow<List<PeerSeenRecordEntity>>

    // Every row currently held for this group/mode, in real chronological order — what
    // BibValidation's extended generic core walks to find a cross-device duplicate (the same
    // shape its existing local-only callers already consume).
    @Query(
        "SELECT * FROM peer_seen_records WHERE raceGroupId = :raceGroupId AND mode = :mode ORDER BY lineNumber",
    )
    fun observeGroup(raceGroupId: String, mode: HistoryMode): Flow<List<PeerSeenRecordEntity>>

    // Purges every row ever merged from this origin — offered the same "always safe, at any
    // time" way PulledRecordDao.deleteForSource is: the ground truth still lives on the
    // originating device, so the very next accelerated pull just re-merges from scratch.
    @Query("DELETE FROM peer_seen_records WHERE originDeviceId = :originDeviceId")
    suspend fun deleteForOrigin(originDeviceId: String)
}
