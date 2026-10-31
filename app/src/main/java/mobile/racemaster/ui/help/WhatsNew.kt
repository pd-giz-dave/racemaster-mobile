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
            version = "0.0.22",
            highlights = listOf(
                "Every screen now has a one-tap Home button, alongside Back.",
                "Help has been redone: short, scannable tips behind a \"?\" icon on almost " +
                    "every screen, plus a full index here with the same content.",
                "Opening a large, actively-recording race's history no longer slows down or " +
                    "risks crashing the app.",
                "Top bars are tighter — Back is now an icon like Home — and long race names " +
                    "no longer wrap onto two lines.",
            ),
        ),
    )
}
