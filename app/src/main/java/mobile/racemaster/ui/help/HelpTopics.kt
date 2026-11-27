package mobile.racemaster.ui.help

/** Short, scannable guidance for one screen (or a small group of closely related screens) —
 *  the opposite of the old essay-per-topic Help screen nobody actually read. The same
 *  [HelpTopic] renders both as a per-screen on-demand popup (see
 *  [mobile.racemaster.ui.components.HelpTopicDialog], wired via each screen's own top bar) and
 *  as one entry in the full [HelpScreen] index — write it once, it never drifts between the two. */
data class HelpTopic(val title: String, val tips: List<String>)

/** One topic per screen, except where several screens share a single topic because they're
 *  really the same concern seen from different places (e.g. Setup Race and Move both just
 *  edit a race's name/location/mode) — see each screen's own `helpTopic` wiring for which one it
 *  uses. [all] is what the full Help screen indexes, in the order a new operator would actually
 *  meet each screen. */
object HelpTopics {
    val Overview = HelpTopic(
        title = "Overview",
        tips = listOf(
            "Any number of phones can log a race side by side: Time Mode times finishers " +
                "like a stopwatch, Bibs Mode records bib numbers in order — location-aware, " +
                "so a station out on the course logs \"Pass\" instead of Start/Finish.",
            "Every race has a location (e.g. \"Finish\", \"CP1\") so different stations' logs " +
                "can be matched up afterwards.",
            "Keep the screen on for the whole race.",
            "In Time Mode, an external clicker or the volume buttons can stand in for tapping " +
                "SPLIT — see Time Mode's own help.",
        ),
    )

    val SetupDevice = HelpTopic(
        title = "Setup Device",
        tips = listOf(
            "Setup Name: the label every other phone and the web app sees this device by.",
            "Setup Server: only needed if this phone pushes data to the server directly, or " +
                "(with Mule Mode on) also relays other phones' data — skip it to stay purely " +
                "Bluetooth. See \"Setup Server\" below for details.",
            "Options: Mule Mode on/off, Bluetooth on/off, server sync on/off, and more — see " +
                "\"Options\" below.",
            "Once a server's set up, every mode screen shows its own \"Server: …\" status line " +
                "— now naming which account it's logged in as (\"Online — name\"), or \"Not " +
                "logged in\" / \"Login expired\" if it isn't; if the last-seen time looks " +
                "stale, something's stopped.",
        ),
    )

    val SetupServer = HelpTopic(
        title = "Setup Server",
        tips = listOf(
            "Enter the Racemaster server URL plus a username/password — only needed if this " +
                "phone pushes its own data to the server, or (with Mule Mode on) also relays " +
                "other phones' data to it.",
            "Log-in saves these and starts using them straight away; tap a field's history " +
                "dropdown to reuse a URL or login typed before.",
            "No Server clears any saved login and reverts to pure device-to-device " +
                "Bluetooth syncing.",
            "\"No internet connection detected\" just means logging in right now will " +
                "probably fail — it doesn't block typing or saving.",
        ),
    )

    val Options = HelpTopic(
        title = "Options",
        tips = listOf(
            "Enable/Disable Mule Mode: turns scanning for and pulling data from nearby " +
                "devices on or off.",
            "Bluetooth and server sync each have their own on/off toggle, independent of " +
                "each other and of Mule Mode.",
            "Force sync now triggers an immediate push (and pull, if muling); Stop/Resume " +
                "auto-sync pauses or resumes the automatic background cycle.",
            "\"Skip races older than\" and the heartbeat interval are tuning knobs — the " +
                "defaults are fine for most races.",
        ),
    )

