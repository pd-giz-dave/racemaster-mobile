package mobile.racemaster.data.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RangeSampleLoggerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleFile(): File = File(tempFolder.root, "samples.csv")

    private val sample = RangeSample(
        timestampMillis = 1_700_000_000_000L,
        context = RangeContext.OUTDOOR_SPARSE_CP,
        distanceMeters = 5.0,
        beaconNumber = 42,
        measuredPowerDbm = -59,
        rssiMedian = -75,
        rssiMin = -79,
        rssiMax = -71,
        sampleCount = 8,
        phoneModel = "SM-G390F",
        androidSdk = 28,
    )

    @Test
    fun firstAppendWritesHeaderThenRow() {
        val file = sampleFile()
        appendSampleToFile(file, sample)
        val lines = file.readLines()
        assertEquals(RANGE_SAMPLE_CSV_HEADER, lines[0])
        assertEquals(
            "2023-11-14T22:13:20Z,OUTDOOR_SPARSE_CP,5.0,42,-59,-75,-79,-71,8,SM-G390F,28",
            lines[1],
        )
    }

    @Test
    fun secondAppendDoesNotRepeatTheHeader() {
        val file = sampleFile()
        appendSampleToFile(file, sample)
        appendSampleToFile(file, sample.copy(beaconNumber = 7))
        val lines = file.readLines()
        assertEquals(3, lines.size)
        assertEquals(RANGE_SAMPLE_CSV_HEADER, lines[0])
    }

    @Test
    fun aPhoneModelContainingACommaIsQuoted() {
        val file = sampleFile()
        appendSampleToFile(file, sample.copy(phoneModel = "Weird, Model"))
        val row = file.readLines()[1]
        assertTrue(row.endsWith("\"Weird, Model\",28"))
    }

    @Test
    fun sampleCountIsZeroForAMissingFile() {
        assertEquals(0, sampleCountInFile(sampleFile()))
    }

    @Test
    fun sampleCountIsTheNumberOfDataRowsNotCountingTheHeader() {
        val file = sampleFile()
        appendSampleToFile(file, sample)
        appendSampleToFile(file, sample)
        assertEquals(2, sampleCountInFile(file))
    }

    @Test
    fun clearDeletesTheFile() {
        val file = sampleFile()
        appendSampleToFile(file, sample)
        assertTrue(file.exists())
        file.delete()
        assertFalse(file.exists())
    }
}
