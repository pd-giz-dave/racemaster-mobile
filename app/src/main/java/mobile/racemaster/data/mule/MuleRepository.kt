package mobile.racemaster.data.mule

import com.juul.kable.Advertisement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import mobile.racemaster.data.db.dao.PulledRecordDao
import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.db.entity.PulledRecordEntity
import mobile.racemaster.data.db.entity.SERVER_TARGET_ID
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.settings.SettingsRepository

const val DEVICE_ROLE_TIME = "TIME"
const val DEVICE_ROLE_BIBS = "BIBS"

// deviceName comes from the entity's own column, not the decoded record — SyncRecord itself
// carries no per-line device name (see its own doc).
data class PulledRecordDisplay(val record: SyncRecord, val deviceName: String, val syncedAtMillis: Long?)

/**
 * Orchestrates Mule Mode: pulling unsynced records from nearby Time/Bibs phones over BLE
 * (via [MulePullClient]) into a local inbox, and pushing that inbox on to the racemaster
 * server over HTTP (via [MuleSyncClient]) once logged in — each race label's records land
 * under that race's own folder server-side (`mobile/<user>/<race label>/`), no target
 * dataset needs picking. Mule can lock onto one Bibs device and one Time device at once —
 * either independently, or both together (the caller is responsible for checking their race
 * labels match; see [mobile.racemaster.ui.mulemode.MuleModeViewModel]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MuleRepository(
    private val pulledRecordDao: PulledRecordDao,
    private val settingsRepository: SettingsRepository,
    private val pullClient: MulePullClient,
    private val syncClient: MuleSyncClient,
    private val raceRepository: RaceRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val unsyncedCount: Flow<Int> = pulledRecordDao.observeUnsyncedCount()
    val lastSyncedAtMillis: Flow<Long?> = pulledRecordDao.observeLastSyncedAtMillis()
    val lastPulledAtMillis: Flow<Long?> = pulledRecordDao.observeLastPulledAtMillis()
    val isLoggedIn: Flow<Boolean> = settingsRepository.authToken.map { it != null }
    val autoSyncStopped: Flow<Boolean> = settingsRepository.autoSyncStopped
    // Both queries below need this device's own stable id to exclude its self-pulled rows
    // (see PulledRecordDao's doc) — read once per collection via getOrCreateDeviceId() rather
    // than a raw DataStore Flow, since it must also handle the first-ever-launch case where
    // no id has been generated yet (the same get-or-create semantics used everywhere else this
    // id is read).
    private val myDeviceIdFlow: Flow<String> = flow { emit(settingsRepository.getOrCreateDeviceId()) }

    val sourceSummaries: Flow<List<PulledSourceSummary>> =
        myDeviceIdFlow.flatMapLatest { myDeviceId -> pulledRecordDao.observeSourceSummaries(myDeviceId) }
    val deviceName: Flow<String?> = settingsRepository.deviceName

    fun observeRecordsForSource(sourceRaceLabel: String): Flow<List<PulledRecordDisplay>> =
        myDeviceIdFlow.flatMapLatest { myDeviceId -> pulledRecordDao.observeForSource(sourceRaceLabel, myDeviceId) }
            .map { entities -> entities.map { PulledRecordDisplay(json.decodeFromString(it.payloadJson), it.deviceName, it.syncedAtMillis) } }

    fun scanForDevices(): Flow<Advertisement> = pullClient.scanForDevices()

    suspend fun readDeviceInfo(advertisement: Advertisement): DeviceInfo = pullClient.readDeviceInfo(advertisement)

    suspend fun setAutoSyncStopped(stopped: Boolean) {
        settingsRepository.setAutoSyncStopped(stopped)
    }

    // The delta-sync cutoff for the next pull from this specific device/race — 0 (request
    // everything) if nothing's ever been pulled from it before.
    suspend fun lastPulledLineNumber(sourceDeviceId: String, sourceRaceLabel: String): Long =
        pulledRecordDao.getLastPulledLineNumber(sourceDeviceId, sourceRaceLabel) ?: 0

    // count starts at 0 and is only ever set from inside pullClient.pull()'s onReceived
    // callback — which runs (and must complete, storing the records) strictly before pull()
    // acks the peripheral. See MulePullClient.pull()'s doc for why that ordering matters.
    // [sourceDeviceName] comes from the same DeviceInfo read that already supplied
    // sourceDeviceRole/sourceRaceLabel/sourceDeviceId (see MuleSyncEngine.pullAllVisibleDevices)
    // — SyncRecord itself carries no per-line device name (see its own doc for why).
    suspend fun pullFrom(
        advertisement: Advertisement,
        sourceDeviceRole: String,
        sourceRaceLabel: String,
        sourceDeviceId: String,
        sourceDeviceName: String,
        sinceLineNumber: Long,
    ): Int {
        var count = 0
        val myDeviceId = settingsRepository.getOrCreateDeviceId()
        val myDeviceName = settingsRepository.getOrCreateDeviceName()
        pullClient.pull(advertisement, myDeviceId, myDeviceName, sinceLineNumber) { records ->
            count = storePulledRecords(sourceDeviceRole, sourceRaceLabel, sourceDeviceId, sourceDeviceName, records)
        }
        return count
    }

    /** Same destination as [pullFrom] (the Mule inbox, pushed on to the server the same way
     *  as anything pulled over BLE) but for this device's *own* records — there's no radio
     *  involved, [records] is read straight from the local database by the caller (see
     *  [MuleSyncEngine]'s `pullSelfRecords`). Lets "self" be treated as a sync candidate the
     *  same as any other discovered device. [sourceDeviceId] is this device's own stable id. */
    suspend fun pullFromSelf(
        sourceDeviceRole: String,
        sourceRaceLabel: String,
        sourceDeviceId: String,
        sourceDeviceName: String,
        records: List<SyncRecord>,
    ): Int = storePulledRecords(sourceDeviceRole, sourceRaceLabel, sourceDeviceId, sourceDeviceName, records)

    private suspend fun storePulledRecords(
        sourceDeviceRole: String,
        sourceRaceLabel: String,
        sourceDeviceId: String,
        sourceDeviceName: String,
        records: List<SyncRecord>,
    ): Int {
        if (records.isEmpty()) return 0
        val now = System.currentTimeMillis()
        pulledRecordDao.insertAll(
            records.map { record ->
                PulledRecordEntity(
                    recordUuid = record.recordUuid,
                    sourceDeviceId = sourceDeviceId,
                    sourceDeviceRole = sourceDeviceRole,
                    sourceRaceLabel = sourceRaceLabel,
                    lineNumber = record.lineNumber,
                    deviceName = sourceDeviceName,
                    payloadJson = json.encodeToString(record),
                    pulledAtMillis = now,
                )
            },
        )
        return records.size
    }

    suspend fun login(baseUrl: String, username: String, password: String) {
        val normalizedUrl = normalizeBaseUrl(baseUrl)
        val response = syncClient.login(normalizedUrl, username, password)
        settingsRepository.setServerSession(normalizedUrl, response.token)
    }

    // Defaults to https:// when the operator doesn't type a scheme — real deployments
    // (including racemaster's own, behind Traefik) serve over TLS, and Android blocks
    // cleartext HTTP by default (targetSdk 28+), so a bare hostname must resolve to https,
    // not silently become http and fail with a cryptic "Cleartext HTTP traffic ... not
    // permitted" exception. An explicit http:// (e.g. a local dev server) is left as-is.
    private fun normalizeBaseUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    /** Considers every record Mule holds — not just what's unsynced — grouped by each
     *  record's own [PulledRecordEntity.sourceRaceLabel] (the race's own name, as recorded on
     *  the originating phone — one status-check + push call per distinct race label, since a
     *  mule can hold records from more than one), and within each race-label group further
     *  grouped by [PulledRecordEntity.deviceName] into the flat per-device line arrays the
     *  server itself stores one-JSON-file-per-device (mirrored here as one `devices` push per
     *  race label rather than one call per device, so a mule holding several nearby phones'
     *  worth of records for the same race still only makes one HTTP round-trip). This lands
     *  under `mobile/<user>/<race label>/` on the server — no target dataset to pick, just the
     *  pushing user's own folder. Before sending, checks the server's own
     *  [MuleSyncClient.getSyncStatus] for this race and filters each device's records against
     *  its own already-stored `lineNumber` (a race-label group can span multiple physical
     *  devices, each with its own independent cursor), so only the genuine delta gets sent
     *  rather than resending everything every tick — safe because the server dedups by
     *  `recordUuid` as a backstop regardless. Only marks a record synced once this same
     *  round's status check independently found the server already has it — never the ones
     *  just sent in this call, since `added` is a bare count with no per-record ack; those get
     *  confirmed (and marked) on a later call once a fresh status check actually reflects
     *  them, so a row that silently didn't land keeps getting retried instead of being falsely
     *  marked done. Returns how many were genuinely new on the server (summed `added` across
     *  every race-label group that needed a push), which is what's meaningful to show the
     *  operator — not the full send size. */
    suspend fun pushToServer(): Int {
        val baseUrl = requireNotNull(settingsRepository.serverBaseUrl.first()) { "Not logged in" }
        val token = requireNotNull(settingsRepository.authToken.first()) { "Not logged in" }

        val all = pulledRecordDao.getAll()
        if (all.isEmpty()) return 0

        val myDeviceId = settingsRepository.getOrCreateDeviceId()
        var added = 0
        for ((raceLabel, rowsForRace) in all.groupBy { it.sourceRaceLabel }) {
            val status = runCatching { syncClient.getSyncStatus(baseUrl, token, raceLabel) }.getOrDefault(emptyMap())
            val byDevice = rowsForRace.groupBy({ it.deviceName }) { json.decodeFromString<SyncRecord>(it.payloadJson) }
            val devicesToSend = recordsDueForDevices(byDevice, status)
            if (devicesToSend.isNotEmpty()) {
                val response = syncClient.pushRecords(baseUrl, token, raceLabel, devicesToSend)
                added += response.added
            }
            // Only the rows this round's *status check* independently found the server already
            // has (excluded from devicesToSend above) are safe to mark synced here — not the
            // ones just sent. `response.added` is a bare count with no per-record ack, so a
            // record that didn't actually land (dropped mid-request, a server-side hiccup
            // silently swallowing just one row of a batch, ...) would otherwise get marked
            // synced anyway and never retried: pushIfNeeded's cheap unsyncedCount gate stops
            // this method being called again at all once every local row looks synced. Leaving
            // just-sent rows unsynced means the *next* auto-sync tick's fresh status check is
            // what actually confirms them, self-correcting instead of trusting this send
            // blindly — previously a record that silently failed to land stayed permanently
            // stuck until something unrelated (e.g. a stopwatch reset) created a new unsynced
            // row and incidentally forced a full re-check.
            val justSentUuids = devicesToSend.values.flatten().map { it.recordUuid }.toSet()
            val confirmedRows = rowsForRace.filter { it.recordUuid !in justSentUuids }
            if (confirmedRows.isNotEmpty()) {
                pulledRecordDao.markSynced(confirmedRows.map { it.recordUuid }, System.currentTimeMillis())
            }

            // Record "synced to SERVER" for this device's own originated lines only, since a
            // race label's own local race (and hence its local history) only exists for
            // records this device itself recorded, not ones merely relayed from a third-party
            // phone.
            val selfOriginated = confirmedRows.filter { it.sourceDeviceId == myDeviceId }
            if (selfOriginated.isNotEmpty()) {
                val race = raceRepository.getRaceByLabel(raceLabel)
                if (race != null) {
                    raceRepository.recordLineSyncs(race.id, selfOriginated.map { it.lineNumber }, SERVER_TARGET_ID, targetName = baseUrl)
                }
            }
        }
        return added
    }
}

// Per-device delta filtering: each device's records are compared against that same device's
// own already-stored max lineNumber (status[deviceName], 0 if the server has nothing for it
// yet) — no more Bibs/Time split, since the server no longer stores one either (lineLabel's
// B/T prefix already carries that distinction end to end). Pulled out as a pure function so
// this logic can be tested directly, without faking pushToServer's network round-trip.
internal fun recordsDueForDevices(
    byDevice: Map<String, List<SyncRecord>>,
    status: Map<String, Long>,
): Map<String, List<SyncRecord>> =
    byDevice
        .mapValues { (deviceName, records) -> records.filter { it.lineNumber > (status[deviceName] ?: 0) } }
        .filterValues { it.isNotEmpty() }
