package mobile.racemaster.ui.racedetails

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
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
import mobile.racemaster.data.repository.isValidLocationForMode
import mobile.racemaster.data.repository.isValidRaceName
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.ui.components.DiscardChangesDialog
import mobile.racemaster.ui.components.HideKeyboardButton
import mobile.racemaster.ui.components.HistoryTextField
import mobile.racemaster.util.withClickSound

/** "Relocate" (formerly "This Race") — name/location/mode editor for the device's
 *  already-created race (see RaceDetailsViewModel's own doc: creation itself moved to Setup
 *  Race, and course/bib-range fields are gone entirely). Reached from any mode screen's own top
 *  bar. Name stays locked once the race is active, same as always; location and mode are both
 *  editable even then — that's the screen's whole new purpose (see RaceDetailsViewModel.save). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceDetailsScreen(
    existingRaceId: Long,
    // true when this save actually changed the mode (not just location) — lets the caller
    // navigate away from whichever mode screen Relocate was launched from, rather than popping
    // back onto it: that screen has no idea its race just switched to a different mode out from
    // under it (its own started/stopped columns and mode-scoped current-segment/Undo are
    // untouched by a mode-only Relocate — see RaceRepository.recordModeStart's own doc), so
    // popping back there left the operator stranded looking at a stale screen for the mode they
    // just left, with its own Undo silently targeting an unrelated entry from that old mode
    // instead of the relocate itself (confirmed in the field).
    onSaved: (modeChanged: Boolean) -> Unit,
    onCancel: () -> Unit,
    viewModel: RaceDetailsViewModel = viewModel(factory = RaceDetailsViewModel.factory(existingRaceId)),
) {
    val existingRace by viewModel.existingRace.collectAsStateWithLifecycle()
    val raceIsActive by viewModel.raceIsActive.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val raceNameHistory by viewModel.raceNameHistory.collectAsStateWithLifecycle()
    val locationHistory by viewModel.locationHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf<AppMode?>(null) }
    // Pre-fill exactly once from the loaded race — later emissions (e.g. a Mule pull touching
    // this race elsewhere) must not stomp on what the operator is typing.
    var prefilled by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    // The exact values this screen was seeded with, so leaving via Back can tell whether the
    // operator actually changed anything this visit — see showDiscardConfirm's own doc below.
    var initialName by remember { mutableStateOf("") }
    var initialLocation by remember { mutableStateOf("") }
    var initialMode by remember { mutableStateOf<AppMode?>(null) }

    LaunchedEffect(existingRace) {
        val race = existingRace ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        name = race.name
        location = race.location
        mode = race.mode?.let { raw -> runCatching { AppMode.valueOf(raw) }.getOrNull() }
        initialName = name
        initialLocation = location
        initialMode = mode
        prefilled = true
    }

    // Name is locked the instant the race is active, exactly as before — it's baked into the
    // label's sync identity, and a race already recording history needs a different name to
    // actually be a new race. Location and mode are NOT gated on raceIsActive any more — that's
    // this screen's whole new purpose (relocating/switching mode mid-race); they only need the
    // initial prefill to have landed, same guard every other field on this screen has always
    // used before it's safe to let the operator start typing.
    val nameFieldEnabled = prefilled && !raceIsActive
    val fieldsEnabled = prefilled
    val nameValid = isValidRaceName(name)
    val locationValid = isValidLocationForMode(location, mode)
    // Pre-race: both name and location/mode matter, exactly as before. Mid-race: name is locked
    // to its already-valid stored value, so only location/mode validity gates Save.
    val canSave = prefilled && !isSaving && locationValid &&
        (raceIsActive || (name.isNotBlank() && nameValid))
    val hasChanges = name != initialName || location != initialLocation || mode != initialMode

    // Leaving with unsaved edits still on screen needs a confirm — no separate draft to revert
    // here (unlike Setup Race/Setup Server), since this screen's own state is only ever seeded
    // from the race's own current values and never persisted anywhere until Save — but silently
    // discarding a typed relocate/mode-switch is still a real "lost my edit" trap worth
    // guarding against. Wired to both the top bar's own Back button and the system back
    // gesture/button.
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val attemptExit = {
        if (hasChanges) showDiscardConfirm = true else onCancel()
    }
    BackHandler(onBack = attemptExit)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relocate") },
                actions = {
                    HideKeyboardButton()
                    TextButton(onClick = withClickSound(attemptExit)) { Text("Back") }
                },
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
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RelocateModeButton("Time", mode == AppMode.TIME, fieldsEnabled, Modifier.weight(1f)) { mode = AppMode.TIME }
                RelocateModeButton("Bibs", mode == AppMode.BIBS, fieldsEnabled, Modifier.weight(1f)) { mode = AppMode.BIBS }
                RelocateModeButton("CP", mode == AppMode.CP, fieldsEnabled, Modifier.weight(1f)) { mode = AppMode.CP }
            }
            HistoryTextField(
                value = location,
                onValueChange = { location = it },
                label = if (mode == AppMode.CP) "Location (e.g. CP1, CP2-Bridge)" else "Location (e.g. Finish, CP1, CP2, et al)",
                history = locationHistory,
                enabled = fieldsEnabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (mode == AppMode.CP && location.isNotBlank() && !locationValid) {
                Text(
                    "CP Mode's location must look like CP1, CP2-Bridge, etc. — \"CP\" followed by a number from 1 upwards, with an optional -name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (raceIsActive) {
                Text(
                    "Saving a new location and/or mode here records that this device has moved " +
                        "and/or switched — its already-recorded entries stay exactly as they are, " +
                        "and this is undoable like any other entry if it was a mistake.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = withClickSound {
                    val chosenMode = mode ?: return@withClickSound
                    isSaving = true
                    scope.launch {
                        val succeeded = viewModel.save(name, location, chosenMode)
                        isSaving = false
                        if (succeeded) onSaved(chosenMode != initialMode)
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (raceIsActive) "Save (relocate)" else "Save") }
        }
    }

    if (showDiscardConfirm) {
        DiscardChangesDialog(
            onConfirm = {
                showDiscardConfirm = false
                onCancel()
            },
            onDismiss = { showDiscardConfirm = false },
        )
    }
}

@Composable
private fun RelocateModeButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = withClickSound(onClick), enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = withClickSound(onClick), enabled = enabled, modifier = modifier) { Text(label) }
    }
}
