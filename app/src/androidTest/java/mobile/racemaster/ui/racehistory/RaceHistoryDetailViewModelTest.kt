package mobile.racemaster.ui.racehistory

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.CpModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode
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
    private lateinit var cpModeRepository: CpModeRepository
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
        raceRepository = RaceRepository(
            db,
            db.raceDao(),
            db.historyLineDao(),
            db.lineSyncDao(),
            settingsRepository,
        )
        timeModeRepository = TimeModeRepository(db, db.raceDao(), db.historyLineDao(), raceRepository)
        bibsModeRepository = BibsModeRepository(db, db.raceDao(), db.historyLineDao(), raceRepository)
        cpModeRepository = CpModeRepository(db, db.raceDao(), db.historyLineDao(), raceRepository)
        raceId = db.raceDao().insert(RaceEntity(label = "Mixed Race", createdAtMillis = 0L, createdByDeviceName = "quiet-thicket"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun viewModel() = RaceHistoryDetailViewModel(raceId, raceRepository, timeModeRepository, bibsModeRepository, cpModeRepository)

    // Explicit recordModeStart call, at the SAME timestamp startStopwatch is about to use, so
    // TimeModeRepository.startStopwatch's own ensureOpenSegment self-heal (see its own doc) finds
    // this segment already open and doesn't fire a second time with an unrelated real-wall-clock
    // timestamp of its own — keeping every row's elapsedMillis meaningful and test-controlled.
    private suspend fun setupTime(atMillis: Long, location: String = "Finish") {
        raceRepository.recordModeStart(raceId, AppMode.TIME, location, timestampMillis = atMillis)
    }

    @Test
    fun mixedModeRaceInterleavesBothCategoriesInOneTrueLineNumberOrder() = runTest {
        // A race is not required to stick to one mode — nothing prevents switching AppMode
        // mid-race without starting a new one (see plan's mixed-mode design note). Race
        // History must show one true chronology by lineNumber, not two separate lists.
        // recordModeStart's own NEW_RACE+LOCATION+MODE_START trio (this race's first-ever mode
        // choice) precedes Time's own real Start row — so this race ends up with 7 lines: Bibs
        // FINISH, Time NEW_RACE, Time LOCATION, Time MODE_START, Time Start, Time Split, Bibs
        // FINISH.
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)
        setupTime(atMillis = 1_000L)
        timeModeRepository.startStopwatch(raceId, startedAtMillis = 1_000L)
        timeModeRepository.recordSplit(raceId, timestampMillis = 2_000L)
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 102, note = null)

        val state = viewModel().uiState.first { it.lines.size == 7 }

        assertEquals((1L..7L).toList(), state.lines.map { it.lineNumber })
        assertEquals(
            listOf(
                HistoryMode.BIBS, HistoryMode.TIME, HistoryMode.TIME, HistoryMode.TIME,
                HistoryMode.TIME, HistoryMode.TIME, HistoryMode.BIBS,
            ),
            state.lines.map { it.mode },
        )
        assertEquals(HistoryAction.MODE_START, state.lines[3].action)
    }

    @Test
    fun elapsedTimeForATimeRowIsRelativeToItsOwnMostRecentClockStartAcrossMixedSegments() = runTest {
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)
        setupTime(atMillis = 1_000L)
        timeModeRepository.startStopwatch(raceId, startedAtMillis = 1_000L)
        timeModeRepository.recordSplit(raceId, timestampMillis = 5_500L)

        // Bibs FINISH + Time's own NEW_RACE/LOCATION/MODE_START/Start quartet + the Split = 6.
        val state = viewModel().uiState.first { it.lines.size == 6 }

        val timeRow = state.lines.single { it.mode == HistoryMode.TIME && it.action == HistoryAction.SPLIT }
        assertEquals(4_500L, timeRow.elapsedMillis)
        val bibsRow = state.lines.single { it.mode == HistoryMode.BIBS }
        assertEquals(0L, bibsRow.elapsedMillis)
        // The Time MODE_START row always reads 0 too — it shares the real Start marker's own
        // instant here (both explicitly at 1_000L).
        val modeStartRow = state.lines.single { it.action == HistoryAction.MODE_START }
        assertEquals(0L, modeStartRow.elapsedMillis)
    }

    @Test
    fun duplicateBibDetectionIsScopedPerBibsSegmentEvenInAMixedModeRace() = runTest {
        // TODO 249's fix: a bib reused in a later Bibs segment (after a Reset) must not be
        // flagged against an earlier, already-reset-away segment — even with Time rows
        // interleaved in between. Bibs' own resetBibsMode inserts just a RESET marker (no fresh
        // Clock row — Bibs Mode's own Start button is what begins the new segment); Time's own
        // setup writes its NEW_RACE/LOCATION/MODE_START trio before its real Start row.
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)
        bibsModeRepository.resetBibsMode(raceId)
        setupTime(atMillis = 1_000L)
        timeModeRepository.startStopwatch(raceId, startedAtMillis = 1_000L)
        bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bibNumber = 101, note = null)

        // Bibs FINISH, Bibs RESET, Time NEW_RACE/LOCATION/MODE_START/Start, Bibs FINISH = 7.
        val state = viewModel().uiState.first { it.lines.size == 7 }

        val bibRows = state.lines.filter { it.mode == HistoryMode.BIBS && it.action == HistoryAction.FINISH }
        assertEquals(2, bibRows.size)
        assertEquals(emptyList<Int>(), bibRows[0].dupSplitRefs)
        assertEquals(emptyList<Int>(), bibRows[1].dupSplitRefs)
    }

    @Test
    fun singleModeTimeRaceRendersWithNoBibsRows() = runTest {
        setupTime(atMillis = 1_000L)
        timeModeRepository.startStopwatch(raceId, startedAtMillis = 1_000L)
        timeModeRepository.recordSplit(raceId, timestampMillis = 2_000L)

        // NEW_RACE + LOCATION + MODE_START + Start + Split = 5 lines, all Time.
        val state = viewModel().uiState.first { it.lines.size == 5 }

        assertEquals(List(5) { HistoryMode.TIME }, state.lines.map { it.mode })
        assertEquals((1L..5L).toList(), state.lines.map { it.lineNumber })
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
