package mobile.racemaster.data.mule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import mobile.racemaster.data.db.dao.ProgressDao
import mobile.racemaster.data.db.entity.ProgressEntity

/** What's currently known about progress for [raceId] — the LOCAL race this was received for,
 *  never re-validated against a reconstructed race-label string for the purpose of deciding
 *  whether to *keep* it (see [ProgressRepository]'s own doc for why: this device's own
 *  race-label building and the web app's own deriveRaceLabel() are separate implementations
 *  that could in principle diverge, so scoping by raceId is exact where a string comparison
 *  would be fragile). [raceLabel] is carried alongside purely so a *downstream* propagation
 *  decision (delivering this same progress on to another phone — see
 *  [MulePullClient.readDeviceInfo]'s own progressToDeliver/progressRaceLabel params) has
 *  something to compare against that other phone's own reported DeviceInfo.raceLabel, which is
 *  the one shared wire identity every phone in this protocol already agrees on (unlike a local
 *  raceId, which is meaningless outside the device that assigned it). */
data class StoredProgress(
    val raceId: Long,
    val raceLabel: String,
    val generatedAt: String,
    val raceName: String,
    val raceDate: String,
    val entries: List<ProgressEntry>,
)

/** [StoredProgress] back into the wire shape both transports (BLE and HTTP) actually send/
 *  receive — used when this device turns around and delivers what it's holding on to another
 *  phone (see [MuleSyncEngine]'s own progress-propagation call sites). */
fun StoredProgress.toPayload(): ProgressPayload = ProgressPayload(raceName, raceDate, generatedAt, entries)

/** In-memory sink (for the BLE-delivery/HTTP-poll hot path — see [current]'s own doc) AND
 *  Room-backed store (see [ProgressEntity]) for race-wide progress data, however it arrived — a
 *  BLE write from the racemaster web app (see [PeripheralSyncService]'s
 *  PROGRESS_CHARACTERISTIC_UUID handling) or a direct HTTP fetch from the server (see
 *  [refreshFromServer]). Every successful receipt writes through to both: [current] purely for
 *  "what does the ACTIVE race have right now" (DeviceInfo reporting, propagation to other
 *  phones — see below), the Room table for "list/view/delete every race's progress, including
 *  ones no longer active" (the Races page — see [observeStored]).
 *
 *  This device — whenever it received progress this way at all — always acts as a Mule for the
 *  purpose of this data: whatever [current] holds is also propagated on to every other phone
 *  it's itself pulling from (leaf Time/Bibs/CP devices), piggybacked onto the connection
 *  [MuleSyncEngine] already opens for its own periodic DeviceInfo refresh — see
 *  [MulePullClient.readDeviceInfo]'s own progressToDeliver/progressRaceLabel params, mirroring
 *  exactly how a sink confirmation is already piggybacked onto that same connection rather than
 *  costing a separate reconnect. Bandwidth-efficient the same way that piggybacking already is:
 *  no extra connection, and the receiving peer's own freshly-read
 *  DeviceInfo.progressGeneratedAt is checked before ever sending the (potentially large) payload,
 *  so an already-up-to-date peer costs nothing beyond the DeviceInfo read this connection was
 *  making anyway (see [shouldDeliverProgress]).
 *
 *  Exposes only the transport-agnostic [StoredProgress] — nothing downstream (the Races page,
 *  a future "bib expectations" consumer) needs to know or care whether a given payload arrived
 *  over BLE or HTTP. */
class ProgressRepository(private val syncClient: MuleSyncClient, private val progressDao: ProgressDao) {
    private val json = Json { ignoreUnknownKeys = true }

    // ONLY the currently active race's own most-recently-received copy — never a list, never
    // history. This is what DeviceInfo.progressGeneratedAt/propagation read; the Races page
    // reads observeStored()/getStored() below instead, which survive a race switch this doesn't.
    private val _current = MutableStateFlow<StoredProgress?>(null)
    val current: StateFlow<StoredProgress?> = _current.asStateFlow()

