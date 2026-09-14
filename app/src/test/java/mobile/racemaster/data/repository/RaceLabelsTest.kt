package mobile.racemaster.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RaceLabelsTest {

    // Mirrors buildRaceLabel's own SimpleDateFormat("yy-MM-dd") exactly (same pattern, same
    // default locale/timezone it uses) rather than hardcoding an expected date string, which
    // would be flaky across machines in different default timezones.
    private fun expectedDate(timestamp: Long) = SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(timestamp))

    @Test
    fun buildRaceLabelOmitsTheCourseSegmentWhenCourseIsBlank() {
        val timestamp = System.currentTimeMillis()
        assertEquals("pontesbury-${expectedDate(timestamp)}", buildRaceLabel("pontesbury", "", timestamp))
    }

    @Test
    fun buildRaceLabelIncludesTheCourseSegmentWhenPresent() {
        val timestamp = System.currentTimeMillis()
        assertEquals("pontesbury-seniors-${expectedDate(timestamp)}", buildRaceLabel("pontesbury", "seniors", timestamp))
    }

    @Test
    fun buildRaceLabelTrimsNameAndCourse() {
        val timestamp = System.currentTimeMillis()
        assertEquals("pontesbury-seniors-${expectedDate(timestamp)}", buildRaceLabel("  pontesbury  ", "  seniors  ", timestamp))
    }

    @Test
    fun raceNameFromLabelStripsOnlyTheTrailingDate() {
        assertEquals("pontesbury-seniors", raceNameFromLabel("pontesbury-seniors-26-09-14"))
        assertEquals("pontesbury", raceNameFromLabel("pontesbury-26-09-14"))
    }

    @Test
    fun raceNameFromLabelLeavesALabelWithNoTrailingDateUnchanged() {
        assertEquals("pontesbury-seniors", raceNameFromLabel("pontesbury-seniors"))
        assertEquals("", raceNameFromLabel(""))
    }

    @Test
    fun raceNameFromLabelIsBuildRaceLabelsOwnInverse() {
        val timestamp = System.currentTimeMillis()
        val label = buildRaceLabel("webtest-Seniors", "", timestamp)
        assertEquals("webtest-Seniors", raceNameFromLabel(label))
    }
}
