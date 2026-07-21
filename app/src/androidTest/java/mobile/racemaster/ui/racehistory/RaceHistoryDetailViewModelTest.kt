package mobile.racemaster.ui.racehistory

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RaceHistoryDetailViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: RacemasterDatabase
    private lateinit var raceRepository: RaceRepository
    private lateinit var timeModeRepository: TimeModeRepository
    private lateinit var bibsModeRepository: BibsModeRepository
    private var raceId: Long = 0

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        val settingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
                produceFile = { tempFolder.newFile("test.preferences_pb") },
            ),
        )
        timeModeRepository = TimeModeRepository(db, db.raceDao(), db.historyLineDao())
        bibsModeRepository = BibsModeRepository(db, db.raceDao(), db.historyLineDao())
        raceRepository = RaceRepository(
            db.raceDao(),
            db.historyLineDao(),
            db.lineSyncDao(),
            db.pulledRecordDao(),
            settingsRepository,
            bibsModeRepository,
        )
        raceId = db.raceDao().insert(RaceEntity(label = "Mixed Race", createdAtMillis = 0L, createdByDeviceName = "quiet-thicket"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun viewModel() = RaceHistoryDetailViewModel(raceId, raceRepository, timeModeRepository, bibsModeRepository)

    @Test
    fun mixedModeRaceInterleavesBothCategoriesInOneTrueLineNumberOrder() = runTest {
        // A race is not required to stick to one mode — nothing prevents switching AppMode
        // mid-race without starting a new one (see plan's mixed-mode design note). Race
        // History must show one true chronology by lineNumber, not two separate lists.
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)
        timeModeRepository.startStopwatch(raceId, startedAtMillis = 1_000L)
        timeModeRepository.recordSplit(raceId, timestampMillis = 2_000L)
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 102, note = null)

        val state = viewModel().uiState.first { it.lines.size == 4 }

        assertEquals(listOf(1L, 2L, 3L, 4L), state.lines.map { it.lineNumber })
        assertEquals(
            listOf(HistoryMode.BIBS, HistoryMode.TIME, HistoryMode.TIME, HistoryMode.BIBS),
            state.lines.map { it.mode },
        )
    }

    @Test
    fun elapsedTimeForATimeRowIsRelativeToItsOwnMostRecentClockStartAcrossMixedSegments() = runTest {
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)
        timeModeRepository.startStopwatch(raceId, startedAtMillis = 1_000L)
        timeModeRepository.recordSplit(raceId, timestampMillis = 5_500L)

        val state = viewModel().uiState.first { it.lines.size == 3 }

        val timeRow = state.lines.single { it.mode == HistoryMode.TIME && it.action == HistoryAction.SPLIT }
        assertEquals(4_500L, timeRow.elapsedMillis)
        val bibsRow = state.lines.single { it.mode == HistoryMode.BIBS }
        assertEquals(0L, bibsRow.elapsedMillis)
    }

    @Test
    fun duplicateBibDetectionIsScopedPerBibsSegmentEvenInAMixedModeRace() = runTest {
        // TODO 249's fix: a bib reused in a later Bibs segment (after a Reset) must not be
        // flagged against an earlier, already-reset-away segment — even with Time rows
        // interleaved in between. resetBibsMode inserts just a RESET marker (no fresh CLOCK
        // row — Bibs Mode's own Start button is what begins the new segment, see
        // BibsModeRepository.resetBibsMode's own doc), so this race ends up with 4 lines
        // total: Bibs FINISH, Bibs RESET, Time's own START, Bibs FINISH.
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)
        bibsModeRepository.resetBibsMode(raceId)
        timeModeRepository.startStopwatch(raceId, startedAtMillis = 1_000L)
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)

        val state = viewModel().uiState.first { it.lines.size == 4 }

        val bibRows = state.lines.filter { it.mode == HistoryMode.BIBS && it.action == HistoryAction.FINISH }
        assertEquals(2, bibRows.size)
        assertEquals(emptyList<Int>(), bibRows[0].dupSplitRefs)
        assertEquals(emptyList<Int>(), bibRows[1].dupSplitRefs)
    }

    @Test
    fun singleModeTimeRaceRendersWithNoBibsRows() = runTest {
        timeModeRepository.startStopwatch(raceId, startedAtMillis = 1_000L)
        timeModeRepository.recordSplit(raceId, timestampMillis = 2_000L)
        timeModeRepository.stopStopwatch(raceId, stoppedAtMillis = 3_000L)

        val state = viewModel().uiState.first { it.lines.size == 3 }

        assertEquals(listOf(HistoryMode.TIME, HistoryMode.TIME, HistoryMode.TIME), state.lines.map { it.mode })
        assertEquals(listOf(1L, 2L, 3L), state.lines.map { it.lineNumber })
    }

    @Test
    fun singleModeBibsRaceRendersWithNoTimeRows() = runTest {
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)
        bibsModeRepository.recordEntry(raceId, HistoryAction.RETIRE, bibNumber = 102, note = null)

        val state = viewModel().uiState.first { it.lines.size == 2 }

        assertEquals(listOf(HistoryMode.BIBS, HistoryMode.BIBS), state.lines.map { it.mode })
    }

    @Test
    fun deviceNameIsSourcedFromRaceCreatedByDeviceName() = runTest {
        val state = viewModel().uiState.first { it.raceLabel == "Mixed Race" }
        assertEquals("quiet-thicket", state.deviceName)
    }
}