    /** [PeripheralSyncService]'s progress-write handler calls this once a chunked BLE delivery is
     *  fully reassembled and decoded. */
    suspend fun storeFromBle(raceId: Long, raceLabel: String, payload: ProgressPayload) {
        store(StoredProgress(raceId, raceLabel, payload.generatedAt, payload.raceName, payload.raceDate, payload.entries))
    }

    /** What DeviceInfo.progressGeneratedAt should report for [raceId] — null if this repository
     *  holds nothing for that exact race (including "holds something, but for a different
     *  race"). Reads [current] only (the active race's own live copy) — deliberately not the
     *  Room table, which can hold a *different*, no-longer-active race's own last-known value
     *  that must never leak into what this device claims to hold right now. */
    fun generatedAtFor(raceId: Long?): String? = _current.value?.takeIf { it.raceId == raceId }?.generatedAt

    /** Called whenever the locally active race changes (see PeripheralSyncService's own
     *  observeServingState, which already clears its request-response cache on every serving
     *  state change for the same reason) — a stored progress payload for a race that's no longer
     *  active must never leak into DeviceInfo/consumers for whatever race replaces it. Only
     *  clears [current] — the Room-persisted copy (see [observeStored]) is exactly what's
     *  supposed to survive this, so the Races page can still show it. */
    fun clearIfRaceChanged(activeRaceId: Long?) {
        if (_current.value != null && _current.value?.raceId != activeRaceId) _current.value = null
    }

    /** Fetches progress for [raceId]/[raceLabel] from the server, sending whatever generatedAt is
     *  already cached for this exact race as the bandwidth-saving hint (see
     *  [MuleSyncClient.getProgress]'s own doc) — a no-op (network call still happens, but nothing
     *  is overwritten) when the server reports `unchanged`. Swallows any failure (unreachable
     *  server, etc.) the same best-effort way [MuleRepository]'s own server-facing calls do. */
    suspend fun refreshFromServer(baseUrl: String, token: String, raceId: Long, raceLabel: String) {
        val known = generatedAtFor(raceId)
        val response = runCatching { syncClient.getProgress(baseUrl, token, raceLabel, known) }.getOrNull() ?: return
        if (!response.unchanged) {
            store(StoredProgress(raceId, raceLabel, response.generatedAt.orEmpty(), response.raceName, response.raceDate, response.entries))
        }
    }

    private suspend fun store(progress: StoredProgress) {
        _current.value = progress
        progressDao.upsert(
            ProgressEntity(
                raceId = progress.raceId,
                raceLabel = progress.raceLabel,
                generatedAt = progress.generatedAt,
                raceName = progress.raceName,
                raceDate = progress.raceDate,
                entriesJson = json.encodeToString(progress.entries),
                storedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    /** Every race this device has ever stored progress for, newest-first — backs the Races
     *  page's own progress-file listing (see [mobile.racemaster.ui.racehistory.RaceHistoryScreen]).
     *  Survives both a race switch and an app restart, unlike [current]. */
    fun observeStored(): Flow<List<StoredProgress>> = progressDao.observeAll().map { rows -> rows.map { it.toStoredProgress() } }

    /** One race's stored progress by [raceId] — backs the Races page's own "view" navigation
     *  (a dedicated detail screen listing every [ProgressEntry]). */
    suspend fun getStored(raceId: Long): StoredProgress? = progressDao.getByRaceId(raceId)?.toStoredProgress()

    /** Permanently removes [raceId]'s stored progress — backs the Races page's own "delete"
     *  action. Also clears [current] if it happened to be the active race's own live copy, so a
     *  deleted-then-immediately-re-asked-for DeviceInfo read doesn't keep reporting a
     *  generatedAt for data that's no longer actually held anywhere. */
    suspend fun delete(raceId: Long) {
        progressDao.deleteByRaceId(raceId)
        if (_current.value?.raceId == raceId) _current.value = null
    }

    private fun ProgressEntity.toStoredProgress(): StoredProgress =
        StoredProgress(raceId, raceLabel, generatedAt, raceName, raceDate, json.decodeFromString(entriesJson))
}
