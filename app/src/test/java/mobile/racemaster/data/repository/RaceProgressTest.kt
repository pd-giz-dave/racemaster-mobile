package mobile.racemaster.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceProgressTest {

    // isRaceInProgress — CP mirrors Time's own started/stopped-timestamp shape (not Bibs'
    // entries-derived one), so a race active *only* in CP Mode must still block a "New Race"
    // from any mode's screen — a blind spot that would otherwise let CP's own live segment be
    // silently orphaned (see RaceProgress.kt's own doc for why this signal was added).

    @Test
    fun cpAloneCountsAsInProgress() {
        assertEquals(
            true,
            isRaceInProgress(
                timeModeStartedAtMillis = null,
                timeModeStoppedAtMillis = null,
                bibsHasRealEntries = false,
                bibsModeStoppedAtMillis = null,
                cpModeStartedAtMillis = 1_000L,
                cpModeStoppedAtMillis = null,
            ),
        )
    }

    @Test
    fun cpStoppedDoesNotCountAsInProgress() {
        assertEquals(
            false,
            isRaceInProgress(
                timeModeStartedAtMillis = null,
                timeModeStoppedAtMillis = null,
                bibsHasRealEntries = false,
                bibsModeStoppedAtMillis = null,
                cpModeStartedAtMillis = 1_000L,
                cpModeStoppedAtMillis = 2_000L,
            ),
        )
    }

    @Test
    fun cpNeverStartedDoesNotCountAsInProgress() {
        assertEquals(
            false,
            isRaceInProgress(
                timeModeStartedAtMillis = null,
                timeModeStoppedAtMillis = null,
                bibsHasRealEntries = false,
                bibsModeStoppedAtMillis = null,
                cpModeStartedAtMillis = null,
                cpModeStoppedAtMillis = null,
            ),
        )
    }

    @Test
    fun cpRunningAlongsideBibsStoppedStillCountsAsInProgress() {
        // Every mode's own contribution is independent — one mode being stopped must never
        // mask another mode still genuinely running.
        assertEquals(
            true,
            isRaceInProgress(
                timeModeStartedAtMillis = null,
                timeModeStoppedAtMillis = null,
                bibsHasRealEntries = true,
                bibsModeStoppedAtMillis = 5_000L,
                cpModeStartedAtMillis = 1_000L,
                cpModeStoppedAtMillis = null,
            ),
        )
    }

    // isRaceActive — same "CP contributes its own independent signal" property, for the
    // stricter "protect from a destructive action" definition.

    @Test
    fun cpStartedAloneCountsAsActive() {
        assertEquals(true, isRaceActive(timeModeStartedAtMillis = null, bibsHasRealEntries = false, cpModeStartedAtMillis = 1_000L))
    }

    @Test
    fun cpNeverStartedAndNothingElseIsNotActive() {
        assertEquals(false, isRaceActive(timeModeStartedAtMillis = null, bibsHasRealEntries = false, cpModeStartedAtMillis = null))
    }

    // isRaceActive deliberately takes no stopped-flag params at all — a merely Stopped-not-Reset
    // CP segment still has cpModeStartedAtMillis set (Reset is the only thing that clears it, see
    // RaceDao.resetCpMode), so it's already covered by cpStartedAloneCountsAsActive above; there's
    // no separate "stopped" input here to test, mirroring Bibs' own identical reasoning for why
    // this function ignores stopped flags entirely.
}
