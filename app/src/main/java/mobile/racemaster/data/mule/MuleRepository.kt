package mobile.racemaster.data.mule

import android.util.Log
import com.juul.kable.Advertisement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.json.Json
import mobile.racemaster.data.db.dao.PulledRecordDao
import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.db.entity.PulledRecordEntity
import mobile.racemaster.data.db.entity.SERVER_TARGET_ID
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.settings.SettingsRepository

private const val TAG = "MuleRepository"

// deviceName comes from the entity's own column, not the decoded record — SyncRecord itself
// carries no per-line device name (see its own doc). syncedToLabel mirrors a local race's own
// LineSyncEntity.targetName (see PulledRecordEntity.syncedTargetName's own doc) so a pulled
// record's "Synced to: X" tag matches a local race's exactly, not just a bare color.
data class PulledRecordDisplay(val record: SyncRecord, val deviceName: String, val syncedAtMillis: Long?, val syncedToLabel: String?)

/**
 * Orchestrates Mule Mode: pulling records from genuinely different nearby Time/Bibs phones
 * over BLE (via [MulePullClient]) into a local inbox, and pushing that inbox — plus this
 * device's own history, built fresh on demand rather than staged anywhere (see
 * [pushToServer]'s own doc) — on to the racemaster server over HTTP (via [MuleSyncClient])
 * once logged in — each race label's records land under that race's own folder server-side
 * (`mobile/<user>/<race label>/`), no target dataset needs picking. Mule can lock onto one
 * Bibs device and one Time device at once — either independently, or both together (the
 * caller is responsible for checking their race labels match; see
 * [mobile.racemaster.ui.mulemode.MuleModeViewModel]).
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

    // Combines what's genuinely pulled from other devices (pulled_records) with this device's
    // own outstanding history (RaceRepository's own device-wide aggregates) — see
    // pushToServer's own doc for why self is no longer staged in pulled_records at all, and
    // hence why that table alone can no longer answer "how much is left to sync" on its own.
    val unsyncedCount: Flow<Int> = combine(
        pulledRecordDao.observeUnsyncedCount(),
        raceRepository.unsyncedHistoryCountAcrossAllRaces,
    ) { pulled, self -> pulled + self }
    val lastSyncedAtMillis: Flow<Long?> = combine(
        pulledRecordDao.observeLastSyncedAtMillis(),
        raceRepository.lastHistorySyncedAtMillisAcrossAllRaces,
    ) { pulled, self -> maxOfNullable(pulled, self) }
    // Deliberately BLE-pull-only (unlike unsyncedCount/lastSyncedAtMillis above) — self no
    // longer "pulls" anything, it pushes straight to the server, so this now means exactly
    // what it says: the last time this device actually received something over the radio from
    // a peer, not a self-pull tick that ran regardless of whether any radio activity happened.
    val lastPulledAtMillis: Flow<Long?> = pulledRecordDao.observeLastPulledAtMillis()
    val isLoggedIn: Flow<Boolean> = settingsRepository.authToken.map { it != null }
    val autoSyncStopped: Flow<Boolean> = settingsRepository.autoSyncStopped
    val bluetoothOff: Flow<Boolean> = settingsRepository.bluetoothOff
    val serverSyncOff: Flow<Boolean> = settingsRepository.serverSyncOff

    val sourceSummaries: Flow<List<PulledSourceSummary>> = pulledRecordDao.observeSourceSummaries()
    val deviceName: Flow<String?> = settingsRepository.deviceName

    // Exposed for Race History to show a *Mule-pulled* source as "too old, no longer checked
    // against the server" — see PulledRecordDao.RaceLabelActivity's own doc for why this is
    // scoped to genuinely-pulled-from-others data only; a local race's own staleness uses
    // RaceRepository.observeLastActivityAtMillis instead.
    val serverSyncMaxAgeDays: Flow<Int> = settingsRepository.serverSyncMaxAgeDays
    val raceLabelLastTouchedAtMillis: Flow<Map<String, Long>> = pulledRecordDao.observeLastTouchedByRaceLabel()
        .map { rows -> rows.associate { it.sourceRaceLabel to it.lastTouchedAtMillis } }

    // See PulledRecordDao.deleteForSource's own doc for why this is always safe to offer,
    // unlike deleting a local race.
    suspend fun deleteSource(sourceRaceLabel: String, sourceDeviceId: String) {
        pulledRecordDao.deleteForSource(sourceRaceLabel, sourceDeviceId)
    }

    fun observeRecordsForSource(sourceRaceLabel: String, sourceDeviceId: String): Flow<List<PulledRecordDisplay>> =
        pulledRecordDao.observeForSource(sourceRaceLabel, sourceDeviceId)
            .map { entities ->
                entities.map {
                    PulledRecordDisplay(json.decodeFromString(it.payloadJson), it.deviceName, it.syncedAtMillis, it.syncedTargetName)
                }
            }

    fun scanForDevices(): Flow<Advertisement> = pullClient.scanForDevices()

    suspend fun readDeviceInfo(advertisement: Advertisement): DeviceInfo = pullClient.readDeviceInfo(advertisement)

    suspend fun setAutoSyncStopped(stopped: Boolean) {
        settingsRepository.setAutoSyncStopped(stopped)
    }

    suspend fun setBluetoothOff(off: Boolean) {
        settingsRepository.setBluetoothOff(off)
    }

    suspend fun setServerSyncOff(off: Boolean) {
        settingsRepository.setServerSyncOff(off)
    }

    // The delta-sync cutoff for the next pull from this specific device/race — 0 (request
    // everything) if nothing's ever been pulled from it before.
    suspend fun lastPulledLineNumber(sourceDeviceId: String, sourceRaceLabel: String): Long =
        pulledRecordDao.getLastPulledLineNumber(sourceDeviceId, sourceRaceLabel) ?: 0

    // count starts at 0 and is only ever set from inside pullClient.pull()'s onReceived
    // callback — which runs (and must complete, storing the records) strictly before pull()
    // acks the peripheral. See MulePullClient.pull()'s doc for why that ordering matters.
    // [sourceDeviceName] comes from the same DeviceInfo read that already supplied
    // sourceRaceLabel/sourceDeviceId (see MuleSyncEngine.pullAllVisibleDevices) — SyncRecord
    // itself carries no per-line device name (see its own doc for why).
    suspend fun pullFrom(
        advertisement: Advertisement,
        sourceRaceLabel: String,
        sourceDeviceId: String,
        sourceDeviceName: String,
        sinceLineNumber: Long,
    ): Int {
        var count = 0
        val myDeviceId = settingsRepository.getOrCreateDeviceId()
        val myDeviceName = settingsRepository.getOrCreateDeviceName()
        pullClient.pull(advertisement, myDeviceId, myDeviceName, sinceLineNumber) { records ->
            count = storePulledRecords(sourceRaceLabel, sourceDeviceId, sourceDeviceName, records)
        }
        return count
    }

    private suspend fun storePulledRecords(
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

    /** Pushes on to the server everything this device knows about for every race label it has
     *  any connection to — its own local races (built fresh from [RaceRepository]'s own
     *  [mobile.racemaster.data.db.entity.HistoryLineEntity] rows on every single attempt, no
     *  local mirror of them ever kept lying around — see [PulledRecordEntity]'s own doc for why
     *  that mirroring was removed after causing a real bug: a race deleted and recreated under
     *  the same label resent the old race's stale rows alongside the new one's) and whatever
     *  it's holding for genuinely different devices pulled over BLE (from [PulledRecordDao],
     *  which now only ever holds that). Race labels from both sources are unioned into one
     *  set — a label can have a local race, pulled-from-others rows, or (a Time phone and a
     *  Bibs phone sharing a label) both — and each gets exactly one status-check + push call,
     *  with self's own device-name key folded into the very same per-race `devices` map any
     *  pulled devices' rows already go into, so a mule holding both still only makes one HTTP
     *  round-trip per race label. This lands under `mobile/<user>/<race label>/` on the
     *  server — no target dataset to pick, just the pushing user's own folder. Before sending,
     *  checks the server's own [MuleSyncClient.getSyncStatus] for this race and filters each
     *  device's records (self included) against its own already-stored `lineNumber`, so only
     *  the genuine delta gets sent rather than resending everything every tick — safe because
     *  the server dedups by `recordUuid` as a backstop regardless, and because comparing
     *  directly against the server's own reported state (rather than trusting any local
     *  "already synced" flag) is exactly what lets this recover on its own if the server's
     *  copy is ever lost. Only marks a record synced once this same round's status check
     *  independently found the server already has it — never the ones just sent in this call,
     *  since `added` is a bare count with no per-record ack; those get confirmed (and marked)
     *  on a later call once a fresh status check actually reflects them, so a row that
     *  silently didn't land keeps getting retried instead of being falsely marked done.
     *  Returns how many were genuinely new on the server (summed `added` across every
     *  race-label group that needed a push), which is what's meaningful to show the operator —
     *  not the full send size. Skips any race-label group with no recent activity — a local
     *  race's own [RaceRepository.observeLastActivityAtMillis], a Mule-pulled source's own
     *  `pulledAtMillis`, or (a label with both) whichever is more recent — within
     *  [SettingsRepository.serverSyncMaxAgeDays]; see that setting's own doc for why every
     *  sync attempt now always reconciles rather than skipping when nothing looks locally
     *  unsynced. A skipped group is simply left as it is; nothing about it is marked one way
     *  or the other. */
    suspend fun pushToServer(): Int {
        val baseUrl = requireNotNull(settingsRepository.serverBaseUrl.first()) { "Not logged in" }
        val token = requireNotNull(settingsRepository.authToken.first()) { "Not logged in" }

        val pulledRows = pulledRecordDao.getAll()
        val localRaces = raceRepository.observeAllRaces().first()
        if (pulledRows.isEmpty() && localRaces.isEmpty()) return 0

        val maxAgeDays = settingsRepository.serverSyncMaxAgeDays.first()
        val touchedSinceMillis = System.currentTimeMillis() - maxAgeDays.days.inWholeMilliseconds
        val myDeviceName = settingsRepository.getOrCreateDeviceName()

        val pulledByLabel = pulledRows.groupBy { it.sourceRaceLabel }
        val localRacesByLabel = localRaces.associateBy { it.label }
        val raceLabels = pulledByLabel.keys + localRacesByLabel.keys

        var added = 0
        for (raceLabel in raceLabels) {
            val pulledForRace = pulledByLabel[raceLabel].orEmpty()
            val localRace = localRacesByLabel[raceLabel]

            val pulledTouchedAtMillis = pulledForRace.maxOfOrNull { it.pulledAtMillis }
            val localTouchedAtMillis = localRace?.let { raceRepository.observeLastActivityAtMillis(it.id).first() }
            val touchedAtMillis = maxOfNullable(pulledTouchedAtMillis, localTouchedAtMillis) ?: continue
            if (touchedAtMillis < touchedSinceMillis) continue

            val status = runCatching { syncClient.getSyncStatus(baseUrl, token, raceLabel) }.getOrDefault(emptyMap())

            // This device's own history, built fresh from the real HistoryLineEntity rows —
            // no locally-staged copy to fall out of sync with what actually happened. Fetched
            // from line 0 (this race's *entire* history), not from status[myDeviceName] — the
            // "confirmed" step below needs the full set to tell "not sent this round because
            // the server already had it" apart from "not sent because it doesn't exist yet",
            // exactly mirroring how pulledForRace (every row ever pulled, not just what's due)
            // already works for other devices below. Pre-filtering this to only the delta would
            // make every fetched record "due" by construction, so every one would always end up
            // in justSentUuids and confirmedSelfRecords would always be empty — self would
            // never be marked synced no matter how many pushes actually landed (confirmed in
            // the field: self stayed permanently red, and lastSyncedAtMillis stayed "never",
            // despite the server genuinely having the data).
            val selfRecords = if (localRace != null) {
                raceRepository.getHistorySinceLineNumber(localRace.id, 0L)
                    .map { it.toSyncRecord(localRace.timeModeStartedAtMillis, location = localRace.location) }
            } else {
                emptyList()
            }

            // Genuinely pulled from other devices — decoded from the inbox. A single row whose
            // payloadJson no longer matches SyncRecord's current shape (e.g. stored before a
            // wire field's type changed) must not take down every other race label's push for
            // the rest of this process's life — this now runs on every tick (see this
            // function's own doc), so one bad row left uncaught here would otherwise wedge
            // sync shut permanently instead of just leaving that one row stuck unsynced
            // (visible via the operator's own unsynced count) while everything else keeps
            // flowing.
            val pulledByDevice = pulledForRace
                .mapNotNull { row ->
                    runCatching { json.decodeFromString<SyncRecord>(row.payloadJson) }
                        .onFailure { Log.w(TAG, "Dropping unparseable pulled record ${row.recordUuid} — stale wire format?", it) }
                        .getOrNull()
                        ?.let { row.deviceName to it }
                }
                .groupBy({ it.first }) { it.second }

            val byDevice = if (selfRecords.isEmpty()) pulledByDevice else pulledByDevice + (myDeviceName to selfRecords)
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
            // synced anyway and never retried. Leaving just-sent rows unsynced means the
            // *next* auto-sync tick's fresh status check is what actually confirms them,
            // self-correcting instead of trusting this send blindly — previously a record
            // that silently failed to land stayed permanently stuck until something unrelated
            // (e.g. a stopwatch reset) created a new unsynced row and incidentally forced a
            // full re-check.
            val justSentUuids = devicesToSend.values.flatten().map { it.recordUuid }.toSet()
            val now = System.currentTimeMillis()

            val confirmedPulledRows = pulledForRace.filter { it.recordUuid !in justSentUuids }
            if (confirmedPulledRows.isNotEmpty()) {
                // Same targetName as the self-originated LineSyncEntity tagging just below —
                // the only destination a pulled record is ever pushed to from here is this
                // server, so this is always accurate, and it's what lets Mule Source Detail
                // show "Synced to: X" exactly like a local race's own history instead of just
                // a bare color.
                pulledRecordDao.markSynced(confirmedPulledRows.map { it.recordUuid }, now, targetName = baseUrl)
            }

            if (localRace != null) {
                val confirmedSelfRecords = selfRecords.filter { it.recordUuid !in justSentUuids }
                if (confirmedSelfRecords.isNotEmpty()) {
                    val confirmedUuids = confirmedSelfRecords.map { it.recordUuid }
                    // Same "confirmed by the server's own status check, not merely attempted"
                    // rule as the pulled-from-others rows above — this is what makes a local
                    // race's own sync dot (RaceHistoryDetailViewModel's `synced`) honest about
                    // having actually reached the server, not just having been handed to a
                    // local inbox the way the old mirrored-copy design used to mark it.
                    raceRepository.markHistorySyncedByUuid(confirmedUuids, now)
                    raceRepository.recordLineSyncs(localRace.id, confirmedSelfRecords.map { it.lineNumber }, SERVER_TARGET_ID, targetName = baseUrl, syncedAtMillis = now)
                }
            }
        }
        return added
    }
}

// null-safe maxOf for two independently-nullable timestamps, where "one side has no signal at
// all" should defer entirely to the other rather than being treated as smaller/earlier — see
// RaceHistoryDetailViewModel's own identical private helper for the same reasoning.
private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
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
