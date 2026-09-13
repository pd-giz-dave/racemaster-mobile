package mobile.racemaster.data.mule

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// PAUSED is never produced by interpretPingOutcome/interpretServerStatus/checkNow below — this
// repository only reports raw reachability (+ auth). It's an AppBannerViewModel-level override
// applied on top of whatever this reports, for when the operator has deliberately turned server
// sync off (see AppBannerViewModel's own doc) — reachability is still polled underneath so the
// real status is ready to show the moment sync is turned back on.
//
// UNAUTHORIZED sits between ONLINE and OFFLINE/INVALID: the server itself is confirmed up and
// genuinely a Racemaster server (ping alone already says so), but this device's own saved
// login either doesn't exist or is no longer accepted — a real, previously-invisible field
// state (see MuleSyncClient.ServerRequestException's own doc for the "server push broken"
// report this traces back to): every push attempt was quietly no-op'ing while the banner still
// read a plain "Online", giving the operator nothing to act on beyond records staying
// permanently unsynced. checkNow/interpretServerStatus below (via MuleSyncClient.checkAuth) is
// what actually notices this apart from a genuine ONLINE — a plain reachability ping alone
// can't tell them apart, since an expired token still gets a perfectly healthy HTTP response.
// Self-heals the same way ONLINE already does: MuleRepository.pushToServer's own automatic
// re-login (see its own doc) updates the saved token the moment the background sync loop next
// runs, and the very next poll tick here picks that up and flips back to ONLINE on its own.
enum class ServerStatus { UNKNOWN, ONLINE, OFFLINE, INVALID, UNAUTHORIZED, PAUSED }

data class ServerStatusState(
    val status: ServerStatus,
    val checkedAtMillis: Long?,
    // Distinct from checkedAtMillis, which advances on every poll attempt regardless of
    // outcome — this is the "Last seen" equivalent for the server (mirroring
    // DiscoveredDevice.lastReachableAtMillis/BluetoothStateRepository.lastWebAppSeenAtMillis):
    // only bumped when a check actually succeeds (status == ServerStatus.ONLINE that tick), so
    // an ongoing outage doesn't misleadingly look "just checked fine" from checkedAtMillis
    // alone advancing every 15s regardless. Null until the very first successful check against
    // the currently configured server URL.
    val lastOnlineAtMillis: Long? = null,
)

/** Turns a raw [PingOutcome] into what it means for the operator: unreachable is OFFLINE
 *  (worth retrying — the server or network could recover); anything that responds but isn't
 *  a genuine Racemaster server (wrong status code, or a 200 without the expected `{"ok":
 *  true}` body) is INVALID, which is a configuration problem (wrong URL/port), not a
 *  transient one — still retried the same way, since there's no other signal to act on. */
fun interpretPingOutcome(outcome: PingOutcome): ServerStatus = when (outcome) {
    is PingOutcome.Unreachable -> ServerStatus.OFFLINE
    is PingOutcome.Responded -> if (outcome.statusCode == 200 && outcome.okField == true) ServerStatus.ONLINE else ServerStatus.INVALID
}

/** Folds a separate, authenticated probe's outcome into [pingStatus] — pulled out as a pure
 *  function (mirroring [interpretPingOutcome]'s own reasoning) so this is directly testable
 *  without a real network round-trip. Only ever *downgrades* a ping-confirmed ONLINE, exactly
 *  the one case a bare reachability check can't itself distinguish (see [ServerStatus.UNAUTHORIZED]'s
 *  own doc) — [pingStatus] being anything else (OFFLINE/INVALID/UNKNOWN) already fully explains
 *  why a push wouldn't succeed right now, so auth is irrelevant and left untouched.
 *  [hasToken] false (never logged in, or logged out) is treated the same as a confirmed
 *  rejection — either way there's no valid credential to push with. [authAccepted] null (the
 *  auth probe itself was inconclusive — e.g. a transient failure on the *second* of the two
 *  requests checkNow makes) deliberately does *not* downgrade to UNAUTHORIZED: that's not
 *  evidence of an actual auth problem, just noise, and ping alone already established the
 *  server itself is healthy. */
