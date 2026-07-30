package mobile.racemaster.data.mule

import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the device's Bluetooth radio is currently on — checked before starting a Kable
 *  scan, since scanning with it off throws (com.juul.kable.UnmetRequirementException)
 *  instead of just failing, and Kable's own reconnection handling doesn't cover "the radio
 *  itself is off" the same way it covers a dropped peripheral connection. */
class BluetoothStateRepository(private val context: Context) {
    fun isEnabled(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        return manager.adapter?.isEnabled == true
    }

    // Tracks whether PeripheralSyncService's own BLE advertising (this device being visible to
    // *other* phones as a Mule) has been failing repeatedly — confirmed in the field on a
    // device whose BLE chipset firmware got wedged such that startAdvertising() kept being
    // rejected by the OS even across a manual Bluetooth off/on toggle in Settings, and only a
    // full phone restart (power cycle) actually recovered it. That's below anything this app's
    // retry loop can reach, so the best it can do is stop failing silently and tell the
    // operator what actually worked in the field, rather than leave them retrying a Bluetooth
    // toggle that (for this class of failure) won't help.
    @Volatile
    private var consecutiveAdvertisingFailures = 0
    private val advertisingWarningFlow = MutableStateFlow<String?>(null)

    /** Non-null once advertising has failed [ADVERTISING_FAILURE_THRESHOLD] times in a row
     *  with no intervening success — see [recordAdvertisingFailure]'s own doc for why a plain
     *  Bluetooth toggle is called out by name here rather than left implicit. */
    val advertisingWarning: StateFlow<String?> = advertisingWarningFlow.asStateFlow()

    @Synchronized
    fun recordAdvertisingSuccess() {
        consecutiveAdvertisingFailures = 0
        advertisingWarningFlow.value = null
    }

    @Synchronized
    fun recordAdvertisingFailure() {
        consecutiveAdvertisingFailures++
        if (consecutiveAdvertisingFailures >= ADVERTISING_FAILURE_THRESHOLD) {
            advertisingWarningFlow.value =
                "Not visible to nearby devices — if turning Bluetooth off/on doesn't fix it, restart the phone"
        }
    }

    private companion object {
        const val ADVERTISING_FAILURE_THRESHOLD = 5
    }
}
