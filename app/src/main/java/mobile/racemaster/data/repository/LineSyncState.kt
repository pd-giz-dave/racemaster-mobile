package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.LineSyncEntity

/**
 * The three-state visual cue for one history line — red until it's left this device at all,
 * orange once some device (a mule, or anything else that just took a relay copy) has it but
 * before that's confirmed reaching a genuine data sink, green only once it has. Mirrors
 * [HistoryLineEntity.syncedAtMillis]'s own repurposed meaning ("confirmed at a sink," not
 * merely "handed to somebody") and [LineSyncEntity.isSink]'s role in getting there — see both
 * fields' own docs for the full reasoning.
 */
enum class LineSyncState { NOT_SYNCED, RELAYED, SYNCED }

/**
 * Computes [LineSyncState] for one line: [syncedAtMillis] non-null means it's confirmed at a
 * sink (green) outright — the fastest, most common check, and already exactly what
 * [HistoryLineEntity.syncedAtMillis] means. Otherwise, [hasAnySync] (whether at least one
 * [LineSyncEntity] row exists for this line, regardless of its own isSink — i.e. it's been
 * relayed to *somebody*, sink or not) distinguishes orange from red. Pulled out as a pure
 * function, mirroring this codebase's established pattern for testable per-line decisions (see
 * e.g. [hasRealEntries], [isModeStarted]), so every mode's ViewModel computes this identically
 * rather than each hand-rolling its own version of the same three-way check.
 */
fun lineSyncState(syncedAtMillis: Long?, hasAnySync: Boolean): LineSyncState = when {
    syncedAtMillis != null -> LineSyncState.SYNCED
    hasAnySync -> LineSyncState.RELAYED
    else -> LineSyncState.NOT_SYNCED
}

/** The set of line numbers with at least one [LineSyncEntity] row, regardless of its own
 *  isSink — i.e. relayed to somebody, sink or not — for [lineSyncState]'s own `hasAnySync`
 *  input. Every consuming ViewModel derives this identically from
 *  [mobile.racemaster.data.repository.RaceRepository.observeLineSyncs]'s own output rather
 *  than each re-deriving it inline. */
fun linesWithAnySync(lineSyncs: List<LineSyncEntity>): Set<Long> = lineSyncs.map { it.lineNumber }.toSet()
