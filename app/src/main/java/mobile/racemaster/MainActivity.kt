package mobile.racemaster

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Operators rely on the screen throughout a race; don't let it sleep on them.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val trigger = onExternalSplitTrigger
        if (trigger != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode in EXTERNAL_TRIGGER_KEYCODES) {
            trigger()
            return true
        }
        return super.dispatchKeyEvent(event)
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