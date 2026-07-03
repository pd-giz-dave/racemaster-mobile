package mobile.racemaster.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BibsModeRepositoryTest {

    private lateinit var db: RacemasterDatabase
    private lateinit var repository: BibsModeRepository
    private var raceId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        repository = BibsModeRepository(db, db.raceDao(), db.bibEntryDao())
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sharedCounterExcludesRetire() = runTest {
        repository.recordEntry(raceId, 101, BibEntryType.START)
        repository.recordEntry(raceId, 102, BibEntryType.START)
        repository.recordEntry(raceId, 101, BibEntryType.FINISH)
        repository.recordEntry(raceId, 103, BibEntryType.RETIRE)
        repository.recordEntry(raceId, 102, BibEntryType.FINISH)
        repository.recordEntry(raceId, 104, BibEntryType.START)

        // Insertion order oldest-to-newest, matching the DAO's id-based ordering reversed.
        val splitNumbers = db.bibEntryDao().observeForRace(raceId).first()
            .sortedBy { it.id }
            .map { it.splitNumber }

        assertEquals(listOf(1, 2, 3, null, 4, 5), splitNumbers)
    }

    @Test
    fun undoAfterRetireLeavesCounterUntouched() = runTest {
        repository.recordEntry(raceId, 101, BibEntryType.START)
        repository.recordEntry(raceId, 103, BibEntryType.RETIRE)
        repository.deleteMostRecent(raceId)
        repository.recordEntry(raceId, 102, BibEntryType.START)

        val entries = db.bibEntryDao().observeForRace(raceId).first().sortedBy { it.id }
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].splitNumber)
        assertEquals(2, entries[1].splitNumber)
    }

    @Test
    fun undoAfterStartDecrementsCounter() = runTest {
        repository.recordEntry(raceId, 101, BibEntryType.START)
        repository.recordEntry(raceId, 102, BibEntryType.START)
        repository.deleteMostRecent(raceId)
        repository.recordEntry(raceId, 103, BibEntryType.START)

        val entries = db.bibEntryDao().observeForRace(raceId).first().sortedBy { it.id }
        val splitNumbers = entries.map { it.splitNumber }
        assertEquals(listOf(1, 2), splitNumbers)
    }

    @Test
    fun retireNeverGetsASplitNumber() = runTest {
        repository.recordEntry(raceId, 105, BibEntryType.RETIRE)

        val entry = db.bibEntryDao().observeForRace(raceId).first().single()
        assertNull(entry.splitNumber)
    }
}