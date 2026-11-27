package mobile.racemaster.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

// Integration-level coverage for the Reset-awareness CountModeRepository gained alongside
// CountModeViewModel.resetCountMode (TODO.md's "the count screen needs a reset option") — the
// pure parse-function tests already in app/src/test's own CountModeRepositoryTest don't exercise
// the Room-backed currentSegment() scoping this needs a real database for.
@RunWith(AndroidJUnit4::class)
class CountModeRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: RacemasterDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var raceRepository: RaceRepository
    private lateinit var repository: CountModeRepository
    private var raceId: Long = 0

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        settingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
                produceFile = { tempFolder.newFile("test.preferences_pb") },
            ),
        )
        raceRepository = RaceRepository(db, db.raceDao(), db.historyLineDao(), db.lineSyncDao(), settingsRepository)
        repository = CountModeRepository(db, db.raceDao(), db.historyLineDao())
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    // Mirrors Setup Race's own real write — see TimeModeRepositoryTest's identical helper. Count
    // no longer has its own AppMode value (see AppMode's own doc on the amalgamation) — "Pen" is
    // what still tags these rows HistoryMode.COUNT (see AppMode.toHistoryMode(location)).
    private suspend fun setupRace() {
        raceRepository.recordModeStart(raceId, AppMode.BIBS, "Pen")
    }

    @Test
    fun recordCountNumbersSequentiallyWithinASegment() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 3)
        repository.recordCount(raceId, beaconNumber = 2, bibNumber = 102, totalCount = 3)
        repository.recordCount(raceId, beaconNumber = 3, bibNumber = 103, totalCount = 3)

        // observeCounts (like every other mode's own live view — see HistoryFold.currentSegmentRows'
        // own doc) returns newest-first, not insertion order.
        val seen = repository.observeCounts(raceId).first().filter { it.action == HistoryAction.COUNT_SEEN }
        assertEquals(listOf("beacon=3 seen=3 of=3", "beacon=2 seen=2 of=3", "beacon=1 seen=1 of=3"), seen.map { it.note })
        assertTrue(repository.observeCounts(raceId).first().any { it.action == HistoryAction.COUNT_COMPLETE })
    }

    @Test
    fun closeCurrentSegmentThenRecordCountRestartsTheTallyFromOne() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 2)
        repository.recordCount(raceId, beaconNumber = 2, bibNumber = 102, totalCount = 2)

        // Mirrors CountModeViewModel.resetCountMode's own write — the same generic RESET-row
        // mechanism every other mode's own Reset button already uses.
        raceRepository.closeCurrentSegment(raceId, HistoryMode.COUNT)

        // The live view is empty immediately after Reset — nothing pre-reset leaks into a
        // restarted count, exactly like Bibs/Time's own current-segment view after their Reset.
        assertTrue(repository.observeCounts(raceId).first().isEmpty())

        // Mirrors the real restart path: the screen already exited (CountModeScreen's own Reset
        // dialog calls onHome()), so the only way back into Count mode is through Setup Race
        // again — which, for this same race, writes a fresh LOCATION+MODE_START pair rather than
        // resuming the reset one (recordModeStart never resumes a reset visit's own counter for
        // Count/Close — see that function's own doc).
        setupRace()

        // Beacon 1 was already seen before the reset — recording it again in the new segment
        // must not be treated as a duplicate, and the tally itself must restart at 1, not resume
        // at 3.
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 2)

        val seen = repository.observeCounts(raceId).first().filter { it.action == HistoryAction.COUNT_SEEN }
        assertEquals(listOf("beacon=1 seen=1 of=2"), seen.map { it.note })

        // Nothing is deleted — the 2 pre-reset COUNT_SEEN rows plus the 1 post-reset one are all
        // still in the race's full history, even though observeCounts (above) only surfaced the
        // post-reset one.
        val all = db.historyLineDao().getAllForRaceAndMode(raceId, HistoryMode.COUNT)
        assertEquals(3, all.count { it.action == HistoryAction.COUNT_SEEN })
    }

    // Eject/uneject (TODO.md: "a means to eject one seen beacon" / "selecting the ejected link
    // should un-eject it ... but should remain tagged 'was-ejected' until its re-seen") —
    // CountModeRepository.observeCounts' own fold (COUNT_EJECTED as an undo-marker, same shape as
    // Undo Last) and observeStatusEvents/statusByBeacon (a separate, unfolded read, collapsed by
    // the free statusByBeacon function) are both exercised here directly against a real database,
    // independent of any ViewModel plumbing.

    @Test
    fun ejectedBeaconDropsOutOfTheCountedSeenSetAndIsTaggedEjected() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 2)
        repository.recordCount(raceId, beaconNumber = 2, bibNumber = 102, totalCount = 2)

        repository.recordEject(raceId, beaconNumber = 1)

        val rows = repository.observeCounts(raceId).first()
        assertEquals(setOf(2), rows.filter { it.action == HistoryAction.COUNT_SEEN }.mapNotNull { parseBeaconCount(it.note) }.toSet())
        // The COUNT_EJECTED row itself (and the COUNT_SEEN row it targets) are both excluded from
        // this fold-collapsed view, exactly like an Undo'd entry — see observeCounts' own doc.
        assertTrue(rows.none { it.action == HistoryAction.COUNT_EJECTED })

        assertEquals(mapOf(1 to CountBeaconStatus.EJECTED), statusByBeacon(repository.observeStatusEvents(raceId).first()))
    }

    @Test
    fun ejectRefLineNumberPointsAtTheBeaconsOwnCountSeenRowLikeUndoLast() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 1)
        val seenRow = db.historyLineDao().getAllForRaceAndMode(raceId, HistoryMode.COUNT).first { it.action == HistoryAction.COUNT_SEEN }

        repository.recordEject(raceId, beaconNumber = 1)

        val ejectRow = db.historyLineDao().getAllForRaceAndMode(raceId, HistoryMode.COUNT).first { it.action == HistoryAction.COUNT_EJECTED }
        assertEquals(seenRow.lineNumber, ejectRow.refLineNumber)
    }

    @Test
    fun ejectingABeaconThatIsNotCurrentlySeenIsANoOp() = runTest {
        setupRace()
        repository.recordEject(raceId, beaconNumber = 99)
        assertTrue(db.historyLineDao().getAllForRaceAndMode(raceId, HistoryMode.COUNT).none { it.action == HistoryAction.COUNT_EJECTED })
    }

    @Test
    fun recordCountAfterAnEjectRecountsWithoutDoubleCountingTheTally() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 2)
        repository.recordCount(raceId, beaconNumber = 2, bibNumber = 102, totalCount = 2)
        repository.recordEject(raceId, beaconNumber = 1)

        // Re-counting beacon 1 (mirrors a fresh detection once CountModeViewModel.unejectBeacon
        // has let it back in) must see the *current* seen total (1, just beacon 2) — not the 2
        // raw COUNT_SEEN rows that already exist from before the eject — else this landed as
        // "seen=3 of=2", over-counting and even wrongly completing the count a beacon early.
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 2)

        val rows = repository.observeCounts(raceId).first()
        assertEquals(setOf(1, 2), rows.filter { it.action == HistoryAction.COUNT_SEEN }.mapNotNull { parseBeaconCount(it.note) }.toSet())
        val latestBeacon1 = rows.filter { it.action == HistoryAction.COUNT_SEEN }.first { parseBeaconCount(it.note) == 1 }
        assertEquals("beacon=1 seen=2 of=2", latestBeacon1.note)
        assertTrue(rows.any { it.action == HistoryAction.COUNT_COMPLETE })
    }

    @Test
    fun unejectTagsWasEjectedInsteadOfEjectedAndClearsOnceReseen() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 1)
        repository.recordEject(raceId, beaconNumber = 1)

        repository.recordUneject(raceId, beaconNumber = 1)
        assertEquals(mapOf(1 to CountBeaconStatus.WAS_EJECTED), statusByBeacon(repository.observeStatusEvents(raceId).first()))
        // Still outstanding, not counted — un-ejecting alone doesn't recount it.
        assertTrue(repository.observeCounts(raceId).first().none { it.action == HistoryAction.COUNT_SEEN })

        // A fresh detection (mirrors CountModeViewModel.commitDetection once this beacon is no
        // longer EJECTED) genuinely re-counts it, and the WAS_EJECTED tag is gone — nothing left
        // to show once it's been seen again.
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 1)
        assertTrue(statusByBeacon(repository.observeStatusEvents(raceId).first()).isEmpty())
        assertTrue(repository.observeCounts(raceId).first().any { it.action == HistoryAction.COUNT_SEEN })
    }

    @Test
    fun unejectingABeaconThatIsNotCurrentlyEjectedIsANoOp() = runTest {
        setupRace()
        repository.recordUneject(raceId, beaconNumber = 99)
        assertTrue(db.historyLineDao().getAllForRaceAndMode(raceId, HistoryMode.COUNT).none { it.action == HistoryAction.COUNT_UNEJECTED })
    }

    @Test
    fun reEjectingAWasEjectedBeaconTagsEjectedAgainWithNoCurrentSeenRowToTarget() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 1)
        repository.recordEject(raceId, beaconNumber = 1)
        repository.recordUneject(raceId, beaconNumber = 1)
        assertEquals(mapOf(1 to CountBeaconStatus.WAS_EJECTED), statusByBeacon(repository.observeStatusEvents(raceId).first()))

        // Mirrors tapping the outstanding list's own "was-ejected" link a second time — this
        // beacon has no live COUNT_SEEN row right now (it was never re-counted since the
        // un-eject), so recordEject can't point a refLineNumber at one the way it does from the
        // seen list; it must still land as EJECTED again.
        repository.recordEject(raceId, beaconNumber = 1)
        assertEquals(mapOf(1 to CountBeaconStatus.EJECTED), statusByBeacon(repository.observeStatusEvents(raceId).first()))

        val reEjectRow = db.historyLineDao().getAllForRaceAndMode(raceId, HistoryMode.COUNT)
            .filter { it.action == HistoryAction.COUNT_EJECTED }
            .maxBy { it.lineNumber }
        assertEquals(null, reEjectRow.refLineNumber)
    }
}
