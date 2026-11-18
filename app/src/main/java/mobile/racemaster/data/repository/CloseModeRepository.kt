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

    /** Every CLOSE row for [raceId] — "already closed" / "closed ×N" are both derived from this
     *  list by filtering on the beacon number [parseBeaconClose] reads out of
     *  [HistoryLineEntity.note]. */
    fun observeCloses(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeAllForRaceAndMode(raceId, HistoryMode.CLOSE)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> =
        historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.CLOSE)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> =
        historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.CLOSE)
}
