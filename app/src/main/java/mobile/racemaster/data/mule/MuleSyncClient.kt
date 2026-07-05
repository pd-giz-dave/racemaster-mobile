package mobile.racemaster.data.mule

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String, val isAdmin: Boolean)

// Exact shape confirmed against racemaster/server.js's getDatasetsForUser().
@Serializable
data class DatasetSummary(
    val owner: String,
    val name: String,
    val fullName: String,
    val visibility: String,
    val eventName: String = "",
    val eventDate: String = "",
    val orphaned: Boolean = false,
)

// Body for POST .../mobile — two separate tables server-side ("-mobile.json"'s `times` and
// `bibs` arrays), not one mixed array, so a race's Time splits and Bib entries stay distinct.
@Serializable
data class MobileSyncPayload(
    val times: List<SyncRecord>,
    val bibs: List<SyncRecord>,
)

@Serializable
data class MobileSyncResponse(val ok: Boolean, val added: Int, val received: Int, val version: Int)

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

    suspend fun listDatasets(baseUrl: String, token: String): List<DatasetSummary> =
        client.get("${baseUrl.trimEnd('/')}/api/datasets") {
            bearerAuth(token)
        }.body()

    // Always sends the *full* record set the caller currently holds, not just what's changed
    // — the server dedups by recordUuid, so this is idempotent, and it means a server-side
    // file that's been deleted or corrupted gets fully reconstructed on the very next push
    // rather than only receiving whatever's new since. The response's `added` count (genuinely
    // new rows, not the full send size) is what should be shown to the operator.
    suspend fun pushRecords(
        baseUrl: String,
        token: String,
        owner: String,
        fullName: String,
        times: List<SyncRecord>,
        bibs: List<SyncRecord>,
    ): MobileSyncResponse =
        client.post("${baseUrl.trimEnd('/')}/api/data/$owner/$fullName/mobile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(MobileSyncPayload(times, bibs))
        }.body()

    fun close() {
        client.close()
    }
}
