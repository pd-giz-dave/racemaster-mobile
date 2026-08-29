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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// PAUSED is never produced by interpretPingOutcome/checkNow below — this repository only
// reports raw reachability. It's an AppBannerViewModel-level override applied on top of
// whatever this reports, for when the operator has deliberately turned server sync off (see
// AppBannerViewModel's own doc) — reachability is still polled underneath so the real status
// is ready to show the moment sync is turned back on.
enum class ServerStatus { UNKNOWN, ONLINE, OFFLINE, INVALID, PAUSED }

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

/**
 * Polls the device's configured Racemaster server URL (see SettingsRepository.serverBaseUrl)
 * on a fixed interval — using the app's own existing `/api/ping` health check, not a new
 * mechanism — and exposes both whether it's reachable right now and when it was last actually
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

    suspend fun checkNow(baseUrl: String): ServerStatus = interpretPingOutcome(syncClient.ping(baseUrl))

    /** Starts (or restarts, if already running) a poll loop that re-checks whenever
     *  [baseUrlFlow] changes, so switching servers via Setup Server is reflected without
     *  waiting out the rest of the previous URL's poll interval. Idempotent-safe to call
     *  more than once — cancels any prior loop first. */
    fun startPolling(scope: CoroutineScope, baseUrlFlow: Flow<String?>) {
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
                    val status = checkNow(baseUrl)
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