    val SetupRace = HelpTopic(
        title = "Setup Race / Move",
        tips = listOf(
            "One race per device at a time — name, location and mode are all set here; type " +
                "any Seniors/Juniors suffix straight into the name. Bibs Mode at \"Pen\" or " +
                "\"Close\" behaves as Count Mode/Close Mode did before — location now decides " +
                "that, not a separate mode.",
            "Scan Server fills in the race's exact server name so this phone's data lands in " +
                "the right place.",
            "Disabled while a race is already active — Reset it first, or use Progress's own " +
                "\"Resume\" if it was stopped by accident.",
            "Move (Bibs Mode's own top bar) edits name/location/mode mid-race — location " +
                "can change any time, name locks once the race has started.",
            "Moving to a location already visited picks up exactly where it left off, " +
                "instead of starting over.",
        ),
    )

    val TimeMode = HelpTopic(
        title = "Time Mode",
        tips = listOf(
            "START begins the stopwatch and logs a fixed \"Start\" marker (S0); SPLIT logs " +
                "the current time every tap.",
            "RESET closes this segment and starts a fresh count — nothing is deleted; tap it " +
                "again to walk back through earlier segments.",
            "Undo last removes only the most recent split, back to this segment's own Start.",
            "A USB or Bluetooth clicker (or the volume buttons) can stand in for tapping SPLIT.",
            "Tap any split row to give it a short note.",
        ),
    )

    val BibsMode = HelpTopic(
        title = "Bibs Mode",
        tips = listOf(
            "START opens the keypad — type a bib's 3 digits (leading zeros for numbers under " +
                "100, e.g. \"007\") and it auto-saves the moment the 3rd digit is entered.",
            "The small Start/Pass/Finish buttons set what a bib auto-saves as, set " +
                "automatically from the race's own Location — tap to switch.",
            "If the default guessed wrong, tap Event right after to correct the type, or tap " +
                "any logged row afterward to fix it.",
            "A repeated bib for the same event is flagged \"dup of S…\", not blocked — a Start " +
                "and a Finish for the same bib is normal.",
            "At a checkpoint, a bib can also be recorded automatically from its beacon the " +
                "moment it's reliably detected — same announce/beep as typing it in. A bib " +
                "already in this segment's list, by beacon or by typing, won't record again " +
                "until that entry's undone.",
            "RESET closes this segment and starts a fresh count; Undo last removes only the " +
                "most recent entry.",
        ),
    )

    val MuleMode = HelpTopic(
        title = "Mule Mode",
        tips = listOf(
            "Mule Mode is an on/off toggle, not a separate recording mode — a phone can record " +
                "its own race and relay other phones' data at the same time.",
            "Turn it on for a phone with internet access sitting near a phone that has none.",
            "It syncs automatically with every nearby RaceMaster phone over Bluetooth — no " +
                "pairing needed.",
            "The timestamps on this screen (\"Last push to server\", \"Web app last " +
                "seen/pushed\") are the quickest way to tell it's actually working — if one " +
                "looks old, something's stopped.",
        ),
    )

    val RaceHistory = HelpTopic(
        title = "Races / History",
        tips = listOf(
            "Lists every race this device has recorded or received, plus any progress files " +
                "it's been sent.",
            "Each split/entry is colour-coded: red — still only on this phone, orange — " +
                "picked up by a Mule but not yet confirmed further, green — confirmed reaching " +
                "the server or a connected web app.",
            "\"Resume\" brings back a race that's still active but isn't this device's current " +
                "one (e.g. after switching away without resetting first).",
            "Deleting a race removes it everywhere — here, the server, any Mule, and the web app.",
        ),
    )

    val HistoryLineFormat = HelpTopic(
        title = "Reading a history line",
        tips = listOf(
            "L001 is this row's permanent line number — never reused or renumbered, the one " +
                "true order every row is sorted by.",
            "S001 is the split number within the current segment — it restarts after every " +
                "Reset/relocate; a row that isn't itself a split (New Race, Location, Ping, " +
                "and the Time/Bibs start marker) shows \"S–\" instead.",
            "Then the action, the bib number (\"–\" for a Time row or a boundary marker), the " +
                "time elapsed since this segment's Start (\"–\" where that doesn't apply), and " +
                "the wall-clock time it was recorded.",
            "Colour shows how far it's travelled: red — only on this phone, orange — picked " +
                "up by a Mule but not yet confirmed further, green — confirmed reaching the " +
                "server or a connected web app.",
            "Below that, as they apply: an operator's own note (or \"Undo L…\", for a row " +
                "that undid an earlier one), \"dup of S…\" for a repeated bib, \"Edited from " +
                "L…\" for a correction, and \"Synced to: …\" naming exactly which device(s) " +
                "this one row has reached.",
            "A run of consecutive Pings that all share the same colour collapses into one row " +
                "spanning the whole run — \"L010 - L014\" and the first and last Ping's own " +
                "times — rather than listing every identical heartbeat separately.",
        ),
    )

