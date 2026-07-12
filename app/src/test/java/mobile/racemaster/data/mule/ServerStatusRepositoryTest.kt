package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerStatusRepositoryTest {

    @Test
    fun unreachableIsOffline() {
        assertEquals(ServerStatus.OFFLINE, interpretPingOutcome(PingOutcome.Unreachable))
    }

    @Test
    fun okTrueIsOnline() {
        assertEquals(ServerStatus.ONLINE, interpretPingOutcome(PingOutcome.Responded(200, okField = true)))
    }

    @Test
    fun wrongStatusCodeIsInvalid() {
        assertEquals(ServerStatus.INVALID, interpretPingOutcome(PingOutcome.Responded(404, okField = null)))
        assertEquals(ServerStatus.INVALID, interpretPingOutcome(PingOutcome.Responded(500, okField = null)))
    }

    @Test
    fun twoHundredWithoutOkTrueIsInvalid() {
        // Reachable, but not actually a Racemaster server — e.g. a captive portal or an
        // unrelated web server that happens to answer 200 on this path.
        assertEquals(ServerStatus.INVALID, interpretPingOutcome(PingOutcome.Responded(200, okField = false)))
        assertEquals(ServerStatus.INVALID, interpretPingOutcome(PingOutcome.Responded(200, okField = null)))
    }
}
