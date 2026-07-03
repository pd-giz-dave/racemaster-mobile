package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import mobile.racemaster.data.db.entity.BibEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BibEntryDao {
    // Ordered by id, not splitNumber: RETIRE rows have a null splitNumber but must
    // still be correctly treated as "latest" for undo when they're the newest entry.
    @Query("SELECT * FROM bib_entries WHERE raceId = :raceId ORDER BY id DESC")
    fun observeForRace(raceId: Long): Flow<List<BibEntryEntity>>

    @Query("SELECT * FROM bib_entries WHERE raceId = :raceId ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(raceId: Long): BibEntryEntity?

    @Insert
    suspend fun insert(entry: BibEntryEntity): Long

    @Delete
    suspend fun delete(entry: BibEntryEntity)
}