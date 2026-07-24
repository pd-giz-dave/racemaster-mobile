package mobile.racemaster.ui.racehistory

import kotlin.time.Duration.Companion.days
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceHistoryViewModelTest {

    // isSkippedAsStale — mirrors MuleRepository.pushToServer's own age cutoff, pulled out as a
    // pure function so it's directly testable without standing up the ViewModel's full
    // repository/Flow graph.

    @Test
    fun aRaceLabelNeverTouchedByMuleIsNeverStale() {
        // Absent entirely from lastTouchedAtMillis — a distinct, unrelated state (e.g.
        // auto-sync simply hasn't run yet), not "too old".
        assertFalse(isSkippedAsStale("Some Race", lastTouchedAtMillis = emptyMap(), maxAgeDays = 2))
    }

    @Test
    fun aRaceLabelTouchedWithinTheWindowIsNotStale() {
        val now = System.currentTimeMillis()
        val lastTouched = mapOf("Some Race" to now - 1.days.inWholeMilliseconds)

        assertFalse(isSkippedAsStale("Some Race", lastTouched, maxAgeDays = 2))
    }

    @Test
    fun aRaceLabelUntouchedPastTheWindowIsStale() {
        val now = System.currentTimeMillis()
        val lastTouched = mapOf("Some Race" to now - 3.days.inWholeMilliseconds)

        assertTrue(isSkippedAsStale("Some Race", lastTouched, maxAgeDays = 2))
    }
}
