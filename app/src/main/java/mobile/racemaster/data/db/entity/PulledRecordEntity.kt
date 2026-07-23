package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Mule's inbox: a record pulled from a Time/Bibs phone over BLE, held until it's pushed on
// to the racemaster server. Mule has no race of its own, so this is a flat holding table
// rather than something hung off a RaceEntity — payloadJson is already in the exact
// {recordUuid, action, number, time, splitNumber, note, timestampMillis} shape the server's
// mobile-append endpoint expects, so it can be pushed on as-is with no further mapping.
@Entity(
    tableName = "pulled_records",
    indices = [
        Index("recordUuid", unique = true),
        Index("sourceDeviceId", "sourceRaceLabel"),
    ],
)
data class PulledRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordUuid: String,
    // The specific physical phone this record was pulled from — distinct from
    // sourceDeviceRole/sourceRaceLabel (which only identify a role+race combination, not a
    // specific device): needed to look up "the last line number already pulled from THIS
    // device" for delta-sync, since line numbers are only meaningful within one originating
    // device's own counter sequence.
    val sourceDeviceId: String,
    val sourceDeviceRole: String,
    val sourceRaceLabel: String,
    // The source device's own permanent line number for this record — see
    // RaceEntity.nextLineNumber. Used to compute the delta to request on the next pull.
    val lineNumber: Long,
    // Denormalized from the pulled SyncRecord's own deviceName at insert time (same
    // convention as RaceEntity.createdByDeviceName) — lets Race History's Mule-source list
    // show "From {name}" without decoding payloadJson just to find out who it came from.
    val deviceName: String = "",
    val payloadJson: String,
    val pulledAtMillis: Long,
    val syncedAtMillis: Long? = null,
    // The same destination string a local race's own LineSyncEntity.targetName captures for
    // a self-originated line's server push (see MuleRepository.pushToServer) — set alongside
    // syncedAtMillis so a Mule-pulled record's "Synced to: X" tag matches a local race's own
    // exactly, instead of only getting a bare synced/unsynced color with no destination.
    val syncedTargetName: String? = null,
)
