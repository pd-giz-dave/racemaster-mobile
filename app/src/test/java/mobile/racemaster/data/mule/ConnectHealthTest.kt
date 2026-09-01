package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectHealthTest {

    @Test
    fun noAttemptsIsZeroFailureRateNotNaN() {
        val health = ConnectHealth(recentAttempts = 0, recentSuccesses = 0)
        assertEquals(0.0, health.failureRate, 0.0)
        assertFalse(health.isStruggling)
    }

    @Test
    fun allSucceededIsNotStruggling() {
        val health = ConnectHealth(recentAttempts = 20, recentSuccesses = 20)
        assertEquals(0, health.recentFailures)
        assertEquals(0.0, health.failureRate, 0.0)
        assertFalse(health.isStruggling)
    }

    @Test
    fun allFailedButBelowMinimumSampleIsNotStrugglingYet() {
        // 100% failure rate, but only 2 attempts — too little data to say anything.
        val health = ConnectHealth(recentAttempts = 2, recentSuccesses = 0)
        assertEquals(1.0, health.failureRate, 0.0)
        assertFalse(health.isStruggling)
    }

    @Test
    fun highFailureRateWithEnoughSamplesIsStruggling() {
        // 6/10 failed (60%), well past the minimum sample size.
        val health = ConnectHealth(recentAttempts = 10, recentSuccesses = 4)
        assertEquals(6, health.recentFailures)
        assertEquals(0.6, health.failureRate, 0.0)
        assertTrue(health.isStruggling)
    }

    @Test
    fun lowFailureRateWithEnoughSamplesIsNotStruggling() {
        // 1/10 failed (10%) — ordinary transient flakiness, not a struggling phone.
        val health = ConnectHealth(recentAttempts = 10, recentSuccesses = 9)
        assertFalse(health.isStruggling)
    }

    @Test
    fun exactlyAtTheWarningThresholdIsStruggling() {
        // 2/5 failed (40%) — right at the boundary, and right at the minimum sample size.
        val health = ConnectHealth(recentAttempts = 5, recentSuccesses = 3)
        assertEquals(0.4, health.failureRate, 0.0)
        assertTrue(health.isStruggling)
    }
}
