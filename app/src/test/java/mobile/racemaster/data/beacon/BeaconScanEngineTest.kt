package mobile.racemaster.data.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconScanEngineTest {
    @Test
    fun firstSightingStartsAFreshEntryWithOneReading() {
        val tracking = updatedDetectionTracking(existing = null, nowMillis = 1_000L, maxGapMillis = 5_000L)
        assertEquals(DetectionTracking(consecutiveReadings = 1, lastSeenAtMillis = 1_000L), tracking)
    }

    @Test
    fun aSightingWithinTheGapIncrementsTheCount() {
        val existing = DetectionTracking(consecutiveReadings = 2, lastSeenAtMillis = 1_000L)
        val tracking = updatedDetectionTracking(existing, nowMillis = 3_000L, maxGapMillis = 5_000L)
        assertEquals(DetectionTracking(consecutiveReadings = 3, lastSeenAtMillis = 3_000L), tracking)
    }

    @Test
    fun aSightingExactlyAtTheGapBoundaryStillCountsAsContinuing() {
        val existing = DetectionTracking(consecutiveReadings = 1, lastSeenAtMillis = 1_000L)
        val tracking = updatedDetectionTracking(existing, nowMillis = 6_000L, maxGapMillis = 5_000L)
        assertEquals(DetectionTracking(consecutiveReadings = 2, lastSeenAtMillis = 6_000L), tracking)
    }

    @Test
    fun aGapBeyondMaxGapMillisResetsToAFreshEntry() {
        val existing = DetectionTracking(consecutiveReadings = 2, lastSeenAtMillis = 1_000L)
        val tracking = updatedDetectionTracking(existing, nowMillis = 6_001L, maxGapMillis = 5_000L)
        assertEquals(DetectionTracking(consecutiveReadings = 1, lastSeenAtMillis = 6_001L), tracking)
    }

    @Test
    fun shouldCommitIsFalseBelowTheRequiredCount() {
        val tracking = DetectionTracking(consecutiveReadings = 2, lastSeenAtMillis = 0L)
        assertFalse(shouldCommit(tracking, requiredConsecutiveReadings = 3))
    }

    @Test
    fun shouldCommitIsTrueOnceTheRequiredCountIsReached() {
        val tracking = DetectionTracking(consecutiveReadings = 3, lastSeenAtMillis = 0L)
        assertTrue(shouldCommit(tracking, requiredConsecutiveReadings = 3))
    }
}
