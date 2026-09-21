package mobile.racemaster.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceLabelsTest {

    @Test
    fun buildRaceLabelIsTheNameVerbatim() {
        assertEquals("pontesbury-seniors", buildRaceLabel("pontesbury-seniors"))
    }

    @Test
    fun buildRaceLabelTrimsTheName() {
        assertEquals("pontesbury", buildRaceLabel("  pontesbury  "))
    }

    @Test
    fun buildRaceLabelNeverAppendsADate() {
        // The whole point of this change: a name already inherited from the server (via Scan
        // Server) or following its own convention must never be silently modified — including
        // one that happens to look like it already ends in a date, or one entered today.
        assertEquals("pontesbury-26-09-14", buildRaceLabel("pontesbury-26-09-14"))
    }

    @Test
    fun raceNameFromLabelStripsOnlyATrailingDate() {
        assertEquals("pontesbury-seniors", raceNameFromLabel("pontesbury-seniors-26-09-14"))
        assertEquals("pontesbury", raceNameFromLabel("pontesbury-26-09-14"))
    }

    @Test
    fun raceNameFromLabelLeavesALabelWithNoTrailingDateUnchanged() {
        // The common case now: buildRaceLabel no longer produces a trailing date at all, so a
        // freshly-created race's own label round-trips through raceNameFromLabel unchanged.
        assertEquals("pontesbury-seniors", raceNameFromLabel("pontesbury-seniors"))
        assertEquals("", raceNameFromLabel(""))
    }
}
