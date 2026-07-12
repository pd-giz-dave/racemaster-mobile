package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RaceDao {
    @Insert
    suspend fun insert(race: RaceEntity): Long

    @Query("SELECT * FROM races WHERE id = :id")
    suspend fun getById(id: Long): RaceEntity?

    @Query("SELECT * FROM races WHERE id = :id")
    fun observeById(id: Long): Flow<RaceEntity?>

    @Query("SELECT * FROM races ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<RaceEntity>>

    @Query("UPDATE races SET timeModeNextSplit = timeModeNextSplit + 1 WHERE id = :raceId")
    suspend fun incrementTimeCounter(raceId: Long)

    @Query("UPDATE races SET timeModeNextSplit = timeModeNextSplit - 1 WHERE id = :raceId")
    suspend fun decrementTimeCounter(raceId: Long)

    @Query("UPDATE races SET bibsModeNextSplit = bibsModeNextSplit + 1 WHERE id = :raceId")
    suspend fun incrementBibsCounter(raceId: Long)

    @Query("UPDATE races SET bibsModeNextSplit = bibsModeNextSplit - 1 WHERE id = :raceId")
    suspend fun decrementBibsCounter(raceId: Long)

    @Query("UPDATE races SET timeModeStartedAtMillis = :startedAtMillis WHERE id = :raceId")
    suspend fun setTimeModeStartedAt(raceId: Long, startedAtMillis: Long)

    @Query("UPDATE races SET timeModeStoppedAtMillis = :stoppedAtMillis WHERE id = :raceId")
    suspend fun setTimeModeStoppedAt(raceId: Long, stoppedAtMillis: Long)

    @Query("UPDATE races SET timeModeStartedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearTimeModeStartedAt(raceId: Long)

    @Query("UPDATE races SET timeModeStoppedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearTimeModeStoppedAt(raceId: Long)

    @Query(
        "UPDATE races SET timeModeNextSplit = 1, timeModeStartedAtMillis = NULL, " +
            "timeModeStoppedAtMillis = NULL WHERE id = :raceId",
    )
    suspend fun resetTimeMode(raceId: Long)

    @Query("UPDATE races SET bibsModeStoppedAtMillis = :stoppedAtMillis WHERE id = :raceId")
    suspend fun setBibsModeStoppedAt(raceId: Long, stoppedAtMillis: Long)

    @Query("UPDATE races SET bibsModeStoppedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearBibsModeStoppedAt(raceId: Long)

    @Query("UPDATE races SET bibsModeNextSplit = 1, bibsModeStoppedAtMillis = NULL WHERE id = :raceId")
    suspend fun resetBibsMode(raceId: Long)

    // Editable at any time via the race details screen, including after the race has
    // stopped — name/course typos shouldn't be permanently locked in once logging is done.
    // The date portion of the label is deliberately not touched here, since it stays fixed to
    // when the race was originally created. bibsRangeStart/bibsRangeCount are included here
    // too, but the screen only actually lets them change while the race is still "fresh" (no
    // real splits/entries recorded) — otherwise it just writes back the same values it read.
    // serverUrl is deliberately NOT touched here — it's not exposed on this screen (it'll
    // live under Mule Mode setup eventually), so an edit here must never clobber it.
    @Query(
        "UPDATE races SET name = :name, course = :course, label = :label, " +
            "bibsRangeStart = :bibsRangeStart, bibsRangeCount = :bibsRangeCount WHERE id = :raceId",
    )
    suspend fun updateDetails(
        raceId: Long,
        name: String,
        course: String,
        label: String,
        bibsRangeStart: Int?,
        bibsRangeCount: Int?,
    )
}