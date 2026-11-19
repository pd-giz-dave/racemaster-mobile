package mobile.racemaster.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// recordClose/recordReopenedIfClosed/observeCloses themselves are Room-backed suspend functions —
// this project has no Room/Robolectric test harness (see every other mode's own repository: none
// has a dedicated DB test either), so what's actually unit-testable here is parseBeaconClose/
// parseBeaconReopened, the one place each row's own note format is read back. recordClose's own
// never-upserts behavior (two calls for the same beacon == two rows, not one replaced row) and
// recordReopenedIfClosed's own "only writes when the beacon's latest row is CLOSE" guard are both
// evident by inspection and covered by the plan's own manual device-test checklist instead.
class CloseModeRepositoryTest {

    @Test
    fun parsesBeaconAndBatteryFromAWellFormedNote() {
        assertEquals(5 to 80, parseBeaconClose("beacon=5 battery=80"))
        assertEquals(123 to 7, parseBeaconClose("beacon=123 battery=7"))
    }

    @Test
    fun nullOrMalformedNoteParsesToNull() {
        assertNull(parseBeaconClose(null))
        assertNull(parseBeaconClose(""))
        assertNull(parseBeaconClose("beacon=5"))
        assertNull(parseBeaconClose("battery=80"))
        assertNull(parseBeaconClose("beacon=5 battery=80 extra"))
    }

    @Test
    fun parsesBeaconNumberFromAWellFormedReopenedNote() {
        assertEquals(5, parseBeaconReopened("beacon=5"))
        assertEquals(123, parseBeaconReopened("beacon=123"))
    }

    @Test
    fun reopenedNullOrMalformedNoteParsesToNull() {
        assertNull(parseBeaconReopened(null))
        assertNull(parseBeaconReopened(""))
        assertNull(parseBeaconReopened("battery=80"))
        // Must never cross-match a CLOSE row's own "beacon=<n> battery=<pct>" note — matchEntire
        // leaves no room for the trailing " battery=..." to be swallowed.
        assertNull(parseBeaconReopened("beacon=5 battery=80"))
    }
}
