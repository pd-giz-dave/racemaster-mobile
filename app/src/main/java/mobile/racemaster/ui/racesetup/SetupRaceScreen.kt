package mobile.racemaster.ui.racesetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
