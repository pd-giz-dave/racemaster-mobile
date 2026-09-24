package mobile.racemaster.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.PulledRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PulledRecordDaoTest {

    private lateinit var db: RacemasterDatabase
    private lateinit var dao: PulledRecordDao

    // A record's real identity is now (sourceDeviceId, sourceRaceLabel, lineNumber) — no
    // recordUuid column exists any more (see PulledRecordEntity's own doc: sync identity is
    // derived from device+lineNumber, not a separately-generated id).
    private fun record(
        lineNumber: Long,
        pulledAtMillis: Long = 0L,
        sourceDeviceId: String = "device-1",
        sourceRaceLabel: String = "Test Race",
        deviceName: String = "clever-gecko",
    ) = PulledRecordEntity(
        sourceDeviceId = sourceDeviceId,
        sourceRaceLabel = sourceRaceLabel,
        lineNumber = lineNumber,
        deviceName = deviceName,
        payloadJson = """{"lineNumber":$lineNumber}""",
        pulledAtMillis = pulledAtMillis,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        dao = db.pulledRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAllIgnoresDuplicateLineNumbersFromTheSameSource() = runTest {
        dao.insertAll(listOf(record(1L), record(2L)))
        // Re-pulling after a dropped connection retry re-sends the same lines — must not duplicate.
        dao.insertAll(listOf(record(1L), record(3L)))

        assertEquals(3, dao.getUnsynced().size)
    }

    @Test
    fun unsyncedCountReflectsOnlyUnsyncedRows() = runTest {
        dao.insertAll(listOf(record(1L), record(2L)))
        assertEquals(2, dao.observeUnsyncedCount().first())

        dao.markSynced("device-1", "Test Race", listOf(1L), syncedAtMillis = 1_000L)
        assertEquals(1, dao.observeUnsyncedCount().first())
    }

    @Test
    fun markSyncedOnlyAffectsGivenLineNumbers() = runTest {
        dao.insertAll(listOf(record(1L), record(2L), record(3L)))
        dao.markSynced("device-1", "Test Race", listOf(1L, 3L), syncedAtMillis = 5_000L)

        val unsynced = dao.getUnsynced()
        assertEquals(1, unsynced.size)
        assertEquals(2L, unsynced.single().lineNumber)
    }

    @Test
    fun lastSyncedAtMillisIsNullUntilAnythingSynced() = runTest {
        dao.insertAll(listOf(record(1L)))
        assertNull(dao.observeLastSyncedAtMillis().first())

        dao.markSynced("device-1", "Test Race", listOf(1L), syncedAtMillis = 42_000L)
        assertEquals(42_000L, dao.observeLastSyncedAtMillis().first())
    }

    @Test
    fun getUnsyncedOrdersByPulledAtMillis() = runTest {
        dao.insertAll(listOf(record(2L, pulledAtMillis = 200L), record(1L, pulledAtMillis = 100L)))
        val ordered = dao.getUnsynced()
        assertTrue(ordered.map { it.lineNumber } == listOf(1L, 2L))
    }

    @Test
    fun lastPulledLineNumberIsNullForAnUnknownSource() = runTest {
        assertNull(dao.getLastPulledLineNumber("device-1", "Test Race"))
    }

    @Test
    fun lastPulledLineNumberIsTheMaxAcrossRecordsFromThatSourceOnly() = runTest {
        dao.insertAll(
            listOf(
                record(3L, sourceDeviceId = "device-1"),
                record(7L, sourceDeviceId = "device-1"),
                // A different device sharing the same race label must not affect device-1's cutoff.
                record(99L, sourceDeviceId = "device-2"),
            ),
        )
        assertEquals(7L, dao.getLastPulledLineNumber("device-1", "Test Race"))
        assertEquals(99L, dao.getLastPulledLineNumber("device-2", "Test Race"))
    }

    @Test
    fun sourceSummariesKeepDifferentDevicesSharingARaceLabelSeparate() = runTest {
        // Two genuinely different phones (e.g. a Time phone and a Bibs phone) can end up with
        // the same race label (name-course-date) when both work the same physical race — their
        // records must never be folded into one summary, or the operator loses one device's
        // history entirely from the list.
        dao.insertAll(
            listOf(
                record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", deviceName = "earlier-device", pulledAtMillis = 100L),
                record(1L, sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label", deviceName = "later-device", pulledAtMillis = 200L),
            ),
        )
        val summaries = dao.observeSourceSummaries().first()
        assertEquals(
            setOf("device-2" to "earlier-device", "device-3" to "later-device"),
            summaries.map { it.sourceDeviceId to it.deviceName }.toSet(),
        )
    }

    @Test
    fun sourceSummariesReportTheMaxLineNumberPerGroupIndependentOfPulledAtOrder() = runTest {
        // A mule-to-mule relay manifest is built straight from this summary — it must report
        // the source's true highest line number regardless of the order rows happened to be
        // pulled in (an edit-echo/undo-marker can arrive in a later batch than its own root).
        dao.insertAll(
            listOf(
                record(9L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", pulledAtMillis = 100L),
                record(3L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", pulledAtMillis = 200L),
                record(40L, sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label", pulledAtMillis = 50L),
            ),
        )
        val summaries = dao.observeSourceSummaries().first().associate { it.sourceDeviceId to it.lastLineNumber }
        assertEquals(mapOf("device-2" to 9L, "device-3" to 40L), summaries)
    }

    @Test
    fun getRecordsSinceOnlyReturnsRowsPastTheCutoffForThatSourceOrderedByLineNumber() = runTest {
        dao.insertAll(
            listOf(
                record(5L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                record(2L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                record(8L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                // Different source sharing the line-number range — must not leak in.
                record(6L, sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label"),
            ),
        )
        val since = dao.getRecordsSince("device-2", "Shared Label", sinceLineNumber = 1L)
        assertEquals(listOf(2L, 5L, 8L), since.map { it.lineNumber })
    }

    @Test
    fun theSameRecordArrivingViaTwoRelayPathsStaysAtOneRow() = runTest {
        // The whole no-hop-count/TTL relay design leans on redundant transfer being a harmless
        // storage no-op — this pins that down directly: the same (source, lineNumber) inserted
        // twice (simulating it reaching this device via two different mule paths) must not
        // duplicate.
        dao.insertAll(listOf(record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label")))
        dao.insertAll(listOf(record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label")))

        assertEquals(1, dao.getUnsynced().size)
    }

    @Test
    fun observeForSourceOnlyReturnsRowsFromTheGivenDevice() = runTest {
        dao.insertAll(
            listOf(
                record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                record(2L, sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label"),
            ),
        )
        val rows = dao.observeForSource("Shared Label", sourceDeviceId = "device-3").first()
        assertEquals(listOf(2L), rows.map { it.lineNumber })
    }

    @Test
    fun deleteForSourceOnlyRemovesTheGivenDevicesRowsForThatRaceLabel() = runTest {
        dao.insertAll(
            listOf(
                record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                // Same device, different race label — must survive.
                record(2L, sourceDeviceId = "device-2", sourceRaceLabel = "Other Label"),
                // Same race label, different device — must survive (see
                // observeForSourceOnlyReturnsRowsFromTheGivenDevice's own reasoning).
                record(3L, sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label"),
            ),
        )
        dao.deleteForSource("Shared Label", sourceDeviceId = "device-2")

        assertEquals(emptyList<Long>(), dao.observeForSource("Shared Label", sourceDeviceId = "device-2").first().map { it.lineNumber })
        assertEquals(listOf(2L), dao.getUnsynced().filter { it.sourceDeviceId == "device-2" }.map { it.lineNumber })
        assertEquals(listOf(3L), dao.observeForSource("Shared Label", sourceDeviceId = "device-3").first().map { it.lineNumber })
    }

    @Test
    fun deleteForSourceResetsTheDeltaSyncCutoffToNothingPulledYet() = runTest {
        // The whole point of allowing deletion mid-race: the next pull re-requests this
        // device's full history from scratch rather than a delta, since there's nothing left
        // locally to compute a cutoff from.
        dao.insertAll(listOf(record(5L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label")))
        assertEquals(5L, dao.getLastPulledLineNumber("device-2", "Shared Label"))

        dao.deleteForSource("Shared Label", sourceDeviceId = "device-2")

        assertNull(dao.getLastPulledLineNumber("device-2", "Shared Label"))
    }

    @Test
    fun unrelayedSinkConfirmedLineNumbersForSourceOnlyReturnsSyncedRowsFromThatSource() = runTest {
        dao.insertAll(
            listOf(
                record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                record(2L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                // Synced, but a different source — must not leak into device-2's own list.
                record(1L, sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label"),
            ),
        )
        dao.markSynced("device-2", "Shared Label", listOf(1L), syncedAtMillis = 1_000L)
        dao.markSynced("device-3", "Shared Label", listOf(1L), syncedAtMillis = 1_000L)

        val confirmed = dao.getUnrelayedSinkConfirmedLineNumbersForSource("device-2", "Shared Label")

        assertEquals(listOf(1L), confirmed)
    }

    @Test
    fun unrelayedSinkConfirmedLineNumbersForSourceIsEmptyWhenNothingIsSyncedYet() = runTest {
        dao.insertAll(listOf(record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label")))

        assertEquals(emptyList<Long>(), dao.getUnrelayedSinkConfirmedLineNumbersForSource("device-2", "Shared Label"))
    }

    @Test
    fun markConfirmationRelayedExcludesARowFromFurtherUnrelayedQueries() = runTest {
        // The whole point of tracking this separately from syncedAtMillis: once a confirmation
        // has actually been told back to its source, it must stop being offered up again on
        // every subsequent tick — otherwise a large race's ack payload only ever grows.
        dao.insertAll(
            listOf(
                record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                record(2L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
            ),
        )
        dao.markSynced("device-2", "Shared Label", listOf(1L, 2L), syncedAtMillis = 1_000L)

        dao.markConfirmationRelayed("device-2", "Shared Label", listOf(1L), relayedAtMillis = 2_000L)

        assertEquals(
            listOf(2L),
            dao.getUnrelayedSinkConfirmedLineNumbersForSource("device-2", "Shared Label"),
        )
    }

    @Test
    fun lastTouchedByRaceLabelSpansEveryContributingDevice() = runTest {
        // Unlike observeSourceSummaries/observeForSource (scoped to one device), this must
        // consider every row for a race label together — mirrors MuleRepository.pushToServer's
        // own pulled-from-others staleness check for that label.
        dao.insertAll(
            listOf(
                record(1L, sourceDeviceId = "device-1", sourceRaceLabel = "Shared Label", pulledAtMillis = 100L),
                record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", pulledAtMillis = 300L),
                record(1L, sourceDeviceId = "device-3", sourceRaceLabel = "Other Label", pulledAtMillis = 200L),
            ),
        )
        val activity = dao.observeLastTouchedByRaceLabel().first().associate { it.sourceRaceLabel to it.lastTouchedAtMillis }
        assertEquals(mapOf("Shared Label" to 300L, "Other Label" to 200L), activity)
    }

    @Test
    fun getAtLineNumberUnderOtherLabelsFindsOnlyThatDevicesOtherLabelsAtThatLine() = runTest {
        dao.insertAll(
            listOf(
                record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "unknown-26-09-23"),
                record(2L, sourceDeviceId = "device-2", sourceRaceLabel = "unknown-26-09-23"),
                record(1L, sourceDeviceId = "device-2", sourceRaceLabel = "lmv-seniors"),
                record(1L, sourceDeviceId = "device-3", sourceRaceLabel = "unknown-26-09-23"),
            ),
        )

        val rows = dao.getAtLineNumberUnderOtherLabels("device-2", "lmv-seniors", 1L)

        assertEquals(listOf("unknown-26-09-23" to 1L), rows.map { it.sourceRaceLabel to it.lineNumber })
    }

    // Confirmed in the field ("fx_tec" phone, Mule mode): marking a large backlog synced/relayed
    // in one call used to compile straight to a `lineNumber IN (:lineNumbers)` query, which blew
    // through SQLite's own per-statement bound-parameter cap once the list ran into the hundreds
    // ("too many SQL variables" — see SQLITE_MAX_IN_LIST_PARAMS's own doc). Both methods now
    // chunk internally, so this must succeed (not throw) and apply to every row of a batch well
    // past that cap, not just the first chunk's worth.
    @Test
    fun markSyncedAndMarkConfirmationRelayedHandleABatchLargerThanSqlitesInListParamCap() = runTest {
        val lineNumbers = (1L..2_500L).toList()
        dao.insertAll(lineNumbers.map { record(it, sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label") })

        dao.markSynced("device-2", "Shared Label", lineNumbers, syncedAtMillis = 1_000L)
        assertEquals(0, dao.observeUnsyncedCount().first())

        dao.markConfirmationRelayed("device-2", "Shared Label", lineNumbers, relayedAtMillis = 2_000L)
        assertEquals(emptyList<Long>(), dao.getUnrelayedSinkConfirmedLineNumbersForSource("device-2", "Shared Label"))
    }
}
