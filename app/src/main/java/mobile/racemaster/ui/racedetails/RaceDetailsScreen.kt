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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import mobile.racemaster.data.settings.AppMode
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
        race.bibsRangeStart?.let { startText = it.toString() }
        race.bibsRangeCount?.let { countText = it.toString() }
        prefilled = true
    }

    // Identical field set for Time and Bibs, both for create and edit — Time Mode never
    // actually reads the first bib number for anything, but the form (and the feedback shown
    // on-screen later) stays the same either way, per instruction.
    val showRunnerFields = mode == AppMode.TIME || mode == AppMode.BIBS

    // "Fresh" = no real progress recorded yet, true both for a brand-new race and for one
    // that's just been Reset — safe to still change the runner count/bib range in either
    // case, since no recorded splits/entries depend on the old value.
    val isFresh = existingRaceId == null || when (mode) {
        AppMode.BIBS -> existingRace?.bibsModeNextSplit == 1
        AppMode.TIME -> existingRace?.timeModeNextSplit == 1
        AppMode.MULE -> true
    }
    val countFieldsEnabled = prefilled && isFresh

    val start = startText.toIntOrNull()
    val count = countText.toIntOrNull()
    val rangeEnd = if (start != null && count != null) start + count - 1 else null
    val countFieldsValid = !showRunnerFields || !countFieldsEnabled ||
        (start != null && start in MIN_BIB_NUMBER..MAX_BIB_NUMBER && count != null && count >= 1 && rangeEnd != null && rangeEnd <= MAX_BIB_NUMBER)
    val canSave = prefilled && !isSaving && name.isNotBlank() && course.isNotBlank() && countFieldsValid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingRaceId == null) "New Race" else "Race Details") },
                navigationIcon = { TextButton(onClick = withClickSound(onCancel)) { Text("Cancel") } },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                // The last field gets "Done" (dismisses the keyboard); every other field gets
                // "Next" — Course is last exactly when the runner fields aren't shown.
                keyboardOptions = KeyboardOptions(imeAction = if (showRunnerFields) ImeAction.Next else ImeAction.Done),
                keyboardActions = if (showRunnerFields) nextFieldAction else KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showRunnerFields) {
                // bringIntoViewRequester + onFocusEvent — see HistoryTextField's own doc for
                // why relying on the keyboard's own ime inset alone isn't reliable everywhere.
                val startFieldRequester = remember { BringIntoViewRequester() }
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.filter(Char::isDigit).take(3) },
                    enabled = countFieldsEnabled,
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
                    enabled = countFieldsEnabled,
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
                if (!countFieldsEnabled) {
                    Text(
                        "First bib number and number of runners are fixed once the race has " +
                            "real splits/entries recorded — Reset to change them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = withClickSound {
                    isSaving = true
                    scope.launch {
                        val raceId = viewModel.save(name, course, start, count)
                        onSaved(raceId)
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (existingRaceId == null) "Create" else "Save") }
        }
    }
}
