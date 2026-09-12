package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import mobile.racemaster.util.withClickSound

/** Shown when a mode's own Start button needs to know which course this session is — see
 *  RaceRepository.resolveCourseRace's own doc for what happens once one is picked. [options]
 *  come straight from the active race's own RaceEntity.courses (set on the race details
 *  screen); a caller only ever shows this at all when there's more than one to choose between
 *  (see e.g. TimeModeViewModel.beginCourse), so there's no "current" selection to highlight —
 *  unlike ActionPickerDialog, every session starts from scratch. */
@Composable
fun CoursePickerDialog(
    options: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which course?") },
        text = {
            Column {
                options.forEach { course ->
                    TextButton(onClick = withClickSound { onSelect(course) }, modifier = Modifier.fillMaxWidth()) {
                        Text(course, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = withClickSound(onDismiss)) { Text("Cancel") } },
    )
}
