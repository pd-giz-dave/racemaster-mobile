package mobile.racemaster.ui.bibsmode

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.NON_ENTRY_ACTIONS
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.ui.components.ActionPickerDialog
import mobile.racemaster.ui.components.DigitKeypad
import mobile.racemaster.ui.components.EntryLogList
import mobile.racemaster.ui.components.EntryModeHeaderInfo
import mobile.racemaster.ui.components.ModeScreenTopBar
import mobile.racemaster.ui.components.ResetButton
import mobile.racemaster.ui.components.UndoLastButton
import mobile.racemaster.ui.components.rememberListClickGuard
import mobile.racemaster.ui.components.startButtonLabel
import mobile.racemaster.util.formatBibsSoFarText
import mobile.racemaster.util.withClickSound

private const val BUTTON_HEIGHT_DP = 48

// Default Material button horizontal padding (24dp/side) leaves almost no room for text once
// three buttons share a row — cut it down instead of shrinking the font, so labels like
// "Event"/"Reset" stay readable.
private val BUTTON_ROW_CONTENT_PADDING = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

// Deliberately much smaller than BUTTON_HEIGHT_DP (48dp, used by the Event/Reset/Undo row) —
// this selector sits in the row that used to be a bare Text showing the current digits, so it's
// sized to roughly match that text's own line height rather than grow the row.
private val DEFAULT_ACTION_BUTTON_HEIGHT_DP = 36.dp
private val DEFAULT_ACTION_BUTTON_PADDING = PaddingValues(horizontal = 6.dp, vertical = 0.dp)

// Bibs Mode's compact, always-visible selector for which HistoryAction a bib's 3rd digit
// auto-saves as by default (see BibsModeViewModel.onDigit) — distinct from the Event button's
// ActionPickerDialog, which stays a full modal list for manual, after-the-fact correction. This
// one trades a full picker for "a single tap changes the default, right here, with the active
// choice always visible" per TODO.md's own requirement, at the cost of only offering the three
// bib-required actions (BIBS_DEFAULT_ACTION_OPTIONS) rather than the full marker list. [options]
// is that same list already narrowed by isBibsActionAllowedAtLocation for the race's current
// location (see BibsModeUiState.defaultActionOptions) — just one button at a CP, two at Start/Finish.
@Composable
private fun DefaultBibsActionSelector(
    selected: HistoryAction,
    options: List<HistoryAction>,
    onSelect: (HistoryAction) -> Unit,
    enabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { action ->
            val buttonModifier = Modifier.height(DEFAULT_ACTION_BUTTON_HEIGHT_DP)
            if (action == selected) {
                Button(
                    onClick = withClickSound { onSelect(action) },
                    enabled = enabled,
                    contentPadding = DEFAULT_ACTION_BUTTON_PADDING,
                    modifier = buttonModifier,
                ) { Text(action.displayName(), style = MaterialTheme.typography.labelSmall) }
            } else {
                OutlinedButton(
                    onClick = withClickSound { onSelect(action) },
                    enabled = enabled,
                    contentPadding = DEFAULT_ACTION_BUTTON_PADDING,
                    modifier = buttonModifier,
                ) { Text(action.displayName(), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibsModeScreen(
    onChangeMode: () -> Unit,
    onEditRace: (raceId: Long) -> Unit,
    onEditEntry: (entryId: Long) -> Unit,
    viewModel: BibsModeViewModel = viewModel(factory = BibsModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val btPollingStatus by viewModel.btPollingStatus.collectAsStateWithLifecycle()

    // No external HID trigger here (unlike Time Mode) — entry is now bib-driven/auto-saving
    // rather than a single "log the pending event" action a volume button could stand in for.

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "Bibs Mode",
                thisRaceEnabled = uiState.raceId != null,
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
            onDefaultBibsActionSelected = viewModel::onDefaultBibsActionSelected,
            onBackspace = viewModel::onBackspace,
            onClear = viewModel::onClear,
            onEventTypeSelected = viewModel::onEventTypeSelected,
            onReset = viewModel::resetBibsMode,
            onUndo = viewModel::undoLast,
            onEditEntry = onEditEntry,
            onGoToLocation = viewModel::goToLocation,
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
    onDefaultBibsActionSelected: (HistoryAction) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onEventTypeSelected: (HistoryAction) -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onEditEntry: (entryId: Long) -> Unit,
    onGoToLocation: (String) -> Unit,
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
                    soFarText = formatBibsSoFarText(uiState.entries.count { it.type !in NON_ENTRY_ACTIONS }),
                    expectedCount = uiState.expectedCount,
                    outstandingCount = uiState.outstandingCount,
                    duplicateBibNumbers = uiState.duplicateBibNumbers,
                    outstandingBibs = uiState.outstandingBibs,
                    unexpectedBibNumbers = uiState.unexpectedBibNumbers,
                    serverStatus = uiState.serverStatus,
                    btPollingStatus = btPollingStatus,
                    otherOpenLocations = uiState.otherOpenLocations,
                    onGoToLocation = onGoToLocation,
                )
                if (!uiState.started) {
                    // Nothing recorded yet for this segment (a fresh race, a race switched
                    // into from a different mode, or one just Reset) — side-effect-free to
                    // just look at, exactly like Time Mode's own pre-Start state. Nothing
                    // below is written until this is pressed (see
                    // BibsModeRepository.startBibsMode). Always enabled, not gated on
                    // uiState.raceId — reaching this screen at all already implies an active
                    // race (see ModePickerViewModel.handleStartTap), so a transiently-null
                    // uiState.raceId (this per-race flow catching up after a Reset/Relocate)
                    // must never flicker this disabled; the label's own location suffix is
                    // what tells the operator which location Start is about to record into.
                    Button(
                        onClick = withClickSound(onStart),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                    ) { Text(startButtonLabel("START", uiState.raceLocation), style = MaterialTheme.typography.displaySmall) }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DefaultBibsActionSelector(
                            selected = uiState.defaultBibsAction,
                            options = uiState.defaultActionOptions,
                            onSelect = onDefaultBibsActionSelected,
                            enabled = uiState.raceId != null,
                        )
                        Text(
                            text = uiState.currentDigits.ifEmpty { "Enter bib" },
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    DigitKeypad(
                        // A digit tap may be the 3rd one, which auto-saves — trigger the list's
                        // click guard here, before that async save, same as every other
                        // record-triggering tap (see ListClickGuard's own doc for why it must be
                        // this early).
                        onDigit = { digit -> listClickGuard.trigger(); onDigit(digit) },
                        onBackspace = onBackspace,
                        onClear = onClear,
                        enabled = uiState.raceId != null,
                        buttonHeight = 52.dp,
                        spacing = 4.dp,
                    )

                    // No Submit button — every bib auto-saves the moment its 3rd digit is typed,
                    // as whichever action DefaultBibsActionSelector above currently has selected
                    // (see BibsModeViewModel.onDigit); Event is what corrects it afterward if the
                    // default guessed wrong.
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = withClickSound { showEventPicker = true },
                            enabled = uiState.raceId != null,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        ) { Text("Event") }
                        ResetButton(
                            resetDescription = "Closes out everything recorded since this segment started — nothing is deleted, every bib entry stays in Race History. Reset again to walk back through earlier segments the same way.",
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
                            options = uiState.eventOptions,
                            current = uiState.entries.firstOrNull()?.type ?: uiState.defaultBibsAction,
                            onSelect = { type ->
                                listClickGuard.trigger()
                                onEventTypeSelected(type)
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
