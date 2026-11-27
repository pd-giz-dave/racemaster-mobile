package mobile.racemaster.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import mobile.racemaster.data.db.dao.HistoryLineDao
import mobile.racemaster.data.db.dao.KnownDeviceDao
import mobile.racemaster.data.db.dao.LineSyncDao
import mobile.racemaster.data.db.dao.PeerSeenRecordDao
import mobile.racemaster.data.db.dao.ProgressDao
import mobile.racemaster.data.db.dao.PulledRecordDao
import mobile.racemaster.data.db.dao.RaceDao
import mobile.racemaster.data.db.dao.TargetedProgressDao
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.KnownDeviceEntity
import mobile.racemaster.data.db.entity.LineSyncEntity
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity
import mobile.racemaster.data.db.entity.ProgressEntity
import mobile.racemaster.data.db.entity.PulledRecordEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.db.entity.TargetedProgressEntity

@Database(
    entities = [
        RaceEntity::class,
        HistoryLineEntity::class,
        PulledRecordEntity::class,
        LineSyncEntity::class,
        KnownDeviceEntity::class,
        ProgressEntity::class,
        TargetedProgressEntity::class,
        PeerSeenRecordEntity::class,
    ],
    // Bumped for the Count/Close-into-Bibs amalgamation (TODO.md: "amalgamate Bibs, Count and
    // Close modes" — see AppMode's own doc) — no column actually changed shape, but
    // RaceEntity.mode can no longer hold "COUNT"/"CLOSE" going forward, and per direct
    // instruction it's fine to simply wipe local history rather than migrate any already-stored
    // rows still carrying those values (nothing outside the dev test rig depends on this data;
    // the only external dependency is the companion web app). No explicit Migration needed, same
    // as the last several bumps — AppContainer's fallbackToDestructiveMigration(dropAllTables =
    // true) already wipes and recreates the whole local DB on any version mismatch.
    version = 41,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RacemasterDatabase : RoomDatabase() {
    abstract fun raceDao(): RaceDao
    abstract fun historyLineDao(): HistoryLineDao
    abstract fun pulledRecordDao(): PulledRecordDao
    abstract fun lineSyncDao(): LineSyncDao
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun progressDao(): ProgressDao
    abstract fun targetedProgressDao(): TargetedProgressDao
    abstract fun peerSeenRecordDao(): PeerSeenRecordDao
}
