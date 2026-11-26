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
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity
import mobile.racemaster.data.mule.toHistoryAction

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
fun statusByBeacon(rawCurrentSegmentRows: List<HistoryLineEntity>): Map<Int, CountBeaconStatus> =
    latestCountEventRowByBeacon(rawCurrentSegmentRows).mapNotNull { (beaconNumber, row) ->
        when (row.action) {
            HistoryAction.COUNT_EJECTED -> beaconNumber to CountBeaconStatus.EJECTED
            HistoryAction.COUNT_UNEJECTED -> beaconNumber to CountBeaconStatus.WAS_EJECTED
            else -> null
        }
    }.toMap()

/** The shared "latest row per beacon" sweep [statusByBeacon] itself reduces down to just the
 *  EJECTED/WAS_EJECTED enum above — pulled out so [mobile.racemaster.ui.countmode.CountModeViewModel]
 *  can read the full row (TODO.md "the ejected lines should follow the same colouring scheme as
 *  the seen lines" needs each row's own lineNumber/syncedAtMillis to compute a LineSyncState,
 *  not just the status enum [statusByBeacon] throws away). Includes the COUNT_SEEN rows too (not
 *  just EJECTED/UNEJECTED) — a beacon re-counted since its last eject must map to its new
 *  COUNT_SEEN row here, same as [statusByBeacon]'s own fold already drops its status entirely in
 *  that case. */
fun latestCountEventRowByBeacon(rawCurrentSegmentRows: List<HistoryLineEntity>): Map<Int, HistoryLineEntity> {
    val latest = LinkedHashMap<Int, HistoryLineEntity>()
    for (row in rawCurrentSegmentRows.sortedBy { it.lineNumber }) {
        if (row.action != HistoryAction.COUNT_SEEN && row.action != HistoryAction.COUNT_EJECTED && row.action != HistoryAction.COUNT_UNEJECTED) continue
        val beaconNumber = parseBeaconCount(row.note) ?: continue
        latest[beaconNumber] = row
    }
    return latest
}

/** Cross-device counterpart of [statusByBeacon] (TODO.md: "if a beacon is ejected it must be
 *  flagged as such on all phones counting beacons ... do this via bluetooth so its instant") —
 *  merges this device's own unfolded current-segment rows ([localRows], see
 *  [CountModeRepository.observeStatusEvents]) with a same-group peer's own already-merged rows
 *  ([peerRows], see [mobile.racemaster.data.peersync.PeerSyncRepository.observeGroup]), ordered by
 *  wall-clock [HistoryLineEntity.timestampMillis]/[PeerSeenRecordEntity.timestampMillis] rather
 *  than [HistoryLineEntity.lineNumber] the way [statusByBeacon] itself orders — unlike a single
 *  device's own gapless, clock-skew-immune sequence number, lineNumber starts over at 1 on every
 *  device and carries no meaning once rows from more than one device are mixed together;
 *  timestamp is the only ordering they share (same reasoning
 *  [mobile.racemaster.data.peersync.PeerSyncRepository]'s own `latestOriginByKey` already applies
 *  for "most recent reporting peer"). A beacon eject/uneject race between two phones is resolved
 *  the same "whichever happened last, by clock" way a same-device race already was — there is no
 *  stronger ordering available across independent devices' clocks, same tradeoff this whole
 *  feature already accepts for "most recent reporting peer" elsewhere. */
fun mergedStatusByBeacon(localRows: List<HistoryLineEntity>, peerRows: List<PeerSeenRecordEntity>): Map<Int, CountBeaconStatus> {
    data class Event(val beaconNumber: Int, val action: HistoryAction, val timestampMillis: Long)
    val localEvents = localRows.mapNotNull { row ->
        if (row.action != HistoryAction.COUNT_SEEN && row.action != HistoryAction.COUNT_EJECTED && row.action != HistoryAction.COUNT_UNEJECTED) {
            return@mapNotNull null
        }
        parseBeaconCount(row.note)?.let { Event(it, row.action, row.timestampMillis) }
    }
    val peerEvents = peerRows.mapNotNull { row ->
        // row.action is the wire string (e.g. "CountEjected" — see toServerAction/toHistoryAction's
        // own doc), never the HistoryAction enum's own .name ("COUNT_EJECTED") — HistoryAction.valueOf
        // here silently failed on every single peer row via runCatching{}.getOrNull(), so no peer
        // eject/uneject/seen ever once reached this merge; confirmed live as a same-group eject
        // that synced correctly into peer_seen_records but never once showed as ejected in the UI.
        val action = row.toHistoryAction()
        if (action != HistoryAction.COUNT_SEEN && action != HistoryAction.COUNT_EJECTED && action != HistoryAction.COUNT_UNEJECTED) return@mapNotNull null
        row.beaconNumber?.let { Event(it, action, row.timestampMillis) }
    }
    val latest = LinkedHashMap<Int, Event>()
    for (event in (localEvents + peerEvents).sortedBy { it.timestampMillis }) {
        latest[event.beaconNumber] = event
    }
    return latest.mapNotNull { (beaconNumber, event) ->
        when (event.action) {
            HistoryAction.COUNT_EJECTED -> beaconNumber to CountBeaconStatus.EJECTED
            HistoryAction.COUNT_UNEJECTED -> beaconNumber to CountBeaconStatus.WAS_EJECTED
            else -> null
        }
    }.toMap()
}

