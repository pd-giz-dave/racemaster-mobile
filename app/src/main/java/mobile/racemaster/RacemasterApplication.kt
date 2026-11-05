package mobile.racemaster

import android.app.Application
import android.os.Handler
import android.os.Looper
import mobile.racemaster.data.mule.PeripheralSyncService
import mobile.racemaster.di.AppContainer
import mobile.racemaster.di.DefaultAppContainer

class RacemasterApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // Both of these are real main-thread work (opening a GATT server and starting BLE
        // advertising; binding to the system TextToSpeech service) — cheap on a modern phone
        // but, on a slow chipset (confirmed on a Samsung XCover 4), enough to measurably delay
        // MainActivity's first frame if run inline here, since this method returns before
        // Android has even dispatched the already-queued activity-launch message. Posting
        // defers them to the end of the main thread's message queue, after that launch message,
        // so MainActivity gets to call setContent() first — they still start automatically
        // within the same cold start, just no longer block it.
        Handler(Looper.getMainLooper()).post {
            // No-ops quietly if Bluetooth permissions haven't been granted yet; the permission
            // request flow starts it again once they have been.
            PeripheralSyncService.startIfPermitted(this)
            // Forces BibSpeaker's lazy TextToSpeech init to start binding now, not whenever Bibs
            // Mode happens to be reached — its own async init can take over a second on an old
            // device, which would otherwise risk silently swallowing the very first bib typed.
            container.bibSpeaker
        }
    }
}