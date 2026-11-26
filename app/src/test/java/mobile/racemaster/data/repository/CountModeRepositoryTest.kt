package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity
import mobile.racemaster.data.mule.toServerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// See CloseModeRepositoryTest's own doc for why only the pure parseBeaconCount function (not
// recordCount/observeCounts themselves) is unit-tested here. recordCount's own doc already notes
// the repository itself does not dedupe — that's the ViewModel's job (CountModeViewModel's own
// seenBeaconNumbers check before ever calling recordCount) — confirmed by inspection: recordCount
// is a plain insert with no SELECT-before-write, same as CloseModeRepository.recordClose.
class CountModeRepositoryTest {

    @Test
    fun parsesBeaconNumberFromAWellFormedNote() {
        // The old bare form (a pre-this-change row) and the current "...seen=.. of=.." form
        // both still parse the beacon number out.
        assertEquals(5, parseBeaconCount("beacon=5"))
        assertEquals(123, parseBeaconCount("beacon=123"))
        assertEquals(5, parseBeaconCount("beacon=5 seen=3 of=10"))
    }

    @Test
    fun nullOrMalformedNoteParsesToNull() {
        assertNull(parseBeaconCount(null))
        assertNull(parseBeaconCount(""))
        assertNull(parseBeaconCount("battery=80"))
        // Must never cross-match CloseModeRepository's own "beacon=<n> battery=<pct>" note.
        assertNull(parseBeaconCount("beacon=5 battery=80"))
    }

    @Test
    fun parsesCompleteTotalFromAWellFormedNote() {
        assertEquals(10, parseCountCompleteTotal("of=10"))
    }

    @Test
    fun completeTotalNullOrMalformedNoteParsesToNull() {
        assertNull(parseCountCompleteTotal(null))
        assertNull(parseCountCompleteTotal(""))
        assertNull(parseCountCompleteTotal("outstanding=3"))
    }

    @Test
    fun parsesOutstandingCountFromAWellFormedNote() {
        assertEquals(3, parseCountOutstanding("outstanding=3"))
    }

    @Test
    fun outstandingNullOrMalformedNoteParsesToNull() {
        assertNull(parseCountOutstanding(null))
        assertNull(parseCountOutstanding(""))
        assertNull(parseCountOutstanding("of=10"))
    }

    // TODO.md "if a beacon is ejected it must be flagged as such on all phones counting beacons"
    // / "a bib must be ejectable no matter what phone did the initial detection" — mergedStatusByBeacon
    // is the pure cross-device merge both rely on, so unlike recordCount/recordEject/recordUneject
    // themselves (DB-touching, not unit-tested here — see this class's own top-of-file doc) this
    // one is worth testing directly.

    private fun localRow(action: HistoryAction, beaconNumber: Int, timestampMillis: Long) = HistoryLineEntity(
        raceId = 1L,
        mode = HistoryMode.COUNT,
        action = action,
        splitNumber = null,
        lineNumber = timestampMillis,
        note = "beacon=$beaconNumber",
        timestampMillis = timestampMillis,
    )

    // action.toServerAction(), not action.name — a real row's action column holds the wire string
    // (e.g. "CountEjected"), never the enum's own .name ("COUNT_EJECTED"); mergedStatusByBeacon's
    // own regression test below (peerRowActionIsParsedAsItsRealWireStringNotTheEnumName) is the
    // direct test for a bug this exact distinction caused: HistoryAction.valueOf(row.action) used
    // to silently fail on every peer row, so no peer eject/uneject/seen ever reached this merge.
    private fun peerRow(action: HistoryAction, beaconNumber: Int, timestampMillis: Long) = PeerSeenRecordEntity(
        raceGroupId = "g",
        mode = HistoryMode.COUNT,
        originDeviceId = "peer-1",
        originDeviceName = "peer-one",
        lineNumber = timestampMillis,
        action = action.toServerAction(),
        beaconNumber = beaconNumber,
        // Mirrors mergeRecords' own note = record.note passthrough — a real peer row carries the
        // source device's own "beacon=<n>" note unchanged, same as localRow below.
        note = "beacon=$beaconNumber",
        timestampMillis = timestampMillis,
    )

    @Test
    fun peerRowActionIsParsedAsItsRealWireStringNotTheEnumName() {
        // The exact regression this closes: a real peer_seen_records row's action column holds
        // "CountEjected" (HistoryAction.toServerAction()'s wire spelling), never "COUNT_EJECTED"
        // (the enum's own .name). mergedStatusByBeacon used to parse it via
        // HistoryAction.valueOf(row.action), which throws on "CountEjected" and was swallowed by
        // runCatching{}.getOrNull() — so a peer's ejected beacon silently never showed as ejected
        // anywhere, confirmed live.
        val status = mergedStatusByBeacon(
            localRows = emptyList(),
            peerRows = listOf(
                PeerSeenRecordEntity(
                    raceGroupId = "g",
                    mode = HistoryMode.COUNT,
                    originDeviceId = "peer-1",
                    originDeviceName = "peer-one",
                    lineNumber = 1,
                    action = "CountEjected",
                    beaconNumber = 11,
                    timestampMillis = 100,
                ),
            ),
        )
        assertEquals(CountBeaconStatus.EJECTED, status[11])
    }

