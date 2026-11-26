package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
 *  timestamp it's reading.
 *
 *  A second, separate warning line appears below whenever [BtPollingStatus.locationServicesEnabled]
 *  is false (see [mobile.racemaster.data.mule.LocationStateRepository]'s own doc for why this
 *  silently blinds BLE scanning fleet-wide, unrelated to whether Bluetooth itself is on) —
 *  always shown alongside, never instead of, the line above: being unable to scan for others
 *  says nothing about whether this device's own advertising/polling is working.
 *
 *  [BtPollingStatus.bluetoothOff] instead appends a red " — Bluetooth off" suffix onto the same
 *  "Last polled by BT" line, rather than replacing it or adding a third line — direct feedback:
 *  a plain "Last polled by BT: never" on this line went unnoticed in the field (indistinguishable
 *  by eye from "nobody's nearby yet"), so the fix keeps the familiar line and makes the actual
 *  cause impossible to miss on it, instead of growing the line count further. */
@Composable
fun BtPollingStatusLine(status: BtPollingStatus, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        // See ServerStatusLine's own identical reasoning for reading locale via
        // LocalConfiguration rather than Locale.getDefault().
        val locale = LocalConfiguration.current.locales[0]
        val lastPolledText = status.lastPolledAtMillis?.let { SimpleDateFormat("HH:mm", locale).format(Date(it)) } ?: "never"
        if (status.bluetoothOff) {
            Text(
                buildAnnotatedString {
                    append("Last polled by BT: $lastPolledText")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                        append(" — Bluetooth off")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
            )
        } else if (status.advertisingWarning != null) {
            Text(status.advertisingWarning, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        } else {
            Text("Last polled by BT: $lastPolledText", style = MaterialTheme.typography.labelMedium)
        }
        if (!status.locationServicesEnabled) {
            Text(
                "Location services off — nearby devices won't be found",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Plain "Bluetooth: ON/OFF" line reflecting the OS radio itself (see
 *  [BtPollingStatus.radioEnabled]'s own doc) — a separate, standalone line from
 *  [BtPollingStatusLine] rather than folded into it, since that one's "Last polled by BT" text is
 *  about whether anyone's actually been seen polling, not about the radio's own on/off state; the
 *  two can and do diverge (a device polled minutes ago, then the operator background-toggled
 *  Bluetooth off, still shows a "last polled" time that was true when it happened). Same
 *  "ON"/"OFF" wording as Mule Mode's own ConnectivityStatusText, for consistency app-wide. */
@Composable
fun BluetoothRadioStatusLine(status: BtPollingStatus, modifier: Modifier = Modifier) {
    if (status.radioEnabled) {
        Text("Bluetooth: ON", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier)
    } else {
        Text(
            "Bluetooth: OFF — turn on Bluetooth in system settings",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}
