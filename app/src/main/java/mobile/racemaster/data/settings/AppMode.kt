package mobile.racemaster.data.settings

import mobile.racemaster.data.db.entity.HistoryMode

// The device's recording mode — mutually exclusive with itself (a phone records at most one of
// these at a time, or none), but no longer with Mule syncing: see
// SettingsRepository.muleSyncEnabled's own doc for why Mule was pulled out of this enum
// entirely rather than staying a 4th, mutually-exclusive value alongside these three.
enum class AppMode { TIME, BIBS, CP }

fun AppMode.toHistoryMode(): HistoryMode = when (this) {
    AppMode.TIME -> HistoryMode.TIME
    AppMode.BIBS -> HistoryMode.BIBS
    AppMode.CP -> HistoryMode.CP
}

// The explicit mode name a MODE_START record's own `note` now states (see SyncRecord's own doc
// for why it no longer relies on bibNumber/splitTime nullness) — title case, matching
// SyncRecordMapping.toServerAction()'s own wire-string convention for every other action.
fun AppMode.wireName(): String = when (this) {
    AppMode.TIME -> "Time"
    AppMode.BIBS -> "Bibs"
    AppMode.CP -> "CP"
}