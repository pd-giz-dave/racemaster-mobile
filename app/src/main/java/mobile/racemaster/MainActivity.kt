package mobile.racemaster

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
        requestIgnoreBatteryOptimizationsIfNeeded()
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

    // A process spawned in the background (e.g. the OS waking the app to service an
    // incoming BLE connection, or right after a reinstall) can miss the one retry attempt
    // in onCreate(): Android throws ForegroundServiceStartNotAllowedException for a
    // foreground-service start attempted before the app has ever actually become visible —
    // and once that happens, nothing else would ever ask again for the rest of this
    // process's life, silently leaving this device permanently un-advertised until the
    // whole process restarts. onResume() fires every time the app genuinely becomes
    // visible (including unlocking into it, or switching back from another app), so
    // retrying here is what actually recovers from that — and it's a safe no-op once the
    // service is already running (just another START_STICKY onStartCommand, no re-init).
    override fun onResume() {
        super.onResume()
        PeripheralSyncService.startIfPermitted(this)
        // Cheap and idempotent — re-asserted on every resume (not just onCreate) as a
        // defensive measure against any OS/OEM quirk that clears window flags across a
        // background/foreground cycle, matching the same "don't trust a single one-shot
        // attempt" reasoning as the service-start retry above.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    // FLAG_KEEP_SCREEN_ON stops the standard display-timeout from firing, but several OEM
    // skins (seen in the field on budget devices in particular) run their own
    // battery-optimization/Doze-style policy that dims or locks the screen on an app it's
    // decided is "inactive" regardless of that flag. Excluding the app from battery
    // optimization is the standard fix — this shows the system's one-tap permission dialog
    // for it, once; if the operator declines, this just no-ops on every later launch rather
    // than nagging (checked fresh each time in case they grant it later via system Settings
    // instead).
    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
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