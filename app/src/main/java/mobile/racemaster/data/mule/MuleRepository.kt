package mobile.racemaster.data.mule

import android.util.Log
import com.juul.kable.Advertisement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import mobile.racemaster.data.db.dao.KnownDeviceDao
import mobile.racemaster.data.db.dao.PulledRecordDao
import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.db.entity.DELETED_RACE_NOTE
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.KnownDeviceEntity
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
    private val knownDeviceDao: KnownDeviceDao,
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
    val isLoggedIn: Flow<Boolean> = settingsRepository.authToken.map { it != null }
    val autoSyncStopped: Flow<Boolean> = settingsRepository.autoSyncStopped
    val bluetoothOff: Flow<Boolean> = settingsRepository.bluetoothOff
    val serverSyncOff: Flow<Boolean> = settingsRepository.serverSyncOff

    val sourceSummaries: Flow<List<PulledSourceSummary>> = pulledRecordDao.observeSourceSummaries()
    val tombstonedSources: Flow<Set<Pair<String, String>>> = pulledRecordDao.observeTombstonedSources()
        .map { keys -> keys.map { it.sourceDeviceId to it.sourceRaceLabel }.toSet() }
    val deviceName: Flow<String?> = settingsRepository.deviceName

    // Distinct from lastSyncedAtMillis above, which is really "last time anything was
    // sink-confirmed" and bumps on a BLE ack from a web-app/mule sink just as much as on an
    // actual HTTP push — too conflated to answer "is this phone still successfully talking to
    // the server" on its own (confirmed as a source of confusion: a device could look "synced"
    // purely from BLE relay confirmations while its own server connection had been broken for
    // hours). Set at the end of every pushToServer() call that returns normally (even with
    // nothing new to send — a no-op reconcile still proves the server round trip itself
    // succeeded), so Mule Mode can show this as its own plain "last push to server" timestamp.
    // In-memory only (unlike lastSyncedAtMillis, which is DB-backed) — this is a liveness signal
    // for the current process, not sync history worth persisting across restarts.
    private val lastPushAttemptAtMillisFlow = MutableStateFlow<Long?>(null)
    val lastPushAttemptAtMillis: StateFlow<Long?> = lastPushAttemptAtMillisFlow.asStateFlow()

    // Exposed for Race History to show a *Mule-pulled* source as "too old, no longer checked
    // against the server" — see PulledRecordDao.RaceLabelActivity's own doc for why this is
    // scoped to genuinely-pulled-from-others data only; a local race's own staleness uses
    // RaceRepository.observeLastActivityAtMillis instead.
    val raceStaleAfterDays: Flow<Int> = settingsRepository.raceStaleAfterDays
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

    // See MulePullClient.decodeAdvertisedIdentity's own doc — a cheap, no-connect-required
    // hint used by MuleSyncEngine to decide whether readDeviceInfo below is even worth
    // attempting on a given tick.
    fun decodeAdvertisedIdentity(advertisement: Advertisement): MuleGattProfile.AdvertisedIdentity? =
        pullClient.decodeAdvertisedIdentity(advertisement)

    // [sourceDeviceId]/[sourceRaceLabel], when this source has already been resolved at least
    // once before, let this same connection also deliver any sink confirmation already owed to
    // it — see MulePullClient.readDeviceInfo's own doc for why piggybacking onto a connection
    // already being made for this refresh, rather than a separate relayConfirmationOnly-style
    // reconnect moments later, is what makes that delivery reliable rather than a
    // 100%-reproducible failure against a real device. Harmless, self-correcting redundancy on
    // a tick where pullFrom also ends up running afterward and delivers the same confirmation
    // again via its own ack — nothing is left to redeliver by the next tick either way.
    suspend fun readDeviceInfo(
        advertisement: Advertisement,
        sourceDeviceId: String? = null,
        sourceRaceLabel: String? = null,
        // Progress this Mule already holds (from a BLE delivery or a direct server fetch — see
        // ProgressRepository) that it should propagate on to whoever's on the other end of this
        // connection, piggybacked exactly like the sink confirmation above — see
        // MulePullClient.readDeviceInfo's own progressToDeliver/progressRaceLabel doc for why.
        // Forwarded straight through; both null (the default) is a no-op, same as
        // sinkConfirmedOrigins ending up empty above.
        progressToDeliver: ProgressPayload? = null,
        progressRaceLabel: String? = null,
        // Forwarded straight through to MulePullClient.readDeviceInfo's own param of the same
        // name — see its own doc. A no-op default, same as that one, for the common case (no
        // caller cares, or sinkConfirmedOrigins ends up empty so it never fires anyway).
        onAckFailure: suspend (String) -> Unit = {},
        // Same idea as onAckFailure, for the progress-delivery write.
        onProgressDeliveryFailure: suspend (String) -> Unit = {},
    ): DeviceInfo {
        val sinkConfirmedOrigins = if (sourceDeviceId != null && sourceRaceLabel != null) {
            val lineNumbers = pulledRecordDao.getUnrelayedSinkConfirmedLineNumbersForSource(sourceDeviceId, sourceRaceLabel)
            if (lineNumbers.isEmpty()) emptyList() else listOf(AckedOrigin(sourceDeviceId, sourceRaceLabel, lineNumbers))
        } else {
            emptyList()
        }
        if (sinkConfirmedOrigins.isEmpty() && progressToDeliver == null) return pullClient.readDeviceInfo(advertisement)
        val myDeviceId = settingsRepository.getOrCreateDeviceId()
        val myDeviceName = settingsRepository.getOrCreateDeviceName()
        return pullClient.readDeviceInfo(
            advertisement,
            myDeviceId,
            myDeviceName,
            sinkConfirmedOrigins,
            progressToDeliver = progressToDeliver,
            progressRaceLabel = progressRaceLabel,
            onConfirmationsRelayed = { relayedOrigins ->
                for (origin in relayedOrigins) {
                    if (origin.originDeviceId == null || origin.originRaceLabel == null) continue
                    pulledRecordDao.markConfirmationRelayed(origin.originDeviceId, origin.originRaceLabel, origin.lineNumbers, System.currentTimeMillis())
                }
            },
            onAckFailure = onAckFailure,
            onProgressDeliveryFailure = onProgressDeliveryFailure,
        )
    }

    // See MulePullClient.pullRelayManifest's own doc — a separate, chunked fetch of a peer's
    // full relay manifest, only worth calling once readDeviceInfo has shown relayCount > 0.
    suspend fun pullRelayManifest(advertisement: Advertisement): List<RelayManifestEntry> = pullClient.pullRelayManifest(advertisement)

    // Phase 3: hands [payload] off to whoever's on the other end of [advertisement], tagged for
    // [targetDeviceId] — see MulePullClient.deliverTargetedProgress's own doc for the connection
    // this opens, and MuleSyncEngine's own call sites for how the caller already knows this
    // connection is either directly with the target or with a peer proven (via its own relay
    // manifest) to be able to reach it. Deliberately bypasses readDeviceInfo's own
    // progressToDeliver/shouldDeliverProgress raceLabel-match gate entirely: this delivery is
    // already addressed by device id, not race identity (see TODO.md's phase-3 "targeting model"
    // decision — a device's self-reported raceLabel is exactly the thing that can't be trusted
    // here). Stamps [targetDeviceId] onto the payload itself (overwriting whatever it already
    // carried — a forwarding hop always addresses the next leg explicitly, never trusts an
    // upstream sender's own copy of this field to already be correct for THIS hop).
    suspend fun deliverTargetedProgress(advertisement: Advertisement, targetDeviceId: String, payload: ProgressPayload) {
        pullClient.deliverTargetedProgress(advertisement, payload.copy(targetDeviceId = targetDeviceId))
    }

    // The durable "seen devices" roster — see KnownDeviceEntity's own doc for how this differs
    // from MuleSyncEngine's own in-memory discoveredFlow.
    val knownDevices: Flow<List<KnownDeviceEntity>> = knownDeviceDao.observeAll()

    // Called from MuleSyncEngine.mergeDeviceInfo on every successful GATT resolve — a blank
    // deviceName would only ever come from a device mid-setup that hasn't picked one yet (see
    // MuleSyncEngine.selfDevice's own handling of that same case), not worth cluttering this
    // list with.
    suspend fun recordDeviceSeen(deviceId: String, deviceName: String) {
        if (deviceName.isBlank()) return
        knownDeviceDao.upsert(KnownDeviceEntity(deviceId, deviceName, System.currentTimeMillis()))
    }

    // "Forget" — see MuleSyncEngine.forgetDevice's own doc for the full picture (this alone only
    // ever clears the persisted roster entry; the live discoveredFlow/relayFlow purge happens
    // there). Always safe to call with a raw BLE address that was never actually resolved/
    // upserted (see KnownDeviceDao.delete's own doc) — forgetting a still-unresolved
    // "Discovering…" ghost is exactly the case this needs to be a harmless no-op for.
    suspend fun forgetKnownDevice(deviceId: String) {
        knownDeviceDao.delete(deviceId)
    }

    // Lets MuleSyncEngine self-loop-guard a relay manifest entry (never pull my own data back
    // from a mule that happens to be relaying it) without reaching into SettingsRepository
    // directly.
    suspend fun myDeviceId(): String = settingsRepository.getOrCreateDeviceId()

    // Answers a mule-to-mule relay pull request — the pulled-inbox equivalent of
    // RaceRepository.getHistorySinceLineNumber, called from PeripheralSyncService.streamRelayedRecords
    // when a neighbor asks for a specific origin's data rather than this device's own race.
    suspend fun relayedRecordsSince(sourceDeviceId: String, sourceRaceLabel: String, sinceLineNumber: Long): List<SyncRecord> =
        pulledRecordDao.getRecordsSince(sourceDeviceId, sourceRaceLabel, sinceLineNumber).mapNotNull { decodeSyncRecord(it, json) }

    // The BLE-ack counterpart to pushToServer's own confirmed-via-status-check marking — lets a
    // relayed pulled_records row be recognized as sink-confirmed the moment a downstream device
    // says so (PeripheralSyncService.markSynced), rather than only ever learning that from this
    // device's own next server push. Deliberately no orange/"relayed onward" state for
    // pulled_records rows (unlike a local race's own LineSyncEntity) — Mule Source Detail stays
    // plain red/green, so a plain (not yet sink-confirmed) relay ack needs no write here at all;
    // only genuinely sink-confirmed uuids are ever passed to this. Safe to call with uuids that
    // don't belong to this device's own pulled_records inbox at all (e.g. they're actually this
    // device's own directly-recorded lines) — see PeripheralSyncService.markSynced's own doc for
    // why that's always a harmless no-op rather than needing to be filtered out first.
    suspend fun markRelayedRecordsSynced(sourceDeviceId: String, sourceRaceLabel: String, lineNumbers: List<Long>, targetName: String) {
        if (lineNumbers.isEmpty()) return
        pulledRecordDao.markSynced(sourceDeviceId, sourceRaceLabel, lineNumbers, System.currentTimeMillis(), targetName)
    }

    // See PeripheralSyncService.backfillSinkAck's own doc. Inclusive of sinceLineNumber itself.
    suspend fun unsyncedPulledLineNumbersUpTo(sourceDeviceId: String, sourceRaceLabel: String, sinceLineNumber: Long): List<Long> =
        pulledRecordDao.getUnsyncedLineNumbersUpTo(sourceDeviceId, sourceRaceLabel, sinceLineNumber)

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

    // Lets MuleSyncEngine.pullAllVisibleDevices decide whether to reconnect to a source that has
    // no new lines to pull, purely to relay a sink confirmation it's already holding for that
    // source (see pullFrom's own doc on sinkConfirmedOrigins) — without this, a source that's
    // already fully pulled would never get told its data has since reached a sink, since the
    // normal delta-driven pull would never fire again for it.
    suspend fun hasSinkConfirmationToRelay(sourceDeviceId: String, sourceRaceLabel: String): Boolean =
        pulledRecordDao.getUnrelayedSinkConfirmedLineNumbersForSource(sourceDeviceId, sourceRaceLabel).isNotEmpty()

    // count starts at 0 and is only ever set from inside pullClient.pull()'s onReceived
    // callback — which runs (and must complete, storing the records) strictly before pull()
    // acks the peripheral. See MulePullClient.pull()'s doc for why that ordering matters.
    // [sourceDeviceName] comes from the same DeviceInfo read that already supplied
    // sourceRaceLabel/sourceDeviceId (see MuleSyncEngine.pullAllVisibleDevices) — SyncRecord
    // itself carries no per-line device name (see its own doc for why).
    //
    // [requestOriginDeviceId]/[requestOriginRaceLabel] are what turn this into a mule-to-mule
    // relay pull rather than a direct one: left null (the default), the wire request asks the
    // peer for its own race, exactly as before this parameter pair existed. Set, they ask the
    // peer to relay a specific RelayManifestEntry instead — [sourceDeviceId]/[sourceRaceLabel]
    // above must then be that entry's true origin (not the peer's own identity), which is what
    // keeps origin attribution intact across arbitrary hop depth once storePulledRecords writes
    // these rows. See MuleSyncEngine.pullAllVisibleDevices for the call site that sets these.
    suspend fun pullFrom(
        advertisement: Advertisement,
        sourceRaceLabel: String,
        sourceDeviceId: String,
        sourceDeviceName: String,
        sinceLineNumber: Long,
        requestOriginDeviceId: String? = null,
        requestOriginRaceLabel: String? = null,
    ): Int {
        var count = 0
        val myDeviceId = settingsRepository.getOrCreateDeviceId()
        val myDeviceName = settingsRepository.getOrCreateDeviceName()
        // Piggybacked onto whatever ack this pull ends up sending (see MulePullClient.pull's
        // own doc) — everything already held for this exact source that's since become
        // sink-confirmed but not yet told back to it, so that confirmation keeps climbing back
        // toward wherever this source's data originally came from, one hop per sync tick — see
        // getUnrelayedSinkConfirmedLineNumbersForSource's own doc for why this is scoped to
        // unrelayed ones only (a bounded delta), and only trustworthy to mark relayed at all
        // because of PeripheralSyncService's deferred GATT response.
        val sinkConfirmedLineNumbers = pulledRecordDao.getUnrelayedSinkConfirmedLineNumbersForSource(sourceDeviceId, sourceRaceLabel)
        val sinkConfirmedOrigins = if (sinkConfirmedLineNumbers.isEmpty()) emptyList() else listOf(AckedOrigin(sourceDeviceId, sourceRaceLabel, sinkConfirmedLineNumbers))
        pullClient.pull(
            advertisement,
            myDeviceId,
            myDeviceName,
            sinceLineNumber,
            requestOriginDeviceId,
            requestOriginRaceLabel,
            sinkConfirmedOrigins,
            onReceived = { records ->
                count = storePulledRecords(sourceRaceLabel, sourceDeviceId, sourceDeviceName, records, direct = requestOriginDeviceId == null)
            },
            onConfirmationsRelayed = { relayedOrigins ->
                // Only retire the "needs relaying" flag once this confirmation has actually
                // reached [sourceDeviceId] itself — requestOriginDeviceId == null means the
                // peripheral this pull just acked IS sourceDeviceId (a direct pull), not merely
                // another intermediate mule that also happens to hold/relay the same origin's
                // data (a relay pull). Confirmed in the field: two mules that both independently
                // relay the same leaf's data (and also pull from each other) would otherwise
                // mark a confirmation "relayed" the moment either told the *other* about it,
                // never actually reaching the leaf that originally recorded the line — each
                // mule's own bookkeeping believed it had already done its job. The
                // sinkConfirmedOrigins are still included in a relay-pull's ack either way
                // (so the intermediate mule also learns/can itself relay it onward) — this only
                // gates whether *this* device stops re-offering it.
                if (requestOriginDeviceId == null) {
                    for (origin in relayedOrigins) {
                        if (origin.originDeviceId == null || origin.originRaceLabel == null) continue
                        pulledRecordDao.markConfirmationRelayed(origin.originDeviceId, origin.originRaceLabel, origin.lineNumbers, System.currentTimeMillis())
                    }
                }
            },
        )
        return count
    }

    private suspend fun storePulledRecords(
        sourceRaceLabel: String,
        sourceDeviceId: String,
        sourceDeviceName: String,
        records: List<SyncRecord>,
        // Pulled straight from the source device itself (not relayed via another mule) — its
        // current history is the truth, so the generation ordering below never refuses it.
        direct: Boolean,
    ): Int {
        if (records.isEmpty()) return 0
        // A "NewRace" marker anywhere in this freshly-pulled batch (see HistoryAction.NEW_RACE's
        // own doc) means the source's race identity was just reset — whatever we already have
        // cached for this exact (sourceDeviceId, sourceRaceLabel) pair is from a different,
        // since-superseded race that merely reused the same label, and must be wiped before the
        // fresh batch (marker included) is stored, not merged with it. Reuses
        // deleteForSource — the same function already backing the manual "delete Mule source" UI
        // action (see MuleRepository.deleteSource) — nothing is actually lost here: the batch
        // that triggered the wipe is inserted immediately after in this same call. No separate
        // cursor reset is needed afterward, unlike the web app's own persisted localStorage
        // cursor — lastPulledLineNumber here is derived fresh from PulledRecordEntity's own
        // current contents each time, so wiping the table alone is self-correcting.
        val newRace = records.firstOrNull { it.action == "NewRace" }
        val heldNewRace = pulledRecordDao.getFirstForSource(sourceDeviceId, sourceRaceLabel)
            ?.let { runCatching { json.decodeFromString<SyncRecord>(it.payloadJson) }.getOrNull() }
            ?.takeIf { it.action == "NewRace" }
        var generation = classifyPulledBatch(heldNewRace, newRace)
        // An older generation than the one held (a copy another mule pulled before the source
        // deleted or recreated its race), or plain deltas against a deletion tombstone: refused,
        // so it can't overwrite the newer data and get relayed or pushed on — unless it came
        // straight from the device, which is never stale about itself. Then a batch with its
        // NewRace replaces what's held; one without (only the lines past a cursor that described
        // what's held, e.g. a tombstone this device has since been adopted over) clears the held
        // copy so the next pull starts from scratch and brings the NewRace with it.
        if (generation == PulledGeneration.SUPERSEDED && direct) {
            if (newRace == null) {
                pulledRecordDao.deleteForSource(sourceRaceLabel, sourceDeviceId)
                return 0
            }
            generation = PulledGeneration.FRESH
        }
        if (generation == PulledGeneration.SUPERSEDED) return 0
        if (newRace != null && generation == PulledGeneration.FRESH) {
            pulledRecordDao.deleteForSource(sourceRaceLabel, sourceDeviceId)
            // The same NewRace marker (same lineNumber and timestamp) already held under a
            // DIFFERENT label for this device means the device was adopted into a new label
            // (RaceRepository.adoptRaceIdentity) and is re-sending that same history under it —
            // the old-label copy would otherwise keep being pushed to the old server folder,
            // resurrecting the device file the web app removes once the adoption has landed.
            val candidates = pulledRecordDao.getAtLineNumberUnderOtherLabels(sourceDeviceId, sourceRaceLabel, newRace.lineNumber)
            for (oldLabel in relabeledSourceLabels(candidates, newRace, json)) pulledRecordDao.deleteForSource(oldLabel, sourceDeviceId)
        }
        val now = System.currentTimeMillis()
        pulledRecordDao.insertAll(
            records.map { record ->
                PulledRecordEntity(
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

    // pushToServer's own last-resort recovery from a 401/403 — see ServerRequestException's own
    // doc for the field report this exists to fix: a server-side session reset (this app's dev
    // server rebuilding sessions.txt from a remote pull, in the confirmed case, but any restart
    // that happens to drop an old token would do the same) invalidates every already-logged-in
    // phone's saved token, with nothing on the phone itself wrong at all. Reaches for
    // SettingsRepository.serverCredentialHistory — most-recent-first, so the first entry for
    // this exact baseUrl is whatever this phone last actually typed and submitted here — rather
    // than needing a separately-tracked "current username" this repository has never kept.
    // Deliberately swallows every failure into a plain null (no saved credentials for this URL,
    // or they no longer work either — e.g. the password itself changed server-side) rather than
    // throwing a second, more confusing exception from in here: either way, the caller's own
    // original ServerRequestException is what should actually reach the operator.
    private suspend fun reauthenticate(baseUrl: String): String? {
        val credential = settingsRepository.serverCredentialHistory.first()
            .firstOrNull { normalizeBaseUrl(it.url) == baseUrl } ?: return null
        return runCatching { syncClient.login(baseUrl, credential.username, credential.password) }
            .onSuccess { settingsRepository.setServerSession(baseUrl, it.token) }
            .getOrNull()?.token
    }

    // See SettingsRepository.clearServerSession's own doc.
    suspend fun clearServerSession() {
        settingsRepository.clearServerSession()
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

    /** Announces a freshly set-up race to the server immediately, called once from Setup Race
     *  right after [RaceRepository.recordModeStart] has written its own local
     *  LOCATION+MODE_START marker pair (see TODO.md's phase 1: "the device file is written to
     *  the server ... as soon as setup is complete"). A plain [pushToServer] call is now
     *  sufficient — it used to need bypassing entirely (an explicit empty-record push) because
     *  its own staleness rule skips any race with no activity at all, which a brand-new race
     *  always was; now that the marker pair itself counts as real activity
     *  ([RaceRepository.observeLastActivityAtMillis] is raceId-scoped, not mode-scoped, so it
     *  sees these rows like any other), the race no longer gets skipped, and [pushToServer]'s own
     *  existing logic pushes them through exactly the way every real record's is. A no-op
     *  (silently swallowed, never thrown) when not logged in or when the push itself fails — the
     *  phone may be offline at setup time, in which case the ordinary [pushToServer] loop will
     *  pick this race up on its own once reachable, same as always. */
    suspend fun announceRaceSetup() {
        runCatching { pushToServer() }
    }

    /** The web app's adoption of [deviceName] (pushing under [raceLabel]) into a real race, per
     *  the server's adoption marker in this login's own folder — see [MuleSyncClient.getAdoption].
     *  Null when not adopted, not logged in, or unreachable (best effort, polled again later). */
    suspend fun adoptionFor(raceLabel: String, deviceName: String): String? {
        val baseUrl = settingsRepository.serverBaseUrl.first() ?: return null
        val token = settingsRepository.authToken.first() ?: return null
        return runCatching { syncClient.getAdoption(baseUrl, token, raceLabel, deviceName) }.getOrNull()
    }

    /** Writes the server's adoption marker on behalf of a web app that reached this device over
     *  Bluetooth but not the server — see [MuleSyncClient.postAdoption]. Best effort. */
    suspend fun writeAdoptionOnBehalf(fromRaceLabel: String, deviceName: String, targetRaceLabel: String) {
        val baseUrl = settingsRepository.serverBaseUrl.first() ?: return
        val token = settingsRepository.authToken.first() ?: return
        runCatching { syncClient.postAdoption(baseUrl, token, fromRaceLabel, deviceName, targetRaceLabel) }
            .onFailure { Log.w(TAG, "adoption write on behalf failed: $fromRaceLabel/$deviceName -> $targetRaceLabel", it) }
    }

    /** Server-side progress for [raceLabel] as a targeted-delivery payload body — used by a mule
     *  relaying an adoption to a Bluetooth-only peer (see MuleSyncEngine's own adoption poll). An
     *  empty payload (adoption only) when there's none yet or the server isn't reachable. */
    suspend fun progressPayloadFor(raceLabel: String): ProgressPayload {
        val baseUrl = settingsRepository.serverBaseUrl.first() ?: return ProgressPayload()
        val token = settingsRepository.authToken.first() ?: return ProgressPayload()
        val response = runCatching { syncClient.getProgress(baseUrl, token, raceLabel, null) }.getOrNull()
            ?: return ProgressPayload()
        return ProgressPayload(response.raceName, response.raceDate, response.generatedAt.orEmpty(), response.entries)
    }

    /** Setup Race's own online branch — races this owner has recent server-side progress for,
     *  within [maxAgeDays] (see [SettingsRepository.raceStaleAfterDays]), for the operator to
     *  pick from instead of typing a name manually. Returns null — never an empty list, which
     *  means "reachable, genuinely nothing recent" — for "couldn't ask at all": not logged in,
     *  or the request itself failed even after one reauthenticate attempt on a 401/403 (same
     *  one-shot recovery [pushToServer] already has, see [reauthenticate]'s own doc; scoped to
     *  right here since this is never called from that loop). The caller's job either way is the
     *  same — fall back to manual race-name entry — but null vs. empty lets the screen say
     *  *why* there's nothing to pick from. */
    suspend fun getAvailableRaces(maxAgeDays: Int): List<AvailableRace>? {
        val baseUrl = settingsRepository.serverBaseUrl.first() ?: return null
        var token = settingsRepository.authToken.first() ?: return null
        return try {
            syncClient.getAvailableRaces(baseUrl, token, maxAgeDays)
        } catch (e: ServerRequestException) {
            if (e.statusCode != 401 && e.statusCode != 403) return null
            token = reauthenticate(baseUrl) ?: return null
            runCatching { syncClient.getAvailableRaces(baseUrl, token, maxAgeDays) }.getOrNull()
        } catch (_: Exception) {
            null
        }
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
     *  the server dedups by `lineNumber` as a backstop regardless, and because comparing
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
     *  [SettingsRepository.raceStaleAfterDays]; see that setting's own doc for why every
     *  sync attempt now always reconciles rather than skipping when nothing looks locally
     *  unsynced. A skipped group is simply left as it is; nothing about it is marked one way
     *  or the other. */
    suspend fun pushToServer(): Int {
        val baseUrl = requireNotNull(settingsRepository.serverBaseUrl.first()) { "Not logged in" }
        var token = requireNotNull(settingsRepository.authToken.first()) { "Not logged in" }

        val pulledRows = pulledRecordDao.getAll()
        val localRaces = raceRepository.observeAllRaces().first()
        if (pulledRows.isEmpty() && localRaces.isEmpty()) return 0

        val maxAgeDays = settingsRepository.raceStaleAfterDays.first()
        val myDeviceName = settingsRepository.getOrCreateDeviceName()

        val pulledByLabel = pulledRows.groupBy { it.sourceRaceLabel }
        // Newest race per label (observeAllRaces is newest-first) — a plain associateBy kept the
        // LAST, i.e. oldest, so a same-label race set up again never got pushed at all.
        val localRacesByLabel = localRaces.distinctBy { it.label }.associateBy { it.label }
        val raceLabels = pulledByLabel.keys + localRacesByLabel.keys

        var added = 0
        // Scoped to once per pushToServer() call (not once per race label) — see
        // reauthenticate's own doc for why a single stale/invalidated token should only ever
        // cost one extra login attempt here, not one per race label this pass happens to touch.
        var reauthAttempted = false
        for (raceLabel in raceLabels) {
            val pulledForRace = pulledByLabel[raceLabel].orEmpty()
            val localRace = localRacesByLabel[raceLabel]

            val pulledTouchedAtMillis = pulledForRace.maxOfOrNull { it.pulledAtMillis }
            val localTouchedAtMillis = localRace?.let { raceRepository.observeLastActivityAtMillis(it.id).first() }
            val touchedAtMillis = maxOfNullable(pulledTouchedAtMillis, localTouchedAtMillis) ?: continue
            if (isRaceStale(touchedAtMillis, maxAgeDays)) continue

            val status = runCatching { syncClient.getSyncStatus(baseUrl, token, raceLabel) }.getOrDefault(emptyMap())
            // Which generation (NewRace) of each device's history the server holds — see
            // startingFreshDevices below. Empty (no generation-based decisions) on an older
            // server without the route or any failure; the line-cursor rule still applies.
            val generations = runCatching { syncClient.getGenerations(baseUrl, token, raceLabel) }.getOrDefault(emptyMap())

            // This device's own history, built fresh from the real HistoryLineEntity rows —
            // no locally-staged copy to fall out of sync with what actually happened. Fetched
            // from line 0 (this race's *entire* history), not from this race's own status entry — the
            // "confirmed" step below needs the full set to tell "not sent this round because
            // the server already had it" apart from "not sent because it doesn't exist yet",
            // exactly mirroring how pulledForRace (every row ever pulled, not just what's due)
            // already works for other devices below. Pre-filtering this to only the delta would
            // make every fetched record "due" by construction, so every one would always end up
            // in justSentUuids and confirmedSelfRecords would always be empty — self would
            // never be marked synced no matter how many pushes actually landed (confirmed in
            // the field: self stayed permanently red, and lastSyncedAtMillis stayed "never",
            // despite the server genuinely having the data).
            //
            val selfRows = if (localRace != null) raceRepository.getHistorySinceLineNumber(localRace.id, 0L) else emptyList()
            val selfRecords = selfRows.map { row -> row.toSyncRecord(localRace?.timeModeStartedAtMillis) }

            // Genuinely pulled from other devices — decoded from the inbox. A single row whose
            // payloadJson no longer matches SyncRecord's current shape (e.g. stored before a
            // wire field's type changed) must not take down every other race label's push for
            // the rest of this process's life — this now runs on every tick (see this
            // function's own doc), so one bad row left uncaught here would otherwise wedge
            // sync shut permanently instead of just leaving that one row stuck unsynced
            // (visible via the operator's own unsynced count) while everything else keeps
            // flowing. See decodeSyncRecord's own doc.
            val pulledByDevice = pulledForRace
                .mapNotNull { row -> decodeSyncRecord(row, json)?.let { row.deviceName to it } }
                .groupBy({ it.first }) { it.second }

            val byDevice = if (selfRecords.isEmpty()) {
                pulledByDevice
            } else {
                pulledByDevice + (myDeviceName to selfRecords)
            }
            // Which devices' own `status` value is impossible for it to legitimately hold —
            // i.e. HIGHER than the highest lineNumber we ourselves have ever seen for that
            // device (our own current history for self; every row ever pulled for a relayed
            // one). The server can never legitimately know about more lines than we've produced
            // for that exact race — line numbers are strictly local-monotonic per raceId — so a
            // status this high can only be stale leftover data from a different, since-
            // superseded race that used to share this exact label. This is what
            // recordsDueForDevices below uses to decide whether to bypass the normal delta
            // filter for a device (see its own doc for why sending everything unfiltered in
            // that case is what actually guarantees a fresh race's own NewRace marker reaches
            // the server at all).
            //
            // Deliberately NOT keyed off whether the local NewRace row's own syncedAtMillis is
            // still null (a real, confirmed bug in an earlier version of this fix): that would
            // create a deadlock, not just a wrong answer — bypassing keeps `justSent` equal to
            // this device's entire history forever, so the "only mark synced what this round's
            // status check independently confirms, never what was merely just sent" rule further
            // down could never fire, so syncedAtMillis could never actually be set, so the
            // bypass could never turn itself off. This comparison has no such problem: once the
            // server's file is wiped-and-replaced (server/routes/mobile.js, triggered by that
            // same push), its own reported status naturally drops to (at most) our own real max,
            // and the bypass naturally, permanently stops on its own — no confirmation-state
            // bookkeeping needed at all.
            //
            // Also whenever the server holds a DIFFERENT generation of a device's history than the
            // one about to be sent (its NewRace timestamp differs): then status's line cursor
            // describes someone else's lines, so the delta past it would lack this generation's
            // own NewRace and be refused (confirmed in the field: a race adopted into a label
            // where this device had earlier deleted a race — the tombstone's line 1 made every
            // push send lines 2+ only, refused every ~5s, forever). Sending everything lets the
            // server see the NewRace and replace (or, for an older relayed copy, refuse) properly.
            val startingFreshDevices = buildSet {
                val selfMax = selfRows.maxOfOrNull { it.lineNumber } ?: 0L
                if ((status[myDeviceName] ?: 0L) > selfMax) add(myDeviceName)
                val selfGeneration = selfRows.filter { it.action == HistoryAction.NEW_RACE }.maxByOrNull { it.lineNumber }
                    ?.let { formatServerTimestamp(it.timestampMillis) }
                if (generationDiffers(generations, myDeviceName, selfGeneration)) add(myDeviceName)
                for ((deviceName, rows) in pulledForRace.groupBy { it.deviceName }) {
                    val pulledMax = rows.maxOfOrNull { it.lineNumber } ?: 0L
                    if ((status[deviceName] ?: 0L) > pulledMax) add(deviceName)
                    val heldGeneration = rows.sortedByDescending { it.lineNumber }.firstNotNullOfOrNull { row ->
                        decodeSyncRecord(row, json)?.takeIf { it.action == "NewRace" }
                    }?.let { formatServerTimestamp(it.timestampMillis) }
                    if (generationDiffers(generations, deviceName, heldGeneration)) add(deviceName)
                }
            }
            // This device's own race is pushed straight from the source — see pushRecords' doc.
            val authoritative = if (selfRecords.isEmpty()) emptyList() else listOf(myDeviceName)
            val devicesToSend = recordsDueForDevices(byDevice, status, startingFreshDevices)
            if (devicesToSend.isNotEmpty()) {
                // See reauthenticate's own doc: a 401/403 here — this device's saved token no
                // longer being accepted, most often a server-side session reset rather than
                // anything wrong on the phone — gets exactly one automatic retry against a fresh
                // login using this phone's own last-saved credentials for this server, before
                // giving up and letting the original exception reach the operator as-is.
                val response = try {
                    syncClient.pushRecords(baseUrl, token, raceLabel, devicesToSend, authoritative)
                } catch (e: ServerRequestException) {
                    if (reauthAttempted || (e.statusCode != 401 && e.statusCode != 403)) throw e
                    reauthAttempted = true
                    token = reauthenticate(baseUrl) ?: throw e
                    syncClient.pushRecords(baseUrl, token, raceLabel, devicesToSend, authoritative)
                }
                added += response.added
                // A relayed copy the server refused as older than (or deleted since) what it
                // holds — the source device deleted or recreated that race after this mule pulled
                // it. Dropping it stops it being resent forever; this device's own race is never
                // older than its own server copy, so it's never touched here.
                for (deviceName in response.superseded) {
                    if (deviceName == myDeviceName) continue
                    pulledRecordDao.deleteForDeviceName(raceLabel, deviceName)
                }
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
            // (deviceName, lineNumber) — a bare lineNumber isn't enough here since devicesToSend
            // spans every device due for this one race label at once (see its own doc); pairing
            // with the device name it actually came from is what SyncRecord's own dropped
            // recordUuid field used to give us for free.
            val justSent = devicesToSend.flatMap { (deviceName, records) -> records.map { deviceName to it.lineNumber } }.toSet()
            val now = System.currentTimeMillis()

            val confirmedPulledRows = pulledForRace.filter { (it.deviceName to it.lineNumber) !in justSent }
            if (confirmedPulledRows.isNotEmpty()) {
                // Same targetName as the self-originated LineSyncEntity tagging just below —
                // the only destination a pulled record is ever pushed to from here is this
                // server, so this is always accurate, and it's what lets Mule Source Detail
                // show "Synced to: X" exactly like a local race's own history instead of just
                // a bare color. Grouped by sourceDeviceId — pulledForRace can span more than one
                // genuinely different source device for this one race label (see this class's
                // own doc), and markSynced is itself scoped to a single source.
                for ((sourceDeviceId, rows) in confirmedPulledRows.groupBy { it.sourceDeviceId }) {
                    pulledRecordDao.markSynced(sourceDeviceId, raceLabel, rows.map { it.lineNumber }, now, targetName = baseUrl)
                }
            }

            if (localRace != null) {
                val confirmedSelfRecords = selfRecords.filter { (myDeviceName to it.lineNumber) !in justSent }
                if (confirmedSelfRecords.isNotEmpty()) {
                    // Same "confirmed by the server's own status check, not merely attempted"
                    // rule as the pulled-from-others rows above — this is what makes a local
                    // race's own sync dot (RaceHistoryDetailViewModel's `synced`) honest about
                    // having actually reached the server, not just having been handed to a
                    // local inbox the way the old mirrored-copy design used to mark it.
                    val confirmedLineNumbers = confirmedSelfRecords.map { it.lineNumber }
                    raceRepository.markHistorySyncedByLineNumber(localRace.id, confirmedLineNumbers, now)
                    raceRepository.recordLineSyncs(localRace.id, confirmedLineNumbers, SERVER_TARGET_ID, targetName = baseUrl, isSink = true, syncedAtMillis = now)
                }
            }
        }
        lastPushAttemptAtMillisFlow.value = System.currentTimeMillis()
        return added
    }
}

// A single row whose payloadJson no longer matches SyncRecord's current shape (e.g. stored
// before a wire field's type changed) must not take down the rest of whatever's decoding it —
// dropped (and logged) rather than thrown, so one bad row leaves itself stuck rather than
// wedging a whole push or relay-serve attempt shut. Shared by pushToServer's own per-device
// decode and relayedRecordsSince, so this tolerance never has to be reimplemented (or drift) a
// second time. Pulled out as a top-level function (like recordsDueForDevices below) so it's
// directly testable without standing up MuleRepository's full dependency graph.
// Whether the server holds a generation of [deviceName]'s history different from [ours] (the
// NewRace timestamp about to be sent). Only a genuine, known mismatch counts — no server entry,
// or no NewRace on our side to compare, leaves the ordinary line-cursor rule in charge.
internal fun generationDiffers(serverGenerations: Map<String, String?>, deviceName: String, ours: String?): Boolean {
    if (ours == null || !serverGenerations.containsKey(deviceName)) return false
    return serverGenerations[deviceName] != ours
}

internal enum class PulledGeneration { FRESH, SAME, SUPERSEDED }

// How a freshly-pulled batch relates to what's already held for that source — the mule-side
// twin of racemaster's server/mobile.js classifyPush. Generations are ordered by their opening
// NewRace's timestamp (one device's own clock). FRESH replaces what's held, SAME merges into it,
// SUPERSEDED (older than held, or deltas with no NewRace against a deletion tombstone) is refused.
internal fun classifyPulledBatch(held: SyncRecord?, incoming: SyncRecord?): PulledGeneration = when {
    incoming == null -> if (held?.note == DELETED_RACE_NOTE) PulledGeneration.SUPERSEDED else PulledGeneration.SAME
    held == null -> PulledGeneration.FRESH
    held.lineNumber == incoming.lineNumber && held.timestampMillis == incoming.timestampMillis -> PulledGeneration.SAME
    incoming.timestampMillis < held.timestampMillis -> PulledGeneration.SUPERSEDED
    else -> PulledGeneration.FRESH
}

// The labels among [candidates] (rows for one device under other labels) holding this exact
// [newRace] marker — same lineNumber and timestamp, i.e. the same race relabeled by adoption
// rather than a genuinely different race that happens to start at the same line.
internal fun relabeledSourceLabels(candidates: List<PulledRecordEntity>, newRace: SyncRecord, json: Json): List<String> =
    candidates
        .filter { row ->
            row.lineNumber == newRace.lineNumber &&
                runCatching { json.decodeFromString<SyncRecord>(row.payloadJson) }.getOrNull()
                    ?.let { it.action == "NewRace" && it.timestampMillis == newRace.timestampMillis } == true
        }
        .map { it.sourceRaceLabel }
        .distinct()

internal fun decodeSyncRecord(row: PulledRecordEntity, json: Json): SyncRecord? =
    runCatching { json.decodeFromString<SyncRecord>(row.payloadJson) }
        .onFailure { Log.w(TAG, "Dropping unparseable pulled record ${row.sourceDeviceId}#${row.lineNumber} — stale wire format?", it) }
        .getOrNull()

// null-safe maxOf for two independently-nullable timestamps, where "one side has no signal at
// all" should defer entirely to the other rather than being treated as smaller/earlier — see
// RaceHistoryDetailViewModel's own identical private helper for the same reasoning.
private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

// Per-device delta filtering: each device's records are compared against that same device's own
// already-stored max lineNumber (status[deviceName], 0 if the server has nothing for it yet) — no
// more Bibs/Time split, since the server no longer stores one either (lineLabel's B/T prefix
// already carries that distinction end to end). Pulled out as a pure function so this logic can
// be tested directly, without faking pushToServer's network round-trip.
//
// A device named in [startingFreshDevices] (see pushToServer's own doc for exactly how that's
// computed — `status[deviceName]` reporting a max the caller itself could never legitimately
// have produced, the signature of stale leftover data from a different, since-superseded race
// that used to share this exact label) is the one deliberate exception to the delta filter
// below: its own fresh race's lineNumbers (1, 2, 3…, including its own NewRace marker — see
// HistoryAction.NEW_RACE's own doc) may be lower than that stale max — filtering by it would
// silently drop the entire new race, marker included, and it would never reach the server at
// all. Sending that device's full record set unfiltered in that case is what actually
// guarantees delivery; the server's own merge logic (see server/routes/mobile.js) is what wipes
// its stale file on seeing the marker. The bypass is self-limiting: once that wipe-and-replace
// lands, the server's own next-reported status can no longer exceed the caller's real max, so
// the caller naturally stops naming this device on its own, with no confirmation-state
// bookkeeping needed.
internal fun recordsDueForDevices(
    byDevice: Map<String, List<SyncRecord>>,
    status: Map<String, Long>,
    startingFreshDevices: Set<String> = emptySet(),
): Map<String, List<SyncRecord>> =
    byDevice
        .mapValues { (deviceName, records) ->
            if (deviceName in startingFreshDevices) records
            else records.filter { it.lineNumber > (status[deviceName] ?: 0) }
        }
        .filterValues { it.isNotEmpty() }
