package mobile.racemaster.ui.bibsmode

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.MainActivity
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.ui.components.ActionPickerDialog
import mobile.racemaster.ui.components.DigitKeypad
import mobile.racemaster.ui.components.EntryLogList
import mobile.racemaster.ui.components.EntryModeHeaderInfo
import mobile.racemaster.ui.components.ModeScreenTopBar
import mobile.racemaster.ui.components.StopOrResetButton
import mobile.racemaster.ui.components.UndoLastButton
import mobile.racemaster.ui.components.rememberListClickGuard
import mobile.racemaster.util.withClickSound

private const val BUTTON_HEIGHT_DP = 48

// Default Material button horizontal padding (24dp/side) leaves almost no room for text once
// four buttons share a row — cut it down instead of shrinking the font, so labels like
// "Finish"/"Seniors" stay readable.
private val BUTTON_ROW_CONTENT_PADDING = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibsModeScreen(
    onChangeMode: () -> Unit,
    onNewRace: () -> Unit,
    onEditRace: (raceId: Long) -> Unit,
    onEditEntry: (entryId: Long) -> Unit,
    viewModel: BibsModeViewModel = viewModel(factory = BibsModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val btPollingStatus by viewModel.btPollingStatus.collectAsStateWithLifecycle()

    // Same external HID trigger mechanism Time Mode uses (MainActivity.onExternalSplitTrigger),
    // registered here while Bibs Mode is on screen so a volume button (or any other recognized
    // HID key) logs the pending event exactly like tapping the Log button.
    val activity = LocalActivity.current as MainActivity
    val currentOnSubmit by rememberUpdatedState(viewModel::submit)
    val canExternalTrigger by rememberUpdatedState(uiState.canSubmit)
    DisposableEffect(activity) {
        activity.onExternalSplitTrigger = { if (canExternalTrigger) currentOnSubmit() }
        onDispose { activity.onExternalSplitTrigger = null }
    }

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "Bibs Mode",
                newRaceEnabled = !uiState.raceInProgress,
                thisRaceEnabled = uiState.raceId != null,
                onNewRace = onNewRace,
                onThisRace = { uiState.raceId?.let(onEditRace) },
                onChangeMode = onChangeMode,
            )
        },
        // MainActivity's outer Scaffold (no bottomBar) already reserves the navigation
        // bar's bottom inset for every screen — without this, this inner Scaffold's own
        // default contentWindowInsets reserves it a second time, wasting a whole nav-bar
        // height of blank space above the system bar and leaving less room for the list.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BibsModeContent(
            uiState = uiState,
            deviceName = deviceName,
            btPollingStatus = btPollingStatus,
            onStart = viewModel::startBibsMode,
            onDigit = viewModel::onDigit,
            onBackspace = viewModel::onBackspace,
            onClear = viewModel::onClear,
            onSetPendingEventType = viewModel::setPendingEventType,
            onSubmit = viewModel::submit,
            onStop = viewModel::stopBibsMode,
            onReset = viewModel::resetBibsMode,
            onUndo = viewModel::undoLast,
            onEditEntry = onEditEntry,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun BibsModeContent(
    uiState: BibsModeUiState,
    deviceName: String?,
    btPollingStatus: BtPollingStatus,
    onStart: () -> Unit,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSetPendingEventType: (HistoryAction) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onEditEntry: (entryId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // BoxWithConstraints (not a fixed dp budget) is what makes the header/list split adapt
    // to whatever screen the app is running on: maxHeight below is the actual measured space
    // available for this content on THIS device. The header gets everything except a fixed
    // floor reserved for the list — not an even split — because a flat 50/50 turned out to
    // clip the header's full keypad+buttons even on a tall phone (XCover) where there was
    // never a problem: the header naturally wants ~80% of the space, and on a tall screen
    // that 80% is still generously more than the list's floor in absolute terms, so it fits
    // without scrolling exactly as before. Only on a short screen (e.g. the Cubot), where
    // the header's natural content would eat the floor too, does the cap actually bind.
    BoxWithConstraints(modifier = modifier) {
        val minListHeight = 140.dp
        val headerMaxHeight = (maxHeight - minListHeight).coerceAtLeast(0.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var showEventPicker by remember { mutableStateOf(false) }
            val listClickGuard = rememberListClickGuard()

            // The header (title/keypad/buttons) is itself scrollable so the quick-entry keypad
            // can scroll out of view on a short screen — there's no time pressure entering
            // bibs, runners can wait in the finish funnel while a bib is being recorded.
            // Editing an already-recorded entry is a separate, dedicated screen (see
            // EditEntryScreen) rather than composed inline here — that inline editor used to
            // live inside this very header's own height-capped, ime-inset-driven scroll
            // region, which confirmed in the field on a Cubot that imePadding()/
            // WindowInsets.ime don't reliably report the keyboard's real height, leaving
            // Save/Cancel genuinely unreachable there.
            val headerScrollState = rememberScrollState()

            Column(
                // Capped so the list below always keeps its floor (see BoxWithConstraints
                // above) — on a short screen where the header's natural content (text lines
                // + keypad + buttons) would otherwise eat the whole screen, this caps it and
                // its own verticalScroll takes over. On a screen tall enough that the header
                // already fits under the cap, heightIn(max=) doesn't force it larger — it
                // just stays its natural size, so the list gets whatever's left over exactly
                // as before.
                modifier = Modifier.heightIn(max = headerMaxHeight).verticalScroll(headerScrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EntryModeHeaderInfo(
                    deviceName = deviceName,
                    raceLabel = uiState.raceLabel,
                    raceLocation = uiState.raceLocation,
                    nextSplitNumber = uiState.nextSplitNumber,
                    dupCount = uiState.dupCount,
                    unsyncedCount = uiState.unsyncedCount,
                    lastSyncedAtMillis = uiState.lastSyncedAtMillis,
                    firstBibNumber = uiState.firstBibNumber,
                    expectedRunnerCount = uiState.expectedRunnerCount,
                    finishedCount = uiState.finishedCount,
                    duplicateBibNumbers = uiState.duplicateBibNumbers,
                    outstandingBibs = uiState.outstandingBibs,
                    serverStatus = uiState.serverStatus,
                    btPollingStatus = btPollingStatus,
                )
                if (!uiState.started) {
                    // Nothing recorded yet for this segment (a fresh race, a race switched
                    // into from a different mode, or one just Reset) — side-effect-free to
                    // just look at, exactly like Time Mode's own pre-Start state. Nothing
                    // below is written until this is pressed (see
                    // BibsModeRepository.startBibsMode).
                    Button(
                        onClick = withClickSound(onStart),
                        enabled = uiState.raceId != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                    ) { Text("START", style = MaterialTheme.typography.displaySmall) }
                } else {
                    Text(
                        text = if (uiState.pendingEventType in BIB_REQUIRED_ACTIONS) {
                            uiState.currentDigits.ifEmpty { "Enter bib" }
                        } else {
                            uiState.pendingEventType.displayName()
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    DigitKeypad(
                        onDigit = onDigit,
                        onBackspace = onBackspace,
                        onClear = onClear,
                        enabled = uiState.raceId != null,
                        buttonHeight = 52.dp,
                        spacing = 4.dp,
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = withClickSound { listClickGuard.trigger(); onSubmit() },
                            enabled = uiState.canSubmit,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        ) { Text(uiState.pendingEventType.displayName()) }
                        OutlinedButton(
                            onClick = withClickSound { showEventPicker = true },
                            enabled = uiState.raceId != null && !uiState.stopped,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        ) { Text("Event") }
                        StopOrResetButton(
                            isStopped = uiState.stopped,
                            resetDescription = "This clears every bib entry and resets ready to start again from scratch.",
                            onStop = onStop,
                            onReset = onReset,
                            enabled = uiState.raceId != null,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        )
                        UndoLastButton(
                            enabled = uiState.raceId != null && uiState.canUndo,
                            onClick = onUndo,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        )
                    }

                    if (showEventPicker) {
                        ActionPickerDialog(
                            options = EVENT_PICKER_OPTIONS,
                            current = uiState.pendingEventType,
                            onSelect = { type ->
                                onSetPendingEventType(type)
                                showEventPicker = false
                            },
                            onDismiss = { showEventPicker = false },
                        )
                    }
                }
            }

            if (uiState.started) {
                HorizontalDivider()
                EntryLogList(
                    entries = uiState.entries,
                    onEntryClick = { onEditEntry(it.id) },
                    modifier = Modifier.weight(1f),
                    clickGuard = listClickGuard,
                )
            }
        }
    }
}
