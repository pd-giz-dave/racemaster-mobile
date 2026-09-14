package mobile.racemaster.data.mule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import mobile.racemaster.data.db.dao.ProgressDao
import mobile.racemaster.data.db.dao.TargetedProgressDao
import mobile.racemaster.data.db.entity.ProgressEntity
import mobile.racemaster.data.db.entity.TargetedProgressEntity

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
class ProgressRepository(
    private val syncClient: MuleSyncClient,
    private val progressDao: ProgressDao,
    private val targetedProgressDao: TargetedProgressDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ONLY the currently active race's own most-recently-received copy — never a list, never
    // history. This is what DeviceInfo.progressGeneratedAt/propagation read; the Races page
    // reads observeStored()/getStored() below instead, which survive a race switch this doesn't.
    private val _current = MutableStateFlow<StoredProgress?>(null)
    val current: StateFlow<StoredProgress?> = _current.asStateFlow()

    /** [PeripheralSyncService]'s progress-write handler calls this once a chunked BLE delivery is
     *  fully reassembled and decoded. [payload.entries] may be a delta (only what changed since
     *  this device's own last-reported progressGeneratedAt — see mule-ble.js's own deliverProgress
     *  doc) rather than the whole race, so it's merged into whatever's already stored for
     *  [raceId], not treated as the complete set — see [mergeEntries]'s own doc. */
    suspend fun storeFromBle(raceId: Long, raceLabel: String, payload: ProgressPayload) {
        val merged = mergeEntries(existingEntries(raceId), payload.entries)
        store(StoredProgress(raceId, raceLabel, payload.generatedAt, payload.raceName, payload.raceDate, merged))
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
     *  already cached for this exact race as the bandwidth-saving/delta-cursor hint (see
     *  [MuleSyncClient.getProgress]'s own doc) — a no-op (network call still happens, but nothing
     *  is overwritten) when the server reports `unchanged`. Otherwise [response.entries] is a
     *  delta once a hint was actually sent, merged into whatever's already stored for [raceId]
     *  rather than replacing it outright — see [mergeEntries]'s own doc, including the one gap
     *  that merge can't close on its own (a bib removed server-side since the last fetch has no
     *  way to signal that on this read path yet — the write path's own `removed` list, see
     *  racemaster's server/mobile.js mergeProgress, has no fetch-side counterpart; a genuinely
     *  stale/removed bib can linger in a phone's local copy until this race's progress is deleted
     *  and re-synced from scratch. Rare enough in practice — an admin deleting a registered
     *  runner mid-race — and low-enough-stakes — phase 4's auto-expectation lists aren't built on
     *  this data yet — to accept rather than build full tombstone tracking for right now). Swallows
     *  any failure (unreachable server, etc.) the same best-effort way [MuleRepository]'s own
     *  server-facing calls do. */
    suspend fun refreshFromServer(baseUrl: String, token: String, raceId: Long, raceLabel: String) {
        val known = generatedAtFor(raceId)
        val response = runCatching { syncClient.getProgress(baseUrl, token, raceLabel, known) }.getOrNull() ?: return
        if (!response.unchanged) {
            val merged = mergeEntries(existingEntries(raceId), response.entries)
            store(StoredProgress(raceId, raceLabel, response.generatedAt.orEmpty(), response.raceName, response.raceDate, merged))
        }
    }

    // The Room-persisted copy, not [current] — correct even across an app restart or a race
    // that isn't the currently-active one (refreshFromServer is only ever called for the active
    // race today, but this stays correct regardless of that happening to be true).
    private suspend fun existingEntries(raceId: Long): List<ProgressEntry> =
        progressDao.getByRaceId(raceId)?.toStoredProgress()?.entries.orEmpty()

    // Upserts [changed] into [existing] by bibNumber — a changed bib replaces its old entry, a
    // new bib is added, everything else is left exactly as it was. Mirrors racemaster's own
    // server/mobile.js mergeProgress (the write-side counterpart), just with no equivalent of its
    // `removed` list on this read path — see refreshFromServer's own doc for that gap.
    private fun mergeEntries(existing: List<ProgressEntry>, changed: List<ProgressEntry>): List<ProgressEntry> {
        if (changed.isEmpty()) return existing
        val byBib = LinkedHashMap<Int, ProgressEntry>(existing.size + changed.size)
        for (entry in existing) byBib[entry.bibNumber] = entry
        for (entry in changed) byBib[entry.bibNumber] = entry
        return byBib.values.toList()
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

    // --- Phase 3: targeted-relay inbox — see TargetedProgressEntity's own doc -----------------

    /** [PeripheralSyncService]'s progress-write handler calls this instead of [storeFromBle] when
     *  an inbound payload's own [ProgressPayload.targetDeviceId] names some OTHER device — cached
     *  verbatim (already in whatever delta form the sender computed it against) rather than
     *  merged into anything, since this device has no race of its own to merge it into; it's
     *  purely in transit. Replaces any already-cached entry for the same target (see the entity's
     *  own doc for why an older superseded delta is never worth keeping alongside a newer one). */
    suspend fun cacheTargetedProgress(targetDeviceId: String, payload: ProgressPayload) {
        targetedProgressDao.upsert(
            TargetedProgressEntity(
                targetDeviceId = targetDeviceId,
                payloadJson = json.encodeToString(payload),
                receivedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    /** Whatever this device is currently holding to forward on to [targetDeviceId] — null if
     *  nothing's cached, or what's cached is older than [maxAgeDays] (the same
     *  [SettingsRepository.raceStaleAfterDays] cutoff [PeripheralSyncService]'s own relay
     *  manifest freshness already uses — see [isRaceStale]). Read, not consumed:
     *  [evictTargetedProgress] is the caller's own job once a delivery genuinely succeeds,
     *  mirroring [mobile.racemaster.data.db.entity.PulledRecordEntity]'s own
     *  read-then-separately-confirm shape — see [mobile.racemaster.data.mule.MuleSyncEngine]'s
     *  own call sites for why a failed handoff deliberately leaves the entry alone instead
     *  (retried on the very next tick, no special-casing needed). */
    suspend fun pendingTargetedProgress(targetDeviceId: String, maxAgeDays: Int): ProgressPayload? {
        val row = targetedProgressDao.getByTarget(targetDeviceId) ?: return null
        if (isRaceStale(row.receivedAtMillis, maxAgeDays)) return null
        return runCatching { json.decodeFromString<ProgressPayload>(row.payloadJson) }.getOrNull()
    }

    /** Clears a targeted-relay inbox entry once its own handoff genuinely succeeds — see
     *  [pendingTargetedProgress]'s own doc; the one place an entry actually leaves the inbox
     *  short of it simply going stale (handled by [pendingTargetedProgress]'s own filter, no
     *  separate sweep needed). */
    suspend fun evictTargetedProgress(targetDeviceId: String) {
        targetedProgressDao.deleteByTarget(targetDeviceId)
    }
}
