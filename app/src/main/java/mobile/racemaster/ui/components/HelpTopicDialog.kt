package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.racemaster.ui.help.HelpTopic
import mobile.racemaster.util.withClickSound

/** Shared "short, scannable tips for this screen" popup — the on-demand half of
 *  [mobile.racemaster.ui.help.HelpTopics]; see that object's own doc for why the same
 *  [HelpTopic] also drives the full Help screen's index. Mirrors [DiscardChangesDialog]'s
 *  shape, just informational (one dismiss action) rather than a confirm/cancel. */
@Composable
fun HelpTopicDialog(topic: HelpTopic, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(topic.title) },
        text = {
            // AlertDialog's text slot doesn't scroll on its own — a topic with enough bullets
            // (e.g. HistoryLineFormat's five) can run taller than the dialog's own max height
            // and get silently clipped above the Got it button without this.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                topic.tips.forEach { tip -> Text("•  $tip") }
            }
        },
        confirmButton = {
            TextButton(onClick = withClickSound(onDismiss)) { Text("Got it") }
        },
    )
}
