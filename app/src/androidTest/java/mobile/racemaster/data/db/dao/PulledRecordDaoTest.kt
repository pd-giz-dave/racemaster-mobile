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

    private fun record(recordUuid: String, pulledAtMillis: Long = 0L) = PulledRecordEntity(
        recordUuid = recordUuid,
        sourceDeviceRole = "BIBS",
        sourceRaceLabel = "Test Race",
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
}
