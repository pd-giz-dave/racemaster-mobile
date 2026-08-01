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
    // Which physical point on the course this device's own records represent (e.g. "Finish",
    // "Start", "Checkpoint 2") — lets more than one device contribute different stations'
    // worth of data for what's otherwise the same race. Not part of `label`: two devices
    // stationed differently for the same physical race must still land under the one shared
    // server-side race folder, not be split into separate ones just because of where they're
    // standing. Carried on every outgoing SyncRecord instead (see SyncRecordMapping's own
    // doc) — the wire protocol has no separate per-race metadata channel to send it through
    // just once.
    val location: String = "Finish",
    val label: String,
    val createdAtMillis: Long,
    val timeModeNextSplit: Int = 1,
    val bibsModeNextSplit: Int = 1,
    // Permanent, race-wide history line counter — completely separate from the two display
    // counters above (which reset per segment on Reset). Only ever incremented, never
    // decremented (not even on Undo): a retired line number is an intentional permanent gap,
    // not something to reuse, so every split/entry/marker this race ever holds gets its own
    // number forever, letting devices sync by delta ("everything after line N").
    val nextLineNumber: Long = 1,
    val timeModeStartedAtMillis: Long? = null,
    val timeModeStoppedAtMillis: Long? = null,
    val bibsRangeStart: Int? = null,
    val bibsRangeCount: Int? = null,
    // Bibs' own started/stopped pair, shaped identically to Time's and CP's own — Bibs still
    // writes a Clock marker row on Start (see BibsModeRepository.startBibsMode/
    // CLOCK_SPLIT_NUMBER), but that row's mere presence is deliberately not what "started" is
    // derived from anymore: relying on it made Bibs the one mode whose "started"/"active"
    // signal couldn't be read directly off this row, for no real reason beyond that marker
    // happening to exist. This is what lets undoing Bibs' very first real entry leave the
    // screen still showing the keypad rather than reverting to a pre-Start state — same as
    // cpModeStartedAtMillis below already does for CP, which never had a marker row to lean on
    // in the first place.
    val bibsModeStartedAtMillis: Long? = null,
    val bibsModeStoppedAtMillis: Long? = null,
    val cpModeNextSplit: Int = 1,
    val cpModeStoppedAtMillis: Long? = null,
    // Time/Bibs' own started-at fields' own sibling — CP also writes a Clock marker row on
    // Start (see CpModeRepository.startCpMode), but exactly like Bibs' own row, its mere
    // presence is deliberately not what "started" is derived from (see
    // bibsModeStartedAtMillis's own doc for why). This is what lets undoing CP's very first
    // Pass/Retire leave the screen still showing the keypad rather than reverting to a
    // pre-Start state.
    val cpModeStartedAtMillis: Long? = null,
    // Which AppMode (TIME/BIBS/MULE) created this race — carried metadata for the sync
    // payload, not used for any local query logic.
    val deviceRole: String? = null,
    // Per-race Racemaster server URL, editable via the race details screen. Independent of
    // the device-wide server URL Mule Mode logs in with — not yet wired into any sync
    // behavior (a broader Mule Mode revamp is planned separately).
    val serverUrl: String? = null,
    // This phone's memorable name (SettingsRepository.getOrCreateDeviceName()) at the moment
    // the race was created — fixed at creation, not updated if the device is later renamed,
    // so history always shows who actually created a given race.
    val createdByDeviceName: String = "",
)