package mobile.racemaster.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode

private val BEACON_CLOSE_NOTE_REGEX = Regex("""beacon=(\d+) battery=(\d+)""")
private val BEACON_REOPENED_NOTE_REGEX = Regex("""beacon=(\d+)""")

/** Parses a CLOSE row's [HistoryLineEntity.note] ("beacon=<n> battery=<pct>") back into
 *  (beaconNumber, batteryPercent) — the one place this format is read back (see
 *  [HistoryAction.CLOSE]'s own doc for why it's encoded in note rather than a column of its
 *  own). Null for any row whose note doesn't match — shouldn't happen for a genuine CLOSE row
 *  written by [CloseModeRepository.recordClose], but a defensively-null return beats a crash on
 *  a malformed/foreign row. */
fun parseBeaconClose(note: String?): Pair<Int, Int>? {
    val match = note?.let { BEACON_CLOSE_NOTE_REGEX.matchEntire(it) } ?: return null
    val (beacon, battery) = match.destructured
    return beacon.toInt() to battery.toInt()
}

/** Parses a CLOSE_REOPENED row's [HistoryLineEntity.note] ("beacon=<n>", no battery — see
 *  [HistoryAction.CLOSE_REOPENED]'s own doc) back into the beacon number. [matchEntire] means
 *  this can never cross-match a CLOSE row's own "beacon=<n> battery=<pct>" note (the trailing
 *  " battery=..." leaves nothing left for an entire-string match), the same guard
 *  [parseBeaconClose]/[mobile.racemaster.data.repository.parseBeaconCount] already rely on for
 *  their own neighboring note formats. */
fun parseBeaconReopened(note: String?): Int? =
    note?.let { BEACON_REOPENED_NOTE_REGEX.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Close Mode's own write path — mirrors [TimeModeRepository]'s shape (plain, non-segmented
 *  insert; no [mobile.racemaster.data.repository.EntryLogModeEngine] reuse, since Close has
 *  neither a split counter nor segments). A beacon re-presented after already being closed is
 *  deliberately **not** deduplicated/upserted — see [HistoryAction.CLOSE]'s own doc — it just
 *  gets a second row, the same append-only, lineNumber-keyed storage every other action already
 *  uses, so "closed ×N" is simply "how many CLOSE rows this race has for this beacon." */
class CloseModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
) {
    suspend fun recordClose(
        raceId: Long,
        beaconNumber: Int,
        bibNumber: Int?,
        batteryPercent: Int,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val race = raceDao.getById(raceId) ?: return@withTransaction
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.CLOSE,
                    action = HistoryAction.CLOSE,
                    bibNumber = bibNumber,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    note = "beacon=$beaconNumber battery=$batteryPercent",
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    /** Writes a [HistoryAction.CLOSE_REOPENED] row for [beaconNumber] if — and only if — its most
     *  recent CLOSE-mode row for [raceId] right now is a CLOSE, i.e. this beacon was marked closed
     *  and has since been heard advertising again. That "is it currently closed" check runs fresh
     *  inside this same write transaction, never from a caller-held snapshot — exactly the
     *  reasoning [CountModeRepository.recordCount]'s own doc gives for why its seen-count
     *  computation moved inside its transaction: two overlapping detections of the same
     *  reappearing beacon must never both decide "currently closed" and double-write. A no-op
     *  once the beacon's already reopened (or was never closed to begin with) — the caller
     *  ([mobile.racemaster.ui.closemode.CloseModeViewModel]'s own debounced detection) can call
     *  this as often as it likes without worrying about duplicate rows. */
    suspend fun recordReopenedIfClosed(
        raceId: Long,
        beaconNumber: Int,
        bibNumber: Int?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val latestIsClose = historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.CLOSE)
                .mapNotNull { row ->
                    val matchesBeacon = when (row.action) {
                        HistoryAction.CLOSE -> parseBeaconClose(row.note)?.first == beaconNumber
                        HistoryAction.CLOSE_REOPENED -> parseBeaconReopened(row.note) == beaconNumber
                        else -> false
                    }
                    if (matchesBeacon) row.lineNumber to (row.action == HistoryAction.CLOSE) else null
                }
                .maxByOrNull { (lineNumber, _) -> lineNumber }
                ?.second ?: false
            if (!latestIsClose) return@withTransaction
            val race = raceDao.getById(raceId) ?: return@withTransaction
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.CLOSE,
                    action = HistoryAction.CLOSE_REOPENED,
                    bibNumber = bibNumber,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    note = "beacon=$beaconNumber",
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    /** Every CLOSE/CLOSE_REOPENED row for [raceId] — "already closed ×N" and "is this beacon
     *  currently closed" are both derived from this list: the former by filtering CLOSE rows on
     *  the beacon number [parseBeaconClose] reads out of [HistoryLineEntity.note], the latter by
     *  which of CLOSE/CLOSE_REOPENED is the *latest* row (by [HistoryLineEntity.lineNumber]) for
     *  that beacon — never "has it ever had a CLOSE row" (see [HistoryAction.CLOSE_REOPENED]'s
     *  own doc). */
    fun observeCloses(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeAllForRaceAndMode(raceId, HistoryMode.CLOSE)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> =
        historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.CLOSE)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> =
        historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.CLOSE)
}
