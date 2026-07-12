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

enum class ServerStatus { UNKNOWN, ONLINE, OFFLINE, INVALID }

data class ServerStatusState(val status: ServerStatus, val checkedAtMillis: Long?)

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
 * on a fixed interval and exposes whether it's reachable — surfaced in the always-visible
 * AppBanner so the operator can tell at a glance, from any screen, whether a push is likely
 * to succeed right now. No URL configured yet reports UNKNOWN (rendered as blank, not an
 * error — that's the expected state before Mule Mode's Setup Server form has been used).
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
                while (isActive) {
                    val status = checkNow(baseUrl)
                    _state.value = ServerStatusState(status, System.currentTimeMillis())
                    delay(POLL_INTERVAL)
                }
            }
        }
    }

    companion object {
        private val POLL_INTERVAL = 15_000.milliseconds
    }
}
