package mobile.racemaster.ui.racedetails

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
import androidx.compose.runtime.LaunchedEffect
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
import mobile.racemaster.data.repository.isValidCpLocation
import mobile.racemaster.data.repository.isValidRaceName
import mobile.racemaster.ui.components.HideKeyboardButton
import mobile.racemaster.ui.components.HistoryTextField
import mobile.racemaster.util.withClickSound

/** "Relocate" (formerly "This Race") — name+location editor for the device's already-created
 *  race (see RaceDetailsViewModel's own doc: creation itself moved to Setup Race, and
 *  course/bib-range fields are gone entirely). Reached from any mode screen's own top bar. Name
 *  stays locked once the race is active, same as always; location is editable even then — that's
 *  the screen's whole new purpose (see RaceDetailsViewModel.relocate). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceDetailsScreen(
    existingRaceId: Long,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    viewModel: RaceDetailsViewModel = viewModel(factory = RaceDetailsViewModel.factory(existingRaceId)),
) {
    val existingRace by viewModel.existingRace.collectAsStateWithLifecycle()
    val raceIsActive by viewModel.raceIsActive.collectAsStateWithLifecycle()
    val cpModeActive by viewModel.cpModeActive.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val raceNameHistory by viewModel.raceNameHistory.collectAsStateWithLifecycle()
    val locationHistory by viewModel.locationHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    // Pre-fill exactly once from the loaded race — later emissions (e.g. a Mule pull touching
    // this race elsewhere) must not stomp on what the operator is typing.
    var prefilled by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(existingRace) {
        val race = existingRace ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        name = race.name
        location = race.location
        prefilled = true
    }

    // Name is locked the instant the race is active, exactly as before — it's baked into the
    // label's sync identity, and a race already recording history needs a different name to
    // actually be a new race. Location is NOT gated on raceIsActive any more — that's this
    // screen's whole new purpose (relocating mid-race); it only needs the initial prefill to
    // have landed, same guard every other field on this screen has always used before it's safe
    // to let the operator start typing.
    val nameFieldEnabled = prefilled && !raceIsActive
    val locationFieldEnabled = prefilled
    val nameValid = isValidRaceName(name)
    // Only enforced when CP Mode is among the currently-active modes on this race — mirrors
    // CpModeScreen's own Start-button gating on the exact same check; every other mode's
    // location stays free-form, same as it always has been.
    val locationValid = !cpModeActive || isValidCpLocation(location)
    // Pre-race: both fields matter, exactly as before. Mid-race: name is locked to its already-
    // valid stored value, so only location's own validity gates Save.
    val canSave = prefilled && !isSaving && location.isNotBlank() && locationValid &&
        (raceIsActive || (name.isNotBlank() && nameValid))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relocate") },
                navigationIcon = { TextButton(onClick = withClickSound(onCancel)) { Text("Cancel") } },
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!deviceName.isNullOrBlank()) {
                Text("Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
            }
            HistoryTextField(
                value = name,
                onValueChange = { name = it },
                label = "Race name (letters, numbers, - only)",
                history = raceNameHistory,
                enabled = nameFieldEnabled,
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
            if (raceIsActive) {
                Text(
                    "Race name is locked because this race has already started — set up a new " +
                        "race (Setup Device > Setup Race) for a different name.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HistoryTextField(
                value = location,
                onValueChange = { location = it },
                label = if (cpModeActive) "Location (e.g. CP1, CP2-Bridge)" else "Location (e.g. Finish, CP1, CP2, et al)",
                history = locationHistory,
                enabled = locationFieldEnabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (cpModeActive && location.isNotBlank() && !locationValid) {
                Text(
                    "CP Mode's location must look like CP1, CP2-Bridge, etc. — \"CP\" followed by a number from 1 upwards, with an optional -name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (raceIsActive) {
                Text(
                    "Saving a new location here records that this device has moved — its already-" +
                        "recorded entries stay exactly as they are, and this is undoable like any " +
                        "other entry if it was a mistake.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = withClickSound {
                    isSaving = true
                    scope.launch {
                        if (raceIsActive) viewModel.relocate(location) else viewModel.save(name, location)
                        onSaved()
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (raceIsActive) "Save (relocate)" else "Save") }
        }
    }
}
