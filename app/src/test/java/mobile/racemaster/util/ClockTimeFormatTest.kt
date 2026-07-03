package mobile.racemaster.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockTimeFormatTest {

    @Test
    fun minutesAndSecondsWithColonSeparator() {
        assertEquals("5:30", parseMinutesSeconds("5:30"))
    }

    @Test
    fun minutesAndSecondsWithDotSeparator() {
        assertEquals("5:30", parseMinutesSeconds("5.30"))
    }

    @Test
    fun minutesAndSecondsWithSpaceSeparator() {
        assertEquals("5:30", parseMinutesSeconds("5 30"))
    }

    @Test
    fun secondsPadToTwoDigits() {
        assertEquals("5:05", parseMinutesSeconds("5:5"))
    }

    @Test
    fun singleNumberIsInterpretedAsSeconds() {
        assertEquals("0:45", parseMinutesSeconds("45"))
    }

    @Test
    fun singleNumberOverSixtySecondsRollsIntoMinutes() {
        assertEquals("1:30", parseMinutesSeconds("90"))
    }

    @Test
    fun secondsOutOfRangeInTwoNumberFormIsInvalid() {
        assertNull(parseMinutesSeconds("5:60"))
    }

    @Test
    fun threeNumbersIsInvalid() {
        assertNull(parseMinutesSeconds("1:02:03"))
    }

    @Test
    fun nonNumericInputIsInvalid() {
        assertNull(parseMinutesSeconds("abc"))
    }
}
