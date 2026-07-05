package mobile.racemaster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import mobile.racemaster.data.mule.PeripheralSyncService
import mobile.racemaster.navigation.RacemasterNavHost
import mobile.racemaster.ui.components.AppBanner
import mobile.racemaster.ui.theme.RacemasterMobileTheme

class MainActivity : ComponentActivity() {

    // Set by whichever mode screen is currently showing (Time mode's split, Bibs mode's Log)
    // while it's able to accept an entry. Lets any USB or Bluetooth HID device that presents
    // itself as a keyboard — presenter clickers, camera shutter remotes, foot pedals, volume
    // buttons — fire the current screen's main action, since Android already delivers those
    // as ordinary KeyEvents with no pairing/GATT code needed on our end.
    var onExternalSplitTrigger: (() -> Unit)? = null

    // Mule Mode's BLE sync needs these to scan/connect/advertise. RacemasterApplication
    // already tried to start PeripheralSyncService once at process start and no-op'd if they
    // weren't granted yet — this launcher requests them (once, on first launch) and retries
    // the service start on the result, whatever the user decides.
    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        PeripheralSyncService.startIfPermitted(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Operators rely on the screen throughout a race; don't let it sleep on them.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestBluetoothPermissionsIfNeeded()
        setContent {
            RacemasterMobileTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = { AppBanner() },
                ) { innerPadding ->
                    RacemasterNavHost(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    // dispatchKeyEvent is a public, ordinary-to-override Activity API — lint's RestrictedApi
    // check flags it as library-group-restricted anyway, a known false positive with this
    // androidx.activity version (ComponentActivity's own override is annotated in a way lint
    // misattributes to callers). Nothing restricted is actually being called here.
    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val trigger = onExternalSplitTrigger
        if (trigger != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode in EXTERNAL_TRIGGER_KEYCODES) {
            trigger()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestBluetoothPermissions.launch(missing.toTypedArray())
        }
    }

    private companion object {
        // Common keys generic Bluetooth/USB HID clickers and foot pedals send.
        val EXTERNAL_TRIGGER_KEYCODES = setOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
        )
    }
}