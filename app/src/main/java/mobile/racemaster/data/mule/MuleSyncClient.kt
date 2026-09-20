package mobile.racemaster.data.mule

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String, val isAdmin: Boolean)

// Identical to SyncRecord except `timestamp` is formatted as "yyyy/MM/dd HH:mm:ss" (the
// device's own local time) rather than sent as a raw epoch value under the wire name
// SyncRecord.timestampMillis carries — purely a server-side display convenience, requested
// once the server started surfacing this field to humans (renamed from timestampMillis to
// timestamp for the same reason: it's a formatted string here, not a millis count, and the
// old name was misleading on this side of the wire). The BLE wire (SyncRecord itself, between
// phones/mules) keeps both the raw Long and the timestampMillis name: an unambiguous instant
// every device can safely reconstruct elapsed times from, which a formatted string isn't
// (especially once devices in different time zones are involved).
@Serializable
private data class ServerSyncRecord(
    val action: String,
    val bibNumber: String?,
    val splitTime: String?,
    val location: String,
    val splitNumber: Int?,
    val lineNumber: Long,
    val refLineNumber: Long? = null,
    val note: String?,
    val timestamp: String,
)

private fun SyncRecord.toServerSyncRecord() = ServerSyncRecord(
    action = action,
    bibNumber = bibNumber,
    splitTime = splitTime,
    location = location,
    splitNumber = splitNumber,
    lineNumber = lineNumber,
    refLineNumber = refLineNumber,
    note = note,
    timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis)),
)

