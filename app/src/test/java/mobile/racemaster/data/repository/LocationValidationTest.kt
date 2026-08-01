package mobile.racemaster.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationValidationTest {

    @Test
    fun bareCpNumberIsValid() {
        assertTrue(isValidCpLocation("CP1"))
        assertTrue(isValidCpLocation("CP12"))
    }

    @Test
    fun cpNumberWithNameSuffixIsValid() {
        assertTrue(isValidCpLocation("CP2-Bridge"))
        assertTrue(isValidCpLocation("CP1-Water Station"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertTrue(isValidCpLocation("  CP1  "))
    }

    @Test
    fun zeroOrNegativeNumberIsInvalid() {
        assertFalse(isValidCpLocation("CP0"))
        assertFalse(isValidCpLocation("CP-1"))
    }

    @Test
    fun leadingZeroIsInvalid() {
        assertFalse(isValidCpLocation("CP01"))
    }

    @Test
    fun missingNumberIsInvalid() {
        assertFalse(isValidCpLocation("CP"))
        assertFalse(isValidCpLocation("CP-Bridge"))
    }

    @Test
    fun wrongPrefixOrCaseIsInvalid() {
        assertFalse(isValidCpLocation("Finish"))
        assertFalse(isValidCpLocation("cp1"))
    }
}
