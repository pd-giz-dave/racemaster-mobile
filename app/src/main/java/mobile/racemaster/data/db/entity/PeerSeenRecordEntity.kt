package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Fast same-mode peer sync (multiple Count phones / multiple Finish phones converging on shared
// state — see the Mule Mode peer-sync design doc). A row here is a genuinely different device's
// own history line, learned via an accelerated GATT pull (see MuleSyncEngine's per-peer
// LOW_LATENCY handling), merged for display alongside this device's own HistoryLineEntity rows —
// never INTO HistoryLineEntity itself, since lineNumber there is private/append-only per device
// (see that class's own doc) and must never carry another device's rows.
//
// Deliberately NOT PulledRecordEntity either, even though both hold "someone else's rows": that
// table is a flat, un-interpreted relay inbox toward the HTTP server (every row just JSON,
// forwarded verbatim, never read back into this device's own mode screens). This table exists
// specifically to be queried alongside local rows by raceGroupId/mode — so beaconNumber/bibNumber
// are real columns, not buried in a note string the way HistoryLineEntity's own COUNT_SEEN rows
// encode theirs (see CountModeRepository.parseBeaconCount) — extracted once, at merge time, by
// whatever reads the incoming SyncRecord.
@Entity(
    tableName = "peer_seen_records",
    indices = [
        // The real-world dedup key a union view folds on — "this beacon/bib has been seen by
        // SOME peer in this group" — not (originDeviceId, lineNumber), since two different
        // devices reporting the same beacon/bib is one real-world event, not two (see this
        // file's own doc and the design doc's "Merge into local state" section).
        Index("raceGroupId", "mode", "beaconNumber"),
        Index("raceGroupId", "mode", "bibNumber"),
        // A specific origin's own rows, in its own chronological order — what a cross-device
        // duplicate check (BibValidation's generic core, extended to this union) walks.
        Index("originDeviceId", "lineNumber"),
    ],
)
data class PeerSeenRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // (groupRaceId, mode, locationCode-if-BIBS) collapsed into one opaque string key by
    // PeerSyncRepository — see its own doc for why one column here is simpler than three.
    val raceGroupId: String,
    val mode: HistoryMode,
    val originDeviceId: String,
    val originDeviceName: String,
    // The origin's own lineNumber — carried as-is (never renumbered into this device's own
    // sequence), since it's only ever read back scoped to its originDeviceId (see
    // HistoryLineEntity.lineNumber's own doc on why a bare value is only meaningful paired with
    // whose sequence it came from).
    val lineNumber: Long,
    val action: String,
    val bibNumber: Int? = null,
    val beaconNumber: Int? = null,
    // Carried straight through from SyncRecord.splitNumber, same as bibNumber above — only ever
    // meaningful for a Bibs/Time-category row; feeds the cross-device duplicate check (see
    // BibValidation.findCanonicalDuplicates) the same way a local HistoryLineEntity row's own
    // splitNumber does.
    val splitNumber: Int? = null,
    val note: String? = null,
    val timestampMillis: Long,
)
