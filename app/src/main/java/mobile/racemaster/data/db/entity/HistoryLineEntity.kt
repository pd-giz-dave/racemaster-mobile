package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Unified history table for both Time Mode and Bibs Mode — replaces the old separate
// finish_splits/bib_entries tables. A single race can legitimately hold rows of both `mode`s
// (nothing in the app prevents switching AppMode without starting a new race), and both
// families already shared one RaceEntity.nextLineNumber sequence even before this merge — this
// table just lets that shared sequence actually be queried/rendered as one true chronology
// instead of two separately-sorted lists.
@Entity(
    tableName = "history_lines",
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
data class HistoryLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raceId: Long,
    // Which family this row belongs to — see HistoryMode's doc.
    val mode: HistoryMode,
    val action: HistoryAction,
    // Non-null for a Bibs-family row whose action is in BIB_REQUIRED_ACTIONS, or for a Bibs
    // UNDO marker (copied from the root row it hides, purely so the marker itself can display
    // which bib got undone) — never meaningful for any other action.
    val bibNumber: Int? = null,
    val splitNumber: Int,
    // Permanent, ascending, race-wide history position — see RaceEntity.nextLineNumber.
    // Assigned once at insert, immutable afterward, never reused even if this row is deleted.
    val lineNumber: Long,
    // Non-null means this row is either an edit-echo (a full copy of an earlier row with the
    // edited field(s) changed) or an undo-marker (action == UNDO) hiding its target — in both
    // cases this always points at the original ROOT row's lineNumber, never at an intermediate
    // echo, so reconstructing "what's currently visible" only ever needs one level of grouping.
    // See HistoryFold.foldLatestVisible.
    val refLineNumber: Long? = null,
    // Operator free-text only, from now on — Time Mode's markers used to be smuggled through
    // this same column via reserved strings; they now live in `action` instead.
    val note: String? = null,
    val timestampMillis: Long,
    // Stable cross-device identifier: local `id` is Room-autoincrement and collides once
    // records from multiple phones are merged by Mule, so this is what travels over BLE/HTTP
    // and is used for sync dedup instead.
    val recordUuid: String = java.util.UUID.randomUUID().toString(),
    val syncedAtMillis: Long? = null,
    // No per-row deviceName: every row in a race's local history was always written by this
    // same physical device (Mule-pulled records live in the wholly separate PulledRecordEntity
    // table, never merged back in) — see RaceEntity.createdByDeviceName, which is what outgoing
    // wire records source their deviceName from instead.
)
