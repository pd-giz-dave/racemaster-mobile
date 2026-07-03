package mobile.racemaster.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType
import kotlinx.coroutines.flow.Flow

@Dao
interface BibEntryDao {
    // Ordered by id, not splitNumber: the fixed Clock row has splitNumber = 0 but the
    // lowest id, so it must sort last by insertion order to be treated as "latest" only
    // when it's genuinely the only row for the race.
    @Query("SELECT * FROM bib_entries WHERE raceId = :raceId ORDER BY id DESC")
    fun observeForRace(raceId: Long): Flow<List<BibEntryEntity>>

    @Query("SELECT * FROM bib_entries WHERE raceId = :raceId ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(raceId: Long): BibEntryEntity?

    @Insert
    suspend fun insert(entry: BibEntryEntity): Long

    @Delete
    suspend fun delete(entry: BibEntryEntity)

    @Query("UPDATE bib_entries SET bibNumber = :bibNumber, type = :type, note = :note WHERE id = :id")
    suspend fun update(id: Long, bibNumber: Int?, type: BibEntryType, note: String?)

    @Query("DELETE FROM bib_entries WHERE raceId = :raceId")
    suspend fun deleteAllForRace(raceId: Long)
}