    @Test
    fun aBeaconEjectedOnlyByAPeerShowsEjectedWithNoLocalRowAtAll() {
        val status = mergedStatusByBeacon(
            localRows = emptyList(),
            peerRows = listOf(peerRow(HistoryAction.COUNT_EJECTED, beaconNumber = 5, timestampMillis = 100)),
        )
        assertEquals(CountBeaconStatus.EJECTED, status[5])
    }

    @Test
    fun aPeersLaterEjectOverridesThisDevicesOwnEarlierSeenRow() {
        val status = mergedStatusByBeacon(
            localRows = listOf(localRow(HistoryAction.COUNT_SEEN, beaconNumber = 7, timestampMillis = 100)),
            peerRows = listOf(peerRow(HistoryAction.COUNT_EJECTED, beaconNumber = 7, timestampMillis = 200)),
        )
        assertEquals(CountBeaconStatus.EJECTED, status[7])
    }

    @Test
    fun aPeersLaterUnejectOverridesThisDevicesOwnEarlierEject() {
        val status = mergedStatusByBeacon(
            localRows = listOf(localRow(HistoryAction.COUNT_EJECTED, beaconNumber = 9, timestampMillis = 100)),
            peerRows = listOf(peerRow(HistoryAction.COUNT_UNEJECTED, beaconNumber = 9, timestampMillis = 200)),
        )
        assertEquals(CountBeaconStatus.WAS_EJECTED, status[9])
    }

    @Test
    fun aLaterPeerRecountClearsAnEarlierLocalEjectEntirely() {
        val status = mergedStatusByBeacon(
            localRows = listOf(localRow(HistoryAction.COUNT_EJECTED, beaconNumber = 3, timestampMillis = 100)),
            peerRows = listOf(peerRow(HistoryAction.COUNT_SEEN, beaconNumber = 3, timestampMillis = 200)),
        )
        assertNull(status[3])
    }

    @Test
    fun unrelatedActionsAndMalformedPeerActionStringsAreIgnored() {
        val status = mergedStatusByBeacon(
            localRows = emptyList(),
            peerRows = listOf(
                peerRow(HistoryAction.COUNT_COMPLETE, beaconNumber = 1, timestampMillis = 100),
                PeerSeenRecordEntity(
                    raceGroupId = "g",
                    mode = HistoryMode.COUNT,
                    originDeviceId = "peer-1",
                    originDeviceName = "peer-one",
                    lineNumber = 1,
                    action = "NOT_A_REAL_ACTION",
                    beaconNumber = 2,
                    timestampMillis = 100,
                ),
            ),
        )
        assertEquals(emptyMap<Int, CountBeaconStatus>(), status)
    }

    // countStatusEventsFrom/lastCountStatusLineNumberFrom's own regression coverage — see their
    // own doc for the field bug this closes: Count Mode peer-sync reusing the general, unfiltered
    // relay-sync path was transferring (and letting its cursor advance past) routine Ping rows
    // that have nothing to do with the seen/ejected union these two functions exist to serve.

    private fun row(action: HistoryAction, lineNumber: Long, beaconNumber: Int? = null) = HistoryLineEntity(
        raceId = 1L,
        mode = HistoryMode.COUNT,
        action = action,
        splitNumber = null,
        lineNumber = lineNumber,
        note = beaconNumber?.let { "beacon=$it" },
        timestampMillis = lineNumber,
    )

    @Test
    fun countStatusEventsFromExcludesNonCountActionsEvenWhenInterspersed() {
        val rows = listOf(
            row(HistoryAction.COUNT_SEEN, lineNumber = 1, beaconNumber = 1),
            row(HistoryAction.PING, lineNumber = 2),
            row(HistoryAction.COUNT_EJECTED, lineNumber = 3, beaconNumber = 1),
            row(HistoryAction.PING, lineNumber = 4),
        )
        val events = countStatusEventsFrom(rows, sinceLineNumber = 0, maxRecords = null)
        assertEquals(listOf(1L, 3L), events.map { it.lineNumber })
    }

    @Test
    fun countStatusEventsFromRespectsSinceLineNumberAndMaxRecords() {
        val rows = (1..5L).map { row(HistoryAction.COUNT_SEEN, lineNumber = it, beaconNumber = it.toInt()) }
        val events = countStatusEventsFrom(rows, sinceLineNumber = 1, maxRecords = 2)
        assertEquals(listOf(2L, 3L), events.map { it.lineNumber })
    }

    @Test
    fun lastCountStatusLineNumberFromIgnoresAPingRowEvenWithAHigherLineNumberThanTheLatestRealEvent() {
        // The exact regression this closes: a plain MAX(lineNumber) over every action (what the
        // DAO-level cursor used to do before this feature existed) would report 99 here and
        // permanently hide line 5's own real COUNT_EJECTED row from a forward-only delta cursor.
        val rows = listOf(
            row(HistoryAction.COUNT_SEEN, lineNumber = 1, beaconNumber = 1),
            row(HistoryAction.COUNT_EJECTED, lineNumber = 5, beaconNumber = 1),
            row(HistoryAction.PING, lineNumber = 99),
        )
        assertEquals(5L, lastCountStatusLineNumberFrom(rows))
    }

    @Test
    fun lastCountStatusLineNumberFromIsZeroForNoCountRelevantRows() {
        assertEquals(0L, lastCountStatusLineNumberFrom(listOf(row(HistoryAction.PING, lineNumber = 10))))
    }

}
