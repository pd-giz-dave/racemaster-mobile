package mobile.racemaster.data.settings

import mobile.racemaster.data.db.entity.HistoryMode

// The device's recording mode — mutually exclusive with itself (a phone records at most one of
// these at a time, or none), but no longer with Mule syncing: see
// SettingsRepository.muleSyncEnabled's own doc for why Mule was pulled out of this enum
// entirely rather than staying a 3rd, mutually-exclusive value alongside these two. CP used to
// be a 3rd value here too — removed (TODO.md: "A rationalisation") once Bibs became
// location-aware and could record a CPn location (and its Pass action) itself, making CP's own
// screen/repository a functionally-identical duplicate with nothing left to justify its own mode.
// COUNT/CLOSE went the same way (TODO.md: "amalgamate Bibs, Count and Close modes") — Pen and
// Close are now just two more Bibs locations (see LocationValidation.isValidLocationForMode),
// with RacemasterNavHost's own Routes.BIBS_MODE dispatching to CountModeScreen/CloseModeScreen
// (unchanged otherwise) purely by the race's current location. HistoryMode itself still has
// distinct COUNT/CLOSE values — see the location-aware toHistoryMode(location) overload below —
// since CountModeRepository/CloseModeRepository's own rows, queries, and peer-sync "which cached
// bucket" filters (PeerSyncRepository's `mode` column) all still key off that distinction; only
// the top-level recording-mode choice itself amalgamated.
enum class AppMode { TIME, BIBS }

// Bucket-#2 (peer-discovery/grouping) mapping — what this device's own race's AppMode resolves
// to for MuleGattProfile's advertised mode byte and PeerGroupDetector's groupKey (which already
// factors in location separately for BIBS, via PeerSyncRepository.groupKey's own locationCode
// handling) — so Pen/Close naturally still group correctly with other phones at the same
// location without this needing a distinct value of its own. NOT what HistoryLineEntity rows get
// tagged with — see the location-aware overload below for that (bucket #1).
fun AppMode.toHistoryMode(): HistoryMode = when (this) {
    AppMode.TIME -> HistoryMode.TIME
    AppMode.BIBS -> HistoryMode.BIBS
}

// Bucket-#1 (which HistoryLineEntity/PeerSeenRecordEntity rows this actually reads/writes)
// mapping — location, not just mode, decides the bucket now that Pen/Close are Bibs locations
// rather than their own AppMode values: RaceRepository.recordModeStart/ensureOpenSegment tag
// their rows with this so CountModeRepository/CloseModeRepository keep finding (and only ever
// finding) exactly the rows they always have, and MuleSyncEngine/PeerSyncEngine's own pull-merge
// side uses it to decide countStatusOnly and to tag cached peer rows the same way. Time is never
// location-aware (TODO.md: "A rationalisation"), so [location] is ignored for it.
fun AppMode.toHistoryMode(location: String): HistoryMode = when {
    this == AppMode.TIME -> HistoryMode.TIME
    location.equals("Pen", ignoreCase = true) -> HistoryMode.COUNT
    location.equals("Close", ignoreCase = true) -> HistoryMode.CLOSE
    else -> HistoryMode.BIBS
}

// The explicit mode name a MODE_START record's own `note` now states (see SyncRecord's own doc
// for why it no longer relies on bibNumber/splitTime nullness) — title case, matching
// SyncRecordMapping.toServerAction()'s own wire-string convention for every other action.
fun AppMode.wireName(): String = when (this) {
    AppMode.TIME -> "Time"
    AppMode.BIBS -> "Bibs"
}