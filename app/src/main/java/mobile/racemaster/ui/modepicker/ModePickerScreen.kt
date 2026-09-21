package mobile.racemaster.ui.modepicker

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.mule.PeripheralSyncService
import mobile.racemaster.data.repository.displayName
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.ui.components.RaceProgressSummary
import mobile.racemaster.util.withClickSound

@Composable
fun ModePickerScreen(
    onModeSelected: (AppMode) -> Unit,
    onSetupRaceNeeded: () -> Unit,
    onMuleModeSelected: () -> Unit,
    onMuleSetupNeeded: () -> Unit,
    onReviewPastRaces: () -> Unit,
    onHelp: () -> Unit,
    onSetupDevice: () -> Unit,
    viewModel: ModePickerViewModel = viewModel(factory = ModePickerViewModel.Factory),
) {
    val hasActiveRace by viewModel.hasActiveRace.collectAsStateWithLifecycle()
    val activeModes by viewModel.activeModes.collectAsStateWithLifecycle()
    val raceSummary by viewModel.raceSummary.collectAsStateWithLifecycle()
    val raceMode by viewModel.raceMode.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val btPollingStatus by viewModel.btPollingStatus.collectAsStateWithLifecycle()
    val muleSyncEnabled by viewModel.muleSyncEnabled.collectAsStateWithLifecycle()

    // A device now records against exactly one race and mode, set up up front via Setup
    // Device > Setup Race (see TODO.md's phase 1) — mode is no longer switched here at all
    // (only via Relocate, once a race exists — see RaceDetailsScreen). Nothing set up yet
    // routes straight to Setup Race, same as an off Mule Mode routes through Options first (see
    // the Mule ModeButton's own doc below).
    fun handleStartTap() {
        val mode = raceMode
        if (hasActiveRace && mode != null) onModeSelected(mode) else onSetupRaceNeeded()
    }

    // The mode picker is always the root of the back stack (see RacemasterNavHost), so
    // pressing back here is the one place that would otherwise exit the app outright —
    // worth a confirmation rather than a stray tap closing everything.
    var showExitConfirm by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    BackHandler { showExitConfirm = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
    ) {
        // Routes into SetupDeviceScreen — the one hub every mode (Time/Bibs/CP/Mule alike)
        // reaches name/server/connectivity setup through, which used to only be reachable from
        // inside Mule Mode's own screen. That stopped making sense once Time/Bibs/CP phones
        // became a distinct, separate role from Mule (see MuleSyncEngine's own doc): an
        // operator on a Time-mode phone shouldn't have to detour through a role they never
        // actually use just to reach setup. "Setup: <name>" rather than a bare echoed name (the
        // previous wording) — a lone device name with no other label read as if the button just
        // *displayed* the name rather than led anywhere.
        Button(
            onClick = withClickSound(onSetupDevice),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(deviceName?.let { "Setup: $it" } ?: "Setup Device", style = MaterialTheme.typography.titleMedium)
        }
        // Replaces the old static "Select device mode" text — a mode is now chosen once, at
        // Setup Race (or changed later via Relocate), not picked here every time, so this is a
        // live status line instead: what's set up and ready, or a nudge toward Setup Race when
        // nothing is yet.
        val currentMode = raceMode
        Text(
            when {
                hasActiveRace && currentMode != null -> "Ready to record ${currentMode.displayName()}"
                hasActiveRace -> "Race set up — mode not yet chosen"
                else -> "Set up a race above to begin"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        // "- active" — a race can still have un-reset activity sitting in a mode it's since been
        // relocated away from (Relocate switches RaceEntity.mode but never resets the mode being
        // left), so this is what tells the operator "yes, this mode you're about to open is the
        // one that's actually live", reading "<mode> - active" instead of "Start <mode>" once it
        // genuinely is.
        val startLabel = raceMode?.let { mode ->
            if (mode in activeModes) "${mode.displayName()} - active" else "Start ${mode.displayName()}"
        } ?: "Start"
        ModeButton(startLabel, enabled = hasActiveRace && raceMode != null) { handleStartTap() }
        // Mule syncing is its own independent on/off flag now (see
        // SettingsRepository.muleSyncEnabled's own doc), not a 4th mode mutually exclusive with
        // the three above, echoed right in the button label so it reads correctly whether or not
        // a race is in progress. Mirrors the New-Race-first flow the three buttons above use
        // when there's nothing to show yet: off routes through Options (there's nothing on
        // Mule Mode's own dashboard worth seeing until it's actually on — same as Time/Bibs/CP
        // routing through New Race first when no race exists), landing on the dashboard once
        // Options' own Enable action fires (see SetupOptionsScreen's onMuleModeEnabled); already
        // on goes straight to the dashboard, same as tapping a mode with an active race already
        // skips New Race.
        ModeButton(if (muleSyncEnabled) "Mule Mode — ON" else "Mule Mode — OFF") {
            if (muleSyncEnabled) onMuleModeSelected() else onMuleSetupNeeded()
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = withClickSound(onReviewPastRaces),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text("Progress", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = withClickSound(onHelp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text("Help", style = MaterialTheme.typography.titleMedium)
            }
        }
        // Once a race has genuinely been set up (a race exists and its mode is known), the same
        // summary block the live mode screens themselves show — same common function, not a
        // separate, differently-shaped card.
        if (hasActiveRace && raceMode != null) {
            raceSummary?.let { summary ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RaceProgressSummary(
                        deviceName = deviceName,
                        raceLabel = summary.raceLabel,
                        raceLocation = summary.raceLocation,
                        nextSplitNumber = summary.nextSplitNumber,
                        unsyncedCount = summary.unsyncedCount,
                        lastSyncedAtMillis = summary.lastSyncedAtMillis,
                        serverStatus = summary.serverStatus,
                        btPollingStatus = btPollingStatus,
                        progressText = summary.progressText,
                    )
                }
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit RaceMaster?") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = {
                TextButton(
                    onClick = withClickSound {
                        // Stop the service (and, via its onDestroy, MuleSyncEngine's scan/
                        // advertise loops) explicitly before finishing — Activity.finish()
                        // alone leaves both running indefinitely in the background, since
                        // neither onTaskRemoved nor stopWithTask fires for a plain finish()
                        // (see PeripheralSyncService.stop's own doc). Confirmed in the field
                        // as Bluetooth staying visibly active after Exit was confirmed.
                        activity?.let { PeripheralSyncService.stop(it) }
                        activity?.finish()
                    },
                ) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { showExitConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModeButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = withClickSound(onClick),
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}