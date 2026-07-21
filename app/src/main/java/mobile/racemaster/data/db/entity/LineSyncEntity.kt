package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// The reserved targetId meaning "the racemaster server", as opposed to a specific deviceId.
const val SERVER_TARGET_ID = "SERVER"

// Simple per-line "who's confirmed to have this" feedback for a *local* race's own history —
// deliberately not a gossip/multi-hop relay: every row here is written directly by whichever
// hop on THIS device actually observed the confirmation (a BLE ack its own GATT server
// received from a puller, or its own successful push to the server for a self-originated
// line) — nothing here is learned from, or re-broadcast to, another device's own knowledge.
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
)
