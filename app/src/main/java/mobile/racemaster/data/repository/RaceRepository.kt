package mobile.racemaster.data.repository

import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.LineSyncDao
import mobile.racemaster.data.db.dao.PulledRecordDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.LineSyncEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow

class RaceRepository(
    private val raceDao: RaceDao,
    private val historyLineDao: HistoryLineDao,
    private val lineSyncDao: LineSyncDao,
    private val pulledRecordDao: PulledRecordDao,
    private val settingsRepository: SettingsRepository,
    private val bibsModeRepository: BibsModeRepository,
) {
    // bibsRangeStart/bibsRangeCount are collected on the race details form for Time Mode too
    // now (form parity with Bibs), even though Time itself never reads bibsRangeStart for
    // anything — it's inert data there, kept only so both modes' forms/feedback stay identical.
    suspend fun startNewRace(
        name: String,
        course: String,
        createdAtMillis: Long = System.currentTimeMillis(),
        deviceRole: String? = null,
        serverUrl: String? = null,
        bibsRangeStart: Int? = null,
        bibsRangeCount: Int? = null,
    ): Long =
        raceDao.insert(
            RaceEntity(
                name = name,
                course = course,
                label = buildRaceLabel(name, course, createdAtMillis),
                createdAtMillis = createdAtMillis,
                deviceRole = deviceRole,
                serverUrl = serverUrl,
                bibsRangeStart = bibsRangeStart,
                bibsRangeCount = bibsRangeCount,
                createdByDeviceName = settingsRepository.getOrCreateDeviceName(),
            ),
        )

    // The date portion of the label is rebuilt from the race's original createdAtMillis, not
    // the edit time — the date is always auto-derived and fixed once the race is created.
    // bibsRangeStart/bibsRangeCount are only ever actually *changed* by the caller while the
    // race is still fresh (see RaceDetailsScreen) — otherwise it just writes back what was
    // already there. serverUrl is untouched here — it's not on this screen (see
    // RaceDao.updateDetails).
    //
    // A changed label also re-homes this device's own Mule-inbox rows (see
    // PulledRecordDao.retagSourceRaceLabel's doc) from the old label onto the new one — without
    // this, MuleRepository.pushToServer keeps grouping already-pulled history under the race's
    // old name forever, so the new name's file on the server would only ever receive whatever's
    // recorded after the rename instead of this race's complete history.
    suspend fun updateRaceDetails(
        raceId: Long,
        name: String,
        course: String,
        bibsRangeStart: Int?,
        bibsRangeCount: Int?,
    ) {
        val race = raceDao.getById(raceId) ?: return
        val label = buildRaceLabel(name, course, race.createdAtMillis)
        raceDao.updateDetails(raceId, name, course, label, bibsRangeStart, bibsRangeCount)
        if (label != race.label) {
            val myDeviceId = settingsRepository.getOrCreateDeviceId()
            pulledRecordDao.retagSourceRaceLabel(myDeviceId, oldLabel = race.label, newLabel = label)
        }
    }

    fun observeRace(id: Long): Flow<RaceEntity?> = raceDao.observeById(id)

    suspend fun getRace(id: Long): RaceEntity? = raceDao.getById(id)

    fun observeAllRaces(): Flow<List<RaceEntity>> = raceDao.observeAll()

    // Permanently erases a race and its full history — RaceDao.deleteById's own FK cascade
    // takes history_lines with it; line_syncs has no such cascade so is cleared explicitly
    // here too. Irreversible, gated behind RaceHistoryScreen's own confirmation dialog before
    // this is ever called. Refuses to delete the race only while it's active per
    // isRaceCurrentlyActive (the one centralized definition — see its own doc) — a race
    // that's merely selected/defined but never started, or one that's been stopped *and*
    // Reset, is fair game, same as changing the device name is. RaceHistoryScreen already
    // disables the delete action accordingly, this is the backstop that holds regardless of
    // how deleteRace ends up called.
    suspend fun deleteRace(raceId: Long) {
        if (isRaceCurrentlyActive(raceId, this, bibsModeRepository)) return
        lineSyncDao.deleteForRace(raceId)
        raceDao.deleteById(raceId)
    }

    // Resolves a self-originated pulled record's sourceRaceLabel back to this device's own
    // local race — see MuleRepository.pushToServer's server-sync line attribution.
    suspend fun getRaceByLabel(label: String): RaceEntity? = raceDao.getByLabel(label)

    // Cross-mode facade: the only two places that need to see a race's Time AND Bibs rows
    // together, rather than through TimeModeRepository/BibsModeRepository's per-mode views.
    //
    // Full, permanent history across every segment and BOTH modes — Race History's one true
    // chronology (see RaceHistoryDetailViewModel), replacing what used to be two separately-
    // sorted "Bib entries"/"Time splits" lists.
    fun observeHistory(raceId: Long): Flow<List<HistoryLineEntity>> = historyLineDao.observeAllForRace(raceId)

    // Delta-sync snapshot for a Mule pull request — every row past the requester's already-
    // known line number, spanning every segment of BOTH modes this device has recorded.
    // Deliberately not scoped to whichever AppMode screen happens to be showing (see
    // PeripheralSyncService.streamRecords) — a mixed-mode race must sync everything it holds
    // over BLE regardless of which mode the operator currently has open.
    suspend fun getHistorySinceLineNumber(raceId: Long, sinceLineNumber: Long): List<HistoryLineEntity> =
        historyLineDao.getSinceLineNumber(raceId, sinceLineNumber)

    // Mode-agnostic: a batch of acked recordUuids is inherently already scoped to whatever
    // was actually streamed out by streamRecords, regardless of mode — see
    // PeripheralSyncService.markSynced.
    suspend fun markHistorySyncedByUuid(recordUuids: List<String>, syncedAtMillis: Long = System.currentTimeMillis()) {
        if (recordUuids.isEmpty()) return
        historyLineDao.markSynced(recordUuids, syncedAtMillis)
    }

    suspend fun getHistoryLineNumbersForUuids(recordUuids: List<String>): List<Long> =
        if (recordUuids.isEmpty()) emptyList() else historyLineDao.getLineNumbersForUuids(recordUuids)

    // Per-line "synced to" feedback for a local race — see LineSyncEntity's doc for why this
    // is deliberately simple bookkeeping, not a gossip/multi-hop relay.
    fun observeLineSyncs(raceId: Long): Flow<List<LineSyncEntity>> = lineSyncDao.observeForRace(raceId)

    suspend fun recordLineSyncs(
        raceId: Long,
        lineNumbers: List<Long>,
        targetId: String,
        targetName: String,
        syncedAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (lineNumbers.isEmpty()) return
        lineSyncDao.insertAll(
            lineNumbers.map {
                LineSyncEntity(raceId = raceId, lineNumber = it, targetId = targetId, targetName = targetName, syncedAtMillis = syncedAtMillis)
            },
        )
    }
}
