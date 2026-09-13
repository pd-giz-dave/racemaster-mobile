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

    // interpretServerStatus — the auth-aware layer on top of a raw ping (see
    // ServerStatus.UNAUTHORIZED's own doc for the field report that motivated it).

    @Test
    fun onlinePingWithAcceptedTokenStaysOnline() {
        assertEquals(ServerStatus.ONLINE, interpretServerStatus(ServerStatus.ONLINE, hasToken = true, authAccepted = true))
    }

    @Test
    fun onlinePingWithRejectedTokenIsUnauthorized() {
        assertEquals(ServerStatus.UNAUTHORIZED, interpretServerStatus(ServerStatus.ONLINE, hasToken = true, authAccepted = false))
    }

    @Test
    fun onlinePingWithNoTokenAtAllIsUnauthorized() {
        assertEquals(ServerStatus.UNAUTHORIZED, interpretServerStatus(ServerStatus.ONLINE, hasToken = false, authAccepted = null))
    }

    @Test
    fun onlinePingWithInconclusiveAuthCheckStaysOnline() {
        // A transient failure on the second (auth) request must not itself be misreported as
        // "logged out" — the server was already confirmed healthy by the ping alone.
        assertEquals(ServerStatus.ONLINE, interpretServerStatus(ServerStatus.ONLINE, hasToken = true, authAccepted = null))
    }

    @Test
    fun nonOnlinePingIsNeverUpgradedOrDowngradedByAuth() {
        assertEquals(ServerStatus.OFFLINE, interpretServerStatus(ServerStatus.OFFLINE, hasToken = true, authAccepted = true))
        assertEquals(ServerStatus.INVALID, interpretServerStatus(ServerStatus.INVALID, hasToken = true, authAccepted = true))
        assertEquals(ServerStatus.UNKNOWN, interpretServerStatus(ServerStatus.UNKNOWN, hasToken = false, authAccepted = null))
    }
}
