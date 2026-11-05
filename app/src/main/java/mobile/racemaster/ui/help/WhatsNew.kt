package mobile.racemaster.ui.help

/** One version's worth of user-visible highlights, newest first — see [HelpScreen]'s own
 *  "What's New" card. Add a new entry here (at the front of [WhatsNew.versions]) whenever a
 *  release has something a marshal would actually notice; a pure internal/bugfix bump doesn't
 *  need one. Deliberately starts from the current version rather than reconstructing one for
 *  every past release. */
data class VersionNotes(val version: String, val highlights: List<String>)

object WhatsNew {
    val versions: List<VersionNotes> = listOf(
        VersionNotes(
            version = "0.0.28",
            highlights = listOf(
                "Setup Beacons now pauses Mule Mode's Bluetooth entirely — both syncing and " +
                    "being visible to other Mules — for as long as the screen is open, and " +
                    "resumes it automatically on exit. The two were found to compete for the " +
                    "same radio badly enough to throw off the scan list's own advertising-" +
                    "interval estimates.",
                "The scan itself now refreshes its underlying Bluetooth session every 90 " +
                    "seconds automatically — a single long-running scan was found to miss more " +
                    "and more advertisements the longer it stayed open without this.",
                "Programmed beacons now use a stronger signal and faster advertising interval " +
                    "(-1dBm, 200ms, up from -7dBm/300ms) — real-world testing showed this " +
                    "meaningfully shrinks the worst-case delay before a beacon gets detected.",
            ),
        ),
        VersionNotes(
            version = "0.0.27",
            highlights = listOf(
                "Setup Beacons now programs a beacon by writing its ID straight into the " +
                    "device name (slot 1), clearing every other slot — instead of the previous " +
                    "scheme of an iBeacon frame in slot 2. The \"Minor ID\" prompt is now " +
                    "\"Beacon Number (0-999)\", and \"Measured power\" is entered as a plain " +
                    "number (no minus sign needed) and checked against its real 0 to -90dBm range.",
                "The scan info icon now shows an estimated advertising interval next to each " +
                    "frame type once it's been seen twice, and frame names are shorter — just " +
                    "\"iBeacon\", \"Name\", \"ACC\", \"User\" — matching the same short names " +
                    "now used in the connected slot list too.",
                "Fixed Calibrate's Stop button scrolling out of reach while calibration was " +
                    "running. The log also now lists newest-first and keeps its own size fixed " +
                    "on screen.",
            ),
        ),
        VersionNotes(
            version = "0.0.26",
            highlights = listOf(
                "Setup Beacons' info icon now recognizes all 7 of a beacon's advertisement " +
                    "types, not just 5 — ACC and User frames used to show up as raw, " +
                    "undecoded bytes.",
            ),
        ),
        VersionNotes(
            version = "0.0.25",
            highlights = listOf(
                "Setup Beacons' info icon now reliably shows every advertisement type a " +
                    "beacon is broadcasting, including Eddystone — it could previously miss " +
                    "those depending on the phone. This needs Location permission, which the " +
                    "app will now ask for if not already granted.",
                "That info dialog is also easier to read while still scanning: lines stay in " +
                    "a fixed order instead of jumping around, and the iBeacon line is now a " +
                    "simple \"Racemaster\" or \"Default\" flag instead of raw technical detail.",
            ),
        ),
        VersionNotes(
            version = "0.0.24",
            highlights = listOf(
                "Setup Beacons' scan list now has an info icon next to every beacon, showing " +
                    "its decoded advertisement with no need to connect — useful to check " +
                    "whether it's already programmed before bothering to tap in.",
                "Fixed beacons not actually advertising after being programmed: a beacon " +
                    "slot's \"trigger\" (fire a one-shot advert on a button click or being " +
                    "picked up) was being left in whatever state it was already in — Program " +
                    "Beacon now always turns it off, so a programmed beacon advertises " +
                    "continuously like a race needs.",
            ),
        ),
        VersionNotes(
            version = "0.0.23",
            highlights = listOf(
                "Added a \"Setup Beacons\" screen, next to Setup Device, to scan, connect to, " +
                    "and program DX-CP27 Bluetooth beacons directly from the phone — no more " +
                    "separate vendor app.",
                "Programming sets this app's own beacon ID, lets you calibrate signal " +
                    "strength by holding the beacon 1 metre away, and shows the beacon's " +
                    "current battery as both a voltage and an approximate percentage.",
                "Power Off and Factory Reset actions were added for beacons too, each behind " +
                    "a confirmation.",
            ),
        ),
        VersionNotes(
            version = "0.0.22",
            highlights = listOf(
                "Every screen now has a one-tap Home button, alongside Back.",
                "Help has been redone: short, scannable tips behind a \"?\" icon on almost " +
                    "every screen, plus a full index here with the same content.",
                "Opening a large, actively-recording race's history no longer slows down or " +
                    "risks crashing the app.",
                "Top bars are tighter — Back is now an icon like Home — and long race names " +
                    "no longer wrap onto two lines.",
                "Added an option to speak bib numbers as they are entered, this is intended " +
                    "to allow marshalls at checkpoints to put the phone in their pocket when " +
                    "the race is using bluetooth beacons",
            ),
        ),
    )
}
