package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mobile.racemaster.util.withClickSound

@Composable
fun StopOrResetButton(
    isStopped: Boolean,
    // Caption for the "Reset course" choice — see onReset's own doc. Reset is non-destructive:
    // it never deletes a row, only adds a Reset marker and starts a fresh count from it — real
    // deletion only ever happens explicitly, from the History list.
    resetDescription: String,
    // Caption for the "End recording" choice — see onEndRecording's own doc.
    endRecordingDescription: String,
    onStop: () -> Unit,
    // Inserts a Reset marker and starts a fresh count from it — see *ModeRepository.resetXMode.
    // Never deletes anything; every prior entry stays in Race History regardless. For a new
    // operator's own practice attempt, not for ending a course for real (see onEndRecording).
    onReset: () -> Unit,
    // Clones a fresh course-less sibling race (same name/courses/location/bib range) and
    // switches to it, leaving this course's own already-recorded data completely untouched —
    // see RaceRepository.endRecordingForCourse's own doc. The alternative to onReset once
    // stopped: this course is finished, not being redone from scratch.
    onEndRecording: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    // Stop is easily undoable (the Undo button brings the clock straight back with no loss of
    // time), so it fires immediately with no confirm, same as Undo. Reset/End recording are
    // both consequential (one wipes every entry, the other moves on from this course entirely)
    // so both stay behind this same confirm dialog rather than either firing immediately.
    var showConfirm by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = withClickSound { if (isStopped) showConfirm = true else onStop() },
        enabled = enabled,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(if (isStopped) "RESET" else "STOP", style = labelStyle)
    }

    // Two genuinely different endings for a stopped segment, both landing back on the pre-Start
    // "START" screen (see each *ModeViewModel's own endRecording/onReset) — Reset course adds a
    // Reset marker and starts a fresh count from it, for a new operator's own practice attempt
    // (nothing is ever deleted by it — every entry stays in Race History regardless; genuine
    // deletion only ever happens explicitly, from the History list); End recording is the norm
    // once a course is actually done, leaving it exactly as recorded and moving on to a fresh
    // Start-time course pick instead. Laid out as a titled Column of TextButtons (like
    // ActionPickerDialog/CoursePickerDialog) rather than confirmButton/dismissButton, since
    // there are two real choices here, not one.
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reset or end recording?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(
                        onClick = withClickSound { onReset(); showConfirm = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Reset course", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                            Text(
                                resetDescription,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                    TextButton(
                        onClick = withClickSound { onEndRecording(); showConfirm = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("End recording", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                            Text(
                                endRecordingDescription,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = withClickSound { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
