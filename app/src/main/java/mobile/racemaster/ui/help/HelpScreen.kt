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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mobile.racemaster.BuildConfig
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        buildAnnotatedString {
                            append("Help ")
                            withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)) {
                                append("v${BuildConfig.VERSION_NAME}")
                            }
                        },
                    )
                },
                navigationIcon = { TextButton(onClick = withClickSound(onBack)) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for
        // every screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append("RaceMaster Mobile is open source — code and issues at ")
                    withLink(
                        LinkAnnotation.Url(
                            url = "https://github.com/pd-giz-dave/racemaster-mobile",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) {
                        append("github.com/pd-giz-dave/racemaster-mobile")
                    }
                    append(".")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            HelpSection(
                title = "Overview",
                body = "RaceMaster Mobile is a companion app for timing races on the day, used " +
                    "alongside the RaceMaster web app that a race director runs to manage entries and " +
                    "produce results. Rather than one person juggling both a stopwatch and a bib list " +
                    "as runners cross the line, any number of phones can run this app side by side, " +
                    "each logging its own station: Time Mode acts as a stopwatch (recording exactly " +
                    "when each finisher crossed the line, in order), Bibs Mode records which bib " +
                    "number each finisher was wearing in the same order, and CP Mode is a lighter " +
                    "Pass/Retire checkpoint variant of Bibs Mode for stations out on the course. " +
                    "Every race also records a location (e.g. \"Finish\", \"CP1\") so logs from " +
                    "different stations can be told apart, and matched up by position afterwards to " +
                    "produce finishing times per runner. The screen is kept on for the whole race, " +
                    "and in Time Mode an external clicker, or the volume up/down buttons, can be used " +
                    "instead of tapping SPLIT so the operator can keep their eyes on the finish line " +
                    "— see \"External triggers\" below.",
            )
            HelpSection(
                title = "Time Mode",
                body = "START begins the stopwatch and records a fixed \"Start\" marker as split S0. " +
                    "SPLIT records the current time every time it's tapped — two fast taps always " +
                    "produce two separate splits. STOP freezes the clock and records a \"Stop\" marker " +
                    "(shown as S– — a boundary marker, not a numbered split); undoing that Stop marker " +
                    "resumes the clock with no time lost. Once stopped, the same button becomes RESET, " +
                    "which wipes every split and returns to the blank pre-start screen (with " +
                    "confirmation). Undo last removes only the most recent split. Tap any split row to " +
                    "give it a short label.",
            )
            HelpSection(
                title = "Bibs Mode — starting a race",
                body = "New Race asks for the race name, the first bib number, and how many runners " +
                    "there are — together these define the legal bib range for the race. A bib " +
                    "outside that range is allowed but flagged (see \"Bibs Mode — duplicates\" below) " +
                    "rather than rejected, the same way a duplicate is. A fixed \"Clock\" marker is " +
                    "automatically recorded as split S0. If the first bib number is below 100, bibs " +
                    "in Bibs/CP Mode must still be typed as 3 digits with leading zeros (e.g. \"007\") " +
                    "— see \"Bibs Mode — logging\" below for why.",
            )
            HelpSection(
                title = "Bibs Mode — logging",
                body = "Type a bib number on the keypad — as soon as its 3rd digit is entered it's " +
                    "recorded automatically as a Finish, no separate button tap needed. That's why a " +
                    "bib below 100 must still be typed with leading zeros (e.g. \"007\", not \"7\") — " +
                    "nothing is recorded until 3 digits have been typed. The number stays showing on " +
                    "screen afterwards so you can double-check it before moving on; typing the next " +
                    "bib's first digit clears it automatically. If that entry wasn't actually a " +
                    "Finish, tap Event straight after and pick Start or Retire instead — both correct " +
                    "the entry you just made in place, keeping the same bib, and the field keeps " +
                    "showing that bib afterwards instead of clearing; they're only offered when the " +
                    "entry on top actually has a bib to correct. Picking Ignore/Seniors/Juniors/Male/" +
                    "Female instead never touches that entry — a marker is a flag about the moment, " +
                    "not a correction, so it always logs as its own fresh, bib-less row on top, " +
                    "whether or not a bib entry is showing. Tapping Event before typing any digits " +
                    "logs a marker the same way, on its own. Undo last removes the most recent entry " +
                    "and brings whatever's now on top back into the field, ready to correct via Event " +
                    "if needed. The \"Next:\" line always shows the split number (as S1, S2, and so " +
                    "on) the next entry will get.",
            )
            HelpSection(
                title = "Bibs Mode — duplicates and out-of-range bibs",
                body = "Entering the same bib number for the same event twice (e.g. two Finishes for " +
                    "bib 101) is still allowed — it's flagged as \"dup of S1\", referencing the other " +
                    "matching row, and a running dup count appears at the top right of the \"Next\" " +
                    "line. A Start and a Finish for the same bib is normal and never counts as a " +
                    "duplicate. A bib outside the race's configured range is logged the same way, " +
                    "flagged inline as \"not in range M to N\" rather than being blocked. Tap any row " +
                    "to correct its bib number or event type — if that resolves the duplicate or range " +
                    "flag, it disappears immediately.",
            )
            HelpSection(
                title = "Bibs Mode — editing rows",
                body = "Tap any logged row to edit it. For a normal row this opens the event type, bib " +
                    "number (numeric keypad), and an optional short note. For the Clock row (split S0) " +
                    "it instead opens an offset time field — enter it as minutes and seconds (any " +
                    "separator, e.g. \"5:30\" or \"5 30\") or as a single number of seconds (e.g. \"90\" " +
                    "means 1:30). This records how late the clock was started after a mass start.",
            )
            HelpSection(
                title = "Bibs Mode — stop and reset",
                body = "STOP freezes logging (the keypad and Event are disabled) and frees up New " +
                    "Race — this records a \"Stop\" marker (shown as S–, not a numbered split) that can " +
                    "be undone to resume logging. Once stopped, the same button becomes RESET, which " +
                    "wipes every bib entry and returns to the same Start-button screen a freshly " +
                    "created race shows (with confirmation).",
            )
            HelpSection(
                title = "CP Mode",
                body = "CP Mode is a lighter checkpoint variant of Bibs Mode, for a station out on " +
                    "the course rather than at the finish line — same keypad, entry list, duplicate " +
                    "flagging, out-of-range flagging, row editing, and Clock-marker Start (split S0), " +
                    "and the same auto-save-on-3-digits entry: typing a bib's 3rd digit records it " +
                    "automatically as a Pass, so bibs below 100 still need leading zeros (e.g. " +
                    "\"007\"). Each Pass gets a running split number too, counting how many have " +
                    "passed since Start/Reset — Retire doesn't (shown as S–), since it never actually " +
                    "crosses the checkpoint. CP has no Event picker — instead there's a single " +
                    "Pass/Retire toggle button that always shows the opposite of whatever the top " +
                    "entry currently is, so it retags that entry in place (keeping the same bib) and " +
                    "can flip it back and forth as many times as needed; the field keeps showing that " +
                    "bib rather than clearing. Tap any row to correct it, and STOP/RESET/Undo last " +
                    "all work exactly as in Bibs Mode — Undo last brings whatever's now on top back " +
                    "into the field the same way. The race's Location on the race details screen must " +
                    "be \"CP\" followed by a number from 1 upwards, with " +
                    "an optional \"-name\" suffix (e.g. \"CP1\", \"CP2-Bridge\") so its entries can be " +
                    "told apart from other stations recording the same race.",
            )
            HelpSection(
                title = "Mule Mode",
                body = "Mule Mode is an on/off toggle (Setup Device > Options > Enable/Disable Mule " +
                    "Mode), not a mode a phone is limited to instead of Time/Bibs/CP — a phone can " +
                    "record its own race in any of those and also mule at the same time. This is " +
                    "for a phone with no internet access at its station: a second, internet-connected " +
                    "phone nearby (in any mode, muling turned on) picks up and forwards its data to " +
                    "the server alongside its own. Tapping \"Mule Mode\" on the Mode Picker (which " +
                    "always shows current ON/OFF state right in its own label) opens this dashboard " +
                    "directly when muling is already on; when it's off, it opens Options instead (see " +
                    "Setup Device below), since there's nothing to show here until it's turned on — " +
                    "Enable Mule Mode there lands straight back here, and Disable Mule Mode from here " +
                    "(via this screen's own Options button) returns to the Mode Picker the same way, " +
                    "since there's nothing left to show once it's off again. This dashboard is " +
                    "muling's own status view regardless of which mode this phone is itself " +
                    "recording — it continuously syncs with every other RaceMaster Mobile device it " +
                    "can see over Bluetooth once muling is on, no pairing step needed, any nearby " +
                    "device running the app is discovered and synced automatically. A phone can also " +
                    "be connected to directly " +
                    "over Bluetooth by the RaceMaster web app, which counts as a second, independent " +
                    "destination — a race can reach race control via the server, a Bluetooth-connected " +
                    "laptop, or both. Timestamps on this screen are the quickest way to tell " +
                    "everything is actually working: \"Last push to server\" (this device's own data " +
                    "reaching the server); \"Web app last seen\"/\"Web app last pushed to\" (a " +
                    "connected web app contacting this device, and this device's own data actually " +
                    "reaching it — \"last seen\" alone doesn't mean something's wrong, it just means " +
                    "nothing new needed sending that time); and, against each entry in Nearby " +
                    "devices, its own \"Last seen\"/\"Last pulled\" pair, the same distinction in the " +
                    "other direction (this device successfully contacting it, versus this device " +
                    "actually pulling new data from it) — Nearby devices itself is also coloured red " +
                    "while a device still has unsynced data and green once it's fully pulled. None of " +
                    "these timestamps carries a separate warning of its own; judge each the same way " +
                    "— if it looks old, something's stopped. Options (top right, next to Mode) covers " +
                    "muling on/off, Bluetooth on/off, server sync on/off, force-syncing, and " +
                    "pausing auto-sync — see Setup Device below.",
            )
            HelpSection(
                title = "Setup Device",
                body = "Setup Device — reached from every mode via the \"Setup: <device name>\" " +
                    "button on the Mode Picker — is where a phone's own identity and connectivity are " +
                    "configured, independently of which mode (Time/Bibs/CP, or none) it's currently " +
                    "recording and of whether muling is on. Setup Name renames this device, the label " +
                    "every other phone sees it by. Setup Server configures the RaceMaster server URL " +
                    "and login — needed only if this phone should push its own data to the server " +
                    "directly, or (with muling turned on) also pull and re-push data collected from " +
                    "other nearby phones; skip it entirely to stay purely device-to-device over " +
                    "Bluetooth. Options covers everything else: Enable/Disable Mule Mode (see Mule " +
                    "Mode above), Bluetooth on/off (turning it off makes this phone invisible to " +
                    "every nearby device — no scanning, no advertising, no GATT reads — until turned " +
                    "back on), server sync on/off (independent of Bluetooth — turns pushing to/" +
                    "checking the server on or off without affecting device-to-device sync), Force " +
                    "sync now (triggers an immediate pull-and-push cycle instead of waiting for the " +
                    "automatic few-second tick), and Stop auto-sync/Resume auto-sync (pauses or " +
                    "resumes that background cycle). These apply the same way regardless of " +
                    "recording mode: a phone still pushes its own recorded data to the server on the " +
                    "same schedule either way, it just never pulls from other phones unless muling " +
                    "is turned on for it too. Once a server is configured, every mode's " +
                    "own screen shows a \"Server: \" line for it (in Mule Mode, directly above \"Last " +
                    "push to server\") — Online, Offline, Invalid server (wrong URL — a real server " +
                    "just isn't answering there), or Paused (server sync turned off in Options), " +
                    "alongside \"(seen HH:MM)\", the last time it was actually confirmed reachable — " +
                    "judge that the same way as any other last-seen time in this app: if it looks old, " +
                    "something's stopped. The small dot in the green app banner at the very top " +
                    "mirrors the same status at a glance, without the timestamp.",
            )
            HelpSection(
                title = "Sync status colours",
                body = "Every logged split/entry is coloured to show how far it's actually travelled: " +
                    "red means it hasn't left this phone yet, orange means a Mule has picked it up but " +
                    "it hasn't yet reached a genuine destination, and green means it's confirmed " +
                    "reaching one — the RaceMaster server, or a Bluetooth device that identifies as " +
                    "one (e.g. the web app). Data can pass through several Mule phones before reaching " +
                    "a destination, so green can take a few sync ticks to appear after a line is first " +
                    "recorded — orange in the meantime just means it's safely off this phone, not stuck.",
            )
            HelpSection(
                title = "External triggers",
                body = "A USB (via OTG) or Bluetooth clicker, presenter remote, camera shutter remote, " +
                    "or foot pedal that enumerates as a HID keyboard can be used in place of tapping " +
                    "SPLIT while Time Mode is on screen. Bibs and CP Mode have no external trigger of " +
                    "their own — entry there is bib-driven (typing 3 digits is what logs it), with no " +
                    "single \"log the next event\" action left for a clicker to stand in for. No " +
                    "pairing code is needed on our end, just pair the device with the phone as normal " +
                    "in Android's Bluetooth settings.",
            )
            HelpSection(
                title = "General",
                body = "New Race starts a fresh race under the current mode (disabled while a race is " +
                    "in progress, to avoid losing it). Time/Bibs/CP are mutually exclusive for a race " +
                    "— only one can be active at once, and picking a different one while another is " +
                    "still active (Started but not yet Stopped and Reset) is blocked with a \"Can't " +
                    "switch mode\" dialog rather than performed. The Mode Picker marks whichever one " +
                    "is holding it \"- active\" right on its own button, alongside the race-in-progress " +
                    "card lower down. Muling on/off is separate (see Mule Mode above) and unaffected " +
                    "by any of this. History on the mode picker shows every previously recorded " +
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
