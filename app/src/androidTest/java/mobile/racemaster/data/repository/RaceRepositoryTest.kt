package mobile.racemaster.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.LineSyncEntity
import mobile.racemaster.data.db.entity.PulledRecordEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.db.entity.SERVER_TARGET_ID
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RaceRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: RacemasterDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var repository: RaceRepository
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
        val bibsModeRepository = BibsModeRepository(db, db.raceDao(), db.historyLineDao())
        repository = RaceRepository(
            db.raceDao(),
            db.historyLineDao(),
            db.lineSyncDao(),
            settingsRepository,
            bibsModeRepository,
        )
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recordLineSyncsIsAttributedToTheGivenTarget() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L, 2L), targetId = "puller-device-id", targetName = "lively-otter")

        val rows = repository.observeLineSyncs(raceId).first()
        assertEquals(setOf(1L, 2L), rows.map { it.lineNumber }.toSet())
        assertEquals(setOf("puller-device-id"), rows.map { it.targetId }.toSet())
    }

    @Test
    fun recordLineSyncsForServerUsesTheReservedConstant() = runTest {
        repository.recordLineSyncs(raceId, listOf(3L), targetId = SERVER_TARGET_ID, targetName = "Server")

        val rows = repository.observeLineSyncs(raceId).first()
        assertEquals(SERVER_TARGET_ID, rows.single().targetId)
    }

    @Test
    fun sameLineCanBeSyncedToMultipleDistinctTargets() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "puller-a", targetName = "lively-otter")
        repository.recordLineSyncs(raceId, listOf(1L), targetId = SERVER_TARGET_ID, targetName = "Server")

        val targets = repository.observeLineSyncs(raceId).first().map { it.targetId }.toSet()
        assertEquals(setOf("puller-a", SERVER_TARGET_ID), targets)
    }

    @Test
    fun reAckingTheSameLineAndTargetReplacesRatherThanDuplicates() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "puller-a", targetName = "lively-otter", syncedAtMillis = 1_000L)
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "puller-a", targetName = "lively-otter", syncedAtMillis = 2_000L)

        val rows = repository.observeLineSyncs(raceId).first()
        assertEquals(1, rows.size)
        assertEquals(2_000L, rows.single().syncedAtMillis)
    }

    @Test
    fun recordLineSyncsPersistsTheDisplayNameDistinctFromTheRawTargetId() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "b3b711bd-95d1-408b-99a0-2a4523df2fc4", targetName = "quiet-thicket")

        val row = repository.observeLineSyncs(raceId).first().single()
        assertEquals("b3b711bd-95d1-408b-99a0-2a4523df2fc4", row.targetId)
        assertEquals("quiet-thicket", row.targetName)
    }

    @Test
    fun getRaceByLabelResolvesTheRaceThisDeviceCreated() = runTest {
        val race = repository.getRaceByLabel("Test Race")
        assertEquals(raceId, race?.id)
    }

    @Test
    fun getRaceByLabelIsNullForAnUnknownLabel() = runTest {
        assertNull(repository.getRaceByLabel("Some other race entirely"))
    }

    @Test
    fun renamingARaceNeedsNoPulledRecordsBookkeeping() = runTest {
        // Unlike the old design (which had to retag a separately-staged mirror of this
        // device's own data onto the new label), MuleRepository.pushToServer now reads this
        // race's own current label fresh from RaceEntity every attempt — a rename just takes
        // effect on the very next push, nothing else needs updating.
        repository.updateRaceDetails(raceId, name = "Renamed", course = "Course", location = "Finish", bibsRangeStart = null, bibsRangeCount = null)

        assertEquals(buildRaceLabel("Renamed", "Course", 0L), repository.getRace(raceId)?.label)
    }

    // location — see RaceEntity.location's own doc. Not part of the label (unlike name/course),
    // so it's the one field here that both persists and can be edited freely without touching
    // buildRaceLabel at all.

    @Test
    fun startNewRacePersistsTheGivenLocation() = runTest {
        val newRaceId = repository.startNewRace(name = "Other Race", course = "Course", location = "Start", createdAtMillis = 0L)

        assertEquals("Start", repository.getRace(newRaceId)?.location)
    }

    @Test
    fun startNewRaceDefaultsLocationToFinish() = runTest {
        val newRaceId = repository.startNewRace(name = "Other Race", course = "Course", createdAtMillis = 0L)

        assertEquals("Finish", repository.getRace(newRaceId)?.location)
    }

    @Test
    fun updateRaceDetailsChangesLocationWithoutAffectingTheLabel() = runTest {
        // "Test Race"/"" (see setUp) wasn't produced by buildRaceLabel in the first place, so
        // this establishes a stable buildRaceLabel-derived label first, then changes only
        // location — the label recomputed from the *same* name/course a second time must come
        // out identical, proving location plays no part in it.
        repository.updateRaceDetails(raceId, name = "Same", course = "Course", location = "Finish", bibsRangeStart = null, bibsRangeCount = null)
        val stableLabel = requireNotNull(repository.getRace(raceId)?.label)

        repository.updateRaceDetails(raceId, name = "Same", course = "Course", location = "Checkpoint 2", bibsRangeStart = null, bibsRangeCount = null)

        val updated = repository.getRace(raceId)
        assertEquals("Checkpoint 2", updated?.location)
        assertEquals(stableLabel, updated?.label)
    }

    // deleteRace — irreversible, gated behind RaceHistoryScreen's own confirmation dialog.

    @Test
    fun deleteRaceRemovesTheRaceItself() = runTest {
        repository.deleteRace(raceId)

        assertNull(repository.getRace(raceId))
    }

    @Test
    fun deleteRaceCascadesToItsHistoryLines() = runTest {
        db.historyLineDao().insert(
            HistoryLineEntity(
                raceId = raceId,
                mode = HistoryMode.TIME,
                action = HistoryAction.SPLIT,
                splitNumber = 1,
                lineNumber = 1L,
                timestampMillis = 0L,
            ),
        )

        repository.deleteRace(raceId)

        assertEquals(emptyList<HistoryLineEntity>(), db.historyLineDao().observeAllForRace(raceId).first())
    }

    @Test
    fun deleteRaceAlsoRemovesItsLineSyncs() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L), targetId = SERVER_TARGET_ID, targetName = "Server")

        repository.deleteRace(raceId)

        assertEquals(emptyList<LineSyncEntity>(), repository.observeLineSyncs(raceId).first())
    }

    @Test
    fun deleteRaceNeedsNoPulledRecordsCleanup() = runTest {
        // This device's own data is never mirrored into pulled_records at all (see
        // PulledRecordEntity's own doc), so deleting a race must never touch that table —
        // whatever's genuinely pulled from other devices under any label (including this
        // race's own) is left completely alone.
        db.pulledRecordDao().insertAll(
            listOf(
                PulledRecordEntity(
                    recordUuid = "other-device-1",
                    sourceDeviceId = "some-other-device-id",
                    sourceRaceLabel = "Test Race",
                    lineNumber = 1L,
                    payloadJson = "{}",
                    pulledAtMillis = 0L,
                ),
            ),
        )

        repository.deleteRace(raceId)

        assertEquals(listOf("other-device-1"), db.pulledRecordDao().getAll().map { it.recordUuid })
    }

    @Test
    fun deleteRaceIsAllowedForTheActiveRaceIfItHasNeverBeenStarted() = runTest {
        // Being "active" (currently selected) alone doesn't protect a race from deletion —
        // only isRaceCurrentlyActive does. A race just created (or switched into) but never
        // started in either mode is fair game.
        settingsRepository.setActiveRaceId(raceId)

        repository.deleteRace(raceId)

        assertNull(repository.getRace(raceId))
    }

    @Test
    fun deleteRaceRefusesARaceThatsStillRunning() = runTest {
        db.raceDao().setTimeModeStartedAt(raceId, 1_000L)

        repository.deleteRace(raceId)

        assertEquals(raceId, repository.getRace(raceId)?.id)
    }

    @Test
    fun deleteRaceRefusesARaceThatsStoppedButNotYetReset() = runTest {
        // Stopping alone must not clear active status — only Reset does (see isRaceActive's
        // own doc). This race's history is still live and un-finalized until it's Reset.
        db.raceDao().setTimeModeStartedAt(raceId, 1_000L)
        db.raceDao().setTimeModeStoppedAt(raceId, 2_000L)

        repository.deleteRace(raceId)

        assertEquals(raceId, repository.getRace(raceId)?.id)
    }

    @Test
    fun deleteRaceIsAllowedOnceAStartedRaceHasBeenStoppedAndReset() = runTest {
        // Reset clears timeModeStartedAtMillis back to null, same as a race that was never
        // started — must not stay permanently protected just because it once ran.
        db.raceDao().setTimeModeStartedAt(raceId, 1_000L)
        db.raceDao().setTimeModeStoppedAt(raceId, 2_000L)
        db.raceDao().resetTimeMode(raceId)

        repository.deleteRace(raceId)

        assertNull(repository.getRace(raceId))
    }

    // deleteRace also clearing a dangling settingsRepository.activeRaceId — without this,
    // Time/Bibs Mode's own raceIdFlow (settingsRepository.activeRaceId, read with no
    // existence check) keeps pointing at the just-deleted row, rendering a "race" with blank
    // details that still looks active enough to enable Start — and crashes the moment an
    // action tries to write to it (confirmed in the field via
    // TimeModeRepository.startStopwatch's own requireNotNull(raceDao.getById(raceId))).

    @Test
    fun deletingTheCurrentlySelectedRaceClearsActiveRaceId() = runTest {
        settingsRepository.setActiveRaceId(raceId)

        repository.deleteRace(raceId)

        assertNull(settingsRepository.activeRaceId.first())
    }

    @Test
    fun deletingARaceThatIsNotTheSelectedOneLeavesActiveRaceIdAlone() = runTest {
        val otherRaceId = db.raceDao().insert(RaceEntity(label = "Other Race", createdAtMillis = 0L))
        settingsRepository.setActiveRaceId(otherRaceId)

        repository.deleteRace(raceId)

        assertEquals(otherRaceId, settingsRepository.activeRaceId.first())
    }
}
