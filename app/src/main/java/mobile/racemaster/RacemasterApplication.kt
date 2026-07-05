package mobile.racemaster

import android.app.Application
import mobile.racemaster.data.mule.PeripheralSyncService
import mobile.racemaster.di.AppContainer
import mobile.racemaster.di.DefaultAppContainer

class RacemasterApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // No-ops quietly if Bluetooth permissions haven't been granted yet; the permission
        // request flow starts it again once they have been.
        PeripheralSyncService.startIfPermitted(this)
    }
}