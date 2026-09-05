package mobile.racemaster.data.settings

// The device's recording mode — mutually exclusive with itself (a phone records at most one of
// these at a time, or none), but no longer with Mule syncing: see
// SettingsRepository.muleSyncEnabled's own doc for why Mule was pulled out of this enum
// entirely rather than staying a 4th, mutually-exclusive value alongside these three.
enum class AppMode { TIME, BIBS, CP }