package mobile.racemaster.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode

// Optional trailing " seen=<n> of=<m>" accepted (and always written — see recordCount) alongside
// the bare "beacon=<n>" this regex has always matched, so a pre-this-change row (bare form) still
// parses. Must NOT also match CloseModeRepository's own "beacon=<n> battery=<pct>" note
// (CountModeRepositoryTest guards this) — the optional group only ever spells out
// " seen=... of=...", nothing else, so a battery note still falls through to no match.
private val BEACON_COUNT_NOTE_REGEX = Regex("""beacon=(\d+)(?: seen=\d+ of=\d+)?""")
private val COUNT_COMPLETE_NOTE_REGEX = Regex("""of=(\d+)""")
private val COUNT_OUTSTANDING_NOTE_REGEX = Regex("""outstanding=(\d+)""")

/** Parses a COUNT_SEEN row's [HistoryLineEntity.note] back into the beacon number — the one
 *  place this format is read back. Null for any row whose note doesn't match. */
fun parseBeaconCount(note: String?): Int? =
    note?.let { BEACON_COUNT_NOTE_REGEX.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Parses a COUNT_COMPLETE row's own "of=<m>" expected total. Null for any row whose note
 *  doesn't match. */
fun parseCountCompleteTotal(note: String?): Int? =
    note?.let { COUNT_COMPLETE_NOTE_REGEX.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Parses a COUNT_STOPPED row's own "outstanding=<n>" count. Null for any row whose note doesn't
 *  match. */
fun parseCountOutstanding(note: String?): Int? =
    note?.let { COUNT_OUTSTANDING_NOTE_REGEX.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

/** Count Mode's own write path — same shape as [CloseModeRepository]. A beacon is only ever
 *  counted once per race *since the last Reset* (unlike Close, re-presentation isn't a concern
 *  here — nothing turns a beacon back into "not yet counted" within the same segment), so
 *  [recordCount] is only ever called once per beacon per segment, gated by the caller's own
 *  "already seen" check (see [HistoryAction.COUNT_SEEN]'s own doc) — this repository itself does
 *  not dedupe. */
class CountModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
) {
    // Scopes [raw] down to the current, not-yet-Reset segment — same generic, LOCATION-anchored
    // mechanism Bibs/Time's own live view uses (see HistoryFold.currentSegmentRows' own doc), so
    // [CountModeViewModel.resetCountMode]'s RESET row (written via
    // RaceRepository.closeCurrentSegment) genuinely zeroes the tally rather than it silently
    // resuming the pre-reset count the next time Count Mode is started. Not location-aware (same
    // as Time, not Bibs — see EntryLogModeEngine's own `locationAware = mode == HistoryMode.BIBS`):
    // Count's own location is always the fixed "Pen" (RaceRepository.recordModeStart), so there's
    // no per-location partitioning to do, only "has this visit been reset or not."
    private fun currentSegment(raw: List<HistoryLineEntity>): List<HistoryLineEntity> =
        currentSegmentRows(
            raw,
            { it.lineNumber },
            { it.refLineNumber },
            { it.note },
            { it.action == HistoryAction.LOCATION },
            { it.action == HistoryAction.RESET },
            { it.action == HistoryAction.UNDO },
            { it.action == HistoryAction.MODE_START || it.action == HistoryAction.NEW_RACE || it.action == HistoryAction.RESET || it.action == HistoryAction.PING },
            locationAware = false,
        )

    /** [totalCount] is the race's own expected total (static for the whole count, so the
     *  caller's own cached value — see [CountModeViewModel.commitDetection] — is safe to pass
     *  straight through); the *seen* tally is deliberately NOT a caller-supplied param — it's
     *  counted fresh from this race's own current-segment COUNT_SEEN rows (see [currentSegment]),
     *  inside this same transaction, right before the insert. Two beacons committing close
     *  together used to both read the ViewModel's own reactive seenBeaconNumbers snapshot before
     *  either write had fed back through it (that Flow only catches up after the DB write below
     *  commits and re-queries), landing the same seenCount on both and then skipping one once the
     *  Flow did catch up — confirmed in the field: "1 of 5" shown for two different beacons, then
     *  jumping straight to "3 of 5". A DB transaction already serializes against any other
     *  recordCount call for this race, so counting here instead is always exactly "how many
     *  distinct beacons this segment has already recorded" at the instant this row is written —
     *  no two calls can ever see the same count. When this beacon brings the count up to the
     *  total, a [HistoryAction.COUNT_COMPLETE] row follows immediately after, in the same
     *  transaction (so it always lands on the very next line number, right behind the beacon that
     *  completed it). */
    suspend fun recordCount(
        raceId: Long,
        beaconNumber: Int,
        bibNumber: Int?,
        totalCount: Int,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val race = raceDao.getById(raceId) ?: return@withTransaction
            val alreadySeen = currentSegment(historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.COUNT))
                .count { it.action == HistoryAction.COUNT_SEEN }
            val seenCount = alreadySeen + 1
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.COUNT,
                    action = HistoryAction.COUNT_SEEN,
                    bibNumber = bibNumber,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    note = "beacon=$beaconNumber seen=$seenCount of=$totalCount",
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
            if (totalCount > 0 && seenCount == totalCount) {
                val updatedRace = raceDao.getById(raceId) ?: return@withTransaction
                historyLineDao.insert(
                    HistoryLineEntity(
                        raceId = raceId,
                        mode = HistoryMode.COUNT,
                        action = HistoryAction.COUNT_COMPLETE,
                        bibNumber = null,
                        splitNumber = null,
                        lineNumber = updatedRace.nextLineNumber,
                        note = "of=$totalCount",
                        timestampMillis = timestampMillis,
                    ),
                )
                raceDao.incrementLineNumber(raceId)
            }
        }
    }

    /** Written once by [CountModeViewModel.stopMode] when Stop is pressed with [outstandingCount]
     *  (> 0) issued beacons still unseen — see [HistoryAction.COUNT_STOPPED]'s own doc. */
    suspend fun recordStopped(
        raceId: Long,
        outstandingCount: Int,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val race = raceDao.getById(raceId) ?: return@withTransaction
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.COUNT,
                    action = HistoryAction.COUNT_STOPPED,
                    bibNumber = null,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    note = "outstanding=$outstandingCount",
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    /** The live, current-segment view — see [currentSegment]'s own doc. Everything before the
     *  most recent Reset is excluded, the same way Bibs/Time's own live views already exclude
     *  whatever an in-context Reset closed out. */
    fun observeCounts(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeAllForRaceAndMode(raceId, HistoryMode.COUNT).map(::currentSegment)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> =
        historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.COUNT)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> =
        historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.COUNT)
}
