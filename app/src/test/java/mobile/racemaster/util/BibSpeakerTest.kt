package mobile.racemaster.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BibSpeakerTest {

    @Test
    fun speaksEachDigitSeparately() {
        assertEquals("one two three", "123".toSpokenDigits())
    }

    @Test
    fun preservesLeadingZeros() {
        assertEquals("zero zero seven", "007".toSpokenDigits())
    }

    @Test
    fun allTenDigitsMapCorrectly() {
        assertEquals(
            "zero one two three four five six seven eight nine",
            "0123456789".toSpokenDigits(),
        )
    }
}
