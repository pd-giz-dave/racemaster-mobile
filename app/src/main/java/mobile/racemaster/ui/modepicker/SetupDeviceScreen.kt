package mobile.racemaster.ui.modepicker

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.ui.mulemode.ConnectivityStatusText
import mobile.racemaster.ui.mulemode.MuleModeViewModel
import mobile.racemaster.util.bluetoothPermissions
import mobile.racemaster.util.hasAllPermissions
import mobile.racemaster.util.isIgnoringBatteryOptimizations
import mobile.racemaster.util.isPermissionRequestDialogSuppressed
import mobile.racemaster.util.notificationPermissions
import mobile.racemaster.util.openAppPermissionSettings
import mobile.racemaster.util.requestIgnoreBatteryOptimizations
import mobile.racemaster.util.withClickSound

/** The one hub every mode (Time/Bibs/CP/Mule alike) reaches device-level setup through — see
 *  [mobile.racemaster.data.mule.MuleSyncEngine]'s own doc for why this needed a home outside
 *  Mule Mode's own screen once Time/Bibs/CP phones became a distinct, separate role from Mule:
 *  every phone still needs its name, server connection, and Bluetooth/server-sync options
 *  configured independently of which role it's playing, and none of that should require
 *  detouring through a role most operators never actually use.
 *
 *  Deliberately just a list of buttons plus a read-only status summary, not the actual editing
 *  UI for any of them — [ConnectivityStatusText] (feedback only, no controls) reuses exactly
 *  what [mobile.racemaster.ui.mulemode.SetupOptionsScreen]'s own toggles are built on
 *  ([MuleModeViewModel]), so this hub and that screen can never show contradictory status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupDeviceScreen(
    onDone: () -> Unit,
    onHome: () -> Unit,
    onSetupName: () -> Unit,
    onSetupServer: () -> Unit,
    onOptions: () -> Unit,
    onSetupRace: () -> Unit,
    connectivityViewModel: MuleModeViewModel = viewModel(factory = MuleModeViewModel.Factory),
) {
    val uiState by connectivityViewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by connectivityViewModel.deviceName.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text("Setup Device") },
                onBack = onDone,
                onHome = onHome,
                helpTopic = HelpTopics.SetupDevice,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = withClickSound(onSetupName),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(deviceName?.let { "Setup Name: $it" } ?: "Setup Name")
            }
            Button(
                onClick = withClickSound(onSetupServer),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Setup Server") }
            Button(
                onClick = withClickSound(onOptions),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Options") }
            // Below Options, per TODO.md's phase 1 — the one place a race now gets created,
            // reachable before any mode is selected so the device file can reach the server (or
            // a mule) as soon as setup is done, independent of mode selection.
            Button(
                onClick = withClickSound(onSetupRace),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Setup Race") }
            Column {
                KeepScreenOnStatus()
                BluetoothPermissionStatus()
                NotificationPermissionStatus()
            }
            ConnectivityStatusText(uiState)
        }
    }
}

/** "Keep screen on" relies on both FLAG_KEEP_SCREEN_ON (MainActivity, always in effect) and
 *  this OS-level battery-optimization exemption — MainActivity only ever asks for the latter
 *  once, silently, at first launch; if that one-shot prompt gets declined (an unexplained
 *  system dialog, easy to dismiss by reflex) there was previously no way to tell from inside
 *  the app, and no way back in short of finding it in system Settings. This surfaces the real
 *  current state (re-checked on every resume, so returning from that system dialog or from
 *  Settings itself updates it immediately) and a Grant action that re-shows the same prompt.
 *
 *  Revoke lands on this app's own App Info screen, then needs one further tap on "Battery" to
 *  reach the actual Unrestricted/Optimised/Restricted toggle — confirmed there's no shorter
 *  path: `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the OS's global list) only
 *  ever lists apps NOT YET exempted, by AOSP design — once an app is exempted it necessarily
 *  drops off that list, so it can never be the way back in, on any device, confirmed live on a
 *  Samsung XCover7; and there's no public (or even working undocumented) intent that deep-links
 *  straight to the per-app toggle screen itself, confirmed empty in the compiled SDK and
 *  confirmed `ActivityNotFoundException` on-device for the one undocumented action AOSP's own
 *  Settings app uses internally for it. The Toast below is the only way left to close that gap:
 *  it can't skip the extra tap, so it says where the extra tap is. */
