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

    private fun record(
        recordUuid: String,
        pulledAtMillis: Long = 0L,
        sourceDeviceId: String = "device-1",
        lineNumber: Long = 1L,
        sourceRaceLabel: String = "Test Race",
        deviceName: String = "clever-gecko",
    ) = PulledRecordEntity(
        recordUuid = recordUuid,
        sourceDeviceId = sourceDeviceId,
        sourceRaceLabel = sourceRaceLabel,
        lineNumber = lineNumber,
        deviceName = deviceName,
        payloadJson = """{"recordUuid":"$recordUuid"}""",
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
    fun insertAllIgnoresDuplicateRecordUuids() = runTest {
        dao.insertAll(listOf(record("a"), record("b")))
        // Re-pulling after a dropped connection retry re-sends the same uuids — must not duplicate.
        dao.insertAll(listOf(record("a"), record("c")))

        assertEquals(3, dao.getUnsynced().size)
    }

    @Test
    fun unsyncedCountReflectsOnlyUnsyncedRows() = runTest {
        dao.insertAll(listOf(record("a"), record("b")))
        assertEquals(2, dao.observeUnsyncedCount().first())

        dao.markSynced(listOf("a"), syncedAtMillis = 1_000L)
        assertEquals(1, dao.observeUnsyncedCount().first())
    }

    @Test
    fun markSyncedOnlyAffectsGivenRecordUuids() = runTest {
        dao.insertAll(listOf(record("a"), record("b"), record("c")))
        dao.markSynced(listOf("a", "c"), syncedAtMillis = 5_000L)

        val unsynced = dao.getUnsynced()
        assertEquals(1, unsynced.size)
        assertEquals("b", unsynced.single().recordUuid)
    }

    @Test
    fun lastSyncedAtMillisIsNullUntilAnythingSynced() = runTest {
        dao.insertAll(listOf(record("a")))
        assertNull(dao.observeLastSyncedAtMillis().first())

        dao.markSynced(listOf("a"), syncedAtMillis = 42_000L)
        assertEquals(42_000L, dao.observeLastSyncedAtMillis().first())
    }

    @Test
    fun getUnsyncedOrdersByPulledAtMillis() = runTest {
        dao.insertAll(listOf(record("later", pulledAtMillis = 200L), record("earlier", pulledAtMillis = 100L)))
        val ordered = dao.getUnsynced()
        assertTrue(ordered.map { it.recordUuid } == listOf("earlier", "later"))
    }

    @Test
    fun lastPulledLineNumberIsNullForAnUnknownSource() = runTest {
        assertNull(dao.getLastPulledLineNumber("device-1", "Test Race"))
    }

    @Test
    fun lastPulledLineNumberIsTheMaxAcrossRecordsFromThatSourceOnly() = runTest {
        dao.insertAll(
            listOf(
                record("a", sourceDeviceId = "device-1", lineNumber = 3L),
                record("b", sourceDeviceId = "device-1", lineNumber = 7L),
                // A different device sharing the same race label must not affect device-1's cutoff.
                record("c", sourceDeviceId = "device-2", lineNumber = 99L),
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
                record("a", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", deviceName = "earlier-device", pulledAtMillis = 100L),
                record("b", sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label", deviceName = "later-device", pulledAtMillis = 200L),
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
                record("a", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", lineNumber = 9L, pulledAtMillis = 100L),
                record("b", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", lineNumber = 3L, pulledAtMillis = 200L),
                record("c", sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label", lineNumber = 40L, pulledAtMillis = 50L),
            ),
        )
        val summaries = dao.observeSourceSummaries().first().associate { it.sourceDeviceId to it.lastLineNumber }
        assertEquals(mapOf("device-2" to 9L, "device-3" to 40L), summaries)
    }

    @Test
    fun getRecordsSinceOnlyReturnsRowsPastTheCutoffForThatSourceOrderedByLineNumber() = runTest {
        dao.insertAll(
            listOf(
                record("a", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", lineNumber = 5L),
                record("b", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", lineNumber = 2L),
                record("c", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", lineNumber = 8L),
                // Different source sharing the line-number range — must not leak in.
                record("d", sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label", lineNumber = 6L),
            ),
        )
        val since = dao.getRecordsSince("device-2", "Shared Label", sinceLineNumber = 1L)
        assertEquals(listOf("b", "a", "c"), since.map { it.recordUuid })
    }

    @Test
    fun theSameRecordArrivingViaTwoRelayPathsStaysAtOneRow() = runTest {
        // The whole no-hop-count/TTL relay design leans on redundant transfer being a harmless
        // storage no-op — this pins that down directly: the same recordUuid inserted twice
        // (simulating it reaching this device via two different mule paths) must not duplicate.
        dao.insertAll(listOf(record("shared-record", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label")))
        dao.insertAll(listOf(record("shared-record", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label")))

        assertEquals(1, dao.getUnsynced().size)
    }

    @Test
    fun observeForSourceOnlyReturnsRowsFromTheGivenDevice() = runTest {
        dao.insertAll(
            listOf(
                record("a", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                record("b", sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label"),
            ),
        )
        val rows = dao.observeForSource("Shared Label", sourceDeviceId = "device-3").first()
        assertEquals(listOf("b"), rows.map { it.recordUuid })
    }

    @Test
    fun deleteForSourceOnlyRemovesTheGivenDevicesRowsForThatRaceLabel() = runTest {
        dao.insertAll(
            listOf(
                record("a", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label"),
                // Same device, different race label — must survive.
                record("b", sourceDeviceId = "device-2", sourceRaceLabel = "Other Label"),
                // Same race label, different device — must survive (see
                // observeForSourceOnlyReturnsRowsFromTheGivenDevice's own reasoning).
                record("c", sourceDeviceId = "device-3", sourceRaceLabel = "Shared Label"),
            ),
        )
        dao.deleteForSource("Shared Label", sourceDeviceId = "device-2")

        assertEquals(emptyList<String>(), dao.observeForSource("Shared Label", sourceDeviceId = "device-2").first().map { it.recordUuid })
        assertEquals(listOf("b"), dao.getUnsynced().filter { it.sourceDeviceId == "device-2" }.map { it.recordUuid })
        assertEquals(listOf("c"), dao.observeForSource("Shared Label", sourceDeviceId = "device-3").first().map { it.recordUuid })
    }

    @Test
    fun deleteForSourceResetsTheDeltaSyncCutoffToNothingPulledYet() = runTest {
        // The whole point of allowing deletion mid-race: the next pull re-requests this
        // device's full history from scratch rather than a delta, since there's nothing left
        // locally to compute a cutoff from.
        dao.insertAll(listOf(record("a", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", lineNumber = 5L)))
        assertEquals(5L, dao.getLastPulledLineNumber("device-2", "Shared Label"))

        dao.deleteForSource("Shared Label", sourceDeviceId = "device-2")

        assertNull(dao.getLastPulledLineNumber("device-2", "Shared Label"))
    }

    @Test
    fun lastTouchedByRaceLabelSpansEveryContributingDevice() = runTest {
        // Unlike observeSourceSummaries/observeForSource (scoped to one device), this must
        // consider every row for a race label together — mirrors MuleRepository.pushToServer's
        // own pulled-from-others staleness check for that label.
        dao.insertAll(
            listOf(
                record("a", sourceDeviceId = "device-1", sourceRaceLabel = "Shared Label", pulledAtMillis = 100L),
                record("b", sourceDeviceId = "device-2", sourceRaceLabel = "Shared Label", pulledAtMillis = 300L),
                record("c", sourceDeviceId = "device-3", sourceRaceLabel = "Other Label", pulledAtMillis = 200L),
            ),
        )
        val activity = dao.observeLastTouchedByRaceLabel().first().associate { it.sourceRaceLabel to it.lastTouchedAtMillis }
        assertEquals(mapOf("Shared Label" to 300L, "Other Label" to 200L), activity)
    }
}
