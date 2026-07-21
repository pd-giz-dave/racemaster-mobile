package mobile.racemaster.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryLineDaoTest {

    private lateinit var db: RacemasterDatabase
    private lateinit var dao: HistoryLineDao
    private var raceId: Long = 0

    private fun line(
        mode: HistoryMode,
        action: HistoryAction,
        lineNumber: Long,
        splitNumber: Int = 0,
        timestampMillis: Long = lineNumber,
    ) = HistoryLineEntity(
        raceId = raceId,
        mode = mode,
        action = action,
        splitNumber = splitNumber,
        lineNumber = lineNumber,
        timestampMillis = timestampMillis,
        recordUuid = "uuid-$lineNumber",
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        dao = db.historyLineDao()
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun timeModeResetBoundaryNeverPicksUpABibsModeReset() = runTest {
        // RESET is one shared action value across both families (see HistoryAction's own doc)
        // — a Bibs RESET landing between two Time rows must not act as a segment boundary for
        // Time Mode's own current-segment query, since every query is scoped by `mode` first
        // regardless of `action`.
        dao.insert(line(HistoryMode.TIME, HistoryAction.START, lineNumber = 1))
        dao.insert(line(HistoryMode.TIME, HistoryAction.SPLIT, lineNumber = 2))
        dao.insert(line(HistoryMode.BIBS, HistoryAction.RESET, lineNumber = 3))
        dao.insert(line(HistoryMode.TIME, HistoryAction.SPLIT, lineNumber = 4))

        val timeSegment = dao.observeCurrentSegment(raceId, HistoryMode.TIME, HistoryAction.RESET).first()
        // No RESET has been logged for Time, so the whole Time history is still "current".
        assertEquals(setOf(1L, 2L, 4L), timeSegment.map { it.lineNumber }.toSet())
    }

    @Test
    fun bibsModeResetBoundaryNeverPicksUpATimeModeReset() = runTest {
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 1))
        dao.insert(line(HistoryMode.TIME, HistoryAction.RESET, lineNumber = 2))
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 3))

        val bibsSegment = dao.observeCurrentSegment(raceId, HistoryMode.BIBS, HistoryAction.RESET).first()
        // No Bibs RESET has been logged, so both Bibs rows are still "current" despite the
        // Time-mode RESET sitting in between.
        assertEquals(setOf(1L, 3L), bibsSegment.map { it.lineNumber }.toSet())
    }

    @Test
    fun eachModesResetActuallyBoundsItsOwnSegment() = runTest {
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 1))
        dao.insert(line(HistoryMode.BIBS, HistoryAction.RESET, lineNumber = 2))
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 3))

        val bibsSegment = dao.observeCurrentSegment(raceId, HistoryMode.BIBS, HistoryAction.RESET).first()
        assertEquals(listOf(3L), bibsSegment.map { it.lineNumber })
    }

    @Test
    fun aSharedUndoRowIsExcludedFromTheOtherModesView() = runTest {
        // UNDO (like START/STOP/RESET) is one of the action values shared by both modes —
        // inserting one under BIBS must never leak into a TIME-scoped query, and vice versa,
        // since every query is scoped by `mode` first regardless of `action`.
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 1))
        dao.insert(line(HistoryMode.BIBS, HistoryAction.UNDO, lineNumber = 2))
        dao.insert(line(HistoryMode.TIME, HistoryAction.SPLIT, lineNumber = 3))

        val timeSegment = dao.observeCurrentSegment(raceId, HistoryMode.TIME, HistoryAction.RESET).first()
        assertEquals(listOf(3L), timeSegment.map { it.lineNumber })

        val bibsSegment = dao.observeCurrentSegment(raceId, HistoryMode.BIBS, HistoryAction.RESET).first()
        assertEquals(setOf(1L, 2L), bibsSegment.map { it.lineNumber }.toSet())
    }

    @Test
    fun observeAllForRaceReturnsBothModesInOneChronology() = runTest {
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 1))
        dao.insert(line(HistoryMode.TIME, HistoryAction.SPLIT, lineNumber = 2))
        dao.insert(line(HistoryMode.BIBS, HistoryAction.START, lineNumber = 3))

        val all = dao.observeAllForRace(raceId).first()
        assertEquals(listOf(3L, 2L, 1L), all.map { it.lineNumber })
    }

    @Test
    fun getSinceLineNumberIsUnscopedAcrossBothModes() = runTest {
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 1))
        dao.insert(line(HistoryMode.TIME, HistoryAction.SPLIT, lineNumber = 2))
        dao.insert(line(HistoryMode.BIBS, HistoryAction.START, lineNumber = 3))

        val since = dao.getSinceLineNumber(raceId, sinceLineNumber = 1)
        assertEquals(listOf(2L, 3L), since.map { it.lineNumber })
    }

    @Test
    fun unsyncedCountIsScopedPerMode() = runTest {
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 1))
        dao.insert(line(HistoryMode.TIME, HistoryAction.SPLIT, lineNumber = 2))

        assertEquals(1, dao.observeUnsyncedCountForRace(raceId, HistoryMode.BIBS).first())
        assertEquals(1, dao.observeUnsyncedCountForRace(raceId, HistoryMode.TIME).first())

        dao.markSynced(listOf("uuid-1"), syncedAtMillis = 1_000L)
        assertEquals(0, dao.observeUnsyncedCountForRace(raceId, HistoryMode.BIBS).first())
        assertEquals(1, dao.observeUnsyncedCountForRace(raceId, HistoryMode.TIME).first())
    }

    @Test
    fun markSyncedIsKeyedByRecordUuidRegardlessOfMode() = runTest {
        dao.insert(line(HistoryMode.BIBS, HistoryAction.FINISH, lineNumber = 1))
        dao.insert(line(HistoryMode.TIME, HistoryAction.SPLIT, lineNumber = 2))

        dao.markSynced(listOf("uuid-1", "uuid-2"), syncedAtMillis = 5_000L)

        val lineNumbers = dao.getLineNumbersForUuids(listOf("uuid-1", "uuid-2"))
        assertEquals(setOf(1L, 2L), lineNumbers.toSet())
        assertTrue(dao.getUnsyncedForRace(raceId, HistoryMode.BIBS).isEmpty())
        assertTrue(dao.getUnsyncedForRace(raceId, HistoryMode.TIME).isEmpty())
    }
}
