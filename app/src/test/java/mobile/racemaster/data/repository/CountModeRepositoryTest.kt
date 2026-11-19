package mobile.racemaster.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// See CloseModeRepositoryTest's own doc for why only the pure parseBeaconCount function (not
// recordCount/observeCounts themselves) is unit-tested here. recordCount's own doc already notes
// the repository itself does not dedupe — that's the ViewModel's job (CountModeViewModel's own
// seenBeaconNumbers check before ever calling recordCount) — confirmed by inspection: recordCount
// is a plain insert with no SELECT-before-write, same as CloseModeRepository.recordClose.
class CountModeRepositoryTest {

    @Test
    fun parsesBeaconNumberFromAWellFormedNote() {
        // The old bare form (a pre-this-change row) and the current "...seen=.. of=.." form
        // both still parse the beacon number out.
        assertEquals(5, parseBeaconCount("beacon=5"))
        assertEquals(123, parseBeaconCount("beacon=123"))
        assertEquals(5, parseBeaconCount("beacon=5 seen=3 of=10"))
    }

    @Test
    fun nullOrMalformedNoteParsesToNull() {
        assertNull(parseBeaconCount(null))
        assertNull(parseBeaconCount(""))
        assertNull(parseBeaconCount("battery=80"))
        // Must never cross-match CloseModeRepository's own "beacon=<n> battery=<pct>" note.
        assertNull(parseBeaconCount("beacon=5 battery=80"))
    }

    @Test
    fun parsesCompleteTotalFromAWellFormedNote() {
        assertEquals(10, parseCountCompleteTotal("of=10"))
    }

    @Test
    fun completeTotalNullOrMalformedNoteParsesToNull() {
        assertNull(parseCountCompleteTotal(null))
        assertNull(parseCountCompleteTotal(""))
        assertNull(parseCountCompleteTotal("outstanding=3"))
    }

    @Test
    fun parsesOutstandingCountFromAWellFormedNote() {
        assertEquals(3, parseCountOutstanding("outstanding=3"))
    }

    @Test
    fun outstandingNullOrMalformedNoteParsesToNull() {
        assertNull(parseCountOutstanding(null))
        assertNull(parseCountOutstanding(""))
        assertNull(parseCountOutstanding("of=10"))
    }
}
