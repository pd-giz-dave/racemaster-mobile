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
import org.junit.Assert.assertNotNull
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
    fun allTypesConsumeTheSharedCounterIncludingRetire() = runTest {
        repository.recordEntry(raceId, BibEntryType.START, 101, note = null)
        repository.recordEntry(raceId, BibEntryType.START, 102, note = null)
        repository.recordEntry(raceId, BibEntryType.FINISH, 101, note = null)
        repository.recordEntry(raceId, BibEntryType.RETIRE, 103, note = null)
        repository.recordEntry(raceId, BibEntryType.FINISH, 102, note = null)
        repository.recordEntry(raceId, BibEntryType.START, 104, note = null)

        val splitNumbers = db.bibEntryDao().observeForRace(raceId).first()
            .sortedBy { it.id }
            .map { it.splitNumber }

        assertEquals(listOf(1, 2, 3, 4, 5, 6), splitNumbers)
    }

    @Test
    fun undoAfterRetireDecrementsCounter() = runTest {
        repository.recordEntry(raceId, BibEntryType.START, 101, note = null)
        repository.recordEntry(raceId, BibEntryType.RETIRE, 103, note = null)
        repository.deleteMostRecent(raceId)
        repository.recordEntry(raceId, BibEntryType.START, 102, note = null)

        val entries = db.bibEntryDao().observeForRace(raceId).first().sortedBy { it.id }
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].splitNumber)
        assertEquals(2, entries[1].splitNumber)
    }

    @Test
    fun undoAfterStartDecrementsCounter() = runTest {
        repository.recordEntry(raceId, BibEntryType.START, 101, note = null)
        repository.recordEntry(raceId, BibEntryType.START, 102, note = null)
        repository.deleteMostRecent(raceId)
        repository.recordEntry(raceId, BibEntryType.START, 103, note = null)

        val entries = db.bibEntryDao().observeForRace(raceId).first().sortedBy { it.id }
        val splitNumbers = entries.map { it.splitNumber }
        assertEquals(listOf(1, 2), splitNumbers)
    }

    @Test
    fun retireGetsASplitNumber() = runTest {
        repository.recordEntry(raceId, BibEntryType.RETIRE, 105, note = null)

        val entry = db.bibEntryDao().observeForRace(raceId).first().single()
        assertEquals(1, entry.splitNumber)
    }

    @Test
    fun noBibTypesHaveBibNumberForcedToNull() = runTest {
        repository.recordEntry(raceId, BibEntryType.IGNORE, bibNumber = 999, note = null)

        val entry = db.bibEntryDao().observeForRace(raceId).first().single()
        assertNull(entry.bibNumber)
    }

    @Test
    fun createRaceWithClockMarkerIsAtomicAndDoesNotConsumeCounter() = runTest {
        val newRaceId = repository.createRaceWithClockMarker("New Race", "Seniors", bibsRangeStart = 100, bibsRangeCount = 20)

        val race = db.raceDao().getById(newRaceId)
        assertNotNull(race)
        assertEquals(100, race?.bibsRangeStart)
        assertEquals(20, race?.bibsRangeCount)
        assertEquals(1, race?.bibsModeNextSplit)

        val entries = db.bibEntryDao().observeForRace(newRaceId).first()
        assertEquals(1, entries.size)
        assertEquals(BibEntryType.CLOCK, entries.single().type)
        assertEquals(0, entries.single().splitNumber)

        // The counter is untouched by Clock — the next real entry still gets split 1.
        repository.recordEntry(newRaceId, BibEntryType.FINISH, 101, note = null)
        val latest = db.bibEntryDao().getLatest(newRaceId)
        assertEquals(1, latest?.splitNumber)
    }

    @Test
    fun deleteMostRecentIsNoOpWhenClockIsTheOnlyRow() = runTest {
        val newRaceId = repository.createRaceWithClockMarker("New Race", "Seniors", bibsRangeStart = 1, bibsRangeCount = 10)

        repository.deleteMostRecent(newRaceId)

        val entries = db.bibEntryDao().observeForRace(newRaceId).first()
        assertEquals(1, entries.size)
        assertEquals(BibEntryType.CLOCK, entries.single().type)
        assertEquals(1, db.raceDao().getById(newRaceId)?.bibsModeNextSplit)
    }

    @Test
    fun updateEntryNeverMutatesSplitNumber() = runTest {
        repository.recordEntry(raceId, BibEntryType.FINISH, 101, note = null)
        val original = db.bibEntryDao().observeForRace(raceId).first().single()

        repository.updateEntry(original.id, bibNumber = 202, type = BibEntryType.START, note = "corrected")

        val updated = db.bibEntryDao().observeForRace(raceId).first().single()
        assertEquals(original.splitNumber, updated.splitNumber)
        assertEquals(202, updated.bibNumber)
        assertEquals(BibEntryType.START, updated.type)
        assertEquals("corrected", updated.note)
    }

    @Test
    fun updateEntryNullsBibNumberForNoBibTypes() = runTest {
        repository.recordEntry(raceId, BibEntryType.FINISH, 101, note = null)
        val original = db.bibEntryDao().observeForRace(raceId).first().single()

        repository.updateEntry(original.id, bibNumber = 101, type = BibEntryType.SENIORS, note = null)

        val updated = db.bibEntryDao().observeForRace(raceId).first().single()
        assertNull(updated.bibNumber)
        assertEquals(BibEntryType.SENIORS, updated.type)
    }

    @Test
    fun stopBibsModeInsertsStopRowAndUndoResumesLogging() = runTest {
        repository.recordEntry(raceId, BibEntryType.FINISH, 101, note = null)
        repository.stopBibsMode(raceId, stoppedAtMillis = 123L)

        val afterStop = db.bibEntryDao().observeForRace(raceId).first().sortedBy { it.id }
        assertEquals(2, afterStop.size)
        assertEquals(BibEntryType.STOP, afterStop.last().type)
        assertEquals(2, afterStop.last().splitNumber)
        assertEquals(123L, db.raceDao().getById(raceId)?.bibsModeStoppedAtMillis)

        repository.deleteMostRecent(raceId)

        val afterUndo = db.bibEntryDao().observeForRace(raceId).first()
        assertEquals(1, afterUndo.size)
        assertNull(db.raceDao().getById(raceId)?.bibsModeStoppedAtMillis)
        assertEquals(2, db.raceDao().getById(raceId)?.bibsModeNextSplit)
    }

    @Test
    fun resetBibsModeLeavesOnlyAFreshClockRow() = runTest {
        repository.recordEntry(raceId, BibEntryType.START, 101, note = null)
        repository.recordEntry(raceId, BibEntryType.FINISH, 101, note = null)
        repository.stopBibsMode(raceId)

        repository.resetBibsMode(raceId)

        val entries = db.bibEntryDao().observeForRace(raceId).first()
        assertEquals(1, entries.size)
        assertEquals(BibEntryType.CLOCK, entries.single().type)
        assertEquals(0, entries.single().splitNumber)

        val race = db.raceDao().getById(raceId)
        assertEquals(1, race?.bibsModeNextSplit)
        assertNull(race?.bibsModeStoppedAtMillis)
    }
}
