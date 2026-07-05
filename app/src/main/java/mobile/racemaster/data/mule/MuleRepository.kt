package mobile.racemaster.data.mule

import com.juul.kable.Advertisement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import mobile.racemaster.data.db.dao.PulledRecordDao
import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.db.entity.PulledRecordEntity
import mobile.racemaster.data.settings.SettingsRepository

const val DEVICE_ROLE_TIME = "TIME"
const val DEVICE_ROLE_BIBS = "BIBS"

data class PulledRecordDisplay(val record: SyncRecord, val syncedAtMillis: Long?)

/**
 * Orchestrates Mule Mode: pulling unsynced records from nearby Time/Bibs phones over BLE
 * (via [MulePullClient]) into a local inbox, and pushing that inbox on to the racemaster
 * server over HTTP (via [MuleSyncClient]) once logged in and a target dataset is picked.
 * Mule can lock onto one Bibs device and one Time device at once — either independently, or
 * both together (the caller is responsible for checking their race labels match; see
 * [mobile.racemaster.ui.mulemode.MuleModeViewModel]).
 */
class MuleRepository(
    private val pulledRecordDao: PulledRecordDao,
    private val settingsRepository: SettingsRepository,
    private val pullClient: MulePullClient,
    private val syncClient: MuleSyncClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val unsyncedCount: Flow<Int> = pulledRecordDao.observeUnsyncedCount()
    val lastSyncedAtMillis: Flow<Long?> = pulledRecordDao.observeLastSyncedAtMillis()
    val lastPulledAtMillis: Flow<Long?> = pulledRecordDao.observeLastPulledAtMillis()
    val selectedDataset: Flow<Pair<String, String>?> = settingsRepository.selectedDataset
    val isLoggedIn: Flow<Boolean> = settingsRepository.authToken.map { it != null }
    val lockedBibsDeviceId: Flow<String?> = settingsRepository.lockedBibsDeviceId
    val lockedTimeDeviceId: Flow<String?> = settingsRepository.lockedTimeDeviceId
    val autoSyncStopped: Flow<Boolean> = settingsRepository.autoSyncStopped
    val sourceSummaries: Flow<List<PulledSourceSummary>> = pulledRecordDao.observeSourceSummaries()

    fun observeRecordsForSource(sourceDeviceRole: String, sourceRaceLabel: String): Flow<List<PulledRecordDisplay>> =
        pulledRecordDao.observeForSource(sourceDeviceRole, sourceRaceLabel).map { entities ->
            entities.map { PulledRecordDisplay(json.decodeFromString(it.payloadJson), it.syncedAtMillis) }
        }

    fun scanForDevices(): Flow<Advertisement> = pullClient.scanForDevices()

    suspend fun readDeviceInfo(advertisement: Advertisement): DeviceInfo = pullClient.readDeviceInfo(advertisement)

    /** Locks Mule onto this device (by its stable `deviceId`, not BLE address) for
     *  auto-pull, replacing any previous lock for the *same* role only — a Bibs lock and a
     *  Time lock coexist independently. */
    suspend fun lockOntoDevice(deviceRole: String, deviceId: String) {
        when (deviceRole) {
            DEVICE_ROLE_BIBS -> settingsRepository.setLockedBibsDeviceId(deviceId)
            DEVICE_ROLE_TIME -> settingsRepository.setLockedTimeDeviceId(deviceId)
        }
    }

    suspend fun setAutoSyncStopped(stopped: Boolean) {
        settingsRepository.setAutoSyncStopped(stopped)
    }

    suspend fun pullFrom(advertisement: Advertisement, sourceDeviceRole: String, sourceRaceLabel: String): Int {
        val records = pullClient.pull(advertisement)
        if (records.isEmpty()) return 0
        val now = System.currentTimeMillis()
        pulledRecordDao.insertAll(
            records.map { record ->
                PulledRecordEntity(
                    recordUuid = record.recordUuid,
                    sourceDeviceRole = sourceDeviceRole,
                    sourceRaceLabel = sourceRaceLabel,
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

    suspend fun listDatasets(): List<DatasetSummary> {
        val baseUrl = requireNotNull(settingsRepository.serverBaseUrl.first()) { "Not logged in" }
        val token = requireNotNull(settingsRepository.authToken.first()) { "Not logged in" }
        return syncClient.listDatasets(baseUrl, token)
    }

    suspend fun selectDataset(owner: String, fullName: String) {
        settingsRepository.setSelectedDataset(owner, fullName)
    }

    /** Pushes every record Mule holds — not just what's unsynced — to the selected dataset,
     *  split into the server's separate `times`/`bibs` tables by each record's source device
     *  role. Resending the full set every time (rather than only the delta) means a
     *  server-side file that's been deleted or corrupted gets fully reconstructed on the
     *  very next push; it's safe to do unconditionally since the server dedups by
     *  `recordUuid`. Returns how many were genuinely new on the server (the response's
     *  `added` count), which is what's meaningful to show the operator — not the full send
     *  size. */
    suspend fun pushToServer(): Int {
        val baseUrl = requireNotNull(settingsRepository.serverBaseUrl.first()) { "Not logged in" }
        val token = requireNotNull(settingsRepository.authToken.first()) { "Not logged in" }
        val (owner, fullName) = requireNotNull(settingsRepository.selectedDataset.first()) { "No dataset selected" }

        val all = pulledRecordDao.getAll()
        if (all.isEmpty()) return 0

        val (timeRows, bibRows) = all.partition { it.sourceDeviceRole == DEVICE_ROLE_TIME }
        val response = syncClient.pushRecords(
            baseUrl,
            token,
            owner,
            fullName,
            times = timeRows.map { json.decodeFromString<SyncRecord>(it.payloadJson) },
            bibs = bibRows.map { json.decodeFromString<SyncRecord>(it.payloadJson) },
        )

        pulledRecordDao.markSynced(all.map { it.recordUuid }, System.currentTimeMillis())
        return response.added
    }

    /** Lets a plain Time/Bibs phone push its *own* records straight to the server, bypassing
     *  the need for a physical Mule entirely — reuses whatever login/dataset the operator
     *  already set up via the Mule Mode screen (these are just shared settings, not something
     *  exclusive to being in Mule Mode). The caller passes the *full* current record set (not
     *  just unsynced ones) so a deleted/corrupted server-side file is fully reconstructed on
     *  the next push. Returns false (a no-op, not an error) if login/dataset aren't
     *  configured yet; that's the expected state for anyone not using server sync at all. */
    suspend fun pushOwnRecords(deviceRole: String, records: List<SyncRecord>): Boolean {
        if (records.isEmpty()) return true
        val baseUrl = settingsRepository.serverBaseUrl.first() ?: return false
        val token = settingsRepository.authToken.first() ?: return false
        val (owner, fullName) = settingsRepository.selectedDataset.first() ?: return false
        syncClient.pushRecords(
            baseUrl,
            token,
            owner,
            fullName,
            times = if (deviceRole == DEVICE_ROLE_TIME) records else emptyList(),
            bibs = if (deviceRole == DEVICE_ROLE_BIBS) records else emptyList(),
        )
        return true
    }
}
