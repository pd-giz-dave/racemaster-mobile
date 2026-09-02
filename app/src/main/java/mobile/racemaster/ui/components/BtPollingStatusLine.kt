package mobile.racemaster.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import mobile.racemaster.data.mule.BtPollingStatus

/** Shared "last polled by BT: HH:MM" line for Time/Bibs/CP mode — a leaf device only ever acts
 *  as a BLE peripheral (advertise + serve GATT reads), so unlike Mule Mode's own Nearby Devices
 *  list it has no visibility of its own into who's actually connecting to it; this is that
 *  visibility, mirroring [ServerStatusLine]'s own shape (one state object in, one line out).
 *  [BtPollingStatus.advertisingWarning] — a definite, actively-detected failure, not a bare
 *  elapsed time — takes over the whole line when present, since a device that isn't even
 *  advertising couldn't be polled by anyone regardless of what [BtPollingStatus.lastPolledAtMillis]
 *  last said. Otherwise plain, uncoloured "last polled" text, same "operator judges staleness by
 *  eye" reasoning [BluetoothStateRepository.lastWebAppSeenAtMillis]'s own doc already establishes
 *  for the equivalent Mule Mode signal — deliberately no computed live "polling has stopped"
 *  threshold here, which would need its own ticker to actually update once minutes pass with no
 *  further poll, unlike this line, which already recomposes whenever a fresh poll bumps the
 *  timestamp it's reading. */
@Composable
fun BtPollingStatusLine(status: BtPollingStatus, modifier: Modifier = Modifier) {
    if (status.advertisingWarning != null) {
        Text(status.advertisingWarning, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = modifier)
        return
    }
    // See ServerStatusLine's own identical reasoning for reading locale via LocalConfiguration
    // rather than Locale.getDefault().
    val locale = LocalConfiguration.current.locales[0]
    val lastPolledText = status.lastPolledAtMillis?.let { SimpleDateFormat("HH:mm", locale).format(Date(it)) } ?: "never"
    Text("Last polled by BT: $lastPolledText", style = MaterialTheme.typography.labelMedium, modifier = modifier)
}