// Body for POST .../mobile — one flat, chronological line array per device (deviceName ->
// lines), mirroring the server's own one-JSON-file-per-device storage exactly. A single
// request can still carry more than one device's lines at once (a Mule pushing data pulled
// from several nearby phones for the same race), just grouped by device instead of by
// Bibs/Time category — see SyncRecord's own doc for why category no longer needs a separate
// list (lineLabel's B/T prefix already carries it) and device no longer needs repeating per
// line (this map's key already does).
@Serializable
private data class MobileSyncPayload(
    val devices: Map<String, List<ServerSyncRecord>>,
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

@Serializable
private data class ErrorResponseBody(val error: String = "")

// Thrown by every request below in place of Ktor's own non-2xx handling, which turned out not
// to be a safe thing to lean on at all: `login`, `getSyncStatus`, and `pushRecords` used to rely
// on this client's default `expectSuccess` to throw on a non-2xx response and never explicitly
// checked the status themselves — but confirmed live (the same way getProgress's own 404 bug
// below was confirmed), that default does *not* reliably throw here. A 401 from `pushRecords`
// decoded its `{"error": "Unauthorised"}` body straight into `MobileSyncResponse(added = 0)`
// (every field of which defaults) — a clean, exception-free "0 new records" result, exactly as
// if the push had genuinely succeeded with nothing new to send. That's the actual root cause a
// "server push broken" field report traced back to here: a local dev server's sessions.txt got
// rebuilt out from under four already-logged-in phones, invalidating their saved tokens, and
// every subsequent push silently no-op'd instead of failing — no exception for
// MuleSyncEngine.pushIfNeeded's runCatching to catch, so no "Push failed" message ever appeared
// anywhere, and the affected records just stayed permanently unsynced with nothing to explain
// why. Every call below now checks `response.status` itself (via [checkSuccess]) instead of
// trusting the client's implicit behavior, so a real failure is always a real, typed exception —
// see [MuleRepository.pushToServer] for how the 401/403 case specifically now gets a chance to
// recover on its own via [statusCode].
class ServerRequestException(val statusCode: Int, message: String) : Exception(message)

private suspend fun checkSuccess(response: HttpResponse) {
    if (response.status.isSuccess()) return
    val serverMessage = runCatching { response.body<ErrorResponseBody>().error }.getOrNull()?.takeIf { it.isNotBlank() }
    throw ServerRequestException(response.status.value, serverMessage ?: response.status.toString())
}

// Response for GET .../progress — see MuleSyncClient.getProgress's own doc. `unchanged` true
// means the server confirmed `since` already matched its own current generatedAt, in which case
// `entries` is omitted server-side and defaults to empty here (ProgressRepository.refreshFromServer
// never reads entries in that case anyway — see its own doc). When `unchanged` is false, `entries`
// is a *delta* — only entries whose own updatedAt is newer than `since` — not the full race, once
// `since` was actually passed (see racemaster's server/routes/mobile.js GET .../progress doc).
@Serializable
data class ProgressResponse(
    val unchanged: Boolean = false,
    val generatedAt: String? = null,
    val raceName: String = "",
    val raceDate: String = "",
    val entries: List<ProgressEntry> = emptyList(),
)

// One entry from GET /api/mobile/races?maxAgeDays=N — see MuleSyncClient.getAvailableRaces' own
// doc. Deliberately lean (no devices/lines/recordCount) — this is the setup-time server-race-scan
// a phone with restricted mobile data runs, not the Mobile Files page's own heavier listing.
@Serializable
data class AvailableRace(
    val raceLabel: String = "",
    val raceName: String = "",
    val raceDate: String = "",
    val generatedAt: String = "",
)

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

    suspend fun login(baseUrl: String, username: String, password: String): LoginResponse {
        val response = client.post("${baseUrl.trimEnd('/')}/api/auth/login") {
            expectSuccess = false
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }
        checkSuccess(response)
        return response.body()
    }

    // No auth, and expectSuccess is scoped to just this call the same way every other request in
    // this client now is (see [ServerRequestException]'s own doc for why none of them can lean
    // on the client's own default anymore) — a ping needs to inspect *any* status code it gets
    // back, including error ones, to tell "reachable but not actually a Racemaster server" (a
    // non-200, or a 200 with the wrong body shape) apart from "unreachable at all", rather than
    // throwing on the non-200 case the way [checkSuccess] deliberately does everywhere else.
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

    // Cheap authenticated probe used only to tell whether [token] itself is still accepted by
    // the server — ServerStatusRepository's own doc for why the AppBanner needs this apart from
    // plain reachability at all (ServerStatus.UNAUTHORIZED). GET /api/mobile/status (see
    // server/routes/mobile.js) needs nothing more than a valid bearer token to succeed and costs
    // one fs.statSync per already-stored device file (see that route's own doc) — deliberately
    // not getSyncStatus/pushRecords themselves, which both need a real race label this device
    // may not even have one for yet. The response body (this account's whole Mobile Files
    // listing) is never read, only the status code.
    //
    // Returns null — "inconclusive", not "rejected" — for anything other than a clean 2xx or a
    // clean 401/403, network failures included (unlike every other call in this client, this one
    // is expected to run continuously in the background every few seconds; a transient timeout
    // here must never itself be misreported as "logged out" — see interpretServerStatus's own
    // doc for how the null case is handled).
    suspend fun checkAuth(baseUrl: String, token: String): Boolean? {
        val response = try {
            client.get("${baseUrl.trimEnd('/')}/api/mobile/status") {
                expectSuccess = false
                bearerAuth(token)
            }
        } catch (_: Exception) {
            return null
        }
        return when {
            response.status.isSuccess() -> true
            response.status.value == 401 || response.status.value == 403 -> false
            else -> null
        }
    }

    // What the server already has stored, per device, for this race — call before pushing so
    // only the lineNumber delta needs to be sent (see MuleRepository.pushToServer). A device
    // absent from the response means the server has nothing for it yet (treat as 0).
    suspend fun getSyncStatus(baseUrl: String, token: String, raceLabel: String): Map<String, Long> {
        val response = client.get("${baseUrl.trimEnd('/')}/api/mobile/${encodePathSegment(raceLabel)}/status") {
            expectSuccess = false
            bearerAuth(token)
        }
        checkSuccess(response)
        return response.body()
    }

    // The response's `added` count (genuinely new rows, not the full send size) is what
    // should be shown to the operator. [raceLabel] scopes the push to
    // `mobile/<user>/<raceLabel>/` on the server — the race's own name as recorded on the phone.
    suspend fun pushRecords(
        baseUrl: String,
        token: String,
        raceLabel: String,
        devices: Map<String, List<SyncRecord>>,
    ): MobileSyncResponse {
        val response = client.post("${baseUrl.trimEnd('/')}/api/mobile/${encodePathSegment(raceLabel)}") {
            expectSuccess = false
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(MobileSyncPayload(devices.mapValues { (_, records) -> records.map { it.toServerSyncRecord() } }))
        }
        checkSuccess(response)
        return response.body()
    }

    // Fetches race-wide progress directly from the server, bypassing Bluetooth entirely — the
    // HTTP twin of the racemaster web app's own BLE delivery (PeripheralSyncService's
    // PROGRESS_CHARACTERISTIC_UUID handling). Bearer-authed, like getSyncStatus/pushRecords —
    // deliberately requires login, unlike progress.json's own incidental unauthenticated
    // exposure via the server's static-file route. [since] is both the bandwidth-saving hint and
    // the delta cursor — pass whatever generatedAt ProgressRepository already has cached for this
    // race, or null on a first-ever fetch — omitted from the request entirely rather than sent
    // empty, so the server can tell "never fetched before" (send everything) apart from "fetched,
    // but held nothing that time" purely by the query parameter's presence. When it doesn't match
    // the server's own current generatedAt, the response's `entries` is a delta (only entries
    // whose own updatedAt is newer than [since]), not the whole race — see ProgressResponse's own
    // doc, and ProgressRepository.refreshFromServer for how that gets merged in rather than
    // replacing what's already stored.
    //
    // Returns null for a 404 (server/routes/mobile.js's GET .../progress: "No progress recorded
    // for this race yet") — the ordinary, expected outcome for the overwhelming majority of
    // races, which never have a Progress tab entry pushed for them at all. expectSuccess is
    // turned off and the status checked explicitly here, rather than trusting this client's own
    // default expectSuccess to throw on it (confirmed live: it doesn't — a 404's
    // {"error": "..."} body was silently decoding into an all-default ProgressResponse instead,
    // which ProgressRepository then stored as if it were genuine empty progress data, cluttering
    // the Races page with a spurious entry for every race that simply never had one pushed).
    suspend fun getProgress(baseUrl: String, token: String, raceLabel: String, since: String?): ProgressResponse? {
        val response = client.get("${baseUrl.trimEnd('/')}/api/mobile/${encodePathSegment(raceLabel)}/progress") {
            expectSuccess = false
            bearerAuth(token)
            since?.let { parameter("since", it) }
        }
        return if (response.status.isSuccess()) response.body() else null
    }

    // The mobile app's own setup-time server-race-scan (SetupRaceScreen/SetupRaceViewModel) —
    // races this owner has progress.json for, whose generatedAt is within [maxAgeDays], leanest
    // shape available (no devices/lines/recordCount — see AvailableRace's own doc and TODO.md's
    // phase-2 correction on why this needed to be server-side filtered rather than downloading
    // GET /api/mobile's full listing just to filter it client-side). Empty list (not an
    // exception) for a genuinely empty result — 404 never applies here, an empty list is a valid,
    // ordinary answer ("nothing recent"), unlike getProgress's single-race 404.
    suspend fun getAvailableRaces(baseUrl: String, token: String, maxAgeDays: Int): List<AvailableRace> {
        val response = client.get("${baseUrl.trimEnd('/')}/api/mobile/races") {
            expectSuccess = false
            bearerAuth(token)
            parameter("maxAgeDays", maxAgeDays)
        }
        checkSuccess(response)
        return response.body()
    }

    fun close() {
        client.close()
    }
}

// Plain CPU-bound string encoding, not I/O — pulled out of the suspend functions above since
// IntelliJ's coroutines inspection otherwise flags any java.net.* call written directly
// inside a `suspend fun` as a possibly-blocking call, regardless of what it actually does.
private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8")
