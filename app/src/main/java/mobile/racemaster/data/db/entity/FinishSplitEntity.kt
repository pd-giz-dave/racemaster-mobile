package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "finish_splits",
    foreignKeys = [
        ForeignKey(
            entity = RaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["raceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("raceId")],
)
data class FinishSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceId: Long,
    val splitNumber: Int,
    val timestampMillis: Long,
    val note: String? = null,
    // Stable cross-device identifier: local `id` is Room-autoincrement and collides once
    // records from multiple phones are merged by Mule, so this is what travels over
    // BLE/HTTP and is used for sync dedup instead.
    val recordUuid: String = java.util.UUID.randomUUID().toString(),
    val syncedAtMillis: Long? = null,
)