    val BeaconOperations = HelpTopic(
        title = "Beacons",
        tips = listOf(
            "Five jobs share this hub: Check (confirm a beacon's on and its number matches its " +
                "label), Close (post-race battery read + power-off) and Count (pre-start " +
                "headcount) — both also their own Setup Race mode now, for a phone dedicated to " +
                "that job all race long; Close's own button here is a shortcut straight to it " +
                "that works regardless of the active race's own mode, since it may genuinely be " +
                "needed outside a race context, while Count's button just points you to Setup " +
                "Race instead, since Count only ever makes sense against a race already set up " +
                "for it — Program (write a beacon's number ahead of a race), and Range " +
                "Calibration (a data-collection tool, not a race-day job).",
        ),
    )

    val ProgramBeacons = HelpTopic(
        title = "Program Beacon",
        tips = listOf(
            "Scan, then tap a beacon to connect — it authenticates automatically and shows its " +
                "real current configuration, including battery. A beacon whose name already " +
                "looks like one this app programmed goes straight for that password; anything " +
                "else tries the factory default first. If that single guess is wrong, the " +
                "password field that appears pre-fills with whichever guess wasn't tried, as a " +
                "starting point to correct from.",
            "An info icon next to every beacon in the scan list decodes every advertisement " +
                "it's broadcasting, straight from the scan packets, with no need to connect — " +
                "its name, iBeacon, Eddystone, ACC and User frames, each labelled with an " +
                "estimated advertising interval once it's been seen twice. Seeing every type " +
                "needs Location permission, which this app asks for up front.",
            "Programming rewrites the password away from the default, then writes a single " +
                "device-name frame into slot 1 — \"RM.\" plus the Beacon Number you enter " +
                "(0-999) plus the signed Measured power you enter or calibrate — at a 200ms " +
                "interval, TX power -1dBm, and its trigger explicitly off so it advertises " +
                "continuously, not just on a button click or being picked up. Every other slot " +
                "is cleared.",
            "Measured power is entered as a plain number (0-90) — no sign needed, it's always " +
                "treated as negative. It needs calibrating per TX-power setting, not guessed: " +
                "hold the beacon exactly 1 metre from the phone and tap Calibrate; it stays " +
                "filled in for the next beacon too, so you only need to do this once per " +
                "session.",
            "Power Off and Factory Reset sit next to Set Program, each behind its own " +
                "confirmation — Power Off needs the beacon's own button pressed to bring it " +
                "back on, Factory Reset wipes the password and every slot back to firmware " +
                "defaults.",
            "Watch the log at the bottom — Set Program re-reads the beacon afterward and " +
                "only reports success once the written values are confirmed, not just acked. " +
                "It lists newest first and keeps itself scrolled to the top while you're " +
                "watching it live.",
            "Mule Mode is paused — both syncing and being visible to other Mules — for as " +
                "long as this screen stays open, and resumes automatically once you leave. " +
                "The two were found to compete for the same Bluetooth radio, badly enough to " +
                "throw off the scan list's advertising-interval estimates.",
        ),
    )

    val IdentifyFob = HelpTopic(
        title = "Check Beacon",
        tips = listOf(
            "Hold one race beacon close to the phone — its Beacon Number (the same number " +
                "entered on Program Beacon when it was programmed) shows as big as the screen " +
                "allows, so it's easy to check against the beacon's own label.",
            "Lists every beacon within half a metre, nearest first — if more than one shows " +
                "up, move the others further away so only the one you're checking is listed.",
            "Read-only: this doesn't pair a bib to a beacon — that pairing happens at " +
                "registration in the Racemaster web app, not on the phone.",
        ),
    )