/** Count Mode peer-sync's own filtered view of [rawCurrentSegmentRows] — just the COUNT_SEEN/
 *  COUNT_EJECTED/COUNT_UNEJECTED rows a peer's "seen" union (see
 *  [mobile.racemaster.data.peersync.PeerSyncRepository.extractBeaconNumber]/
 *  observeSeenBeaconNumbers, which only ever look at these three actions) actually needs — never
 *  the Ping/Location/Mode-start/etc. noise the race's *full* history otherwise carries (confirmed
 *  live: a Count Mode peer-sync pull reusing the general relay-sync computeRecordsPayload path
 *  was transferring routine Ping keepalive rows alongside the real seen/ejected events, wasting
 *  BLE chunk budget on data no Count Mode reader ever consults). A pure top-level function (like
 *  [statusByBeacon] above), not an instance method, so it's directly unit-testable against a
 *  plain row list without a fake DAO — [CountModeRepository.countStatusEventsSince] is a thin
 *  wrapper supplying [rawCurrentSegmentRows] from [CountModeRepository.currentSegmentRaw].
 *  [sinceLineNumber]/[maxRecords] page the same way computeRecordsPayload's general path does —
 *  see PullRequest.maxRecords's own doc. */
fun countStatusEventsFrom(rawCurrentSegmentRows: List<HistoryLineEntity>, sinceLineNumber: Long, maxRecords: Int?): List<HistoryLineEntity> {
    val relevant = rawCurrentSegmentRows.filter {
        (it.action == HistoryAction.COUNT_SEEN || it.action == HistoryAction.COUNT_EJECTED || it.action == HistoryAction.COUNT_UNEJECTED) &&
            it.lineNumber > sinceLineNumber
    }.sortedBy { it.lineNumber }
    return if (maxRecords != null) relevant.take(maxRecords) else relevant
}

/** The highest lineNumber among [countStatusEventsFrom]'s own filtered rows (0 if none) — what a
 *  Count Mode peer-sync pull compares its own cursor against instead of the race's raw
 *  DeviceInfo.lastLineNumber, which also counts every Ping/Location/etc. line this feature has no
 *  use for and would otherwise never stop reporting as "something new to pull". */
