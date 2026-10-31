package mobile.racemaster.ui.help

/** Short, scannable guidance for one screen (or a small group of closely related screens) —
 *  the opposite of the old essay-per-topic Help screen nobody actually read. The same
 *  [HelpTopic] renders both as a per-screen on-demand popup (see
 *  [mobile.racemaster.ui.components.HelpTopicDialog], wired via each screen's own top bar) and
 *  as one entry in the full [HelpScreen] index — write it once, it never drifts between the two. */
data class HelpTopic(val title: String, val tips: List<String>)

/** One topic per screen, except where several screens share a single topic because they're
 *  really the same concern seen from different places (e.g. Setup Race and Relocate both just
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
                "— if it looks stale, something's stopped.",
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
        title = "Setup Race / Relocate",
        tips = listOf(
            "One race per device at a time — name, location and mode are all set here; type " +
                "any Seniors/Juniors suffix straight into the name.",
            "Scan Server fills in the race's exact server name so this phone's data lands in " +
                "the right place.",
            "Disabled while a race is already active — Reset it first, or use Progress's own " +
                "\"Resume\" if it was stopped by accident.",
            "Relocate (each mode's own top bar) edits name/location/mode mid-race — location " +
                "can change any time, name locks once the race has started.",
            "Relocating to a location already visited picks up exactly where it left off, " +
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
        ),
    )

    val all: List<HelpTopic> = listOf(
        Overview, SetupDevice, SetupServer, Options, SetupRace, TimeMode, BibsMode, MuleMode,
        RaceHistory, HistoryLineFormat,
    )
}
