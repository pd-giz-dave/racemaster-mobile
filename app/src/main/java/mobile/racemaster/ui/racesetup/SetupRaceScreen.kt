package mobile.racemaster.ui.racesetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mobile.racemaster.data.mule.AvailableRace
import mobile.racemaster.data.repository.isValidRaceName
import mobile.racemaster.ui.components.HideKeyboardButton
import mobile.racemaster.ui.components.HistoryTextField
import mobile.racemaster.util.withClickSound

/** Sets up the one race this device will record against — name and location only (see
 *  TODO.md's phase 1: course/first-bib/runner-count are gone). The seniors/juniors suffix the
 *  web app expects is just typed as part of the race name now — e.g. "Pontesbury-Seniors" —
 *  rather than picked from a separate menu. Reached from Setup Device, before any mode is
 *  selected. Disabled while a race is already active, same as NameDeviceScreen's own guard for
 *  renaming — see SetupRaceViewModel's own doc. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupRaceScreen(
    onDone: () -> Unit,
    viewModel: SetupRaceViewModel = viewModel(factory = SetupRaceViewModel.Factory),
) {
    val hasActiveRace by viewModel.hasActiveRace.collectAsStateWithLifecycle()
    val raceNameHistory by viewModel.raceNameHistory.collectAsStateWithLifecycle()
    val locationHistory by viewModel.locationHistory.collectAsStateWithLifecycle()
    val availableRaces by viewModel.availableRaces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var name by remember { mutableStateOf("") }
    // "Finish" out of the box, same default RaceDetailsScreen's own Location field used to
    // have — most stations recording a race are at the finish line.
    var location by remember { mutableStateOf("Finish") }
    var isSaving by remember { mutableStateOf(false) }

    val nameValid = isValidRaceName(name)
    val canSave = !hasActiveRace && !isSaving && name.isNotBlank() && nameValid && location.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Race") },
                navigationIcon = { TextButton(onClick = withClickSound(onDone)) { Text("Cancel") } },
                actions = { HideKeyboardButton() },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for
        // every screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasActiveRace) {
                Text(
                    "Can't set up a new race while one is already active — stop and reset it " +
                        "first, or go to Progress (Races) to resume a previously stopped one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HistoryTextField(
                value = name,
                onValueChange = { name = it },
                label = "Race name (letters, numbers, - only)",
                history = raceNameHistory,
                enabled = !hasActiveRace,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (name.isNotBlank() && !nameValid) {
                Text(
                    "Race name can only contain letters (a-z, A-Z), numbers (0-9), and hyphens (-).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HistoryTextField(
                value = location,
                onValueChange = { location = it },
                label = "Location (e.g. Finish, CP1, CP2, et al)",
                history = locationHistory,
                enabled = !hasActiveRace,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )

            // Online branch — scan the server for this owner's own recent races and pick one
            // instead of typing a name (TODO.md's phase 2). Location must already be entered:
            // the picked race still needs it (see SetupRaceViewModel.pickAvailableRace), and
            // asking for it up front avoids a second interruption right after picking.
            OutlinedButton(
                onClick = withClickSound { viewModel.scanServer() },
                enabled = !hasActiveRace && location.isNotBlank() && availableRaces != AvailableRacesState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan Server for Recent Races") }
            when (val state = availableRaces) {
                AvailableRacesState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                AvailableRacesState.Unavailable -> Text(
                    "No recent race found on the server (or it's unreachable) — enter a race name below instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AvailableRacesState.NotChecked -> {}
                is AvailableRacesState.Found -> AvailableRacesDialog(
                    races = state.races,
                    onPick = { race ->
                        isSaving = true
                        scope.launch {
                            viewModel.pickAvailableRace(race, location)
                            onDone()
                        }
                    },
                    onDismiss = viewModel::dismissAvailableRaces,
                )
            }

            Text("— or enter a race name manually —", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = withClickSound {
                    isSaving = true
                    scope.launch {
                        viewModel.save(name, location)
                        onDone()
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create") }
        }
    }
}

@Composable
private fun AvailableRacesDialog(races: List<AvailableRace>, onPick: (AvailableRace) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = withClickSound(onDismiss),
        title = { Text("Recent races") },
        text = {
            if (races.isEmpty()) {
                Text("No recent races found on the server.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(races, key = { it.raceLabel }) { race ->
                        TextButton(
                            onClick = withClickSound { onPick(race) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(race.raceName.ifBlank { race.raceLabel }, style = MaterialTheme.typography.bodyLarge)
                                Text(race.raceLabel, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = withClickSound(onDismiss)) { Text("Cancel") } },
    )
}
