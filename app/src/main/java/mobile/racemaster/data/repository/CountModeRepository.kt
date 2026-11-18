package mobile.racemaster.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode

private val BEACON_COUNT_NOTE_REGEX = Regex("""beacon=(\d+)""")

/** Parses a COUNT_SEEN row's [HistoryLineEntity.note] ("beacon=<n>") back into the beacon
 *  number — the one place this format is read back. Null for any row whose note doesn't match. */
fun parseBeaconCount(note: String?): Int? =
    note?.let { BEACON_COUNT_NOTE_REGEX.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Count Mode's own write path — same shape as [CloseModeRepository]. A beacon is only ever
 *  counted once per race (unlike Close, re-presentation isn't a concern here — nothing turns a
 *  beacon back into "not yet counted"), so [recordCount] is only ever called once per beacon per
 *  race, gated by the caller's own "already seen" check (see [HistoryAction.COUNT_SEEN]'s own
 *  doc) — this repository itself does not dedupe. */
class CountModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
) {
    suspend fun recordCount(
        raceId: Long,
        beaconNumber: Int,
        bibNumber: Int?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val race = raceDao.getById(raceId) ?: return@withTransaction
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.COUNT,
                    action = HistoryAction.COUNT_SEEN,
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

    fun observeCounts(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeAllForRaceAndMode(raceId, HistoryMode.COUNT)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> =
        historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.COUNT)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> =
        historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.COUNT)
}
