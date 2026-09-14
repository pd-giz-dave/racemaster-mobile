package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Mule's targeted-relay inbox: a progress payload this device is holding only to forward on to
// some OTHER device ([targetDeviceId]), never its own race — the progress-delivery counterpart
// to [PulledRecordEntity]'s own pulled-records inbox for records (see that entity's own doc for
// the shape this deliberately mirrors: a flat holding table, not hung off a RaceEntity, since a
// pure relay hop has no race of its own). A phone receives one of these via
// PROGRESS_CHARACTERISTIC_UUID whenever the payload's own targetDeviceId doesn't match its own
// device id — see mobile.racemaster.data.mule.PeripheralSyncService.handleProgressPayload — which
// means "forward this on, don't adopt it" (a targetDeviceId that DOES match is the adoption
// trigger instead — see mobile.racemaster.data.repository.RaceRepository.adoptRaceIdentity, no
// inbox row involved there at all). Unique on targetDeviceId: only the most recently received
// delta for a given target is ever worth holding — an older, superseded one is simply replaced,
// never accumulated, since a fresher one already supersedes it entirely.
@Entity(tableName = "targeted_progress", indices = [Index("targetDeviceId", unique = true)])
data class TargetedProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetDeviceId: String,
    // The full wire ProgressPayload (already in delta form, whatever the sender computed it
    // against), verbatim — this device never inspects/merges it, only ever re-serves it as-is to
    // whichever hop it hands off to next (see MuleSyncEngine's own targeted-delivery call sites).
    val payloadJson: String,
    val receivedAtMillis: Long,
)
