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
import mobile.racemaster.data.settings.AppMode
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
        repository = RaceRepository(
            db,
            db.raceDao(),
            db.historyLineDao(),
            db.lineSyncDao(),
            settingsRepository,
        )
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recordLineSyncsIsAttributedToTheGivenTarget() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L, 2L), targetId = "puller-device-id", targetName = "lively-otter", isSink = false)

        val rows = repository.observeLineSyncs(raceId).first()
        assertEquals(setOf(1L, 2L), rows.map { it.lineNumber }.toSet())
        assertEquals(setOf("puller-device-id"), rows.map { it.targetId }.toSet())
    }

    @Test
    fun recordLineSyncsForServerUsesTheReservedConstant() = runTest {
        repository.recordLineSyncs(raceId, listOf(3L), targetId = SERVER_TARGET_ID, targetName = "Server", isSink = true)

        val rows = repository.observeLineSyncs(raceId).first()
        assertEquals(SERVER_TARGET_ID, rows.single().targetId)
    }

    @Test
    fun sameLineCanBeSyncedToMultipleDistinctTargets() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "puller-a", targetName = "lively-otter", isSink = false)
        repository.recordLineSyncs(raceId, listOf(1L), targetId = SERVER_TARGET_ID, targetName = "Server", isSink = true)

        val targets = repository.observeLineSyncs(raceId).first().map { it.targetId }.toSet()
        assertEquals(setOf("puller-a", SERVER_TARGET_ID), targets)
    }

    @Test
    fun reAckingTheSameLineAndTargetReplacesRatherThanDuplicates() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "puller-a", targetName = "lively-otter", isSink = false, syncedAtMillis = 1_000L)
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "puller-a", targetName = "lively-otter", isSink = false, syncedAtMillis = 2_000L)

        val rows = repository.observeLineSyncs(raceId).first()
        assertEquals(1, rows.size)
        assertEquals(2_000L, rows.single().syncedAtMillis)
    }

    @Test
    fun recordLineSyncsPersistsTheDisplayNameDistinctFromTheRawTargetId() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "b3b711bd-95d1-408b-99a0-2a4523df2fc4", targetName = "quiet-thicket", isSink = false)

        val row = repository.observeLineSyncs(raceId).first().single()
        assertEquals("b3b711bd-95d1-408b-99a0-2a4523df2fc4", row.targetId)
        assertEquals("quiet-thicket", row.targetName)
    }

    @Test
    fun recordLineSyncsPersistsWhetherTheTargetIsASink() = runTest {
        repository.recordLineSyncs(raceId, listOf(1L), targetId = "relay-mule", targetName = "quiet-thicket", isSink = false)
        repository.recordLineSyncs(raceId, listOf(2L), targetId = SERVER_TARGET_ID, targetName = "Server", isSink = true)

        val rows = repository.observeLineSyncs(raceId).first().associateBy { it.lineNumber }
        assertEquals(false, rows.getValue(1L).isSink)
        assertEquals(true, rows.getValue(2L).isSink)
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
    fun recordModeStartWritesLocationThenModeStartBothScopedToTheChosenMode() = runTest {
        repository.recordModeStart(raceId, AppMode.TIME, "Finish")

        val rows = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.lineNumber }
        assertEquals(2, rows.size)
        assertEquals(HistoryAction.LOCATION, rows[0].action)
        assertEquals("Finish", rows[0].note)
        assertEquals(HistoryMode.TIME, rows[0].mode)
        assertEquals(HistoryAction.MODE_START, rows[1].action)
        assertEquals("Time", rows[1].note)
        assertEquals(HistoryMode.TIME, rows[1].mode)
        assertNull(rows[1].bibNumber)
        assertNull(rows[1].splitNumber)

        val race = db.raceDao().getById(raceId)
        assertEquals("TIME", race?.mode)
        assertEquals("Finish", race?.location)
    }

    // switchActiveRace — the "auto delete the empty placeholder" cleanup every setActiveRaceId
    // call site routes through instead of calling settingsRepository.setActiveRaceId directly.
    // Judged by whether the race being switched away from has ever recorded a single history
    // line (see switchActiveRace's own doc) — the "course" concept this used to be judged by
    // (a still course-less row) is gone entirely (TODO.md's phase 1).

    @Test
    fun switchActiveRaceDeletesARaceWithNoHistoryBeingSwitchedAwayFrom() = runTest {
        val pendingId = repository.startNewRace(name = "Acme")
        settingsRepository.setActiveRaceId(pendingId)
        val newRaceId = repository.startNewRace(name = "Other")

        repository.switchActiveRace(newRaceId)

        assertNull(repository.getRace(pendingId))
        assertEquals(newRaceId, settingsRepository.activeRaceId.first())
    }

    @Test
    fun switchActiveRaceKeepsARaceWithRealHistoryBeingSwitchedAwayFrom() = runTest {
        val acmeId = repository.startNewRace(name = "Acme")
        db.historyLineDao().insert(
            HistoryLineEntity(
                raceId = acmeId, mode = HistoryMode.TIME, action = HistoryAction.SPLIT,
                splitNumber = 1, lineNumber = 1L, timestampMillis = 0L,
            ),
        )
        settingsRepository.setActiveRaceId(acmeId)
        val newRaceId = repository.startNewRace(name = "Other")

        repository.switchActiveRace(newRaceId)

        // Real history means the race is never deleted, regardless of whether it was ever
        // actually started in either mode.
        assertEquals("Acme", repository.getRace(acmeId)?.name)
        assertEquals(newRaceId, settingsRepository.activeRaceId.first())
    }

    @Test
    fun switchActiveRaceIsSafeWhenNothingWasActiveBefore() = runTest {
        val newRaceId = repository.startNewRace(name = "Acme")

        repository.switchActiveRace(newRaceId)

        assertEquals(newRaceId, settingsRepository.activeRaceId.first())
    }

    @Test
    fun switchActiveRaceIsSafeWhenSwitchingToTheSameRace() = runTest {
        val pendingId = repository.startNewRace(name = "Acme")
        settingsRepository.setActiveRaceId(pendingId)

        repository.switchActiveRace(pendingId)

        // Must not delete the race it's simultaneously being asked to promote.
        assertEquals(pendingId, repository.getRace(pendingId)?.id)
        assertEquals(pendingId, settingsRepository.activeRaceId.first())
    }

    @Test
    fun renamingARaceNeedsNoPulledRecordsBookkeeping() = runTest {
        // Unlike the old design (which had to retag a separately-staged mirror of this
        // device's own data onto the new label), MuleRepository.pushToServer now reads this
        // race's own current label fresh from RaceEntity every attempt — a rename just takes
        // effect on the very next push, nothing else needs updating.
        repository.updateRaceDetails(raceId, name = "Renamed", location = "Finish")

        // The label is just the new name verbatim now (see buildRaceLabel's own doc) — no
        // date/course is ever appended.
        assertEquals(buildRaceLabel("Renamed"), repository.getRace(raceId)?.label)
    }

    // location — see RaceEntity.location's own doc. Not part of the label (unlike name), so
    // it's the one field here that both persists and can be edited freely without touching
    // buildRaceLabel at all.

    @Test
    fun startNewRacePersistsTheGivenLocation() = runTest {
        val newRaceId = repository.startNewRace(name = "Other Race", location = "Start", createdAtMillis = 0L)

        assertEquals("Start", repository.getRace(newRaceId)?.location)
    }

    @Test
    fun startNewRaceDefaultsLocationToFinish() = runTest {
        val newRaceId = repository.startNewRace(name = "Other Race", createdAtMillis = 0L)

        assertEquals("Finish", repository.getRace(newRaceId)?.location)
    }

    @Test
    fun updateRaceDetailsChangesLocationWithoutAffectingTheLabel() = runTest {
        // "Test Race"/"" (see setUp) wasn't produced by buildRaceLabel in the first place, so
        // this establishes a stable buildRaceLabel-derived label first, then changes only
        // location — the label recomputed from the *same* name a second time must come out
        // identical, proving location plays no part in it.
        repository.updateRaceDetails(raceId, name = "Same", location = "Finish")
        val stableLabel = requireNotNull(repository.getRace(raceId)?.label)

        repository.updateRaceDetails(raceId, name = "Same", location = "Checkpoint 2")

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
        repository.recordLineSyncs(raceId, listOf(1L), targetId = SERVER_TARGET_ID, targetName = "Server", isSink = true)

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
                    sourceDeviceId = "some-other-device-id",
                    sourceRaceLabel = "Test Race",
                    lineNumber = 1L,
                    payloadJson = "{}",
                    pulledAtMillis = 0L,
                ),
            ),
        )

        repository.deleteRace(raceId)

        assertEquals(listOf(1L), db.pulledRecordDao().getAll().map { it.lineNumber })
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

    // forceResetActiveModes — un-sticks a race whose active mode is no longer reachable via
    // that mode's own in-context Reset button (e.g. activeRaceId has since moved to a different
    // race), so it can then go through the normal deleteRace flow above.

    @Test
    fun forceResetActiveModesClearsWhicheverModeIsStillStarted() = runTest {
        db.raceDao().setTimeModeStartedAt(raceId, 1_000L)

        repository.forceResetActiveModes(raceId)

        assertNull(repository.getRace(raceId)?.timeModeStartedAtMillis)
    }

    @Test
    fun forceResetActiveModesClearsEveryStartedModeAtOnce() = runTest {
        // Safe to call even when more than one mode happens to be started for the same race —
        // each is reset independently.
        db.raceDao().setTimeModeStartedAt(raceId, 1_000L)
        db.raceDao().setBibsModeStartedAt(raceId, 2_000L)
        db.raceDao().setCpModeStartedAt(raceId, 3_000L)

        repository.forceResetActiveModes(raceId)

        val race = repository.getRace(raceId)
        assertNull(race?.timeModeStartedAtMillis)
        assertNull(race?.bibsModeStartedAtMillis)
        assertNull(race?.cpModeStartedAtMillis)
    }

    @Test
    fun forceResetActiveModesInsertsARealResetMarkerLikeAnInContextResetWould() = runTest {
        // Must do exactly what pressing Reset from Bibs Mode's own screen would have — a real
        // RESET marker row, not just a silent column clear — so Race History later reads this
        // race's unfinished segment as genuinely closed off, the same as any other Reset (see
        // forceResetActiveModes' own doc).
        db.raceDao().setBibsModeStartedAt(raceId, 1_000L)

        repository.forceResetActiveModes(raceId)

        val resetRows = db.historyLineDao().observeAllForRace(raceId).first().filter { it.action == HistoryAction.RESET }
        assertEquals(1, resetRows.size)
        assertEquals(HistoryMode.BIBS, resetRows.single().mode)
    }

    @Test
    fun forceResetActiveModesNeverInsertsAMarkerForAModeThatWasNeverStarted() = runTest {
        // Only Bibs was ever started for this race — Time/CP must stay completely untouched,
        // not gain a spurious Reset line for a mode this race never actually used.
        db.raceDao().setBibsModeStartedAt(raceId, 1_000L)

        repository.forceResetActiveModes(raceId)

        assertEquals(1, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun forceResetActiveModesMakesTheRaceDeletable() = runTest {
        db.raceDao().setBibsModeStartedAt(raceId, 1_000L)

        repository.forceResetActiveModes(raceId)
        repository.deleteRace(raceId)

        assertNull(repository.getRace(raceId))
    }

    @Test
    fun forceResetActiveModesIsANoOpOnARaceWithNothingStarted() = runTest {
        // Every reset query is a harmless no-op against an already-null field — must not throw
        // or otherwise misbehave against a race that was never started at all.
        repository.forceResetActiveModes(raceId)

        assertEquals(raceId, repository.getRace(raceId)?.id)
    }

    // blockedModeSwitchReason — Bibs and CP are mutually exclusive for the same race; see the
    // function's own doc for why (both are alternate ways of logging the same station). Stop
    // alone (not a full Reset) is enough to permit a switch — only a mode that's still actually
    // recording (started, not yet stopped) blocks one.

    @Test
    fun blockedModeSwitchReasonAllowsSwitchingToBibsWhenCpWasNeverStarted() = runTest {
        assertNull(repository.blockedModeSwitchReason(raceId, AppMode.BIBS))
    }

    @Test
    fun blockedModeSwitchReasonAllowsSwitchingToCpWhenBibsWasNeverStarted() = runTest {
        assertNull(repository.blockedModeSwitchReason(raceId, AppMode.CP))
    }

    @Test
    fun blockedModeSwitchReasonRefusesSwitchingToBibsWhileCpIsStarted() = runTest {
        db.raceDao().setCpModeStartedAt(raceId, 1_000L)

        assertEquals(
            "CP Mode still has an active race — Stop it before switching to Bibs Mode.",
            repository.blockedModeSwitchReason(raceId, AppMode.BIBS),
        )
    }

    @Test
    fun blockedModeSwitchReasonAllowsSwitchingToBibsOnceCpIsStoppedEvenIfNotReset() = runTest {
        // Merely Stopping CP (no Reset) is now enough to permit the switch — unlike isRaceActive
        // (a different, deliberately stricter guard for delete/rename protection), which still
        // treats a Stopped-not-Reset race as active.
        db.raceDao().setCpModeStartedAt(raceId, 1_000L)
        db.raceDao().setCpModeStoppedAt(raceId, 2_000L)

        assertNull(repository.blockedModeSwitchReason(raceId, AppMode.BIBS))
    }

    @Test
    fun blockedModeSwitchReasonAllowsSwitchingToBibsOnceCpHasBeenStoppedAndReset() = runTest {
        db.raceDao().setCpModeStartedAt(raceId, 1_000L)
        db.raceDao().setCpModeStoppedAt(raceId, 2_000L)
        db.raceDao().resetCpMode(raceId)

        assertNull(repository.blockedModeSwitchReason(raceId, AppMode.BIBS))
    }

    @Test
    fun blockedModeSwitchReasonRefusesSwitchingToCpWhileBibsIsStarted() = runTest {
        db.raceDao().setBibsModeStartedAt(raceId, 1_000L)

        assertEquals(
            "Bibs Mode still has an active race — Stop it before switching to CP Mode.",
            repository.blockedModeSwitchReason(raceId, AppMode.CP),
        )
    }

    @Test
    fun blockedModeSwitchReasonAllowsSwitchingToCpOnceBibsIsStoppedEvenIfNotReset() = runTest {
        // Merely Stopping Bibs (no Reset) is now enough to permit the switch — see the Bibs↔CP
        // mirror test's own doc above.
        db.raceDao().setBibsModeStartedAt(raceId, 1_000L)
        db.raceDao().setBibsModeStoppedAt(raceId, 2_000L)

        assertNull(repository.blockedModeSwitchReason(raceId, AppMode.CP))
    }

    @Test
    fun blockedModeSwitchReasonAllowsSwitchingToCpOnceBibsHasBeenStoppedAndReset() = runTest {
        db.raceDao().setBibsModeStartedAt(raceId, 1_000L)
        db.raceDao().setBibsModeStoppedAt(raceId, 2_000L)
        db.raceDao().resetBibsMode(raceId)

        assertNull(repository.blockedModeSwitchReason(raceId, AppMode.CP))
    }

    @Test
    fun blockedModeSwitchReasonRefusesSwitchingToTimeWhileBibsIsActive() = runTest {
        // Generalized to all three modes (see blockedModeSwitchReason's own doc: "only one of
        // the three may be started at once") — this pre-existing test used to assert the
        // opposite (that Time was exempt), which stopped matching that generalization; fixed
        // to assert the function's actual, current, documented behavior instead.
        db.raceDao().setBibsModeStartedAt(raceId, 1_000L)

        assertEquals(
            "Bibs Mode still has an active race — Stop it before switching to Time Mode.",
            repository.blockedModeSwitchReason(raceId, AppMode.TIME),
        )
    }

    @Test
    fun blockedModeSwitchReasonAllowsSwitchingToTimeOnceNothingElseIsActive() = runTest {
        db.raceDao().setBibsModeStartedAt(raceId, 1_000L)
        db.raceDao().setBibsModeStoppedAt(raceId, 2_000L)
        db.raceDao().resetBibsMode(raceId)

        assertNull(repository.blockedModeSwitchReason(raceId, AppMode.TIME))
    }
}
