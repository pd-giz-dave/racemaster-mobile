package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.util.withClickSound

/** The actual Bluetooth on/off, server-sync on/off, and auto-sync controls — split out from
 *  [SetupDeviceScreen]'s own plain status summary so that hub stays a quick glance, with the
 *  interactive controls one tap further in here. Reuses [MuleModeViewModel] rather than a
 *  dedicated one of its own — it already exposes exactly this state (and the control
 *  functions), regardless of which mode this phone is actually in; see that ViewModel's own
 *  uiState fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupOptionsScreen(
    onDone: () -> Unit,
    // Fires right after a successful Enable (not Disable) — see ModePickerScreen's own
    // onMuleSetupNeeded/onMuleModeSelected doc for why: tapping "Mule Mode" on the picker while
    // it's off routes here first (there's nothing to show on Mule Mode's own dashboard until
    // it's actually on), the same way tapping Time/Bibs/CP with no active race routes through
    // New Race first — turning it on here is this flow's own "Create", so it lands on Mule
    // Mode's dashboard next, same as Create lands on that mode's own screen.
    onMuleModeEnabled: () -> Unit = {},
    // Fires right after a successful Disable, but only meaningfully wired when this screen was
    // reached via Mule Mode's own "Options" button (see Routes.setupOptions/RacemasterNavHost) —
    // that's the one path guaranteed to have muling on when arriving, and the dashboard
    // underneath has nothing left to show once it's off, so this pops back past both Options and
    // it to the Mode Picker, mirroring onMuleModeEnabled's own forward trip. Left a no-op
    // (default) everywhere else Options is reached (Setup Device's own button, the Mode Picker's
    // Mule-off routing) — an operator who detoured here from Time/Bibs/CP to disable muling
    // should land back where they were, not get bounced to the picker.
    onMuleModeDisabled: () -> Unit = {},
    viewModel: MuleModeViewModel = viewModel(factory = MuleModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Options") },
                navigationIcon = { TextButton(onClick = withClickSound(onDone)) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MuleSyncControl(
                uiState = uiState,
                viewModel = viewModel,
                onEnabled = onMuleModeEnabled,
                onDisabled = onMuleModeDisabled,
            )
            HorizontalDivider()
            BluetoothAndServerSyncToggles(uiState = uiState, viewModel = viewModel)
            HorizontalDivider()
            AutoSyncControls(uiState = uiState, viewModel = viewModel)
            HorizontalDivider()
            RaceStalenessControl(viewModel = viewModel)
        }
    }
}

// The explicit on/off control for SettingsRepository.muleSyncEnabled — see its own doc for what
// it actually gates (scanning for and pulling from nearby devices), and why it's independent of
// whichever recording mode (Time/Bibs/CP/none) this phone is itself in: this control lives here,
// on the Options screen every mode reaches via Setup Device, specifically so a phone already
// recording a race doesn't have to detour through Mule Mode's own screen just to also start
// relaying a second, internet-less phone's data on to the server.
@Composable
internal fun MuleSyncControl(
    uiState: MuleModeUiState,
    viewModel: MuleModeViewModel,
    onEnabled: () -> Unit = {},
    onDisabled: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (uiState.muleSyncEnabled) {
                "Mule syncing: ON — also scanning for and pulling data from nearby devices"
            } else {
                "Mule syncing: OFF — not scanning for other devices"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (uiState.muleSyncEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (uiState.muleSyncEnabled) {
            OutlinedButton(
                onClick = withClickSound {
                    viewModel.setMuleSyncEnabled(false)
                    onDisabled()
                },
            ) {
                Text("Disable Mule Mode")
            }
        } else {
            Button(
                onClick = withClickSound {
                    viewModel.setMuleSyncEnabled(true)
                    onEnabled()
                },
            ) {
                Text("Enable Mule Mode")
            }
        }
    }
}

// See SettingsRepository.raceStaleAfterDays's own doc — a general setting (not specific to
// Setup Server, unlike its predecessor) governing both the server-push reconciliation
// (MuleRepository.pushToServer) and what this device is willing to relay onward to another
// Mule over BLE (PeripheralSyncService's own freshRelayManifest). Given its own explicit "Save"
// (rather than autosaving on every keystroke, or requiring a focus-loss/dismiss gesture the way
// a full-page form's "Log-in" button used to bundle it in) since this screen otherwise has no
// submit step of its own — every other control here is an immediate-effect toggle/button.
@Composable
internal fun RaceStalenessControl(viewModel: MuleModeViewModel) {
    val savedDays by viewModel.raceStaleAfterDays.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    // Pre-fill exactly once from whatever's already saved, same reasoning as
    // MuleServerSetupScreen's own "prefilled" flag — later emissions (this screen's own Save
    // below included) must not stomp on what the operator is mid-typing.
    var prefilled by remember { mutableStateOf(false) }
    LaunchedEffect(savedDays) {
        if (prefilled) return@LaunchedEffect
        // null means "DataStore hasn't answered yet" (distinct from a genuinely-loaded 2 —
        // see raceStaleAfterDays' own doc) — wait for the real value rather than locking the
        // field in at whatever the StateFlow's initial placeholder happened to be.
        val loaded = savedDays ?: return@LaunchedEffect
        text = loaded.toString()
        prefilled = true
    }
    val value = text.toIntOrNull()
    val canSave = value != null && value >= 1 && value != savedDays
    // Re-parses text (a live State, unlike a captured val) at the moment this actually runs,
    // rather than trusting canSave/value as captured above — a real device under heavy
    // background BLE/GATT load can process a queued tap well after the keystroke that made it
    // valid, invoking a save() closure bound to an already-stale composition whose captured
    // value was still null/unchanged; confirmed live via logging (a tap logged text="7" but a
    // captured value of null from an earlier recomposition). Reading straight off
    // viewModel.raceStaleAfterDays.value rather than the collected savedDays for the same
    // reason — a StateFlow read is always current, a Compose state snapshot from an earlier
    // frame might not be.
    fun save() {
        val parsed = text.toIntOrNull() ?: return
        if (parsed < 1 || parsed == viewModel.raceStaleAfterDays.value) return
        viewModel.setRaceStaleAfterDays(parsed)
        focusManager.clearFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                singleLine = true,
                label = { Text("Skip races older than (days)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                // save() re-validates internally (see its own doc) — no need to gate this on
                // the possibly-stale canSave read here too.
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = withClickSound(::save), enabled = canSave) { Text("Save") }
        }
        Text(
            "Races untouched this long are no longer checked against the server or relayed to other Mules.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// The push/auto-sync half of what used to be one combined AutoSyncStatus composable on Mule
// Mode's own screen (see MuleModeScreen.kt's PullStatusLine for the other, BLE-pull-only half
// that stayed there). This half is mode-agnostic — pushIfNeeded()/forceSyncNow() push THIS
// device's own recorded data to the server regardless of Bluetooth role, so every mode needs
// access to it, not just Mule. forceSyncNow() also triggers a pull pass, but that's a no-op on
// a non-Mule phone (nothing ever populates discoveredFlow without scanning), so it's safe to
// expose here unconditionally.
@Composable
internal fun AutoSyncControls(uiState: MuleModeUiState, viewModel: MuleModeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            uiState.autoSyncStopped -> Text(
                "Auto-sync: STOPPED",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            uiState.autoSyncArmed -> Text(
                if (uiState.muleSyncEnabled) {
                    "Auto-sync: ON — pulling and pushing every few seconds"
                } else {
                    "Auto-sync: ON — pushing every few seconds"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        uiState.autoWarning?.let { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // Shared with Mule Mode's own dashboard (see MuleModeScreen.kt's LastPushLine) so
        // there's one copy of this text, not two that could drift.
        LastPushLine(uiState)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = withClickSound(viewModel::forceSyncNow), enabled = !uiState.isBusy) {
                Text("Force sync now")
            }
            if (uiState.autoSyncStopped) {
                TextButton(onClick = withClickSound(viewModel::resumeAutoSync)) { Text("Resume auto-sync") }
            } else {
                TextButton(onClick = withClickSound(viewModel::stopAutoSync)) { Text("Stop auto-sync") }
            }
        }
    }
}
