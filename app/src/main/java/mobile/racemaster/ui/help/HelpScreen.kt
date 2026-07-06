package mobile.racemaster.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help") },
                navigationIcon = { TextButton(onClick = withClickSound(onBack)) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HelpSection(
                title = "Overview",
                body = "RaceMaster Mobile is a companion app for timing races on the day, used " +
                    "alongside the RaceMaster web app that a race director runs to manage entries and " +
                    "produce results. Rather than one person juggling both a stopwatch and a bib list " +
                    "as runners cross the line, two phones can run this app side by side: one in Time " +
                    "Mode acting as a stopwatch (recording exactly when each finisher crossed the " +
                    "line, in order), and one in Bibs Mode recording which bib number each finisher " +
                    "was wearing, in the same order. The two logs are later matched up by position to " +
                    "produce finishing times per runner. The screen is kept on and (where supported) " +
                    "pinned in place for the whole race so it can't be bumped into another app, and an " +
                    "external clicker can be used instead of tapping the screen so the operator can " +
                    "keep their eyes on the finish line.",
            )
            HelpSection(
                title = "Time Mode",
                body = "START begins the stopwatch and records a fixed \"Start\" marker as split #0. " +
                    "SPLIT records the current time every time it's tapped — two fast taps always " +
                    "produce two separate splits. STOP freezes the clock and records a \"Stop\" split; " +
                    "undoing that Stop split resumes the clock with no time lost. Once stopped, the " +
                    "same button becomes RESET, which wipes every split and returns to the blank " +
                    "pre-start screen (with confirmation). Undo last removes only the most recent " +
                    "split. Tap any split row to give it a short label.",
            )
            HelpSection(
                title = "Bibs Mode — starting a race",
                body = "New Race asks for the race name, the first bib number, and how many runners " +
                    "there are — together these define the legal bib range for the race. A bib " +
                    "outside that range is rejected with an on-screen error. A fixed \"Clock\" marker " +
                    "is automatically recorded as split #0.",
            )
            HelpSection(
                title = "Bibs Mode — logging",
                body = "Type a bib number on the keypad and tap the big Log button to record it — by " +
                    "default it logs \"Finish\". Tap Event to change what the next tap will log: " +
                    "Finish, Start, Retire (each needs a bib number), or Ignore/Seniors/Juniors/Male/" +
                    "Female (group-wide markers that don't need a bib number — the keypad digits are " +
                    "cleared automatically when one of these is chosen). After each log, the Event " +
                    "choice resets back to Finish. The \"Next: #N\" line always shows the split number " +
                    "the next entry will get.",
            )
            HelpSection(
                title = "Bibs Mode — duplicates",
                body = "Entering the same bib number for the same event twice (e.g. two Finishes for " +
                    "bib 101) is still allowed — it's flagged as \"dup of #N\", referencing the other " +
                    "matching row, and a running dup count appears at the top right of the \"Next\" " +
                    "line. A Start and a Finish for the same bib is normal and never counts as a " +
                    "duplicate. Tap any row to correct its bib number or event type — if that resolves " +
                    "the duplicate, the flag disappears immediately.",
            )
            HelpSection(
                title = "Bibs Mode — editing rows",
                body = "Tap any logged row to edit it. For a normal row this opens the event type, bib " +
                    "number, and an optional short note. For the Clock row (split #0) it instead opens " +
                    "an offset time field — enter it as minutes and seconds (any separator, e.g. " +
                    "\"5:30\" or \"5 30\") or as a single number of seconds (e.g. \"90\" means 1:30). " +
                    "This records how late the clock was started after a mass start.",
            )
            HelpSection(
                title = "Bibs Mode — stop and reset",
                body = "STOP freezes logging (Log/Event are disabled) and frees up New Race — this " +
                    "records a \"Stop\" marker that can be undone to resume logging. Once stopped, the " +
                    "same button becomes RESET, which wipes every bib entry back to just a fresh Clock " +
                    "marker (with confirmation).",
            )
            HelpSection(
                title = "External triggers",
                body = "A USB (via OTG) or Bluetooth clicker, presenter remote, camera shutter remote, " +
                    "or foot pedal that enumerates as a HID keyboard can be used in place of tapping " +
                    "the main button — this works for Time Mode's SPLIT and Bibs Mode's Log, whichever " +
                    "screen is currently showing. No pairing code is needed on our end, just pair the " +
                    "device with the phone as normal in Android's Bluetooth settings.",
            )
            HelpSection(
                title = "General",
                body = "New Race starts a fresh race under the current mode (disabled while a race is " +
                    "in progress, to avoid losing it). Mode switches between Time/Bibs/Mule for the " +
                    "same active race. History on the mode picker shows every previously recorded " +
                    "race, read-only. Button presses play a short click sound at full volume, " +
                    "regardless of the phone's own volume/Touch sounds setting, so it's audible at " +
                    "a noisy finish line.",
            )
        }
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
}
