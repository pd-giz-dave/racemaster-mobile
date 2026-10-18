package mobile.racemaster.data.settings

import mobile.racemaster.data.db.entity.HistoryMode

// The device's recording mode — mutually exclusive with itself (a phone records at most one of
// these at a time, or none), but no longer with Mule syncing: see
// SettingsRepository.muleSyncEnabled's own doc for why Mule was pulled out of this enum
// entirely rather than staying a 3rd, mutually-exclusive value alongside these two. CP used to
// be a 3rd value here too — removed (TODO.md: "A rationalisation") once Bibs became
// location-aware and could record a CPn location (and its Pass action) itself, making CP's own
// screen/repository a functionally-identical duplicate with nothing left to justify its own mode.
enum class AppMode { TIME, BIBS }

fun AppMode.toHistoryMode(): HistoryMode = when (this) {
    AppMode.TIME -> HistoryMode.TIME
    AppMode.BIBS -> HistoryMode.BIBS
}

// The explicit mode name a MODE_START record's own `note` now states (see SyncRecord's own doc
// for why it no longer relies on bibNumber/splitTime nullness) — title case, matching
// SyncRecordMapping.toServerAction()'s own wire-string convention for every other action.
fun AppMode.wireName(): String = when (this) {
    AppMode.TIME -> "Time"
    AppMode.BIBS -> "Bibs"
}