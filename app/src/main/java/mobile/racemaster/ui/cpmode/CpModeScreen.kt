package mobile.racemaster.ui.cpmode

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.MainActivity
import mobile.racemaster.data.db.entity.HistoryAction
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
// four buttons share a row — cut it down instead of shrinking the font, matching Bibs Mode's
// own identical row.
private val BUTTON_ROW_CONTENT_PADDING = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

/** CP Mode's screen — structurally the same as Bibs Mode's (same header, keypad, entry list,
 *  all reused via the shared `ui/components` composables), differing only in its fixed
 *  Pass/Retire button pair in place of Bibs' dynamic Submit-plus-Event-picker: CP has exactly
 *  two possible actions, so each gets its own always-visible button that submits directly
 *  rather than staging a "pending type" first. Editing an already-recorded entry navigates to
 *  the shared [mobile.racemaster.ui.editentry.EditEntryScreen] rather than composing an editor
 *  inline (see that screen's own doc for why). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpModeScreen(
    onChangeMode: () -> Unit,
    onNewRace: () -> Unit,
    onEditRace: (raceId: Long) -> Unit,
    onEditEntry: (entryId: Long) -> Unit,
    viewModel: CpModeViewModel = viewModel(factory = CpModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    // Same external HID trigger mechanism Time/Bibs Mode use (MainActivity.onExternalSplitTrigger)
    // — a volume button (or any other recognized HID key) logs a Pass, CP's primary action,
    // exactly like tapping the Pass button.
    val activity = LocalActivity.current as MainActivity
    val currentOnPass by rememberUpdatedState { viewModel.submit(HistoryAction.PASS) }
    val canExternalTrigger by rememberUpdatedState(uiState.canSubmit)
    DisposableEffect(activity) {
        activity.onExternalSplitTrigger = { if (canExternalTrigger) currentOnPass() }
        onDispose { activity.onExternalSplitTrigger = null }
    }

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "CP Mode",
                newRaceEnabled = !uiState.raceInProgress,
                thisRaceEnabled = uiState.raceId != null,
                onNewRace = onNewRace,
                onThisRace = { uiState.raceId?.let(onEditRace) },
                onChangeMode = onChangeMode,
            )
        },
        // MainActivity's outer Scaffold (no bottomBar) already reserves the navigation bar's
        // bottom inset for every screen — without this, this inner Scaffold's own default
        // contentWindowInsets reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        CpModeContent(
            uiState = uiState,
            deviceName = deviceName,
            onStart = viewModel::startCpMode,
            onDigit = viewModel::onDigit,
            onBackspace = viewModel::onBackspace,
            onClear = viewModel::onClear,
            onPass = { viewModel.submit(HistoryAction.PASS) },
            onRetire = { viewModel.submit(HistoryAction.RETIRE) },
            onStop = viewModel::stopCpMode,
            onReset = viewModel::resetCpMode,
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
private fun CpModeContent(
    uiState: CpModeUiState,
    deviceName: String?,
    onStart: () -> Unit,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onPass: () -> Unit,
    onRetire: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onEditEntry: (entryId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // See BibsModeScreen's own doc for why this is a measured (not fixed-fraction) header/list
    // split — identical reasoning applies here.
    BoxWithConstraints(modifier = modifier) {
        val minListHeight = 140.dp
        val headerMaxHeight = (maxHeight - minListHeight).coerceAtLeast(0.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val listClickGuard = rememberListClickGuard()

            // See BibsModeScreen's own doc for why the header is itself scrollable, and for
            // why editing navigates to a dedicated screen rather than composing an editor
            // inline here.
            val headerScrollState = rememberScrollState()

            Column(
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
                )
                if (!uiState.started) {
                    // Nothing recorded yet for this segment (a fresh race, a race switched
                    // into from a different mode, or one just Reset) — side-effect-free to
                    // just look at, exactly like Time/Bibs Mode's own pre-Start state. Unlike
                    // Bibs, pressing this writes no history row at all — see
                    // CpModeRepository.startCpMode's own doc.
                    Button(
                        onClick = withClickSound(onStart),
                        enabled = uiState.raceId != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                    ) { Text("START", style = MaterialTheme.typography.displaySmall) }
                } else {
                    Text(
                        text = uiState.currentDigits.ifEmpty { "Enter bib" },
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
                            onClick = withClickSound { listClickGuard.trigger(); onPass() },
                            enabled = uiState.canSubmit,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        ) { Text("Pass") }
                        Button(
                            onClick = withClickSound { listClickGuard.trigger(); onRetire() },
                            enabled = uiState.canSubmit,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        ) { Text("Retire") }
                        StopOrResetButton(
                            isStopped = uiState.stopped,
                            resetDescription = "This clears every checkpoint entry and resets ready to start again from scratch.",
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