@Composable
private fun KeepScreenOnStatus() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    LifecycleResumeEffect(Unit) {
        granted = context.isIgnoringBatteryOptimizations()
        onPauseOrDispose {}
    }
    StatusRow(
        label = "Keep screen on",
        granted = granted,
        notGrantedMessage = "the screen may sleep mid-race",
        onGrant = { context.requestIgnoreBatteryOptimizations() },
        onRevoke = {
            Toast.makeText(context, "On the next screen, tap Battery, then choose Optimised or Restricted", Toast.LENGTH_LONG).show()
            context.openAppPermissionSettings()
        },
    )
}

/** Same gap as [KeepScreenOnStatus], for the Bluetooth permissions (or legacy location
 *  permission pre-API-31) MainActivity also only ever asks for once at first launch: without
 *  every one of these, Mule Mode's whole BLE subsystem — both hosting and pulling — refuses to
 *  start at all (see PeripheralSyncService.startIfPermitted's own doc), silently, with no other
 *  indication anything is wrong beyond Mule Mode just never syncing. */
@Composable
private fun BluetoothPermissionStatus() {
    val context = LocalContext.current
    val permissions = remember { bluetoothPermissions() }
    var granted by remember { mutableStateOf(context.hasAllPermissions(permissions)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.hasAllPermissions(permissions)
    }
    LifecycleResumeEffect(Unit) {
        granted = context.hasAllPermissions(permissions)
        onPauseOrDispose {}
    }
    StatusRow(
        label = "Bluetooth",
        granted = granted,
        notGrantedMessage = "Mule Mode can't scan, host, or sync over Bluetooth",
        onGrant = {
            if (context.isPermissionRequestDialogSuppressed(permissions)) {
                context.openAppPermissionSettings()
            } else {
                launcher.launch(permissions)
            }
        },
        onRevoke = { context.openAppPermissionSettings() },
    )
}

/** Soft version of [BluetoothPermissionStatus]: POST_NOTIFICATIONS only exists from API 33 on,
 *  and missing it only stops the ongoing sync notification's text from refreshing — nothing
 *  else depends on it (see PeripheralSyncService.hasNotificationPermission's own doc) — so
 *  nothing is shown at all below API 33, where the permission doesn't exist to begin with. */
@Composable
private fun NotificationPermissionStatus() {
    val permissions = remember { notificationPermissions() }
    if (permissions.isEmpty()) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasAllPermissions(permissions)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.hasAllPermissions(permissions)
    }
    LifecycleResumeEffect(Unit) {
        granted = context.hasAllPermissions(permissions)
        onPauseOrDispose {}
    }
    StatusRow(
        label = "Notifications",
        granted = granted,
        notGrantedMessage = "the ongoing sync notification won't update",
        onGrant = {
            if (context.isPermissionRequestDialogSuppressed(permissions)) {
                context.openAppPermissionSettings()
            } else {
                launcher.launch(permissions)
            }
        },
        onRevoke = { context.openAppPermissionSettings() },
    )
}

/** Granted shows a Revoke action alongside Grant's not-granted one — not a real self-revoke
 *  (Android has no API for an app to strip its own permission, only to request one; see
 *  openAppPermissionSettings's own doc for why it lands on a system settings screen instead),
 *  but the quickest path there, mainly so this whole grant/revoke cycle can be re-tested by
 *  hand without needing adb.
 *
 *  The action is a plain clickable [Text], not a [androidx.compose.material3.TextButton]: a
 *  real button's enforced ~48dp minimum touch target was what kept these rows tall regardless
 *  of how tight the parent Column's spacing was set — three rows' worth of that padding is
 *  most of what made this status block feel sparse against the buttons above it. */
@Composable
private fun StatusRow(
    label: String,
    granted: Boolean,
    notGrantedMessage: String,
    onGrant: () -> Unit,
    onRevoke: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (granted) "$label: granted" else "$label: not granted — $notGrantedMessage",
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        Text(
            if (granted) "Revoke" else "Grant",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = withClickSound(if (granted) onRevoke else onGrant))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}