fun lastCountStatusLineNumberFrom(rawCurrentSegmentRows: List<HistoryLineEntity>): Long =
    rawCurrentSegmentRows
        .filter { it.action == HistoryAction.COUNT_SEEN || it.action == HistoryAction.COUNT_EJECTED || it.action == HistoryAction.COUNT_UNEJECTED }
        .maxOfOrNull { it.lineNumber } ?: 0L

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
     *  defensive `?: return@withTransaction`.
     *
     *  TODO.md "a bib must be ejectable no matter what phone did the initial detection": a beacon
     *  only ever counted by a same-group peer, never locally, has no local COUNT_SEEN [target]
     *  row for this device to point refLineNumber at — [allowWithoutLocalRecord] is the caller's
     *  (CountModeViewModel) own assurance, already verified against the peer-merged union/status,
     *  that ejecting it anyway is legitimate; this device then writes a plain, non-fold-targeting
     *  COUNT_EJECTED row (same shape the WAS_EJECTED re-eject case already uses) with [fallbackBibNumber]
     *  (the race-wide beacon→bib mapping, not scoped to any one device) standing in for the bib
     *  number a local [target] row would otherwise have supplied. */
    suspend fun recordEject(
        raceId: Long,
        beaconNumber: Int,
        fallbackBibNumber: Int? = null,
        allowWithoutLocalRecord: Boolean = false,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val raw = historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.COUNT)
            val target = currentSegment(raw).firstOrNull {
                it.action == HistoryAction.COUNT_SEEN && parseBeaconCount(it.note) == beaconNumber
            }
            val race = raceDao.getById(raceId) ?: return@withTransaction
            if (target == null &&
                statusByBeacon(currentSegmentRaw(raw))[beaconNumber] != CountBeaconStatus.WAS_EJECTED &&
                !allowWithoutLocalRecord
            ) {
                return@withTransaction
            }
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.COUNT,
                    action = HistoryAction.COUNT_EJECTED,
                    bibNumber = target?.bibNumber ?: fallbackBibNumber,
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
     *  own doc for why this is a plain status marker, not another undo-fold row (it never makes
     *  its own target vanish the way [recordEject]'s own refLineNumber does). TODO.md: "CountUnejected
     *  lines should contain the bib number and ref line to the ejection (if it is local)" — [target]
     *  below is the most recent local COUNT_EJECTED row for this beacon in the current segment, if
     *  one exists; its [refLineNumber] and bib number are carried onto this row exactly the way
     *  [recordEject] already carries its own target's bib number, which Race History already
     *  renders generically as "Edited from L{n}" (see RaceHistoryDetailViewModel's own
     *  `editedFromLineNumber = it.refLineNumber`) with no further UI wiring needed. A beacon
     *  un-ejected from a peer's own report (no local ejection row at all — TODO.md's "flagged as
     *  such on all phones" cuts both ways, see [allowWithoutLocalRecord]) has no [target] to
     *  reference; [fallbackBibNumber] (the race-wide beacon->bib mapping, mirroring [recordEject]'s
     *  own parameter) stands in for the bib number a local target would otherwise have supplied.
     *
     *  A no-op unless [beaconNumber]'s current [statusByBeacon] status is actually EJECTED — the
     *  caller (CountModeViewModel.unejectBeacon) only ever offers this for an ejected beacon, but
     *  this stays safe to call either way. [allowWithoutLocalRecord] mirrors [recordEject]'s own
     *  parameter — a beacon ejected only by a same-group peer has no local COUNT_EJECTED row for
     *  this device's own [statusByBeacon] to see, so without it this device could never un-eject
     *  a peer's own ejection. */
    suspend fun recordUneject(
        raceId: Long,
        beaconNumber: Int,
        fallbackBibNumber: Int? = null,
        allowWithoutLocalRecord: Boolean = false,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val raw = historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.COUNT)
            val currentRaw = currentSegmentRaw(raw)
            if (statusByBeacon(currentRaw)[beaconNumber] != CountBeaconStatus.EJECTED && !allowWithoutLocalRecord) {
                return@withTransaction
            }
            // The most recent local ejection for this beacon, if any — a re-eject cycle can
            // leave more than one COUNT_EJECTED row for the same beacon in this segment, and
            // it's always whichever happened last that this un-eject is actually undoing.
            val target = currentRaw
                .filter { it.action == HistoryAction.COUNT_EJECTED && parseBeaconCount(it.note) == beaconNumber }
                .maxByOrNull { it.lineNumber }
            val race = raceDao.getById(raceId) ?: return@withTransaction
            historyLineDao.insert(
                HistoryLineEntity(
                    raceId = raceId,
                    mode = HistoryMode.COUNT,
                    action = HistoryAction.COUNT_UNEJECTED,
                    bibNumber = target?.bibNumber ?: fallbackBibNumber,
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
     *  same current-segment window [observeCounts] folds away the eject/uneject rows from.
     *  Returns the raw rows themselves (not [statusByBeacon]'s own collapsed map) so
     *  [CountModeViewModel] can merge them with a same-group peer's own equivalent rows via
     *  [mergedStatusByBeacon] — see that function's own doc for why the merge needs raw,
     *  timestamped events rather than either side's own already-collapsed-by-lineNumber result. */
    fun observeStatusEvents(raceId: Long): Flow<List<HistoryLineEntity>> =
        historyLineDao.observeAllForRaceAndMode(raceId, HistoryMode.COUNT).map(::currentSegmentRaw)

    /** Count Mode peer-sync's own filtered view of [currentSegmentRaw] — just the COUNT_SEEN/
     *  COUNT_EJECTED/COUNT_UNEJECTED rows a peer's "seen" union (see
     *  [mobile.racemaster.data.peersync.PeerSyncRepository.extractBeaconNumber]/
     *  observeSeenBeaconNumbers, which only ever look at these three actions) actually needs —
     *  never the Ping/Location/Mode-start/etc. noise the race's *full* history otherwise carries
     *  (confirmed live: a Count Mode peer-sync pull reusing the general relay-sync
     *  computeRecordsPayload path was transferring routine Ping keepalive rows alongside the real
     *  seen/ejected events, wasting BLE chunk budget on data no Count Mode reader ever consults).
     *  [sinceLineNumber]/[maxRecords] page the same way computeRecordsPayload's general path
     *  does — see PullRequest.maxRecords's own doc. */
    suspend fun countStatusEventsSince(raceId: Long, sinceLineNumber: Long, maxRecords: Int?): List<HistoryLineEntity> =
        countStatusEventsFrom(currentSegmentRaw(historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.COUNT)), sinceLineNumber, maxRecords)

    /** The highest lineNumber among [countStatusEventsSince]'s own filtered rows (0 if none) —
     *  what a Count Mode peer-sync pull compares its own cursor against instead of the race's raw
     *  DeviceInfo.lastLineNumber, which also counts every Ping/Location/etc. line this feature has
     *  no use for and would otherwise never stop reporting as "something new to pull". */
    suspend fun lastCountStatusLineNumber(raceId: Long): Long =
        lastCountStatusLineNumberFrom(currentSegmentRaw(historyLineDao.getAllForRaceAndMode(raceId, HistoryMode.COUNT)))

    fun observeUnsyncedCount(raceId: Long): Flow<Int> =
        historyLineDao.observeUnsyncedCountForRace(raceId, HistoryMode.COUNT)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> =
        historyLineDao.observeLastSyncedAtMillis(raceId, HistoryMode.COUNT)
}
