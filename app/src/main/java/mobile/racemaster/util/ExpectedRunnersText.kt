package mobile.racemaster.util

/** Bibs Mode's feedback line — [finishedCount] is the raw count of FINISH-or-RETIRE records
 *  (see [mobile.racemaster.data.repository.accountedForRecordCount]; a RETIRE means that bib
 *  is accounted for and won't cross the line either), purely `expected - accountedFor`, not a
 *  distinct-bib count: a bib logged as FINISH twice by mistake still represents two records
 *  here, a recording error to be corrected later rather than something this figure quietly
 *  papers over. Returns null when the race has neither field set (e.g. still loading). */
fun formatBibsExpectedText(firstBibNumber: Int?, expectedRunnerCount: Int?, finishedCount: Int): String? {
    if (firstBibNumber == null && expectedRunnerCount == null) return null
    val parts = listOfNotNull(
        firstBibNumber?.let { "First bib $it" },
        expectedRunnerCount?.let { expected ->
            val outstanding = (expected - finishedCount).coerceAtLeast(0)
            "$outstanding of $expected still outstanding"
        },
    )
    return parts.joinToString(" · ")
}

/** Time Mode's feedback line — unlike Bibs, Time Mode never records *which* bib crossed, just
 *  an anonymous split count, so there's no way to say how many are still outstanding. This
 *  just echoes what was entered on the race details form plus a running tally, not a
 *  remaining-count. Returns null when the race has neither field set (e.g. still loading). */
fun formatTimeSplitsText(firstBibNumber: Int?, expectedRunnerCount: Int?, splitCount: Int): String? {
    if (firstBibNumber == null && expectedRunnerCount == null) return null
    val parts = listOfNotNull(
        firstBibNumber?.let { "First bib $it" },
        expectedRunnerCount?.let { "runners $it" },
        "$splitCount split${if (splitCount == 1) "" else "s"} so far",
    )
    return parts.joinToString(", ")
}
