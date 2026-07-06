package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "races")
data class RaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Raw user-entered fields, kept separately from the computed label below so a race's
    // details can be re-edited later without having to parse them back out of it.
    val name: String = "",
    val course: String = "",
    val label: String,
    val createdAtMillis: Long,
    val timeModeNextSplit: Int = 1,
    val bibsModeNextSplit: Int = 1,
    val timeModeStartedAtMillis: Long? = null,
    val timeModeStoppedAtMillis: Long? = null,
    val bibsRangeStart: Int? = null,
    val bibsRangeCount: Int? = null,
    val bibsModeStoppedAtMillis: Long? = null,
    // Which AppMode (TIME/BIBS/MULE) created this race — carried metadata for the sync
    // payload, not used for any local query logic.
    val deviceRole: String? = null,
    // Per-race Racemaster server URL, editable via the race details screen. Independent of
    // the device-wide server URL Mule Mode logs in with — not yet wired into any sync
    // behavior (a broader Mule Mode revamp is planned separately).
    val serverUrl: String? = null,
)