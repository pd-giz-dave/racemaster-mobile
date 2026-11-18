package mobile.racemaster.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// recordClose/observeCloses themselves are Room-backed suspend functions — this project has no
// Room/Robolectric test harness (see every other mode's own repository: none has a dedicated DB
// test either), so what's actually unit-testable here is parseBeaconClose, the one place
// recordClose's own "beacon=<n> battery=<pct>" note format is read back. recordClose's own
// never-upserts behavior (two calls for the same beacon == two rows, not one replaced row) is
// evident by inspection — a plain insert with no SELECT-before-write — and is covered by the
// plan's own manual device-test checklist instead.
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
}
