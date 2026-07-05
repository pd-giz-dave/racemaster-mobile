package mobile.racemaster.data.repository

import androidx.room.withTransaction
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.dao.BibEntryDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.BIB_REQUIRED_TYPES
import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.flow.Flow

class BibsModeRepository(
    private val db: RacemasterDatabase,
    private val raceDao: RaceDao,
    private val bibEntryDao: BibEntryDao,
) {
    fun observeEntries(raceId: Long): Flow<List<BibEntryEntity>> = bibEntryDao.observeForRace(raceId)

    fun observeUnsyncedCount(raceId: Long): Flow<Int> = bibEntryDao.observeUnsyncedCountForRace(raceId)

    fun observeLastSyncedAtMillis(raceId: Long): Flow<Long?> = bibEntryDao.observeLastSyncedAtMillis(raceId)

    // One-shot snapshot for Mule's BLE pull, not a live subscription.
    suspend fun getUnsyncedEntries(raceId: Long): List<BibEntryEntity> = bibEntryDao.getUnsyncedForRace(raceId)

    // Every entry for the race regardless of sync state — used to resend the full set to the
    // server rather than just the delta.
    suspend fun getAllEntries(raceId: Long): List<BibEntryEntity> = bibEntryDao.getAllForRace(raceId)

    suspend fun markEntriesSyncedByUuid(recordUuids: List<String>, syncedAtMillis: Long = System.currentTimeMillis()) {
        if (recordUuids.isEmpty()) return
        bibEntryDao.markSynced(recordUuids, syncedAtMillis)
    }

    // The only entry point for creating a Bibs race: atomically writes the race row (with its
    // bib range) and the fixed Clock marker (split #0, doesn't consume the counter) so the two
    // can never exist independently of each other, e.g. if the process dies mid-creation.
    suspend fun createRaceWithClockMarker(
        label: String,
        bibsRangeStart: Int,
        bibsRangeCount: Int,
        createdAtMillis: Long = System.currentTimeMillis(),
        deviceRole: String? = null,
    ): Long = db.withTransaction {
        val raceId = raceDao.insert(
            RaceEntity(
                label = label,
                createdAtMillis = createdAtMillis,
                bibsRangeStart = bibsRangeStart,
                bibsRangeCount = bibsRangeCount,
                deviceRole = deviceRole,
            ),
        )
        bibEntryDao.insert(
            BibEntryEntity(
                raceId = raceId,
                bibNumber = null,
                type = BibEntryType.CLOCK,
                splitNumber = CLOCK_SPLIT_NUMBER,
                note = null,
                timestampMillis = createdAtMillis,
            ),
        )
        raceId
    }

    suspend fun recordEntry(
        raceId: Long,
        type: BibEntryType,
        bibNumber: Int?,
        note: String?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = race.bibsModeNextSplit
            raceDao.incrementBibsCounter(raceId)
            bibEntryDao.insert(
                BibEntryEntity(
                    raceId = raceId,
                    bibNumber = if (type in BIB_REQUIRED_TYPES) bibNumber else null,
                    type = type,
                    splitNumber = splitNumber,
                    note = note,
                    timestampMillis = timestampMillis,
                ),
            )
        }
    }

    // splitNumber is assigned once at creation and is never touched here — it stays stable
    // across edits, exactly like Time Mode's split-label editing never touches its number.
    suspend fun updateEntry(id: Long, bibNumber: Int?, type: BibEntryType, note: String?) {
        bibEntryDao.update(id, if (type in BIB_REQUIRED_TYPES) bibNumber else null, type, note)
    }

    suspend fun deleteMostRecent(raceId: Long) {
        db.withTransaction {
            val latest = bibEntryDao.getLatest(raceId) ?: return@withTransaction
            if (latest.type == BibEntryType.CLOCK) return@withTransaction
            bibEntryDao.delete(latest)
            if (latest.type == BibEntryType.STOP) {
                raceDao.clearBibsModeStoppedAt(raceId)
            }
            raceDao.decrementBibsCounter(raceId)
        }
    }

    suspend fun stopBibsMode(raceId: Long, stoppedAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val race = requireNotNull(raceDao.getById(raceId)) { "Race $raceId not found" }
            val splitNumber = race.bibsModeNextSplit
            raceDao.incrementBibsCounter(raceId)
            raceDao.setBibsModeStoppedAt(raceId, stoppedAtMillis)
            bibEntryDao.insert(
                BibEntryEntity(
                    raceId = raceId,
                    bibNumber = null,
                    type = BibEntryType.STOP,
                    splitNumber = splitNumber,
                    note = null,
                    timestampMillis = stoppedAtMillis,
                ),
            )
        }
    }

    // No separate "press Start to begin" screen exists for Bibs mode, so Reset must leave it
    // immediately ready to log again — re-inserting Clock keeps that invariant true after a
    // reset, same as it's kept true at creation.
    suspend fun resetBibsMode(raceId: Long, resetAtMillis: Long = System.currentTimeMillis()) {
        db.withTransaction {
            bibEntryDao.deleteAllForRace(raceId)
            raceDao.resetBibsMode(raceId)
            bibEntryDao.insert(
                BibEntryEntity(
                    raceId = raceId,
                    bibNumber = null,
                    type = BibEntryType.CLOCK,
                    splitNumber = CLOCK_SPLIT_NUMBER,
                    note = null,
                    timestampMillis = resetAtMillis,
                ),
            )
        }
    }

    companion object {
        const val CLOCK_SPLIT_NUMBER = 0
    }
}
