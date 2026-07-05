package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bib_entries",
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
data class BibEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceId: Long,
    val bibNumber: Int?,
    val type: BibEntryType,
    val splitNumber: Int,
    val note: String? = null,
    val timestampMillis: Long,
    // Stable cross-device identifier: local `id` is Room-autoincrement and collides once
    // records from multiple phones are merged by Mule, so this is what travels over
    // BLE/HTTP and is used for sync dedup instead.
    val recordUuid: String = java.util.UUID.randomUUID().toString(),
    val syncedAtMillis: Long? = null,
)