    val CloseMode = HelpTopic(
        title = "Close Mode",
        tips = listOf(
            "Lists every beacon currently in range, nearest first. Tap one to connect, read its " +
                "battery, and power it off — the result is logged here and syncs to the server " +
                "the same way any other recorded entry does, so anyone still holding a beacon " +
                "shows up in \"Outstanding\" below.",
            "A beacon already closed this race still shows up if it's heard again (\"closed " +
                "×N\") — e.g. a runner who went straight to this station without crossing the " +
                "finish line first. Closing it again is fine; it just adds another record.",
            "Set up as its own Setup Race mode, location fixed to \"Close\" — lets a dedicated " +
                "close-station phone be told apart from every other device in the web app's own " +
                "device list, since it never logs a Bib or Time entry of its own.",
            "The top bar's \"Stop\" clears this race's mode (already-closed beacons stay " +
                "recorded) so a different mode can be set up for it — the only way to leave " +
                "Close Mode, since it has no Reset. Also reachable without a race in Close " +
                "mode at all via the Beacons hub's own \"Close\" shortcut, in which case Stop " +
                "stays disabled.",
        ),
    )

    val CountMode = HelpTopic(
        title = "Count Mode",
        tips = listOf(
            "A pre-start headcount: shows a running count of unique beacons seen nearby, no " +
                "tapping required — just walk runners past the phone one at a time. Beacon 000 " +
                "(the sweep) is never counted.",
            "Does nothing at all until this race has an actual beacon expectation list (from " +
                "progress.json) — with none yet, nearby beacons are simply ignored rather than " +
                "being counted against a total of 0.",
            "Only close-range beacons count, and each one only once — a beacon that's already " +
                "been seen doesn't add to the total again, so lingering near the phone is " +
                "harmless.",
            "Set up as its own Setup Race mode, location fixed to \"Pen\" — lets a dedicated " +
                "headcount phone be told apart from every other device in the web app's own " +
                "device list, since it never logs a Bib or Time entry of its own.",
            "The top bar's \"Stop\" clears this race's mode (beacons already seen stay " +
                "recorded, and still count if Count is set up again later) so a different mode " +
                "can be set up for it. \"Reset\", at the bottom of the screen, also clears the " +
                "running tally itself — restarting Count genuinely starts from zero rather than " +
                "resuming it — and leaves this screen the same way Stop does.",
            "Unlike Close, there's no standing shortcut to this screen from the Beacons hub — " +
                "Count only ever makes sense against a race already set up for it, so the " +
                "Beacons hub's own \"Count\" button just points you back to Setup Race instead.",
        ),
    )

    val RangeCalibration = HelpTopic(
        title = "Range Calibration",
        tips = listOf(
            "A data-collection tool, not a race-day job: the shipped distance estimate shown " +
                "elsewhere in the app is known to be inaccurate, and this records real " +
                "(context, measured distance, beacon, signal) samples to work out a better one " +
                "later, offline — it doesn't fix the estimate itself.",
            "Pick the Context that matches where you're standing, enter the distance you've " +
                "actually measured to a beacon, then tap Record on that beacon's row below. " +
                "\"Today's estimate\" next to each beacon is the app's current (inaccurate) " +
                "formula, shown only so you can see at a glance how far off it is right now.",
            "Share sends every sample recorded so far — this session and every earlier one, " +
                "the file keeps accumulating across app restarts — as a CSV you can analyse " +
                "afterward. Clear wipes it, behind a confirmation, to start a clean session.",
        ),
    )

    val all: List<HelpTopic> = listOf(
        Overview, SetupDevice, SetupServer, SetupRace, BeaconOperations, IdentifyFob,
        ProgramBeacons, CloseMode, RangeCalibration, Options, TimeMode, BibsMode, CountMode,
        MuleMode, RaceHistory, HistoryLineFormat,
    )
}
