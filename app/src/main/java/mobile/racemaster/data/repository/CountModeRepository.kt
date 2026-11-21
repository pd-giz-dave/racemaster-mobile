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

/** Parses a COUNT_SEEN/COUNT_EJECTED/COUNT_UNEJECTED row's [HistoryLineEntity.note] back into the
 *  beacon number — the one place this format is read back. Null for any row whose note doesn't
 *  match. */
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

/** A beacon's own informational status in the outstanding list, once it's ever been ejected —
 *  TODO.md: an ejected beacon "should appear in the 'to be seen' list with an 'ejected' link"
 *  (tap to un-eject), and once un-ejected "should remain tagged 'was-ejected' until its re-seen".
 *  A beacon with no status (absent from [statusByBeacon]'s own result) has simply never been
 *  ejected — plain outstanding, nothing to show. */
enum class CountBeaconStatus { EJECTED, WAS_EJECTED }

/** Each beacon's own most recent COUNT_EJECTED/COUNT_UNEJECTED row in [rawCurrentSegmentRows], by
 *  [HistoryLineEntity.lineNumber] — a beacon re-ejected after being un-ejected, or un-ejected
 *  twice, is still just "whichever happened last." A COUNT_SEEN row (re-counted since its last
 *  eject) clears a beacon's status entirely, same as if it had never been ejected — there is
 *  nothing left to tag once a fresh count has genuinely superseded it.
 *
 *  Deliberately reads [rawCurrentSegmentRows] straight (NOT [CountModeRepository]'s own
 *  fold-collapsed [CountModeRepository.observeCounts]) — [HistoryAction.COUNT_EJECTED] being an
 *  undo-marker for the live counted-set's own purposes (see that action's own doc) means the
 *  folded view never shows a COUNT_EJECTED/COUNT_UNEJECTED row at all, exactly the way a plain
 *  Undo makes its own target vanish rather than visibly "crossed out" — this status tag is a
 *  separate, purely informational read of the same current-segment window, bypassing that fold
 *  on purpose. See [CountModeRepository.currentSegmentRaw]. */
fun statusByBeacon(rawCurrentSegmentRows: List<HistoryLineEntity>): Map<Int, CountBeaconStatus> {
    val latest = LinkedHashMap<Int, HistoryLineEntity>()
    for (row in rawCurrentSegmentRows.sortedBy { it.lineNumber }) {
        if (row.action != HistoryAction.COUNT_SEEN && row.action != HistoryAction.COUNT_EJECTED && row.action != HistoryAction.COUNT_UNEJECTED) continue
        val beaconNumber = parseBeaconCount(row.note) ?: continue
        latest[beaconNumber] = row
    }
    return latest.mapNotNull { (beaconNumber, row) ->
        when (row.action) {
            HistoryAction.COUNT_EJECTED -> beaconNumber to CountBeaconStatus.EJECTED
            HistoryAction.COUNT_UNEJECTED -> beaconNumber to CountBeaconStatus.WAS_EJECTED
            else -> null
        }
    }.toMap()
}

/** Count Mode's own write path — same shape as [CloseModeRepository]. A beacon is only ever
 *  counted once per race *since the last Reset* (unlike Close, re-presentation isn't a concern
 *  here — nothing turns a beacon back into "not yet counted" within the same segment except an
 *  explicit eject, see [HistoryAction.COUNT_EJECTED]'s own doc), so [recordCount] is only ever
 *  called once per beacon per segment, gated by the caller's own "already seen or still ejected"
 *  check (see [HistoryAction.COUNT_SEEN]'s own doc) — this repository itself does not dedupe. */
class CountModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
) {
    // Scopes [raw] down to the current, not-yet-Reset segment AND folds it (an ejected beacon's
    // own COUNT_SEEN row is excluded from the result entirely — see HistoryAction.COUNT_EJECTED's
    // own doc) — same generic, LOCATION-anchored mechanism Bibs/Time's own live view uses (see
    // HistoryFold.currentSegmentRows' own doc), so [CountModeViewModel.resetCountMode]'s RESET row
    // (written via RaceRepository.closeCurrentSegment) genuinely zeroes the tally rather than it
    // silently resuming the pre-reset count the next time Count Mode is started. Not location-
    // aware (same as Time, not Bibs — see EntryLogModeEngine's own `locationAware = mode ==
    // HistoryMode.BIBS`): Count's own location is always the fixed "Pen"
    // (RaceRepository.recordModeStart), so there's no per-location partitioning to do, only "has
    // this visit been reset or not."
    private fun currentSegment(raw: List<HistoryLineEntity>): List<HistoryLineEntity> =
        currentSegmentRows(
            raw,
            { it.lineNumber },
            { it.refLineNumber },
            { it.note },
            { it.action == HistoryAction.LOCATION },
            { it.action == HistoryAction.RESET },
            { it.action == HistoryAction.UNDO || it.action == HistoryAction.COUNT_EJECTED },
            { it.action == HistoryAction.MODE_START || it.action == HistoryAction.NEW_RACE || it.action == HistoryAction.RESET || it.action == HistoryAction.PING },
            locationAware = false,
        )

    // The same current-segment (not-yet-Reset) window as [currentSegment], but UNFOLDED — i.e.
    // without treating COUNT_EJECTED as an undo-marker — so a beacon's own COUNT_EJECTED/
    // COUNT_UNEJECTED rows are still visible for [statusByBeacon] to read, the way [currentSegment]
    // itself deliberately no longer allows (see that function's own doc). Calls the same lower-
    // level HistoryFold building blocks [currentSegmentRows] itself composes, just skipping its
    // own [foldLatestVisible] step.
    private fun currentSegmentRaw(raw: List<HistoryLineEntity>): List<HistoryLineEntity> {
        val targets = resetTargets(raw, { it.refLineNumber }, { it.action == HistoryAction.RESET })
        val displayFiltered = raw.filterNot {
            it.action == HistoryAction.MODE_START || it.action == HistoryAction.NEW_RACE || it.action == HistoryAction.RESET || it.action == HistoryAction.PING
        }
        val ascending = displayFiltered.sortedBy { it.lineNumber }
        val allVisits = visits(ascending, { it.lineNumber }, { it.note }, { it.action == HistoryAction.LOCATION })
        val current = currentSegmentVisits(allVisits, targets, locationAware = false)
        return current.flatMap { it.rows }
    }

    /** [totalCount] is the race's own expected total (static for the whole count, so the
     *  caller's own cached value — see [CountModeViewModel.commitDetection] — is safe to pass
     *  straight through); the *seen* tally is deliberately NOT a caller-supplied param — it's
     *  counted fresh from this race's own current-segment COUNT_SEEN rows (see [currentSegment],
     *  already eject-aware), inside this same transaction, right before the insert. Two beacons
     *  committing close together used to both read the ViewModel's own reactive seenBeaconNumbers
     *  snapshot before either write had fed back through it (that Flow only catches up after the
     *  DB write below commits and re-queries), landing the same seenCount on both and then
     *  skipping one once the Flow did catch up — confirmed in the field: "1 of 5" shown for two
     *  different beacons, then jumping straight to "3 of 5". A DB transaction already serializes
     *  against any other recordCount call for this race, so counting here instead is always
     *  exactly "how many distinct beacons this segment has already recorded" at the instant this
     *  row is written — no two calls can ever see the same count. When this beacon brings the
     *  count up to the total, a [HistoryAction.COUNT_COMPLETE] row follows immediately after, in
     *  the same transaction (so it always lands on the very next line number, right behind the
     *  beacon that completed it). */
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

    /** "Eject" one seen beacon (TODO.md) — the same append-only "undo marker" shape Bibs/Time's
     *  own Undo Last uses (see [HistoryAction.COUNT_EJECTED]'s own doc): refLineNumber points at
     *  [beaconNumber]'s own COUNT_SEEN row in the current segment, so [currentSegment]'s own fold
     *  drops that row's whole group and the beacon reverts to outstanding.
     *
     *  Also doubles as "re-eject" for a beacon currently tagged WAS_EJECTED (TODO.md: the
     *  outstanding list's own "was-ejected" tag is itself a re-eject link) — such a beacon has no
     *  current COUNT_SEEN row to target at all (it was never re-counted since its last
     *  un-eject), so there's nothing for the fold to exclude; this writes a second, plain
     *  COUNT_EJECTED row with no refLineNumber instead, the same non-fold-targeting shape
     *  [recordUneject] already uses, just enough for [statusByBeacon] to read EJECTED again.
     *
     *  A no-op for any other beacon (never seen, or already EJECTED) — the caller
     *  (CountModeViewModel.ejectBeacon) only ever offers this from the seen list or a
     *  WAS_EJECTED tag, but this stays safe to call either way, mirroring undoMostRecent's own
     *  defensive `?: return@withTransaction`. */
    suspend fun recordEject(
        raceId: Long,
        beaconNumber: Int,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val raw = historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.COUNT)
            val target = currentSegment(raw).firstOrNull {
                it.action == HistoryAction.COUNT_SEEN && parseBeaconCount(it.note) == beaconNumber
            }
            val race = raceDao.getById(raceId) ?: return@withTransaction
            if (target == null && statusByBeacon(currentSegmentRaw(raw))[beaconNumber] != CountBeaconStatus.WAS_EJECTED) {
                return@withTransaction
            }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.COUNT,
                    action = HistoryAction.COUNT_EJECTED,
                    bibNumber = target?.bibNumber,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    note = "beacon=$beaconNumber",
                    timestampMillis = timestampMillis,
                    refLineNumber = target?.lineNumber,
                ),
            )
            raceDao.incrementLineNumber(raceId)
        }
    }

    /** The operator's own "let this one back in" (TODO.md) — see [HistoryAction.COUNT_UNEJECTED]'s
     *  own doc for why this is a plain status marker, not another refLineNumber/undo-fold row. A
     *  no-op unless [beaconNumber]'s current [statusByBeacon] status is actually EJECTED — the
     *  caller (CountModeViewModel.unejectBeacon) only ever offers this for an ejected beacon, but
     *  this stays safe to call either way. */
    suspend fun recordUneject(
        raceId: Long,
        beaconNumber: Int,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val raw = historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.COUNT)
            if (statusByBeacon(currentSegmentRaw(raw))[beaconNumber] != CountBeaconStatus.EJECTED) return@withTransaction
            val race = raceDao.getById(raceId) ?: return@withTransaction
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.COUNT,
                    action = HistoryAction.COUNT_UNEJECTED,
                    bibNumber = null,
                    splitNumber = null,
                    lineNumber = race.nextLineNumber,
                    note = "beacon=$beaconNumber",
                    timestampMillis = timestampMillis,
                ),
            )
            raceDao.incrementLineNumber(raceId)
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
     *  whatever an in-context Reset closed out; an ejected-and-not-yet-reseen beacon's own
     *  COUNT_SEEN row is excluded too (see [HistoryAction.COUNT_EJECTED]'s own doc) — this is the
     *  one place "is this beacon counted right now" is decided, a plain `count { action ==
     *  COUNT_SEEN }` over the result. For a beacon's own ejected/was-ejected status instead, see
     *  [observeStatusByBeacon]. */
    fun observeCounts(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeAllForRaceAndMode(raceId, HistoryMode.COUNT).map(::currentSegment)

    /** See [statusByBeacon]/[currentSegmentRaw]'s own docs — a separate, unfolded read of the
     *  same current-segment window [observeCounts] folds away the eject/uneject rows from. */
    fun observeStatusByBeacon(raceId: Long): Flow<Map<Int, CountBeaconStatus>> =
        historyLineDao.observeAllForRaceAndMode(raceId, HistoryMode.COUNT).map { statusByBeacon(currentSegmentRaw(it)) }

    fun observeUnsyncedCount(raceId: Long): Flow<Int> =
        historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.COUNT)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> =
        historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.COUNT)
}
