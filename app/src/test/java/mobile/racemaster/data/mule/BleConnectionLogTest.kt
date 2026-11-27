package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BleConnectionLogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fileOf(lines: List<String>): File {
        val file = File(tempFolder.root, "ble-log.txt")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }

    @Test
    fun trimOldestHalfDropsTheFirstHalfOfTheLinesKeepingTheRest() {
        val file = fileOf((1..10).map { "line$it" })
        trimOldestHalf(file)
        assertEquals((6..10).map { "line$it" }, file.readLines())
    }

    @Test
    fun trimOldestHalfOnASingleLineFileKeepsThatOneLine() {
        // size/2 rounds down to 0 for a single line — nothing to drop, so trimming a file this
        // small is a no-op rather than emptying it; MAX_FILE_SIZE_BYTES being reached by a
        // single line at all is a degenerate case this doesn't try to special-case further.
        val file = fileOf(listOf("only line"))
        trimOldestHalf(file)
        assertEquals(listOf("only line"), file.readLines())
    }
}
