package mobile.racemaster.ui.timemode

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.MainActivity
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.ui.components.ModeScreenTopBar
import mobile.racemaster.ui.components.SplitRow
import mobile.racemaster.ui.components.StopOrResetButton
import mobile.racemaster.ui.components.SyncStatusLine
import mobile.racemaster.ui.components.UndoLastButton
import mobile.racemaster.ui.components.rememberListClickGuard
import mobile.racemaster.util.formatTimeSplitsText
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeModeScreen(
    onChangeMode: () -> Unit,
    onNewRace: () -> Unit,
    onEditRace: (raceId: Long) -> Unit,
    onEditSplit: (splitId: Long) -> Unit,
    viewModel: TimeModeViewModel = viewModel(factory = TimeModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    // Registers this screen's main action as the target for an external USB/Bluetooth trigger
    // (see MainActivity.onExternalSplitTrigger) while it's on screen — mirrors the big on-screen
    // button exactly, including its own START/SPLIT toggle (see TimeModeContent's identical
    // `if (uiState.stopwatchStarted) onSplit else onStart` below), so a clicker fires whichever
    // of the two the operator would otherwise have tapped. Same enabled condition as that
    // button too: a race must exist and not already be stopped.
    val activity = LocalActivity.current as MainActivity
    val currentOnMainAction by rememberUpdatedState {
        if (uiState.stopwatchStarted) viewModel.recordSplit() else viewModel.startStopwatch()
    }
    val canExternalTrigger by rememberUpdatedState(uiState.raceId != null && !uiState.stopwatchStopped)
    DisposableEffect(activity) {
        activity.onExternalSplitTrigger = { if (canExternalTrigger) currentOnMainAction() }
        onDispose { activity.onExternalSplitTrigger = null }
    }

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "Time Mode",
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
        TimeModeContent(
            uiState = uiState,
            deviceName = deviceName,
            onStart = viewModel::startStopwatch,
            onSplit = viewModel::recordSplit,
            onStop = viewModel::stopStopwatch,
            onReset = viewModel::resetStopwatch,
            onUndo = viewModel::undoLast,
            onEditSplit = onEditSplit,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TimeModeContent(
    uiState: TimeModeUiState,
    deviceName: String?,
    onStart: () -> Unit,
    onSplit: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onEditSplit: (splitId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // BoxWithConstraints (not a fixed dp budget) is what makes the header/list split adapt
    // to whatever screen the app is running on: maxHeight below is the actual measured space
    // available for this content on THIS device. The header gets everything except a fixed
    // floor reserved for the list — not an even split — so a tall phone with room to spare
    // still shows the header's full content without scrolling, exactly as before; only a
    // short screen where the header would otherwise eat the list's floor actually caps it.
    BoxWithConstraints(modifier = modifier) {
        val minListHeight = 140.dp
        val headerMaxHeight = (maxHeight - minListHeight).coerceAtLeast(0.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val listClickGuard = rememberListClickGuard()

            // This header (ticker/main button/Stop-Reset/Undo) is itself scrollable so that on
            // a short screen the main button stays reachable by scrolling back up. Editing a
            // split's note is a separate, dedicated screen (see EditSplitScreen) rather than
            // composed inline here — this used to be its own editor pinned above the list, but
            // that put it inside this very header's own height-capped, ime-inset-driven scroll
            // region, which confirmed in the field on a Cubot that imePadding()/
            // WindowInsets.ime don't reliably report the keyboard's real height, leaving
            // Save/Cancel genuinely unreachable there.
            val headerScrollState = rememberScrollState()

            Column(
                // Capped (see BoxWithConstraints above) only once the splits list actually
                // exists below (stopwatch started) — on a short screen this stops the header
                // (ticker/button/Stop-Reset/Undo) from eating the list's floor, with its own
                // verticalScroll taking over instead. No cap before the race starts (nothing
                // below competing for the space, and this is a much shorter header anyway —
                // just the ticker and START button).
                modifier = if (uiState.stopwatchStarted) {
                    Modifier.heightIn(max = headerMaxHeight).verticalScroll(headerScrollState)
                } else {
                    Modifier.verticalScroll(headerScrollState)
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!deviceName.isNullOrBlank()) {
                    Text(text = "Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
                }
                Text(text = "Race name: ${uiState.raceLabel}", style = MaterialTheme.typography.labelMedium)
                Text(text = "Location: ${uiState.raceLocation}", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "Next: ${formatSplitRef(uiState.nextSplitNumber)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    SyncStatusLine(uiState.unsyncedCount, uiState.lastSyncedAtMillis)
                }
                formatTimeSplitsText(uiState.firstBibNumber, uiState.expectedRunnerCount, uiState.finishedCount)?.let { text ->
                    Text(text = text, style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    text = if (uiState.stopwatchStarted) formatElapsed(uiState.liveElapsedMillis) else "",
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )

                // Plain single-tap Button — deliberately no combinedClickable/long-press/double-tap
                // gesture handling here. Two finishers crossing close together means two fast taps
                // must both register as splits; any gesture-disambiguation delay would risk
                // swallowing or merging the second one.
                Button(
                    onClick = withClickSound {
                        if (uiState.stopwatchStarted) {
                            listClickGuard.trigger()
                            onSplit()
                        } else {
                            onStart()
                        }
                    },
                    enabled = uiState.raceId != null && !uiState.stopwatchStopped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                ) {
                    val label = when {
                        uiState.stopwatchStopped -> "STOPPED"
                        uiState.stopwatchStarted -> "SPLIT"
                        else -> "START"
                    }
                    Text(label, style = MaterialTheme.typography.displaySmall)
                }

                if (uiState.stopwatchStarted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StopOrResetButton(
                            isStopped = uiState.stopwatchStopped,
                            resetDescription = "This clears every split and resets ready to start again from scratch (under the same race name).",
                            onStop = onStop,
                            onReset = onReset,
                            modifier = Modifier.weight(1f),
                        )
                        UndoLastButton(
                            enabled = uiState.canUndo,
                            onClick = onUndo,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (uiState.stopwatchStarted) {
                HorizontalDivider()

                val listState = rememberLazyListState()
                LaunchedEffect(uiState.splits.firstOrNull()?.id) {
                    if (uiState.splits.isNotEmpty()) listState.scrollToItem(0)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(uiState.splits, key = { it.id }) { split ->
                        // Start/Stop marker rows are never editable — retyping either one's
                        // own note would break every query keyed off it (the repository also
                        // refuses this as a backstop, but the UI shouldn't offer it at all).
                        val isMarkerRow = split.action == HistoryAction.START || split.action == HistoryAction.STOP
                        SplitRow(
                            splitNumber = split.splitNumber,
                            elapsedMillis = split.elapsedMillis,
                            actionLabel = split.action.displayName(),
                            note = split.note,
                            syncState = split.syncState,
                            onClick = if (isMarkerRow || listClickGuard.isSuppressed) null else { { onEditSplit(split.id) } },
                        )
                    }
                }
            }
        }
    }
}
