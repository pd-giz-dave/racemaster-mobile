package mobile.racemaster.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.KnownDeviceEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnownDeviceDaoTest {

    private lateinit var db: RacemasterDatabase
    private lateinit var dao: KnownDeviceDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        dao = db.knownDeviceDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertInsertsANewDevice() = runTest {
        dao.upsert(KnownDeviceEntity("device-1", "witty-warbler", 100L))

        assertEquals(listOf("witty-warbler"), dao.observeAll().first().map { it.deviceName })
    }

    @Test
    fun upsertOnAnExistingDeviceIdReplacesNameAndLastSeenRatherThanDuplicating() = runTest {
        // A device re-resolving must refresh its name (the operator can rename a device between
        // sessions) and last-seen time, not stick with whatever was first recorded.
        dao.upsert(KnownDeviceEntity("device-1", "witty-warbler", 100L))
        dao.upsert(KnownDeviceEntity("device-1", "renamed-warbler", 200L))

        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("renamed-warbler", all.single().deviceName)
        assertEquals(200L, all.single().lastSeenAtMillis)
    }

    @Test
    fun observeAllOrdersByMostRecentlySeenFirst() = runTest {
        dao.upsert(KnownDeviceEntity("device-1", "earlier", 100L))
        dao.upsert(KnownDeviceEntity("device-2", "later", 200L))

        assertEquals(listOf("later", "earlier"), dao.observeAll().first().map { it.deviceName })
    }

    @Test
    fun deleteRemovesOnlyTheGivenDevice() = runTest {
        dao.upsert(KnownDeviceEntity("device-1", "keep-me", 100L))
        dao.upsert(KnownDeviceEntity("device-2", "forget-me", 200L))

        dao.delete("device-2")

        assertEquals(listOf("keep-me"), dao.observeAll().first().map { it.deviceName })
    }

    @Test
    fun deleteOnAnUnknownDeviceIdIsAHarmlessNoOp() = runTest {
        // "Forget" must be safe to call for a device that was never actually resolved/upserted
        // (an unresolved BLE ghost's raw address) — see KnownDeviceDao.delete's own doc.
        dao.upsert(KnownDeviceEntity("device-1", "keep-me", 100L))

        dao.delete("never-seen-device")

        assertEquals(listOf("keep-me"), dao.observeAll().first().map { it.deviceName })
    }
}
