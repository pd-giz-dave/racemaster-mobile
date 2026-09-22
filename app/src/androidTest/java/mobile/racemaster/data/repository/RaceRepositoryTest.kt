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
    fun recordModeStartWritesNewRaceThenLocationThenModeStartForABrandNewRace() = runTest {
        // The test race from setUp() has mode == null (never started), so this is its genuine
        // first-ever recordModeStart call — the NEW_RACE marker (see HistoryAction.NEW_RACE's own
        // doc) is written first, shifting LOCATION/MODE_START to lineNumbers 2/3.
        repository.recordModeStart(raceId, AppMode.TIME, "Finish")

        val rows = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.lineNumber }
        assertEquals(3, rows.size)
        assertEquals(HistoryAction.NEW_RACE, rows[0].action)
        assertEquals(HistoryMode.TIME, rows[0].mode)
        assertEquals(HistoryAction.LOCATION, rows[1].action)
        assertEquals("Finish", rows[1].note)
        assertEquals(HistoryMode.TIME, rows[1].mode)
        assertEquals(HistoryAction.MODE_START, rows[2].action)
        assertEquals("Time", rows[2].note)
        assertEquals(HistoryMode.TIME, rows[2].mode)
        assertNull(rows[2].bibNumber)
        assertNull(rows[2].splitNumber)

        val race = db.raceDao().getById(raceId)
        assertEquals("TIME", race?.mode)
        assertEquals("Finish", race?.location)
    }

    @Test
    fun recordModeStartDoesNotWriteASecondNewRaceMarkerOnRelocate() = runTest {
        repository.recordModeStart(raceId, AppMode.TIME, "Finish")
        repository.recordModeStart(raceId, AppMode.BIBS, "CP1")

        val rows = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.lineNumber }
        assertEquals(1, rows.count { it.action == HistoryAction.NEW_RACE })
        assertEquals(HistoryAction.NEW_RACE, rows[0].action)
    }

    // recordModeStart's own resume-on-relocate behavior (TODO.md: "relocating back to some
    // previous location... must pick up where it left off") — see HistoryFold's own doc for the
    // location-visit mechanics this relies on.

    @Test
    fun relocatingToAGenuinelyNewLocationResetsTheCounterToOne() = runTest {
        repository.recordModeStart(raceId, AppMode.TIME, "Finish")
        db.raceDao().setTimeModeNextSplit(raceId, 5) // simulate a few splits already recorded

        repository.recordModeStart(raceId, AppMode.TIME, "CP1")

        assertEquals(1, db.raceDao().getById(raceId)?.timeModeNextSplit)
    }

    @Test
    fun relocatingBackToAnAlreadyVisitedNotYetResetLocationResumesTheCounter() = runTest {
        repository.recordModeStart(raceId, AppMode.TIME, "Finish")
        db.historyLineDao().insert(
            HistoryLineEntity(
                raceId = raceId, mode = HistoryMode.TIME, action = HistoryAction.SPLIT,
                splitNumber = 1, lineNumber = db.raceDao().getById(raceId)!!.nextLineNumber, timestampMillis = 0L,
            ),
        )
        db.raceDao().incrementLineNumber(raceId)
        db.raceDao().setTimeModeNextSplit(raceId, 2)
        repository.recordModeStart(raceId, AppMode.TIME, "CP1") // relocate away, no Reset

        repository.recordModeStart(raceId, AppMode.TIME, "Finish") // relocate back

        // Resumes at 2 (one past Finish's own highest recorded split, 1) rather than resetting.
        assertEquals(2, db.raceDao().getById(raceId)?.timeModeNextSplit)
    }

    @Test
    fun relocatingBackToALocationWhoseOnlyPriorVisitWasResetStartsFreshInstead() = runTest {
        repository.recordModeStart(raceId, AppMode.TIME, "Finish")
        db.raceDao().setTimeModeNextSplit(raceId, 4)
        repository.closeCurrentSegment(raceId, HistoryMode.TIME)

        repository.recordModeStart(raceId, AppMode.TIME, "Finish")

        // The old Finish visit was Reset away, so this is treated as genuinely fresh.
        assertEquals(1, db.raceDao().getById(raceId)?.timeModeNextSplit)
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
    fun deleteRaceIsAllowedOnceAStartedRaceHasBeenReset() = runTest {
        // Reset clears timeModeStartedAtMillis back to null, same as a race that was never
        // started — must not stay permanently protected just because it once ran.
        db.raceDao().setTimeModeStartedAt(raceId, 1_000L)
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
    // race), so it can then go through the normal deleteRace flow above. closeCurrentSegment (the
    // shared mechanism behind both this and an in-context Reset) needs at least one row to close
    // — a real race reaching this state always has one (Start can't be pressed without a mode
    // being set up first), so these fixtures insert one too rather than relying on the
    // started-at column alone.

    private suspend fun markStartedWithHistory(mode: HistoryMode, startedAtMillis: Long) {
        when (mode) {
            HistoryMode.TIME -> db.raceDao().setTimeModeStartedAt(raceId, startedAtMillis)
            HistoryMode.BIBS -> db.raceDao().setBibsModeStartedAt(raceId, startedAtMillis)
            HistoryMode.CP -> db.raceDao().setCpModeStartedAt(raceId, startedAtMillis)
        }
        val race = db.raceDao().getById(raceId)!!
        db.historyLineDao().insert(
            HistoryLineEntity(
                raceId = raceId, mode = mode, action = HistoryAction.CLOCK,
                splitNumber = 0, lineNumber = race.nextLineNumber, timestampMillis = startedAtMillis,
            ),
        )
        db.raceDao().incrementLineNumber(raceId)
    }

    @Test
    fun forceResetActiveModesClearsWhicheverModeIsStillStarted() = runTest {
        markStartedWithHistory(HistoryMode.TIME, 1_000L)

        repository.forceResetActiveModes(raceId)

        assertNull(repository.getRace(raceId)?.timeModeStartedAtMillis)
    }

    @Test
    fun forceResetActiveModesClearsEveryStartedModeAtOnce() = runTest {
        // Safe to call even when more than one mode happens to be started for the same race —
        // each is reset independently.
        markStartedWithHistory(HistoryMode.TIME, 1_000L)
        markStartedWithHistory(HistoryMode.BIBS, 2_000L)
        markStartedWithHistory(HistoryMode.CP, 3_000L)

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
        markStartedWithHistory(HistoryMode.BIBS, 1_000L)

        repository.forceResetActiveModes(raceId)

        val resetRows = db.historyLineDao().observeAllForRace(raceId).first().filter { it.action == HistoryAction.RESET }
        assertEquals(1, resetRows.size)
        assertEquals(HistoryMode.BIBS, resetRows.single().mode)
    }

    @Test
    fun forceResetActiveModesNeverInsertsAMarkerForAModeThatWasNeverStarted() = runTest {
        // Only Bibs was ever started for this race — Time/CP must stay completely untouched,
        // not gain a spurious Reset line for a mode this race never actually used.
        markStartedWithHistory(HistoryMode.BIBS, 1_000L)

        repository.forceResetActiveModes(raceId)

        // The bare Clock row plus the new Reset marker — nothing for Time/CP.
        assertEquals(2, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun forceResetActiveModesMakesTheRaceDeletable() = runTest {
        markStartedWithHistory(HistoryMode.BIBS, 1_000L)

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

    // recordModeStart's own mode switching is never blocked, regardless of whether another mode
    // is still started — there used to be a separate blockedModeSwitchReason guard here (removed:
    // it required a full Reset of the other mode first once Stop went away, which actively fought
    // recordModeStart's own resume-on-relocate-back behavior — a mode left mid-recording is
    // always safely resumable later via Relocate, so blocking the switch in the first place was
    // pure friction with nothing left to protect against).

    @Test
    fun recordModeStartSwitchesModeEvenWhileAnotherModeIsStillStarted() = runTest {
        db.raceDao().setCpModeStartedAt(raceId, 1_000L)

        repository.recordModeStart(raceId, AppMode.BIBS, "Finish")

        assertEquals("BIBS", repository.getRace(raceId)?.mode)
        // CP's own started-at is untouched — relocating away from it doesn't Reset it, so it
        // stays resumable later via Relocate (see recordModeStart's own resume doc).
        assertEquals(1_000L, repository.getRace(raceId)?.cpModeStartedAtMillis)
    }
}
