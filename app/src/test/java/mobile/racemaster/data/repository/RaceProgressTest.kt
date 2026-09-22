package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceProgressTest {

    // isRaceActive — used both to guard a destructive action and to decide "in progress" for the
    // mode picker/blocking a new race (see its own doc — the two questions collapsed into one
    // once Stop was dropped, see HistoryAction's own doc: there's no more separate "running vs.
    // merely stopped" distinction). Time, Bibs, and CP each contribute independently via a plain
    // started-at timestamp, so a race active in only one of the three must still block a "New
    // Race" from any mode's screen — a blind spot that would otherwise let that mode's own live
    // segment be silently orphaned.

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

    // activeModeLabels — names which mode(s) isRaceActive is actually keying off, for
    // RaceHistoryScreen's "can't be deleted" caption. Confirmed in the field: a race that's been
    // fully Reset in Time Mode still stayed undeletable because it had also, at some point, been
    // started in Bibs Mode via a mode switch and never separately reset there — with no way to
    // tell why from Time Mode's own (fully idle-looking) screen.

    @Test
    fun namesOnlyTheModeThatsActuallyStarted() {
        assertEquals(listOf("Time Mode"), activeModeLabels(timeModeStartedAtMillis = 1_000L, bibsModeStartedAtMillis = null, cpModeStartedAtMillis = null))
        assertEquals(listOf("Bibs Mode"), activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = 1_000L, cpModeStartedAtMillis = null))
        assertEquals(listOf("CP Mode"), activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, cpModeStartedAtMillis = 1_000L))
    }

    @Test
    fun namesEveryModeStillStartedAtOnce() {
        // The exact scenario that motivated this: Time reset (null) but Bibs still started from
        // an earlier mode switch on the same race — must name Bibs, not silently drop it.
        assertEquals(
            listOf("Bibs Mode", "CP Mode"),
            activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = 1_000L, cpModeStartedAtMillis = 2_000L),
        )
    }

    @Test
    fun namesNothingWhenNoModeIsActive() {
        assertEquals(emptyList<String>(), activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, cpModeStartedAtMillis = null))
    }

    // isModeStarted — the per-mode mapping onto whichever of RaceEntity's own
    // timeModeStartedAtMillis/bibsModeStartedAtMillis/cpModeStartedAtMillis fields is right.

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
    fun isModeStartedIsFalseForANullRace() {
        assertFalse(isModeStarted(AppMode.BIBS, null))
    }
}
