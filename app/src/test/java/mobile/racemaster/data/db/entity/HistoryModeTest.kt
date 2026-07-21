package mobile.racemaster.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryModeTest {

    @Test
    fun columnFormsUseLAndSPrefixesRespectively() {
        assertEquals("L012", formatLineColumn(12L))
        assertEquals("S012", formatSplitColumn(12))
    }

    @Test
    fun columnFormsZeroPadToAtLeastThreeDigits() {
        assertEquals("L000", formatLineColumn(0L))
        assertEquals("L001", formatLineColumn(1L))
        assertEquals("L099", formatLineColumn(99L))
        assertEquals("S000", formatSplitColumn(0))
    }

    @Test
    fun columnFormsOverflowPastThreeDigitsRatherThanTruncating() {
        assertEquals("L1000", formatLineColumn(1_000L))
        assertEquals("L123456", formatLineColumn(123_456L))
        assertEquals("S1000", formatSplitColumn(1_000))
    }

    @Test
    fun refFormsAreUnpaddedForInlineProse() {
        // Deliberately not zero-padded — "dup of S1"/"Undo L5" read naturally in a sentence,
        // unlike the fixed-width column forms above.
        assertEquals("L5", formatLineRef(5L))
        assertEquals("S1", formatSplitRef(1))
        assertEquals("L12", formatLineRef(12L))
    }
}
