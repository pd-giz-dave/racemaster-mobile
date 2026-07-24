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
    fun noActivitySignalAtAllIsNeverStale() {
        // null — a Mule-pulled label never seen, or a local race with no history yet — is a
        // distinct, unrelated state, not "too old".
        assertFalse(isSkippedAsStale(lastTouchedAtMillis = null, maxAgeDays = 2))
    }

    @Test
    fun touchedWithinTheWindowIsNotStale() {
        val now = System.currentTimeMillis()

        assertFalse(isSkippedAsStale(now - 1.days.inWholeMilliseconds, maxAgeDays = 2))
    }

    @Test
    fun untouchedPastTheWindowIsStale() {
        val now = System.currentTimeMillis()

        assertTrue(isSkippedAsStale(now - 3.days.inWholeMilliseconds, maxAgeDays = 2))
    }
}
