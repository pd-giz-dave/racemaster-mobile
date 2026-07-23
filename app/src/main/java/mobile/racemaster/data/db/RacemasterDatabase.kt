package mobile.racemaster.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.LineSyncDao
import mobile.racemaster.data.db.dao.PulledRecordDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.LineSyncEntity
import mobile.racemaster.data.db.entity.PulledRecordEntity
import mobile.racemaster.data.db.entity.RaceEntity

@Database(
    entities = [
        RaceEntity::class,
        HistoryLineEntity::class,
        PulledRecordEntity::class,
        LineSyncEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RacemasterDatabase : RoomDatabase() {
    abstract fun raceDao(): RaceDao
    abstract fun historyLineDao(): HistoryLineDao
    abstract fun pulledRecordDao(): PulledRecordDao
    abstract fun lineSyncDao(): LineSyncDao
}
