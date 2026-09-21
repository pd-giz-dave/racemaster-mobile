package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "races")
data class RaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Raw user-entered fields, kept separately from the computed label below so a race's
    // details can be re-edited later without having to parse them back out of it. The notion of
    // a "course" distinct from the race itself was dropped in phase 1 (see TODO.md) — a race is
    // now just this name (which may, by convention, carry its own Seniors/Juniors suffix as
    // plain text) and a location; `label` (see buildRaceLabel) is built with an always-blank
    // course, which it already tolerates by omitting that segment entirely.
    val name: String = "",
    // Which physical point on the course this device's own records represent (e.g. "Finish",
    // "Start", "Checkpoint 2") — lets more than one device contribute different stations'
    // worth of data for what's otherwise the same race. Not part of `label`: two devices
    // stationed differently for the same physical race must still land under the one shared
    // server-side race folder, not be split into separate ones just because of where they're
    // standing. Not carried on the wire at all any more — it travels only via `note` on the
    // LOCATION/MODE_START/SETUP boundary-marker rows (see SyncRecord's own doc); this column is
    // this device's own current value, kept in sync by RaceRepository.recordModeStart.
    val location: String = "Finish",
    // This race's single, currently-active recording mode (RaceRepository.recordModeStart's own
    // AppMode.name) — a race records at most one mode at a time now; changing it (via Relocate)
    // writes a fresh LOCATION+MODE_START pair rather than letting more than one mode run
    // concurrently. Null only very transiently between a race row being inserted and
    // recordModeStart's own follow-up write in the same save flow — every race a screen can
    // actually observe has one.
    val mode: String? = null,
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