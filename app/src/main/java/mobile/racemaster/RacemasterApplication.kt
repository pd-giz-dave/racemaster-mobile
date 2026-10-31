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
        // Forces BibSpeaker's lazy TextToSpeech init to start binding now, not whenever Bibs
        // Mode happens to be reached — its own async init can take over a second on an old
        // device, which would otherwise risk silently swallowing the very first bib typed.
        container.bibSpeaker
    }
}