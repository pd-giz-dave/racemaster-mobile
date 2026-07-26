package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceProgressTest {

    // isRaceInProgress — Time, Bibs, and CP all contribute independently via the same
    // started/stopped-timestamp shape (see RaceEntity's own started/stopped field pairs), so a
    // race active in only one of the three must still block a "New Race" from any mode's
    // screen — a blind spot that would otherwise let that mode's own live segment be silently
    // orphaned.

    @Test
    fun cpAloneCountsAsInProgress() {
        assertEquals(
            true,
            isRaceInProgress(
                timeModeStartedAtMillis = null,
                timeModeStoppedAtMillis = null,
                bibsModeStartedAtMillis = null,
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
                bibsModeStartedAtMillis = null,
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
                bibsModeStartedAtMillis = null,
                bibsModeStoppedAtMillis = null,
                cpModeStartedAtMillis = null,
                cpModeStoppedAtMillis = null,
            ),
        )
    }

    @Test
    fun bibsAloneCountsAsInProgress() {
        // Bibs is shaped exactly like CP/Time now — a started-but-not-stopped timestamp is
        // sufficient on its own, with no dependence on whether any real entry has been logged
        // yet (unlike the old entries-derived signal).
        assertEquals(
            true,
            isRaceInProgress(
                timeModeStartedAtMillis = null,
                timeModeStoppedAtMillis = null,
                bibsModeStartedAtMillis = 1_000L,
                bibsModeStoppedAtMillis = null,
                cpModeStartedAtMillis = null,
                cpModeStoppedAtMillis = null,
            ),
        )
    }

    @Test
    fun bibsStoppedDoesNotCountAsInProgress() {
        assertEquals(
            false,
            isRaceInProgress(
                timeModeStartedAtMillis = null,
                timeModeStoppedAtMillis = null,
                bibsModeStartedAtMillis = 1_000L,
                bibsModeStoppedAtMillis = 2_000L,
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
                bibsModeStartedAtMillis = 1_000L,
                bibsModeStoppedAtMillis = 5_000L,
                cpModeStartedAtMillis = 1_000L,
                cpModeStoppedAtMillis = null,
            ),
        )
    }

    // isRaceActive — same "each mode contributes its own independent signal" property, for the
    // stricter "protect from a destructive action" definition. All three params are now shaped
    // identically (a plain started-at timestamp), so one representative pair of tests per mode
    // is enough to prove the same thing three times over.

    @Test
    fun cpStartedAloneCountsAsActive() {
        assertEquals(true, isRaceActive(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, cpModeStartedAtMillis = 1_000L))
    }

    @Test
    fun bibsStartedAloneCountsAsActive() {
        assertEquals(true, isRaceActive(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = 1_000L, cpModeStartedAtMillis = null))
    }

    @Test
    fun timeStartedAloneCountsAsActive() {
        assertEquals(true, isRaceActive(timeModeStartedAtMillis = 1_000L, bibsModeStartedAtMillis = null, cpModeStartedAtMillis = null))
    }

    @Test
    fun nothingStartedIsNotActive() {
        assertEquals(false, isRaceActive(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, cpModeStartedAtMillis = null))
    }

    // isRaceActive deliberately takes no stopped-flag params at all — a merely Stopped-not-Reset
    // segment still has its own started-at field set (Reset is the only thing that clears any of
    // them, see RaceDao.resetBibsMode/resetCpMode/resetTimeMode), so it's already covered by the
    // "started alone counts as active" cases above; there's no separate "stopped" input here to
    // test.

    // isModeStarted — the per-mode mapping RaceDetailsScreen uses to decide which fields lock
    // read-only once a race is under way.

    @Test
    fun isModeStartedReadsEachModesOwnField() {
        val race = RaceEntity(
            label = "Test Race",
            createdAtMillis = 0L,
            timeModeStartedAtMillis = 1_000L,
            bibsModeStartedAtMillis = 2_000L,
            cpModeStartedAtMillis = 3_000L,
        )
        assertTrue(isModeStarted(AppMode.TIME, race))
        assertTrue(isModeStarted(AppMode.BIBS, race))
        assertTrue(isModeStarted(AppMode.CP, race))
    }

    @Test
    fun isModeStartedIsFalseForAModeThatHasNotStartedEvenIfAnotherHas() {
        val race = RaceEntity(label = "Test Race", createdAtMillis = 0L, bibsModeStartedAtMillis = 2_000L)
        assertFalse(isModeStarted(AppMode.TIME, race))
        assertTrue(isModeStarted(AppMode.BIBS, race))
        assertFalse(isModeStarted(AppMode.CP, race))
    }

    @Test
    fun isModeStartedIsAlwaysFalseForMule() {
        val race = RaceEntity(
            label = "Test Race",
            createdAtMillis = 0L,
            timeModeStartedAtMillis = 1_000L,
            bibsModeStartedAtMillis = 2_000L,
            cpModeStartedAtMillis = 3_000L,
        )
        assertFalse(isModeStarted(AppMode.MULE, race))
    }

    @Test
    fun isModeStartedIsFalseForANullRace() {
        assertFalse(isModeStarted(AppMode.BIBS, null))
    }
}
