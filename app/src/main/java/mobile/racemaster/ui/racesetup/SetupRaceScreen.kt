package mobile.racemaster.ui.racesetup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import mobile.racemaster.data.mule.AvailableRace
import mobile.racemaster.data.repository.isValidLocationForMode
import mobile.racemaster.data.repository.isValidRaceName
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.ui.components.DiscardChangesDialog
import mobile.racemaster.ui.components.HideKeyboardButton
import mobile.racemaster.ui.components.HistoryTextField
import mobile.racemaster.util.formatShortDate
import mobile.racemaster.util.withClickSound

/** Sets up the one race this device will record against — name, location and mode (see
 *  TODO.md's phase 1: course/first-bib/runner-count are gone). The seniors/juniors suffix the
 *  web app expects is just typed as part of the race name now — e.g. "Pontesbury-Seniors" —
 *  rather than picked from a separate menu. Reached from Setup Device. Disabled while a race is
 *  already active, same as NameDeviceScreen's own guard for renaming — see SetupRaceViewModel's
 *  own doc. A race records at most one mode at a time (changeable later only via Relocate — see
 *  RaceDetailsScreen), so choosing it here is what lets Mode Picker collapse down to a single
 *  "Start <mode>" button once this screen's own Save has run. */
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
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf<AppMode?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    // The exact values this screen was seeded with, kept alongside the editable ones above so
    // the "Discard changes?" exit-confirmation (see attemptExit/hasChanges below) can tell
    // whether the operator has actually typed anything this visit worth warning about losing.
    // Save's own "already set up" signal comes from hasActiveRace alone (see canSave below), not
    // from hasChanges — hasActiveRace already correctly reflects the race's real state, including
    // flipping back to false once Race History's force-reset flow clears it, and Save must stay
    // reachable whenever !hasActiveRace regardless of whether the fields were retyped: a
    // force-reset race with nothing retyped is exactly the case where the operator needs to
    // resave the same values with nothing to change (a real, confirmed bug when hasChanges used
    // to gate Save too).
    var initialName by remember { mutableStateOf("") }
    var initialLocation by remember { mutableStateOf("") }
    var initialMode by remember { mutableStateOf<AppMode?>(null) }
    // Seeds this screen's own editable state from the persisted draft exactly once, the first
    // time it actually arrives (DataStore reads are async, so the very first composition sees
    // [draft] still null, not yet the real stored draft — see SetupRaceViewModel.draft's own doc
    // for why that distinction, not just "the first emission", is what this must wait for: a
    // plain `remember { mutableStateOf(draft.name) }` above would miss that first real emission,
    // and treating a non-null placeholder's own arrival as "seeded" would latch in stale
    // defaults and then persist them over the real draft moments later — a real, confirmed bug).
    var draftSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(draft) {
        if (draftSeeded) return@LaunchedEffect
        val loadedDraft = draft ?: return@LaunchedEffect
        draftSeeded = true
        // "unknown" alone would collide identically across every never-named device — the date
        // suffix keeps them distinguishable (see formatShortDate's own doc). Only applies to
        // this fallback, never to a real name — see buildRaceLabel's own doc for why a typed,
        // server-inherited, or convention-following name is never modified.
        name = loadedDraft.name.ifBlank { "unknown-${formatShortDate(System.currentTimeMillis())}" }
        location = loadedDraft.location.ifBlank { "Finish" }
        mode = loadedDraft.mode?.let { raw -> runCatching { AppMode.valueOf(raw) }.getOrNull() }
        initialName = name
        initialLocation = location
        initialMode = mode
    }
    // Persists every keystroke/pick back to the draft so navigating away (e.g. via Setup
    // Device) and back doesn't lose progress — a no-op no-DB-write concern (DataStore), so
    // firing on every change rather than only on blur/Save is fine. The draft is never cleared
    // on a successful Save either (see SetupRaceViewModel.save's own doc) — it's a durable
    // sticky default across every visit to this screen, not just within one not-yet-saved
    // session, the same way Setup Server's own draft behaves.
    LaunchedEffect(name, location, mode) {
        if (draftSeeded) viewModel.saveDraft(name, location, mode)
    }
    // One-shot connectivity hint for Scan Server's own enablement — see
    // MuleServerSetupScreen's identical `hasInternet` pattern for why this is checked once on
    // entry rather than subscribed to live.
    var hasInternet by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { hasInternet = viewModel.hasInternetConnectivity() }

    // A blank name is allowed — it defaults to "unknown" at save time (see effectiveName below),
    // ToDo: a blank name is not allowed
    // a real fallback *value* rather than just UI chrome, flagging to the web app that this
    // device hasn't been adopted yet. A non-blank name still has to pass the real format check.
    val nameValid = name.isBlank() || isValidRaceName(name)
    val effectiveName = name.trim().ifBlank { "unknown" }
    val locationValid = isValidLocationForMode(location, mode)
    val hasChanges = name != initialName || location != initialLocation || mode != initialMode
    val canSave = !hasActiveRace && !isSaving && nameValid && mode != null && locationValid

    // Leaving with unsaved edits still on screen needs a confirm — and, if confirmed, the draft
    // must revert to what it held on entry (see SetupRaceViewModel.revertDraft's own doc),
    // not just pop the screen: otherwise this visit's abandoned edits stay sitting in the
    // draft, indistinguishable from an already-saved baseline, and Save comes back permanently
    // disabled on the next visit — a real, confirmed bug. Wired to both the top bar's own Back
    // button and the system back gesture/button, so neither one bypasses this.
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val attemptExit = {
        if (hasChanges) showDiscardConfirm = true else onDone()
    }
    BackHandler(onBack = attemptExit)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Race") },
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

            // Online branch — scan the server for this owner's own recent races and pick one to
            // fill in the name field below, instead of typing it. Picking a race here is purely
            // an autofill (see SetupRaceScreen's own onPick below) — Save is the one and only
            // place anything actually gets written, so this needs no location/mode chosen first.
            OutlinedButton(
                onClick = withClickSound { viewModel.scanServer() },
                enabled = !hasActiveRace && availableRaces != AvailableRacesState.Loading && hasInternet == true,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan Server for a race name or enter it") }
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
                        name = race.raceName.ifBlank { race.raceLabel }
                        viewModel.dismissAvailableRaces()
                    },
                    onDismiss = viewModel::dismissAvailableRaces,
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
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChoiceButton("Time", mode == AppMode.TIME, !hasActiveRace, Modifier.weight(1f)) { mode = AppMode.TIME }
                ModeChoiceButton("Bibs", mode == AppMode.BIBS, !hasActiveRace, Modifier.weight(1f)) { mode = AppMode.BIBS }
                ModeChoiceButton("CP", mode == AppMode.CP, !hasActiveRace, Modifier.weight(1f)) { mode = AppMode.CP }
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
            if (location.isNotBlank() && mode == AppMode.CP && !locationValid) {
                Text(
                    "CP Mode's location must look like \"CP1\", \"CP2-Bridge\", etc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = withClickSound {
                    val chosenMode = mode ?: return@withClickSound
                    isSaving = true
                    scope.launch {
                        viewModel.save(effectiveName, location, chosenMode)
                        onDone()
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            Text(
                "Saving also makes this device visible to the web app.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showDiscardConfirm) {
        DiscardChangesDialog(
            onConfirm = {
                showDiscardConfirm = false
                scope.launch {
                    viewModel.revertDraft(initialName, initialLocation, initialMode)
                    onDone()
                }
            },
            onDismiss = { showDiscardConfirm = false },
        )
    }
}

@Composable
private fun ModeChoiceButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = withClickSound(onClick), enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = withClickSound(onClick), enabled = enabled, modifier = modifier) { Text(label) }
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
