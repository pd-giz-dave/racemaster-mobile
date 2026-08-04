package mobile.racemaster.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceNameValidationTest {

    @Test
    fun lettersDigitsAndHyphensAreValid() {
        assertTrue(isValidRaceName("Autumn10k"))
        assertTrue(isValidRaceName("autumn-10k-2026"))
        assertTrue(isValidRaceName("ABC123"))
        assertTrue(isValidRaceName("---"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertTrue(isValidRaceName("  Autumn10k  "))
    }

    @Test
    fun blankNameIsInvalid() {
        assertFalse(isValidRaceName(""))
        assertFalse(isValidRaceName("   "))
    }

    @Test
    fun spacesInsideTheNameAreInvalid() {
        assertFalse(isValidRaceName("Autumn 10k"))
    }

    @Test
    fun punctuationOtherThanHyphenIsInvalid() {
        assertFalse(isValidRaceName("Autumn_10k"))
        assertFalse(isValidRaceName("Autumn10k!"))
        assertFalse(isValidRaceName("Autumn's Race"))
        assertFalse(isValidRaceName("Autumn/10k"))
    }
}
