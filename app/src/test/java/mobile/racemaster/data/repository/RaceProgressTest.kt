package mobile.racemaster.data.repository

import mobile.racemaster.data.settings.AppMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RaceProgressTest {

    // isRaceActive — used both to guard a destructive action and to decide "in progress" for the
    // mode picker/blocking a new race (see its own doc — the two questions collapsed into one
    // once Stop was dropped, see HistoryAction's own doc: there's no more separate "running vs.
    // merely stopped" distinction). Time and Bibs each contribute independently via a plain
    // started-at timestamp, so a race active in only one of the two must still block a "New
    // Race" from either mode's screen — a blind spot that would otherwise let that mode's own
    // live segment be silently orphaned. Count/Close have no started-at column of their own —
    // they're active for as long as [mode]/[location] name Bibs-at-Pen/Close instead (Count/
    // Close no longer have their own AppMode value — see AppMode's own doc on the amalgamation).

    @Test
    fun bibsStartedAloneCountsAsActive() {
        assertEquals(true, isRaceActive(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = 1_000L, mode = AppMode.BIBS.name, location = "Start"))
    }

    @Test
    fun timeStartedAloneCountsAsActive() {
        assertEquals(true, isRaceActive(timeModeStartedAtMillis = 1_000L, bibsModeStartedAtMillis = null, mode = AppMode.TIME.name, location = "Finish"))
    }

    @Test
    fun nothingStartedIsNotActive() {
        assertEquals(false, isRaceActive(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, mode = null, location = null))
    }

    @Test
    fun countModeIsAlwaysActiveOnceSelected() {
        assertEquals(true, isRaceActive(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, mode = AppMode.BIBS.name, location = "Pen"))
    }

    @Test
    fun closeModeIsAlwaysActiveOnceSelected() {
        assertEquals(true, isRaceActive(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, mode = AppMode.BIBS.name, location = "Close"))
    }

    @Test
    fun bibsAtOrdinaryLocationWithNothingStartedIsNotActive() {
        // Bibs-at-Pen/Close is always-active (no started-at column of its own); Bibs at an
        // ordinary location (Start/CPn/Finish) is not — it still needs Start pressed, same as
        // ever, so this must not regress into treating every Bibs-mode race as active.
        assertEquals(false, isRaceActive(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, mode = AppMode.BIBS.name, location = "Start"))
    }

    // activeModeLabels — names which mode(s) isRaceActive is actually keying off, for
    // RaceHistoryScreen's "can't be deleted" caption. Confirmed in the field: a race that's been
    // fully Reset in Time Mode still stayed undeletable because it had also, at some point, been
    // started in Bibs Mode via a mode switch and never separately reset there — with no way to
    // tell why from Time Mode's own (fully idle-looking) screen.

    @Test
    fun namesOnlyTheModeThatsActuallyStarted() {
        assertEquals(listOf("Time Mode"), activeModeLabels(timeModeStartedAtMillis = 1_000L, bibsModeStartedAtMillis = null, mode = AppMode.TIME.name, location = "Finish"))
        assertEquals(listOf("Bibs Mode"), activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = 1_000L, mode = AppMode.BIBS.name, location = "Start"))
    }

    @Test
    fun namesEveryModeStillStartedAtOnce() {
        // The exact scenario that motivated this: Time reset (null) but Bibs still started from
        // an earlier mode switch on the same race — must name Bibs, not silently drop it.
        assertEquals(
            listOf("Bibs Mode"),
            activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = 1_000L, mode = AppMode.BIBS.name, location = "Start"),
        )
    }

    @Test
    fun namesNothingWhenNoModeIsActive() {
        assertEquals(emptyList<String>(), activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, mode = null, location = null))
    }

    @Test
    fun namesCountOrCloseWhenCurrentlySelected() {
        assertEquals(listOf("Count Mode"), activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, mode = AppMode.BIBS.name, location = "Pen"))
        assertEquals(listOf("Close Mode"), activeModeLabels(timeModeStartedAtMillis = null, bibsModeStartedAtMillis = null, mode = AppMode.BIBS.name, location = "Close"))
    }
}
