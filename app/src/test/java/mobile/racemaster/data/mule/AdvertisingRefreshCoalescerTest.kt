package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// See AdvertisingRefreshCoalescer's own class doc for what this replaces and why — in particular
// the regression test below for the field-confirmed "22 restarts in 15 seconds" bug this class
// exists to make structurally impossible, not just less likely.
class AdvertisingRefreshCoalescerTest {
    private val window = 2_000L

    @Test
    fun nothingPendingWithNoDirtySignalEver() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        assertNull(coalescer.nextCheckDelayMillis(0L))
        assertNull(coalescer.nextCheckDelayMillis(1_000_000L))
    }

    @Test
    fun oneDirtySignalIsOwedOnceTheWindowElapses() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        coalescer.onDirty(1_000L)
        assertEquals(window, coalescer.nextCheckDelayMillis(1_000L))
        assertEquals(1_000L, coalescer.nextCheckDelayMillis(2_000L))
        assertEquals(0L, coalescer.nextCheckDelayMillis(3_000L))
        assertEquals(0L, coalescer.nextCheckDelayMillis(9_999L))
    }

    @Test
    fun aSecondDirtySignalWhileOnePendingDoesNotPushTheDeadlineOut() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        coalescer.onDirty(1_000L)
        coalescer.onDirty(2_500L)
        // Still anchored to the first signal (1_000L), not the second — fires at 3_000L, not 4_500L.
        assertEquals(0L, coalescer.nextCheckDelayMillis(3_000L))
    }

    @Test
    fun suppressedNeverFiresRegardlessOfHowLongSomethingHasBeenPending() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        coalescer.onDirty(1_000L)
        coalescer.onSuppressionChanged(suppressed = true, nowMillis = 1_500L)
        assertNull(coalescer.nextCheckDelayMillis(1_500L))
        assertNull(coalescer.nextCheckDelayMillis(100_000L))
    }

    @Test
    fun leavingSuppressionWithNothingPendingStartsAFreshWindowFromThatMoment() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        coalescer.onSuppressionChanged(suppressed = true, nowMillis = 1_000L)
        coalescer.onSuppressionChanged(suppressed = false, nowMillis = 5_000L)
        assertEquals(window, coalescer.nextCheckDelayMillis(5_000L))
        assertEquals(0L, coalescer.nextCheckDelayMillis(7_000L))
    }

    @Test
    fun leavingSuppressionWhenSomethingWasAlreadyPendingKeepsTheOriginalDeadline() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        coalescer.onDirty(1_000L)
        coalescer.onSuppressionChanged(suppressed = true, nowMillis = 1_200L)
        coalescer.onSuppressionChanged(suppressed = false, nowMillis = 2_900L)
        // Original deadline (1_000L + window = 3_000L) is preserved, not reset to 2_900L + window.
        assertEquals(100L, coalescer.nextCheckDelayMillis(2_900L))
        assertEquals(0L, coalescer.nextCheckDelayMillis(3_000L))
    }

    @Test
    fun becomingSuppressedWithNothingPendingCreatesNoSpuriousPendingState() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        coalescer.onSuppressionChanged(suppressed = true, nowMillis = 1_000L)
        coalescer.onSuppressionChanged(suppressed = false, nowMillis = 1_000L)
        // Becoming un-suppressed again right away with nothing ever pending must not have
        // fabricated a pending refresh out of the first (no-op) suppression call.
        assertEquals(window, coalescer.nextCheckDelayMillis(1_000L))
    }

    @Test
    fun onRefreshedClearsPendingStateSoTheNextDirtySignalStartsFresh() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        coalescer.onDirty(1_000L)
        coalescer.onRefreshed()
        assertNull(coalescer.nextCheckDelayMillis(1_000L))
        coalescer.onDirty(10_000L)
        assertEquals(window, coalescer.nextCheckDelayMillis(10_000L))
    }

    // Direct regression test for the field-confirmed bug: a flapping hasSameGroupPeerNearby
    // signal (observed live as 22 restarts in 15 seconds) must now collapse into at most one
    // fire-now result per coalesceWindowMillis, however rapidly it flaps.
    @Test
    fun aRapidlyFlappingSuppressionSignalNeverFiresMoreThanOncePerWindow() {
        val coalescer = AdvertisingRefreshCoalescer(window)
        var fireCount = 0
        var time = 0L
        var suppressedNow = false
        repeat(1_000) {
            // Flap every 50ms — far faster than the 2s window.
            suppressedNow = !suppressedNow
            coalescer.onSuppressionChanged(suppressedNow, time)
            if (!suppressedNow) coalescer.onDirty(time)
            if (coalescer.nextCheckDelayMillis(time) == 0L) {
                fireCount++
                coalescer.onRefreshed()
            }
            time += 50L
        }
        val totalWindows = (time / window) + 1
        assertEquals(true, fireCount <= totalWindows)
    }
}
