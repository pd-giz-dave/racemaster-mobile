package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // relevantRelayEntries — the one loop-prevention guard this device's own relay-pull loop
    // needs beyond the delta-cursor comparison itself (see pullAllVisibleDevices' own doc):
    // never treat my own data, handed back to me by a mule relaying it, as worth pulling.

    private fun relayEntry(originDeviceId: String, lastLineNumber: Long = 10L) = RelayManifestEntry(
        originDeviceId = originDeviceId,
        originDeviceName = "device-$originDeviceId",
        originRaceLabel = "race-$originDeviceId",
        lastLineNumber = lastLineNumber,
    )

    @Test
    fun myOwnOriginIsFilteredOutOfARelayManifest() {
        val entries = listOf(relayEntry("me"), relayEntry("someone-else"))

        val relevant = relevantRelayEntries(myDeviceId = "me", relayEntries = entries)

        assertEquals(listOf("someone-else"), relevant.map { it.originDeviceId })
    }

    @Test
    fun everyGenuinelyOtherOriginPassesThrough() {
        val entries = listOf(relayEntry("device-a"), relayEntry("device-b"))

        val relevant = relevantRelayEntries(myDeviceId = "me", relayEntries = entries)

        assertEquals(entries, relevant)
    }

    @Test
    fun anEmptyManifestStaysEmpty() {
        assertTrue(relevantRelayEntries(myDeviceId = "me", relayEntries = emptyList()).isEmpty())
    }

    // dedupRelayRows — a relay-only row must drop the instant its origin becomes directly
    // BLE-visible, so the same source never shows twice in the Mule Mode device list.

    private fun relayRow(originDeviceId: String) = DiscoveredDevice(
        deviceKey = "relay:$originDeviceId:race",
        advertisement = null,
        deviceId = originDeviceId,
        relayedViaDeviceName = "some-mule",
    )

    @Test
    fun aRelayRowIsDroppedOnceItsOriginIsDirectlyVisible() {
        val relayRows = mapOf("relay:phone-a:race" to relayRow("phone-a"))

        val deduped = dedupRelayRows(directDeviceIds = setOf("phone-a"), relayRows = relayRows)

        assertTrue(deduped.isEmpty())
    }

    @Test
    fun aRelayRowSurvivesWhenNoDirectEntryMatchesItsOrigin() {
        val relayRows = mapOf("relay:phone-a:race" to relayRow("phone-a"))

        val deduped = dedupRelayRows(directDeviceIds = setOf("phone-b"), relayRows = relayRows)

        assertEquals(relayRows, deduped)
    }

    @Test
    fun onlyTheMatchingRelayRowIsDroppedNotOthers() {
        val relayRows = mapOf(
            "relay:phone-a:race" to relayRow("phone-a"),
            "relay:phone-b:race" to relayRow("phone-b"),
        )

        val deduped = dedupRelayRows(directDeviceIds = setOf("phone-a"), relayRows = relayRows)

        assertEquals(setOf("phone-b"), deduped.values.map { it.deviceId }.toSet())
    }
}
