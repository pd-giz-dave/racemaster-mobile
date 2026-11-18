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
        assertEquals(5, parseBeaconCount("beacon=5"))
        assertEquals(123, parseBeaconCount("beacon=123"))
    }

    @Test
    fun nullOrMalformedNoteParsesToNull() {
        assertNull(parseBeaconCount(null))
        assertNull(parseBeaconCount(""))
        assertNull(parseBeaconCount("battery=80"))
        assertNull(parseBeaconCount("beacon=5 battery=80"))
    }
}
