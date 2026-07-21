package mobile.racemaster.data.mule

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String, val isAdmin: Boolean)

// Body for POST .../mobile — one flat, chronological line array per device (deviceName ->
// lines), mirroring the server's own one-JSON-file-per-device storage exactly. A single
// request can still carry more than one device's lines at once (a Mule pushing data pulled
// from several nearby phones for the same race), just grouped by device instead of by
// Bibs/Time category — see SyncRecord's own doc for why category no longer needs a separate
// list (lineLabel's B/T prefix already carries it) and device no longer needs repeating per
// line (this map's key already does).
@Serializable
data class MobileSyncPayload(
    val devices: Map<String, List<SyncRecord>>,
)

// Only `added` is ever actually read (see MuleRepository.pushToServer) — the rest of what the
// server's own reply shape happens to include (`ok`/`received`/`version`, or anything else) is
// deliberately not modelled here at all, rather than declared as required fields kotlinx.serialization
// then demands be present. A production server running an older/different build than this
// exact endpoint's current shape (confirmed in the field: a response missing `version`) would
// otherwise throw a MissingFieldException and surface as a baffling "Push failed" to the
// operator over something that was never actually a real failure. `added` itself defaults to 0
// as a last-resort fallback rather than failing outright even if the server one day drops it
// too — worst case, a genuinely new push briefly under-reports "0 new records" instead of
// crashing.
@Serializable
data class MobileSyncResponse(val added: Int = 0)

@Serializable
data class PingResponseBody(val ok: Boolean = false)

/** Outcome of a [MuleSyncClient.ping] call, kept separate from the interpretation of what it
 *  *means* (see ServerStatusRepository) — this just reports what happened on the wire. */
sealed interface PingOutcome {
    /** Got an HTTP response at all — [statusCode] may still be non-200, and [okField] is
     *  null if the body wasn't 200 or couldn't be parsed as `{"ok": ...}`. */
    data class Responded(val statusCode: Int, val okField: Boolean?) : PingOutcome

    /** No response reached us at all — DNS failure, connection refused, timeout, etc. */
    data object Unreachable : PingOutcome
}

/** HTTP client for the racemaster server's existing bearer-token API, plus the new
 *  mobile-append endpoint this feature adds server-side. */
class MuleSyncClient {
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun login(baseUrl: String, username: String, password: String): LoginResponse =
        client.post("${baseUrl.trimEnd('/')}/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body()

    // No auth, and expectSuccess is scoped to just this call (unlike the rest of this
    // client, which relies on the default expectSuccess=true throwing on non-2xx to signal
    // failure to their callers) — a ping needs to inspect *any* status code it gets back,
    // including error ones, to tell "reachable but not actually a Racemaster server" (a
    // non-200, or a 200 with the wrong body shape) apart from "unreachable at all".
    suspend fun ping(baseUrl: String): PingOutcome =
        try {
            val response = client.get("${baseUrl.trimEnd('/')}/api/ping") {
                expectSuccess = false
            }
            val statusCode = response.status.value
            val okField = if (statusCode == 200) runCatching { response.body<PingResponseBody>().ok }.getOrNull() else null
            PingOutcome.Responded(statusCode, okField)
        } catch (_: Exception) {
            PingOutcome.Unreachable
        }

    // What the server already has stored, per device, for this race — call before pushing so
    // only the lineNumber delta needs to be sent (see MuleRepository.pushToServer). A device
    // absent from the response means the server has nothing for it yet (treat as 0).
    suspend fun getSyncStatus(baseUrl: String, token: String, raceLabel: String): Map<String, Long> =
        client.get("${baseUrl.trimEnd('/')}/api/mobile/${encodePathSegment(raceLabel)}/status") {
            bearerAuth(token)
        }.body()

    // The response's `added` count (genuinely new rows, not the full send size) is what
    // should be shown to the operator. [raceLabel] scopes the push to
    // `mobile/<user>/<raceLabel>/` on the server — the race's own name as recorded on the phone.
    suspend fun pushRecords(
        baseUrl: String,
        token: String,
        raceLabel: String,
        devices: Map<String, List<SyncRecord>>,
    ): MobileSyncResponse =
        client.post("${baseUrl.trimEnd('/')}/api/mobile/${encodePathSegment(raceLabel)}") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(MobileSyncPayload(devices))
        }.body()

    fun close() {
        client.close()
    }
}

// Plain CPU-bound string encoding, not I/O — pulled out of the suspend functions above since
// IntelliJ's coroutines inspection otherwise flags any java.net.* call written directly
// inside a `suspend fun` as a possibly-blocking call, regardless of what it actually does.
private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8")
