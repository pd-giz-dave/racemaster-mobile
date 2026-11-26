package mobile.racemaster.data.peersync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mobile.racemaster.data.db.dao.PeerOriginSummary
import mobile.racemaster.data.db.dao.PeerSeenRecordDao
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity
import mobile.racemaster.data.mule.SyncRecord

/**
 * Same-mode peer-sync feature — merges a same-group peer's own [SyncRecord]s (pulled directly from
 * it, exactly the way an ordinary relay pull already works, just over its own accelerated
 * connection — see [PeerSyncEngine]) into [PeerSeenRecordEntity], and serves the union queries
 * each mode's own ViewModel reads alongside its local rows. [mergeRecords] is called from
 * [PeerSyncEngine] — never [mobile.racemaster.data.mule.MuleSyncEngine], which has no knowledge of
 * this feature at all (see [PeerSyncEngine]'s own doc for why that independence matters).
 *
 * Deliberately a separate class from [mobile.racemaster.data.mule.MuleRepository], the same way
 * [mobile.racemaster.data.mule.ProgressRepository] is: this is a wholly different concern
 * (same-mode peer convergence) from Mule's own pull-and-relay-to-server machinery, even though
 * both ultimately ride the same GATT connection primitives.
 */
class PeerSyncRepository(
    private val peerSeenRecordDao: PeerSeenRecordDao,
) {
    /** Collapses a convergence group's three parts — [groupRaceId]/[mode]/[locationCode] (see
     *  [mobile.racemaster.data.mule.MuleGattProfile.AdvertisedIdentity]'s own doc for what each
     *  means and why Bibs alone needs the location half) — into the one opaque string
     *  [PeerSeenRecordEntity.raceGroupId] stores and every query below is scoped by. [locationCode]
     *  is only ever meaningful for [HistoryMode.BIBS] (Count/Close each already imply a fixed
     *  station via mode alone); always pass 0 for any other mode so two Count phones on the same
     *  race still land in the same group regardless of whatever locationCode either happened to
     *  last advertise. */
    fun groupKey(groupRaceId: Int, mode: HistoryMode, locationCode: Byte): String {
        val effectiveLocationCode = if (mode == HistoryMode.BIBS) locationCode else 0
        return "$groupRaceId:${mode.name}:$effectiveLocationCode"
    }

    /** The resume cursor for the next accelerated pull from [originDeviceId] — the same
     *  delta-sync shape [mobile.racemaster.data.mule.MuleRepository.lastPulledLineNumber] already
     *  uses for the ordinary relay-inbox path, just scoped to this merge table instead. 0 (treated
     *  as "nothing merged yet, request everything") the first time. */
    suspend fun lastMergedLineNumber(originDeviceId: String): Long =
        peerSeenRecordDao.getLastMergedLineNumber(originDeviceId) ?: 0L

    /** Count Mode's own cursor — see [PeerSeenRecordDao.getLastMergedCountStatusLineNumber]'s own
     *  doc for why this must be a separate query from [lastMergedLineNumber] rather than reusing
     *  it. Compared against [mobile.racemaster.data.mule.DeviceInfo.lastCountStatusLineNumber],
     *  never [mobile.racemaster.data.mule.DeviceInfo.lastLineNumber]. */
    suspend fun lastMergedCountStatusLineNumber(originDeviceId: String): Long =
        peerSeenRecordDao.getLastMergedCountStatusLineNumber(originDeviceId) ?: 0L

    /** Merges a batch of a peer's own [SyncRecord]s, already known (by [PeerSyncEngine], via
     *  [PeerGroupDetector.groupKeyOf]) to belong to [raceGroupId] — see [groupKey].
     *  [SyncRecord.bibNumber] already carries the Bibs/Finish dedup key as a real field;
     *  [extractBeaconNumber] below additionally parses Count Mode's own "beacon=N ..." convention
     *  out of `note` (see [mobile.racemaster.data.repository.CountModeRepository.parseBeaconCount],
     *  which does the same for purely local rows — SyncRecord has no dedicated beaconNumber field
     *  of its own) once here, at merge time, rather than leaving every later query to re-parse it.
     *  Insert-only, no dedup at write time (see [PeerSeenRecordDao.insertAll]'s own doc for why
     *  that's safe to defer to read time). */
    suspend fun mergeRecords(
        raceGroupId: String,
        mode: HistoryMode,
        originDeviceId: String,
        originDeviceName: String,
        records: List<SyncRecord>,
    ) {
        if (records.isEmpty()) return
        val rows = records.map { record ->
            PeerSeenRecordEntity(
                raceGroupId = raceGroupId,
                mode = mode,
                originDeviceId = originDeviceId,
                originDeviceName = originDeviceName,
                lineNumber = record.lineNumber,
                action = record.action,
                bibNumber = record.bibNumber,
                beaconNumber = extractBeaconNumber(record.note),
                splitNumber = record.splitNumber,
                note = record.note,
                timestampMillis = record.timestampMillis,
            )
        }
        peerSeenRecordDao.insertAll(rows)
    }

    /** Every beacon any peer in [raceGroupId]/[mode] has reported seen — the peer half of Count
     *  Mode's union view (see [mobile.racemaster.data.repository.CountModeRepository]'s own
     *  local-only equivalent for the half this joins with). */
    fun observeSeenBeaconNumbers(raceGroupId: String, mode: HistoryMode): Flow<Set<Int>> =
        peerSeenRecordDao.observeSeenBeaconNumbers(raceGroupId, mode).map { it.toSet() }

    /** Every bib any peer in [raceGroupId]/[mode] has reported — the peer half of the Bibs/Finish
     *  union view. */
    fun observeSeenBibNumbers(raceGroupId: String, mode: HistoryMode): Flow<Set<Int>> =
        peerSeenRecordDao.observeSeenBibNumbers(raceGroupId, mode).map { it.toSet() }

    /** Every row currently merged for [raceGroupId]/[mode], in real chronological order — what a
     *  cross-device duplicate check (extending
     *  [mobile.racemaster.data.repository.BibValidation]'s generic core) walks alongside the local
     *  rows it already covers. */
    fun observeGroup(raceGroupId: String, mode: HistoryMode): Flow<List<PeerSeenRecordEntity>> =
        peerSeenRecordDao.observeGroup(raceGroupId, mode)

    /** One specific device's own raw rows within [raceGroupId]/[mode] — what a "Peers" list
     *  item's own detail screen shows (PeerActivityDetailScreen). */
    fun observeOrigin(raceGroupId: String, mode: HistoryMode, originDeviceId: String): Flow<List<PeerSeenRecordEntity>> =
        peerSeenRecordDao.observeForOrigin(raceGroupId, mode, originDeviceId)

    /** Race History's own "Peers" list items — see [PeerOriginSummary]'s own doc. */
    fun observeOriginSummaries(): Flow<List<PeerOriginSummary>> = peerSeenRecordDao.observeOriginSummaries()

    /** TODO.md's own "the activity history should be ... with the same dustbin for delete" —
     *  see [PeerSeenRecordDao.deleteGroupOrigin]'s own doc for why clearing just this one
     *  origin's rows is enough to make the next pull from it start fresh. */
    suspend fun deleteGroupOrigin(raceGroupId: String, mode: HistoryMode, originDeviceId: String) =
        peerSeenRecordDao.deleteGroupOrigin(raceGroupId, mode, originDeviceId)

    /** Every beacon any peer in [raceGroupId]/[mode] has reported, mapped to whichever peer
     *  reported it *most recently* — what
     *  [mobile.racemaster.ui.countmode.CountModeViewModel] tags a peer-sourced "seen" entry with
     *  in the UI. Collapses to one origin per beacon even if several different peers have reported
     *  the same one; see [observeForBeacon] for the full, un-collapsed history if that's ever
     *  needed instead. */
    fun observeLatestOriginByBeacon(raceGroupId: String, mode: HistoryMode): Flow<Map<Int, String>> =
        observeGroup(raceGroupId, mode).map { rows -> latestOriginByKey(rows) { it.beaconNumber } }

    /** The Bibs/Finish counterpart of [observeLatestOriginByBeacon] — every bib any peer in
     *  [raceGroupId]/[mode] has reported, mapped to its most recent reporting peer. Feeds
     *  [mobile.racemaster.ui.bibsmode.BibsModeViewModel]'s "already recorded by a peer" warning
     *  (the existing [mobile.racemaster.data.repository.unexpectedBibWarning] warn-don't-block
     *  convention, reused rather than a second UI mechanism). */
    fun observeLatestOriginByBib(raceGroupId: String, mode: HistoryMode): Flow<Map<Int, String>> =
        observeGroup(raceGroupId, mode).map { rows -> latestOriginByKey(rows) { it.bibNumber } }

    companion object {
        private fun <K> latestOriginByKey(rows: List<PeerSeenRecordEntity>, keyOf: (PeerSeenRecordEntity) -> K?): Map<K, String> =
            rows.mapNotNull { row -> keyOf(row)?.let { it to row } }
                .groupBy({ (key, _) -> key }, { (_, row) -> row })
                .mapValues { (_, group) -> group.maxBy { it.timestampMillis }.originDeviceName }

        // Mirrors CountModeRepository.parseBeaconCount's own "beacon=N ..." note convention for
        // COUNT_SEEN/COUNT_EJECTED/COUNT_UNEJECTED rows — see that function's own doc. Duplicated
        // here (rather than shared) because that function is private to CountModeRepository and
        // scoped to its own HistoryLineEntity rows, not this table's SyncRecord-shaped input.
        private val BEACON_NOTE_PATTERN = Regex("""beacon[:=](\d+)""")

        private fun extractBeaconNumber(note: String?): Int? =
            note?.let { BEACON_NOTE_PATTERN.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }
}
