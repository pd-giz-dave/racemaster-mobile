package mobile.racemaster.data.db.dao

// SQLite's own hard cap on bound parameters per statement (SQLITE_MAX_VARIABLE_NUMBER) — 999 on
// every SQLite version older than 3.32.0 (2020-05-22), still the default compiled into a good
// number of Android OS builds/OEM SQLite forks still in the field (confirmed in the field on an
// "fx_tec" phone: "too many SQL variables" from an `IN (:lineNumbers)` clause with only a few
// hundred entries). A query built by expanding a `lineNumber IN (:lineNumbers)` list parameter —
// Room turns each list element into its own bound `?` — must therefore never hand more than this
// many elements to one statement, leaving headroom for that same statement's other bound
// parameters (raceId, syncedAtMillis, etc.). Any DAO batching a large IN-list update (see
// HistoryLineDao.markSynced/PulledRecordDao.markSynced/markConfirmationRelayed) should chunk
// against this rather than a version-specific/OS-specific higher limit, since there's no reliable
// way to query the connected device's own compiled-in SQLite limit at runtime.
internal const val SQLITE_MAX_IN_LIST_PARAMS = 900
