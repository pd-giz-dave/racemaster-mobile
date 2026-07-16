package mobile.racemaster.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.MulePullClient
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.MuleSyncClient
import mobile.racemaster.data.mule.MuleSyncEngine
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.settings.SettingsRepository

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

interface AppContainer {
    val raceRepository: RaceRepository
    val timeModeRepository: TimeModeRepository
    val bibsModeRepository: BibsModeRepository
    val settingsRepository: SettingsRepository
    val muleRepository: MuleRepository
    val muleSyncEngine: MuleSyncEngine
    val serverStatusRepository: ServerStatusRepository
    val bluetoothStateRepository: BluetoothStateRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val database: RacemasterDatabase by lazy {
        Room.databaseBuilder(context, RacemasterDatabase::class.java, "racemaster.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context.dataStore)
    }

    override val raceRepository: RaceRepository by lazy {
        RaceRepository(database.raceDao(), settingsRepository)
    }

    override val timeModeRepository: TimeModeRepository by lazy {
        TimeModeRepository(database, database.raceDao(), database.finishSplitDao(), settingsRepository)
    }

    override val bibsModeRepository: BibsModeRepository by lazy {
        BibsModeRepository(database, database.raceDao(), database.bibEntryDao(), settingsRepository)
    }

    private val muleSyncClient: MuleSyncClient by lazy { MuleSyncClient() }

    override val muleRepository: MuleRepository by lazy {
        MuleRepository(database.pulledRecordDao(), settingsRepository, MulePullClient(), muleSyncClient)
    }

    override val muleSyncEngine: MuleSyncEngine by lazy {
        MuleSyncEngine(muleRepository, bluetoothStateRepository, raceRepository, timeModeRepository, bibsModeRepository, settingsRepository)
    }

    override val serverStatusRepository: ServerStatusRepository by lazy {
        ServerStatusRepository(muleSyncClient)
    }

    override val bluetoothStateRepository: BluetoothStateRepository by lazy {
        BluetoothStateRepository(context)
    }
}