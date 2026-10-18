package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.settings.AppMode
import org.junit.Assert.assertEquals
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

    @Test
    fun bibsAcceptsStartFinishAndCpLocationsCaseInsensitively() {
        assertTrue(isValidLocationForMode("Start", AppMode.BIBS))
        assertTrue(isValidLocationForMode("start", AppMode.BIBS))
        assertTrue(isValidLocationForMode("Finish", AppMode.BIBS))
        assertTrue(isValidLocationForMode("FINISH", AppMode.BIBS))
        assertTrue(isValidLocationForMode("CP1", AppMode.BIBS))
        assertTrue(isValidLocationForMode("CP2-Bridge", AppMode.BIBS))
    }

    @Test
    fun bibsRejectsFreeTextAndBlank() {
        assertFalse(isValidLocationForMode("Water Station", AppMode.BIBS))
        assertFalse(isValidLocationForMode("", AppMode.BIBS))
        assertFalse(isValidLocationForMode("   ", AppMode.BIBS))
        assertFalse(isValidLocationForMode("cp1", AppMode.BIBS))
    }

    @Test
    fun timeStaysFreeForm() {
        assertTrue(isValidLocationForMode("Water Station", AppMode.TIME))
        assertFalse(isValidLocationForMode("", AppMode.TIME))
    }

    @Test
    fun nullModeIsNeverValid() {
        assertFalse(isValidLocationForMode("Start", null))
    }

    @Test
    fun defaultActionForLocationMapsStartAndFinish() {
        assertEquals(HistoryAction.START, defaultActionForLocation("Start"))
        assertEquals(HistoryAction.START, defaultActionForLocation(" start "))
        assertEquals(HistoryAction.FINISH, defaultActionForLocation("Finish"))
        assertEquals(HistoryAction.FINISH, defaultActionForLocation("FINISH"))
    }

    @Test
    fun defaultActionForLocationMapsEverythingElseToPass() {
        assertEquals(HistoryAction.PASS, defaultActionForLocation("CP1"))
        assertEquals(HistoryAction.PASS, defaultActionForLocation("CP2-Bridge"))
        assertEquals(HistoryAction.PASS, defaultActionForLocation(""))
    }

    @Test
    fun cpLocationOnlyAllowsPassRetireAndIgnore() {
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.PASS, "CP1"))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.RETIRE, "CP2-Bridge"))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.IGNORE, "CP1"))
        assertFalse(isBibsActionAllowedAtLocation(HistoryAction.START, "CP1"))
        assertFalse(isBibsActionAllowedAtLocation(HistoryAction.FINISH, "CP1"))
        assertFalse(isBibsActionAllowedAtLocation(HistoryAction.SENIORS, "CP1"))
        assertFalse(isBibsActionAllowedAtLocation(HistoryAction.JUNIORS, "CP1"))
        assertFalse(isBibsActionAllowedAtLocation(HistoryAction.MALE, "CP1"))
        assertFalse(isBibsActionAllowedAtLocation(HistoryAction.FEMALE, "CP1"))
    }

    @Test
    fun startAndFinishAllowEverythingExceptPass() {
        assertFalse(isBibsActionAllowedAtLocation(HistoryAction.PASS, "Start"))
        assertFalse(isBibsActionAllowedAtLocation(HistoryAction.PASS, "FINISH"))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.START, "Start"))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.FINISH, "Finish"))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.RETIRE, "Start"))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.IGNORE, "Finish"))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.SENIORS, "Start"))
    }

    @Test
    fun blankOrInvalidLocationIsPermissive() {
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.PASS, ""))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.START, ""))
        assertTrue(isBibsActionAllowedAtLocation(HistoryAction.PASS, "Water Station"))
    }
}
