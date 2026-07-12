package mobile.racemaster.data.repository

import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow

class RaceRepository(
    private val raceDao: RaceDao,
    private val settingsRepository: SettingsRepository,
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
    }

    fun observeRace(id: Long): Flow<RaceEntity?> = raceDao.observeById(id)

    suspend fun getRace(id: Long): RaceEntity? = raceDao.getById(id)

    fun observeAllRaces(): Flow<List<RaceEntity>> = raceDao.observeAll()
}
