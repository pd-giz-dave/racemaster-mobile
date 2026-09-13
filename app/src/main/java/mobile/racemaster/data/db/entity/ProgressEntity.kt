package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// One row per LOCAL race — a race-wide progress.json snapshot this device has received (over
// BLE from the racemaster web app, or fetched directly over HTTP), persisted so it survives a
// race switch or app restart, unlike ProgressRepository's own in-memory `current` cache (which
// only ever holds the most recently received copy, for the currently active race). This is what
// lets the Races page list/view/delete progress independently of which race happens to be
// active right now — see ProgressRepository.observeStored/delete.
//
// Deliberately NOT foreign-keyed/cascade-deleted against RaceEntity: a locally-recorded race and
// the server-side progress data pulled for it are two genuinely independent things (the same
// reasoning PulledRecordEntity's own doc gives for staying separate from a local race's own
// history) — deleting one must never silently take the other with it, and an operator who's
// already deleted their local race history may still want to keep the bib-allocations/progress
// snapshot around for reference.
//
// payloadJson is the entries list only (List<ProgressEntry>, via kotlinx.serialization), not the
// whole ProgressPayload — raceName/raceDate/generatedAt already have their own columns, mirroring
// PulledRecordEntity.payloadJson's own "store the wire shape verbatim, decode back on read"
// pattern rather than needing a Room TypeConverter for the nested Map<String, String> inside
// each ProgressEntry.
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val raceId: Long,
    val raceLabel: String,
    val generatedAt: String,
    val raceName: String,
    val raceDate: String,
    val entriesJson: String,
    val storedAtMillis: Long,
)
