package mobile.racemaster.ui.racehistory

import org.junit.Assert.assertEquals
import org.junit.Test

// formatGeneratedAt — pulled out as a pure function so the Races page's progress-file rows'
// timestamp display is directly testable without composing the whole screen.
class RaceHistoryScreenTest {
    @Test
    fun trimsTheIsoTimestampToDateAndTime() {
        assertEquals("2026-08-23 10:00:00", formatGeneratedAt("2026-08-23T10:00:00.000Z"))
    }

    @Test
    fun toleratesNoMillisecondsSuffix() {
        assertEquals("2026-08-23 10:00:00", formatGeneratedAt("2026-08-23T10:00:00"))
    }

    @Test
    fun fallsBackToTheRawStringWhenItDoesNotLookLikeIso() {
        assertEquals("not-a-date", formatGeneratedAt("not-a-date"))
    }

    @Test
    fun fallsBackToTheRawStringWhenBlank() {
        assertEquals("", formatGeneratedAt(""))
    }
}
