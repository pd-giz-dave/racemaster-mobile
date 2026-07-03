package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "races")
data class RaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val createdAtMillis: Long,
    val timeModeNextSplit: Int = 1,
    val bibsModeNextSplit: Int = 1,
    val timeModeStartedAtMillis: Long? = null,
    val timeModeStoppedAtMillis: Long? = null,
    val bibsRangeStart: Int? = null,
    val bibsRangeCount: Int? = null,
    val bibsModeStoppedAtMillis: Long? = null,
)