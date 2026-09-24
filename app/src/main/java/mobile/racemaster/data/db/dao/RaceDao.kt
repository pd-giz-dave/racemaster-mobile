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

    // Permanently removes the race — HistoryLineEntity's ForeignKey(onDelete = CASCADE) takes
    // its full history down with it in the same statement; nothing else in Room references
    // raceId, so this is the only query needed to fully erase a race locally. Irreversible —
    // gated behind RaceHistoryScreen's own confirmation dialog, not enforced here.
    @Query("DELETE FROM races WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM races WHERE id = :id")
    suspend fun getById(id: Long): RaceEntity?

    @Query("SELECT * FROM races WHERE id = :id")
    fun observeById(id: Long): Flow<RaceEntity?>

    @Query("SELECT * FROM races ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<RaceEntity>>

    // The newest local race under [label] — Setup Race continues this one rather than creating a
    // duplicate (see RaceRepository.startOrContinueRace). Newest-first because a device can still
    // hold older same-label duplicates created before that rule existed.
    @Query("SELECT * FROM races WHERE label = :label ORDER BY createdAtMillis DESC, id DESC LIMIT 1")
    suspend fun getByLabel(label: String): RaceEntity?

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

    @Query("UPDATE races SET timeModeStartedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearTimeModeStartedAt(raceId: Long)

    @Query("UPDATE races SET timeModeNextSplit = 1, timeModeStartedAtMillis = NULL WHERE id = :raceId")
    suspend fun resetTimeMode(raceId: Long)

    @Query("UPDATE races SET bibsModeStartedAtMillis = :startedAtMillis WHERE id = :raceId")
    suspend fun setBibsModeStartedAt(raceId: Long, startedAtMillis: Long)

    @Query("UPDATE races SET bibsModeStartedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearBibsModeStartedAt(raceId: Long)

    // Clears bibsModeStartedAtMillis too — this is what returns the screen to its pre-Start
    // state (see RaceEntity.bibsModeStartedAtMillis's own doc).
    @Query("UPDATE races SET bibsModeNextSplit = 1, bibsModeStartedAtMillis = NULL WHERE id = :raceId")
    suspend fun resetBibsMode(raceId: Long)

    @Query("UPDATE races SET cpModeNextSplit = cpModeNextSplit + 1 WHERE id = :raceId")
    suspend fun incrementCpCounter(raceId: Long)

    @Query("UPDATE races SET cpModeNextSplit = cpModeNextSplit - 1 WHERE id = :raceId")
    suspend fun decrementCpCounter(raceId: Long)

    @Query("UPDATE races SET cpModeStartedAtMillis = :startedAtMillis WHERE id = :raceId")
    suspend fun setCpModeStartedAt(raceId: Long, startedAtMillis: Long)

    @Query("UPDATE races SET cpModeStartedAtMillis = NULL WHERE id = :raceId")
    suspend fun clearCpModeStartedAt(raceId: Long)

    // Clears cpModeStartedAtMillis too — this is what returns the screen to its pre-Start state
    // (see RaceEntity.cpModeStartedAtMillis's own doc).
    @Query("UPDATE races SET cpModeNextSplit = 1, cpModeStartedAtMillis = NULL WHERE id = :raceId")
    suspend fun resetCpMode(raceId: Long)

    // The permanent, race-wide history line counter — see RaceEntity.nextLineNumber. Only
    // ever incremented, including across a Reset (unlike the display counters above).
    @Query("UPDATE races SET nextLineNumber = nextLineNumber + 1 WHERE id = :raceId")
    suspend fun incrementLineNumber(raceId: Long)

    // Editable at any time via the race details screen, including after the race has
    // stopped — name typos shouldn't be permanently locked in once logging is done. Only the
    // name and location (and `label`'s name portion, rebuilt from it) can actually change here.
    // The date portion of the label is deliberately not touched here either, since it stays fixed to
    // when the race was originally created. serverUrl is deliberately NOT touched here — it's
    // not exposed on this screen (it'll live under Mule Mode setup eventually), so an edit here
    // must never clobber it.
    @Query("UPDATE races SET name = :name, location = :location, label = :label WHERE id = :raceId")
    suspend fun updateDetails(raceId: Long, name: String, location: String, label: String)

    // Relocation's own counter reset/restore — deliberately narrower than resetTimeMode/
    // resetBibsMode/resetCpMode above, which also clear started/stoppedAtMillis and would wrongly
    // kick an in-progress mode back to pre-Start state. A relocation mid-recording only needs the
    // display counter itself to move (forward: reset to 1 after saving the old value into the new
    // marker row's own priorSplitCounter; on undo: restored back to that saved value) — see
    // RaceRepository.recordModeStart and EntryLogModeEngine/TimeModeRepository's own
    // undoMostRecent LOCATION branch.
    @Query("UPDATE races SET timeModeNextSplit = :value WHERE id = :raceId")
    suspend fun setTimeModeNextSplit(raceId: Long, value: Int)

    @Query("UPDATE races SET bibsModeNextSplit = :value WHERE id = :raceId")
    suspend fun setBibsModeNextSplit(raceId: Long, value: Int)

    @Query("UPDATE races SET cpModeNextSplit = :value WHERE id = :raceId")
    suspend fun setCpModeNextSplit(raceId: Long, value: Int)

    // RaceRepository.recordModeStart's own mode+location update — used both for its forward
    // write (Setup Race / Relocate choosing a new mode/location) and, with the *previous*
    // mode/location instead, to restore both when a LOCATION marker is undone (see
    // EntryLogModeEngine/TimeModeRepository's own LOCATION-undo branch). Narrower than
    // updateDetails above, which also touches name/label; neither of those ever changes here.
    @Query("UPDATE races SET mode = :mode, location = :location WHERE id = :raceId")
    suspend fun updateModeAndLocation(raceId: Long, mode: String?, location: String)
}