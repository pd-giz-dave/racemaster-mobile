package mobile.racemaster

import android.app.Application
import mobile.racemaster.di.AppContainer
import mobile.racemaster.di.DefaultAppContainer

class RacemasterApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}