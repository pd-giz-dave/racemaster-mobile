package mobile.racemaster.util

/** Bibs/CP Mode's feedback line — [outstandingCount] and [expectedCount] come from phase 4's
 *  progress-record-derived expectation (see [mobile.racemaster.data.repository.expectedBibsAtLocation]),
 *  not a configured range. Null when nothing's expected yet (no progress data to judge by — see
 *  that function's own defensive-empty doc), matching how this line used to disappear entirely
 *  when no range was configured. */
fun formatBibsExpectedText(expectedCount: Int, outstandingCount: Int): String? {
    if (expectedCount == 0) return null
    return "$outstandingCount of $expectedCount still outstanding"
}

/** Time Mode's feedback line — Time Mode never records *which* bib crossed, just an anonymous
 *  split count, so there's no per-bib expectation to report at all (see
 *  [mobile.racemaster.data.repository.starters]'s own doc — that's a Bibs/CP Mode-only concept).
 *  Just a running tally. */
fun formatTimeSplitsText(splitCount: Int): String = "$splitCount split${if (splitCount == 1) "" else "s"} so far"
