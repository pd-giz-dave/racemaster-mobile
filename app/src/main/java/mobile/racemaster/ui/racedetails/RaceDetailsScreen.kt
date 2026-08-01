package mobile.racemaster.ui.racedetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mobile.racemaster.data.repository.MAX_BIB_NUMBER
import mobile.racemaster.data.repository.MIN_BIB_NUMBER
import mobile.racemaster.data.repository.isModeStarted
import mobile.racemaster.data.repository.isValidCpLocation
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.ui.components.HideKeyboardButton
import mobile.racemaster.ui.components.HistoryTextField
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceDetailsScreen(
    mode: AppMode,
    existingRaceId: Long?,
    onSaved: (raceId: Long) -> Unit,
    onCancel: () -> Unit,
    viewModel: RaceDetailsViewModel = viewModel(factory = RaceDetailsViewModel.factory(mode, existingRaceId)),
) {
    val existingRace by viewModel.existingRace.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val raceNameHistory by viewModel.raceNameHistory.collectAsStateWithLifecycle()
    val courseHistory by viewModel.courseHistory.collectAsStateWithLifecycle()
    val locationHistory by viewModel.locationHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The on-screen keyboard has no physical Tab key, and without an explicit ImeAction.Next
    // + KeyboardActions.onNext every field here defaults to a plain "Done" action that just
    // dismisses the keyboard — leaving no way to advance to the next field at all, let alone
    // one that also scrolls it into view (bringIntoView() below only fires on focus, and a
    // field the operator can't reach by tapping — fully scrolled below the keyboard — can
    // never receive that focus in the first place). moveFocus(Down) plus each field's own
    // bringIntoView-on-focus (see HistoryTextField) together give the keyboard's own "next"
    // arrow the same effect a physical Tab key would have.
    val focusManager = LocalFocusManager.current
    val nextFieldAction = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })

    var name by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }
    // "Finish" out of the box for a new race (most stations recording a race are at the
    // finish line) — an existing race's own saved value overrides this below, same as
    // name/course's own (blank) defaults get overridden once the real race loads.
    var location by remember { mutableStateOf("Finish") }
    var startText by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("") }
    // Pre-fill exactly once from the loaded race, when editing — later emissions (e.g. a
    // Mule pull touching this race elsewhere) must not stomp on what the operator is typing.
    var prefilled by remember { mutableStateOf(existingRaceId == null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(existingRace) {
        val race = existingRace ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        name = race.name
        course = race.course
        location = race.location
        race.bibsRangeStart?.let { startText = it.toString() }
        race.bibsRangeCount?.let { countText = it.toString() }
        prefilled = true
    }

    // Auto-fills Course/Location with the most-recently-used value for a brand-new race only
    // (editing an existing race already pre-fills from its own real saved values above) — each
    // history list is already stored most-recent-first (see
    // SettingsRepository.addToStringHistory), so the first entry is exactly that. Race name is
    // deliberately left alone, unlike Course/Location which usually repeat across races at the
    // same event — auto-filling it risks silently resubmitting an old race's name for what
    // should be a genuinely new one. Each guarded by both a one-shot flag (so picking a
    // different value afterward doesn't get stomped once the flow re-emits) and a "still at its
    // untouched initial value" check (so a fast operator who starts typing before the history
    // flow's first real emission arrives never gets overwritten).
    var courseAutoFilled by remember { mutableStateOf(false) }
    LaunchedEffect(courseHistory) {
        if (existingRaceId == null && !courseAutoFilled && course.isBlank() && courseHistory.isNotEmpty()) {
            course = courseHistory.first()
            courseAutoFilled = true
        }
    }
    var locationAutoFilled by remember { mutableStateOf(false) }
    LaunchedEffect(locationHistory) {
        if (existingRaceId == null && !locationAutoFilled && location == "Finish" && locationHistory.isNotEmpty()) {
            location = locationHistory.first()
            locationAutoFilled = true
        }
    }

    // Identical field set for Time, Bibs, and CP, both for create and edit — Time Mode never
    // actually reads the first bib number for anything, but the form (and the feedback shown
    // on-screen later) stays the same either way, per instruction.
    val showRunnerFields = mode == AppMode.TIME || mode == AppMode.BIBS || mode == AppMode.CP

    // Whether [mode] has been started for this segment — same per-mode startedAtMillis field
    // isRaceActive/isRaceInProgress read (see RaceProgress.kt), only clearing on Reset, not on
    // Stop. Once true, every field except runner count locks read-only: name/course/location/
    // first bib number are what other stations and the server record already key off, so
    // changing them mid-race would desync what's already been recorded elsewhere — Reset is
    // the deliberate "start over" escape hatch instead. Runner count stays editable throughout
    // since the final headcount can genuinely still change right up to race start.
    val isStarted = existingRaceId != null && isModeStarted(mode, existingRace)
    val fieldsEnabled = prefilled && !isStarted
    val countFieldEnabled = prefilled

    val start = startText.toIntOrNull()
    val count = countText.toIntOrNull()
    val rangeEnd = if (start != null && count != null) start + count - 1 else null
    val countFieldsValid = !showRunnerFields || !countFieldEnabled ||
        (start != null && start in MIN_BIB_NUMBER..MAX_BIB_NUMBER && count != null && count >= 1 && rangeEnd != null && rangeEnd <= MAX_BIB_NUMBER)
    val locationValid = mode != AppMode.CP || isValidCpLocation(location)
    val canSave = prefilled && !isSaving && name.isNotBlank() && course.isNotBlank() && location.isNotBlank() && countFieldsValid && locationValid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingRaceId == null) "New Race" else "Race Details") },
                navigationIcon = { TextButton(onClick = withClickSound(onCancel)) { Text("Cancel") } },
                actions = { HideKeyboardButton() },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for
        // every screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // Each field brings itself into view on focus (see HistoryTextField/the two raw
        // OutlinedTextFields below) — a per-field fix for a budget/older device where the
        // keyboard's own ime inset either never fired or settled too late to scroll a
        // just-tapped field out from behind it. That leaves one gap: nothing brings the
        // Save/Create button itself into view once the operator's done with the last field.
        // Re-settling to the max scroll position specifically when the keyboard finishes
        // closing (not on every intermediate frame while it's still open, which would fight
        // the per-field scrolling above) is what keeps the submit button reachable afterward
        // — same fix already proven in TimeModeScreen/BibsModeScreen's own editors.
        val scrollState = rememberScrollState()
        val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
        LaunchedEffect(imeBottomPx == 0) {
            if (imeBottomPx == 0) scrollState.animateScrollTo(scrollState.maxValue)
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!deviceName.isNullOrBlank()) {
                Text("Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
            }
            HistoryTextField(
                value = name,
                onValueChange = { name = it },
                label = "Race name",
                // Picking a previous name only ever fills this field — course/first bib/runner
                // count are unrelated and stay exactly as already entered (see
                // SettingsRepository.raceNameHistory's own doc).
                history = raceNameHistory,
                enabled = fieldsEnabled,
                // Always followed by Course, so always "Next" — see nextFieldAction's own doc.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = nextFieldAction,
                modifier = Modifier.fillMaxWidth(),
            )
            HistoryTextField(
                value = course,
                onValueChange = { course = it },
                label = "Course (e.g. Seniors, Juniors)",
                // Same independent-field behavior as the Race name field above — picking a
                // previous course only ever fills this field.
                history = courseHistory,
                enabled = fieldsEnabled,
                // Always followed by Location, so always "Next" — see nextFieldAction's own doc.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = nextFieldAction,
                modifier = Modifier.fillMaxWidth(),
            )
            HistoryTextField(
                value = location,
                onValueChange = { location = it },
                label = if (mode == AppMode.CP) "Location (e.g. CP1, CP2-Bridge)" else "Location (e.g. Finish, CP1, CP2, et al)",
                // Same independent-field behavior as Race name/Course above — picking a
                // previous location only ever fills this field.
                history = locationHistory,
                enabled = fieldsEnabled,
                // The last field gets "Done" (dismisses the keyboard); every other field gets
                // "Next" — Location is last exactly when the runner fields aren't shown.
                keyboardOptions = KeyboardOptions(imeAction = if (showRunnerFields) ImeAction.Next else ImeAction.Done),
                keyboardActions = if (showRunnerFields) nextFieldAction else KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (mode == AppMode.CP && location.isNotBlank() && !locationValid) {
                Text(
                    "CP Mode's location must look like CP1, CP2-Bridge, etc. — \"CP\" followed by a number from 1 upwards, with an optional -name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (isStarted) {
                Text(
                    "Once the race has started, only Number of runners can still be changed — " +
                        "Reset to edit anything else.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (showRunnerFields) {
                // bringIntoViewRequester + onFocusEvent — see HistoryTextField's own doc for
                // why relying on the keyboard's own ime inset alone isn't reliable everywhere.
                val startFieldRequester = remember { BringIntoViewRequester() }
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.filter(Char::isDigit).take(3) },
                    enabled = fieldsEnabled,
                    singleLine = true,
                    label = { Text("First bib number (1–999)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = nextFieldAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(startFieldRequester)
                        .onFocusEvent { state ->
                            if (state.isFocused) scope.launch { startFieldRequester.bringIntoView() }
                        },
                )
                val countFieldRequester = remember { BringIntoViewRequester() }
                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter(Char::isDigit).take(3) },
                    enabled = countFieldEnabled,
                    singleLine = true,
                    label = { Text("Number of runners") },
                    // Last field in this branch — "Done" dismisses the keyboard.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(countFieldRequester)
                        .onFocusEvent { state ->
                            if (state.isFocused) scope.launch { countFieldRequester.bringIntoView() }
                        },
                )
            }
            // Save/Create alongside "Clear race" — same side-by-side pattern as Setup Server's
            // Log-in/No Server row: primary action plus a "reset to nothing" secondary action
            // that exits the form the same way the primary's success path does. Always shown,
            // same as Setup Server's own row — Clear race just stays disabled rather than the
            // row disappearing, whether that's because there's no race yet to clear
            // (existingRaceId null) or because this one is currently active (canClearRace).
            val canClearRace by viewModel.canClearRace.collectAsStateWithLifecycle()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = withClickSound {
                        isSaving = true
                        scope.launch {
                            val raceId = viewModel.save(name, course, location, start, count)
                            onSaved(raceId)
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) { Text(if (existingRaceId == null) "Create" else "Save") }
                OutlinedButton(
                    onClick = withClickSound {
                        isSaving = true
                        scope.launch {
                            viewModel.clearRace()
                            isSaving = false
                            onCancel()
                        }
                    },
                    enabled = canClearRace && !isSaving,
                    modifier = Modifier.weight(1f),
                ) { Text("Clear race") }
            }
        }
    }
}