fun interpretServerStatus(pingStatus: ServerStatus, hasToken: Boolean, authAccepted: Boolean?): ServerStatus {
    if (pingStatus != ServerStatus.ONLINE) return pingStatus
    if (!hasToken || authAccepted == false) return ServerStatus.UNAUTHORIZED
    return ServerStatus.ONLINE
}

/**
 * Polls the device's configured Racemaster server URL (see SettingsRepository.serverBaseUrl)
 * on a fixed interval — using the app's own existing `/api/ping` health check for reachability,
 * plus (once that confirms a genuine, reachable Racemaster server) [MuleSyncClient.checkAuth]
 * against this device's own saved token, so ONLINE actually means "a push would succeed right
 * now", not just "the server itself is up" — see [ServerStatus.UNAUTHORIZED]'s own doc for the
 * gap that used to leave. Exposes both the current status and when it was last actually
 * confirmed online ([ServerStatusState.lastOnlineAtMillis]). Surfaced in the always-visible
 * AppBanner so the operator can tell at a glance, from any screen and regardless of mode,
 * whether a push is likely to succeed right now. No URL configured yet reports UNKNOWN
 * (rendered as blank, not an error — that's the expected state before Mule Mode's Setup Server
 * form has been used).
 */
class ServerStatusRepository(private val syncClient: MuleSyncClient) {
    private val _state = MutableStateFlow(ServerStatusState(ServerStatus.UNKNOWN, null))
    val state: StateFlow<ServerStatusState> = _state.asStateFlow()

    private var pollingJob: Job? = null

    // [token] defaults to null (pure reachability, same as this function's own pre-auth-check
    // shape) rather than being required — [interpretServerStatus] already treats a missing
    // token as UNAUTHORIZED once the server itself is confirmed reachable (see its own doc), so
    // this stays a safe, honest default rather than a trap for some future caller that forgets
    // to pass one.
    suspend fun checkNow(baseUrl: String, token: String? = null): ServerStatus {
        val pingStatus = interpretPingOutcome(syncClient.ping(baseUrl))
        val authAccepted = if (pingStatus == ServerStatus.ONLINE && token != null) syncClient.checkAuth(baseUrl, token) else null
        return interpretServerStatus(pingStatus, hasToken = token != null, authAccepted = authAccepted)
    }

    /** Starts (or restarts, if already running) a poll loop that re-checks whenever
     *  [baseUrlFlow] changes, so switching servers via Setup Server is reflected without
     *  waiting out the rest of the previous URL's poll interval. Idempotent-safe to call
     *  more than once — cancels any prior loop first. [tokenFlow] is read fresh (via `.first()`)
     *  on every individual tick rather than folded into the same collectLatest key as
     *  [baseUrlFlow] — a token changing (an ordinary login, or MuleRepository.pushToServer's own
     *  automatic re-login recovering from [ServerStatus.UNAUTHORIZED]) must be picked up by the
     *  very next tick, but must *not* restart the loop and reset lastOnlineAtMillis back to null
     *  the way a genuine server-URL change legitimately does. */
    fun startPolling(scope: CoroutineScope, baseUrlFlow: Flow<String?>, tokenFlow: Flow<String?>) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            baseUrlFlow.collectLatest { baseUrl ->
                if (baseUrl == null) {
                    _state.value = ServerStatusState(ServerStatus.UNKNOWN, null)
                    return@collectLatest
                }
                // Local to this collectLatest invocation, not a class field — switching servers
                // via Setup Server starts a fresh collectLatest block (a new URL cancels the
                // previous one), so a "last seen" time against the *old* URL is correctly
                // dropped rather than carried over and shown against the new one.
                var lastOnlineAtMillis: Long? = null
                while (isActive) {
                    val status = checkNow(baseUrl, tokenFlow.first())
                    val now = System.currentTimeMillis()
                    if (status == ServerStatus.ONLINE) lastOnlineAtMillis = now
                    _state.value = ServerStatusState(status, now, lastOnlineAtMillis)
                    delay(POLL_INTERVAL)
                }
            }
        }
    }

    companion object {
        private val POLL_INTERVAL = 15_000.milliseconds
    }
}
