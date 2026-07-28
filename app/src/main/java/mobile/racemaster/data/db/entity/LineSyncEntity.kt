package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// The reserved targetId meaning "the racemaster server", as opposed to a specific deviceId.
const val SERVER_TARGET_ID = "SERVER"

// Simple per-line "who's confirmed to have this" feedback for a *local* race's own history —
// each row here is written directly by whichever hop on THIS device actually observed the
// confirmation (a BLE ack its own GATT server received from a puller, or its own successful
// push to the server for a self-originated line). [isSink] is what that confirmation actually
// means: true when [targetId] is a genuine data sink (the server, or a Bluetooth device that
// identified as one) — the green threshold — versus false, meaning [targetId] merely took a
// relay copy — the intermediate orange threshold (see HistoryLineEntity.syncedAtMillis's own
// doc for how the two combine). A row's own [targetId]/[targetName] still only ever names the
// immediate hop that told this device, even for a confirmation that itself arrived via
// [mobile.racemaster.data.mule.AckPayload.sinkConfirmedRecordUuids] (i.e. one relayed here from
// further up an N-hop mule chain) — the wire protocol carries no chain-of-custody field for the
// true originating sink's own identity through arbitrary hop depth, so "Synced to: X" may name
// an intermediate mule rather than the actual sink once data has crossed more than one hop.
@Entity(
    tableName = "line_syncs",
    indices = [Index("raceId", "lineNumber", "targetId", unique = true)],
)
data class LineSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceId: Long,
    val lineNumber: Long,
    // A deviceId, or SERVER_TARGET_ID.
    val targetId: String,
    // The memorable device name (or "Server") to actually display — captured at write time,
    // rather than depending on a live BLE-scan-built directory that may never have resolved
    // this particular puller.
    val targetName: String = "",
    val syncedAtMillis: Long,
    val isSink: Boolean = false,
)
