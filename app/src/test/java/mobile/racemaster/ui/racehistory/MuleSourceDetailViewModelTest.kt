package mobile.racemaster.ui.racehistory

import org.junit.Assert.assertEquals
import org.junit.Test

class MuleSourceDetailViewModelTest {

    // Regression coverage for the centiseconds fix: adding ".CC" to the wire `time` format
    // used to silently zero out every Mule-pulled Time record's displayed elapsed time, since
    // a naive split on ":" expecting exactly 3 integer parts would fail to parse "SS.CC".

    @Test
    fun parsesCentisecondsSuffix() {
        assertEquals(90_420L, parseElapsedClock("00:01:30.42"))
    }

    @Test
    fun parsesZeroCentiseconds() {
        assertEquals(90_000L, parseElapsedClock("00:01:30.00"))
    }

    @Test
    fun toleratesTheOlderCentisecondsFreeFormat() {
        assertEquals(90_000L, parseElapsedClock("00:01:30"))
    }

    @Test
    fun parsesHoursAndMinutesCorrectly() {
        assertEquals((3_661L * 1000L) + 250L, parseElapsedClock("01:01:01.25"))
    }

    @Test
    fun nullTimeParsesToZero() {
        assertEquals(0L, parseElapsedClock(null))
    }

    @Test
    fun malformedTimeParsesToZeroRatherThanThrowing() {
        assertEquals(0L, parseElapsedClock("not-a-time"))
        assertEquals(0L, parseElapsedClock("01:02"))
        assertEquals(0L, parseElapsedClock(""))
    }
}
