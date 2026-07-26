package mobile.racemaster.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTimeFormatTest {

    @Test
    fun formatsHoursMinutesSecondsWithNoSubSecondPrecision() {
        assertEquals("00:01:30", formatElapsedSplitTime(90_000L))
    }

    @Test
    fun roundsDownBelowHalfASecond() {
        assertEquals("00:00:01", formatElapsedSplitTime(1_490L))
    }

    @Test
    fun roundsUpAtExactlyHalfASecond() {
        assertEquals("00:00:02", formatElapsedSplitTime(1_500L))
    }

    @Test
    fun roundsUpAboveHalfASecond() {
        assertEquals("00:00:02", formatElapsedSplitTime(1_530L))
    }

    @Test
    fun roundingCanCarryIntoMinutesAndHours() {
        // 59:59.6 rounds up to a full hour, not 59:60.
        assertEquals("01:00:00", formatElapsedSplitTime(3_599_600L))
    }

    @Test
    fun negativeMillisIsCoercedToZero() {
        assertEquals("00:00:00", formatElapsedSplitTime(-500L))
    }
}
