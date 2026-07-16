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
 * server over HTTP (via [MuleSyncClient]) once logged in — each race label's records land
 * under that race's own folder server-side (`mobile/<user>/<race label>/`), no target
 * dataset needs picking. Mule can lock onto one Bibs device and one Time device at once —
 * either independently, or both together (the caller is responsible for checking their race
 * labels match; see [mobile.racemaster.ui.mulemode.MuleModeViewModel]).
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
    val isLoggedIn: Flow<Boolean> = settingsRepository.authToken.map { it != null }
    val autoSyncStopped: Flow<Boolean> = settingsRepository.autoSyncStopped
    val sourceSummaries: Flow<List<PulledSourceSummary>> = pulledRecordDao.observeSourceSummaries()
    val deviceName: Flow<String?> = settingsRepository.deviceName

    fun observeRecordsForSource(sourceDeviceRole: String, sourceRaceLabel: String): Flow<List<PulledRecordDisplay>> =
        pulledRecordDao.observeForSource(sourceDeviceRole, sourceRaceLabel).map { entities ->
            entities.map { PulledRecordDisplay(json.decodeFromString(it.payloadJson), it.syncedAtMillis) }
        }

    fun scanForDevices(): Flow<Advertisement> = pullClient.scanForDevices()

    suspend fun readDeviceInfo(advertisement: Advertisement): DeviceInfo = pullClient.readDeviceInfo(advertisement)

    suspend fun setAutoSyncStopped(stopped: Boolean) {
        settingsRepository.setAutoSyncStopped(stopped)
    }

    // count starts at 0 and is only ever set from inside pullClient.pull()'s onReceived
    // callback — which runs (and must complete, storing the records) strictly before pull()
    // acks the peripheral. See MulePullClient.pull()'s doc for why that ordering matters.
    suspend fun pullFrom(advertisement: Advertisement, sourceDeviceRole: String, sourceRaceLabel: String): Int {
        var count = 0
        pullClient.pull(advertisement) { records -> count = storePulledRecords(sourceDeviceRole, sourceRaceLabel, records) }
        return count
    }

    /** Same destination as [pullFrom] (the Mule inbox, pushed on to the server the same way
     *  as anything pulled over BLE) but for this device's *own* records — there's no radio
     *  involved, [records] is read straight from the local database by the caller (see
     *  MuleModeViewModel.pullSelf). Lets "self" be treated as a sync candidate the same as
     *  any other discovered device, rather than relying solely on PeripheralSyncService's own
     *  separate, invisible self-push loop. */
    suspend fun pullFromSelf(sourceDeviceRole: String, sourceRaceLabel: String, records: List<SyncRecord>): Int =
        storePulledRecords(sourceDeviceRole, sourceRaceLabel, records)

    private suspend fun storePulledRecords(sourceDeviceRole: String, sourceRaceLabel: String, records: List<SyncRecord>): Int {
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

    /** Pushes every record Mule holds — not just what's unsynced — grouped by each record's
     *  own [PulledRecordEntity.sourceRaceLabel] (the race's own name, as recorded on the
     *  originating phone — one push call per distinct race label, since a mule can hold
     *  records from more than one), and within each group split into the server's separate
     *  `times`/`bibs` tables by source device role. This lands under `mobile/<user>/<race
     *  label>/` on the server — no target dataset to pick, just the pushing user's own
     *  folder. Resending the full set every time (rather than only the delta) means a
     *  server-side file that's been deleted or corrupted gets fully reconstructed on the
     *  very next push; safe to do unconditionally since the server wholly replaces each
     *  device's section rather than merging by `recordUuid`. Returns how many were genuinely
     *  new on the server (summed `added` across every race-label group), which is what's
     *  meaningful to show the operator — not the full send size. */
    suspend fun pushToServer(): Int {
        val baseUrl = requireNotNull(settingsRepository.serverBaseUrl.first()) { "Not logged in" }
        val token = requireNotNull(settingsRepository.authToken.first()) { "Not logged in" }

        val all = pulledRecordDao.getAll()
        if (all.isEmpty()) return 0

        var added = 0
        for ((raceLabel, rowsForRace) in all.groupBy { it.sourceRaceLabel }) {
            val (timeRows, bibRows) = rowsForRace.partition { it.sourceDeviceRole == DEVICE_ROLE_TIME }
            val response = syncClient.pushRecords(
                baseUrl,
                token,
                raceLabel,
                times = timeRows.map { json.decodeFromString<SyncRecord>(it.payloadJson) },
                bibs = bibRows.map { json.decodeFromString<SyncRecord>(it.payloadJson) },
            )
            pulledRecordDao.markSynced(rowsForRace.map { it.recordUuid }, System.currentTimeMillis())
            added += response.added
        }
        return added
    }

    /** Lets a plain Time/Bibs phone push its *own* records straight to the server, bypassing
     *  the need for a physical Mule entirely — reuses whatever login the operator already set
     *  up via the Mule Mode screen (these are just shared settings, not something exclusive to
     *  being in Mule Mode). [raceLabel] is this phone's own active race's name — see
     *  [pushToServer]'s doc for how that scopes the server-side path. The caller passes the
     *  *full* current record set (not just unsynced ones) so a deleted/corrupted server-side
     *  file is fully reconstructed on the next push. Returns false (a no-op, not an error) if
     *  not logged in yet; that's the expected state for anyone not using server sync at all. */
    suspend fun pushOwnRecords(deviceRole: String, raceLabel: String, records: List<SyncRecord>): Boolean {
        if (records.isEmpty()) return true
        val baseUrl = settingsRepository.serverBaseUrl.first() ?: return false
        val token = settingsRepository.authToken.first() ?: return false
        syncClient.pushRecords(
            baseUrl,
            token,
            raceLabel,
            times = if (deviceRole == DEVICE_ROLE_TIME) records else emptyList(),
            bibs = if (deviceRole == DEVICE_ROLE_BIBS) records else emptyList(),
        )
        return true
    }
}
