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
    indices = [Index("recordUuid", unique = true)],
)
data class PulledRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordUuid: String,
    val sourceDeviceRole: String,
    val sourceRaceLabel: String,
    val payloadJson: String,
    val pulledAtMillis: Long,
    val syncedAtMillis: Long? = null,
)
