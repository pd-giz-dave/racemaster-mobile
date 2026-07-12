package mobile.racemaster.data.mule

import android.bluetooth.BluetoothManager
import android.content.Context

/** Whether the device's Bluetooth radio is currently on — checked before starting a Kable
 *  scan, since scanning with it off throws (com.juul.kable.UnmetRequirementException)
 *  instead of just failing, and Kable's own reconnection handling doesn't cover "the radio
 *  itself is off" the same way it covers a dropped peripheral connection. */
class BluetoothStateRepository(private val context: Context) {
    fun isEnabled(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        return manager.adapter?.isEnabled == true
    }
}
