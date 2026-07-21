package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuleSyncEngineTest {

    // pushResultMessage — TODO 250's fix: a genuinely successful automatic push must now
    // surface a status message too, not just failures (previously an operator watching Mule
    // Mode passively had no way to tell a successful background push had happened at all).

    @Test
    fun successfulAutomaticPushWithNewRecordsSetsAMessage() {
        assertEquals("Pushed 3 new records to the server", pushResultMessage(auto = true, result = Result.success(3)))
    }

    @Test
    fun successfulAutomaticPushWithNothingNewIsSuppressed() {
        // Avoids spamming a message every 10s auto-sync tick once a device is fully caught up.
        assertNull(pushResultMessage(auto = true, result = Result.success(0)))
    }

    @Test
    fun successfulManualPushAlwaysSetsAMessageEvenWithNothingNew() {
        // A manual "Force sync now" tap should always confirm something happened, unlike the
        // background loop.
        assertEquals("Pushed 0 new records to the server", pushResultMessage(auto = false, result = Result.success(0)))
    }

    @Test
    fun singularRecordCountUsesSingularWording() {
        assertEquals("Pushed 1 new record to the server", pushResultMessage(auto = false, result = Result.success(1)))
    }

    @Test
    fun automaticPushFailureAlwaysSetsAMessage() {
        assertEquals(
            "Push failed: boom",
            pushResultMessage(auto = true, result = Result.failure(RuntimeException("boom"))),
        )
    }

    @Test
    fun manualPushFailureAlwaysSetsAMessage() {
        assertEquals(
            "Push failed: boom",
            pushResultMessage(auto = false, result = Result.failure(RuntimeException("boom"))),
        )
    }
}
