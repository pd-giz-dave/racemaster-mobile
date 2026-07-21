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
            db.pulledRecordDao(),
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

    // updateRaceDetails re-homing Mule-inbox rows onto a renamed race's new label — see
    // PulledRecordDao.retagSourceRaceLabel's doc for why this matters: without it,
    // MuleRepository.pushToServer would keep grouping this race's already-pulled history
    // under its old name forever, so the new name's server file would only ever get whatever
    // was recorded after the rename.

    @Test
    fun renamingARaceRetagsThisDevicesOwnPulledRecordsOntoTheNewLabel() = runTest {
        val myDeviceId = settingsRepository.getOrCreateDeviceId()
        db.pulledRecordDao().insertAll(
            listOf(
                PulledRecordEntity(
                    recordUuid = "self-1",
                    sourceDeviceId = myDeviceId,
                    sourceDeviceRole = "TIME",
                    sourceRaceLabel = "Test Race",
                    lineNumber = 1L,
                    payloadJson = "{}",
                    pulledAtMillis = 0L,
                ),
            ),
        )

        repository.updateRaceDetails(raceId, name = "Renamed", course = "Course", bibsRangeStart = null, bibsRangeCount = null)
        val newLabel = repository.getRace(raceId)?.label
        assertEquals(buildRaceLabel("Renamed", "Course", 0L), newLabel)

        val allRecords = db.pulledRecordDao().getAll()
        assertEquals(listOf(newLabel), allRecords.map { it.sourceRaceLabel })
    }

    @Test
    fun renamingARaceNeverTouchesAnotherDevicesPulledRecordsForTheSameOldLabel() = runTest {
        db.pulledRecordDao().insertAll(
            listOf(
                PulledRecordEntity(
                    recordUuid = "other-device-1",
                    sourceDeviceId = "some-other-device-id",
                    sourceDeviceRole = "TIME",
                    sourceRaceLabel = "Test Race",
                    lineNumber = 1L,
                    payloadJson = "{}",
                    pulledAtMillis = 0L,
                ),
            ),
        )

        repository.updateRaceDetails(raceId, name = "Renamed", course = "Course", bibsRangeStart = null, bibsRangeCount = null)

        val allRecords = db.pulledRecordDao().getAll()
        assertEquals("Test Race", allRecords.single().sourceRaceLabel)
    }

    @Test
    fun editingDetailsWithoutActuallyChangingTheComputedLabelLeavesPulledRecordsAlone() = runTest {
        // "Test Race" wasn't produced by buildRaceLabel in the first place (see setUp), so
        // this covers updateRaceDetails's own no-op guard directly: a genuine no-rename edit
        // (label unchanged) must not touch sourceRaceLabel at all. First call establishes a
        // stable buildRaceLabel-derived label; the second repeats the exact same name/course,
        // so the recomputed label is identical and no retag should happen.
        repository.updateRaceDetails(raceId, name = "Same", course = "Course", bibsRangeStart = null, bibsRangeCount = null)
        val stableLabel = requireNotNull(repository.getRace(raceId)?.label)

        db.pulledRecordDao().insertAll(
            listOf(
                PulledRecordEntity(
                    recordUuid = "self-1",
                    sourceDeviceId = settingsRepository.getOrCreateDeviceId(),
                    sourceDeviceRole = "TIME",
                    sourceRaceLabel = stableLabel,
                    lineNumber = 1L,
                    payloadJson = "{}",
                    pulledAtMillis = 0L,
                ),
            ),
        )

        repository.updateRaceDetails(raceId, name = "Same", course = "Course", bibsRangeStart = 1, bibsRangeCount = 10)

        assertEquals(stableLabel, repository.getRace(raceId)?.label)
        val allRecords = db.pulledRecordDao().getAll()
        assertEquals(stableLabel, allRecords.single().sourceRaceLabel)
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
}
