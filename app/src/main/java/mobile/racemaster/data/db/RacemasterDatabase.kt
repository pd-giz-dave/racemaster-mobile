package mobile.racemaster.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import mobile.racemaster.data.db.dao.BibEntryDao
import mobile.racemaster.data.db.dao.FinishSplitDao
import mobile.racemaster.data.db.dao.PulledRecordDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.FinishSplitEntity
import mobile.racemaster.data.db.entity.PulledRecordEntity
import mobile.racemaster.data.db.entity.RaceEntity

@Database(
    entities = [
        RaceEntity::class,
        FinishSplitEntity::class,
        BibEntryEntity::class,
        PulledRecordEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RacemasterDatabase : RoomDatabase() {
    abstract fun raceDao(): RaceDao
    abstract fun finishSplitDao(): FinishSplitDao
    abstract fun bibEntryDao(): BibEntryDao
    abstract fun pulledRecordDao(): PulledRecordDao
}