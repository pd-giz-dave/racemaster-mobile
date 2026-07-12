package mobile.racemaster.util

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNameGeneratorTest {

    @Test
    fun nameIsTwoWordsSeparatedByHyphen() {
        val name = generateDeviceName(Random(42))
        val parts = name.split("-")
        assertEquals(2, parts.size)
        assertTrue(parts[0].isNotBlank())
        assertTrue(parts[1].isNotBlank())
    }

    @Test
    fun sameSeedProducesSameName() {
        assertEquals(generateDeviceName(Random(7)), generateDeviceName(Random(7)))
    }

    @Test
    fun nameIsLowercaseOnly() {
        val name = generateDeviceName(Random(99))
        assertEquals(name.lowercase(), name)
    }
}
