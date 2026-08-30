package mobile.racemaster.data.mule

import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.db.entity.KnownDeviceEntity
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

    // previouslySeenDevices — MuleModeScreen's "Previously seen" list: the persisted roster
    // minus whatever's already showing up live, so the same device never shows twice.

    private fun known(deviceId: String, name: String = "device-$deviceId") = KnownDeviceEntity(deviceId, name, 0L)

    @Test
    fun aKnownDeviceNotCurrentlyLiveIsIncluded() {
        val result = previouslySeenDevices(known = listOf(known("phone-a")), liveDeviceIds = emptySet())

        assertEquals(listOf("phone-a"), result.map { it.deviceId })
    }

    @Test
    fun aKnownDeviceCurrentlyLiveIsExcluded() {
        val result = previouslySeenDevices(known = listOf(known("phone-a"), known("phone-b")), liveDeviceIds = setOf("phone-a"))

        assertEquals(listOf("phone-b"), result.map { it.deviceId })
    }

    @Test
    fun anEmptyRosterStaysEmpty() {
        assertTrue(previouslySeenDevices(known = emptyList(), liveDeviceIds = setOf("phone-a")).isEmpty())
    }

    // withLastPulledAtMillis — the per-device "Last pulled" feedback NearbyDevicesSection shows
    // against every live row, joined from PulledSourceSummary rather than tracked in
    // DiscoveredDevice itself.

    private fun summary(sourceDeviceId: String, sourceRaceLabel: String = "race-1", lastPulledAtMillis: Long = 1_000L) =
        PulledSourceSummary(sourceRaceLabel, sourceDeviceId, deviceName = "device-$sourceDeviceId", lastPulledAtMillis, lastLineNumber = 5L)

    @Test
    fun fillsInLastPulledAtMillisForAMatchingDeviceIdAndRaceLabel() {
        val devices = listOf(DiscoveredDevice(deviceKey = "phone-a", advertisement = null, deviceId = "phone-a", raceLabel = "race-1"))

        val result = withLastPulledAtMillis(devices, listOf(summary("phone-a", "race-1", lastPulledAtMillis = 5_000L)))

        assertEquals(5_000L, result.single().lastPulledAtMillis)
    }

    @Test
    fun leavesARowWithNoMatchingSummaryAtItsDefaultNull() {
        val devices = listOf(DiscoveredDevice(deviceKey = "phone-a", advertisement = null, deviceId = "phone-a", raceLabel = "race-1"))

        val result = withLastPulledAtMillis(devices, emptyList())

        assertNull(result.single().lastPulledAtMillis)
    }

    @Test
    fun aRaceLabelMismatchIsNotTreatedAsAMatch() {
        // The same physical device running two different race labels over time must not have
        // one race label's pull time bleed onto the other's row — see PulledSourceSummary's own
        // doc on why a source is grouped by (device, race label) together, never device alone.
        val devices = listOf(DiscoveredDevice(deviceKey = "phone-a", advertisement = null, deviceId = "phone-a", raceLabel = "race-2"))

        val result = withLastPulledAtMillis(devices, listOf(summary("phone-a", "race-1")))

        assertNull(result.single().lastPulledAtMillis)
    }

    @Test
    fun aSelfRowWithNoDeviceIdIsLeftUnchanged() {
        val selfDevice = DiscoveredDevice(deviceKey = "self", advertisement = null, deviceId = null, isSelf = true)

        val result = withLastPulledAtMillis(listOf(selfDevice), listOf(summary("phone-a")))

        assertNull(result.single().lastPulledAtMillis)
    }

    // shouldConnect — the connect-gating decision that replaces "connect+DeviceInfo-read every
    // visible device every tick" with "only when there's actually a reason to." Every case here
    // must fail SAFE toward connecting (never silently miss real new data) — the only thing
    // it's allowed to skip is a real, expensive GATT round trip to a device it already has
    // strong reason to believe is unchanged.

    private fun device(
        deviceId: String? = "phone-a",
        confirmedLineNumber: Long? = 10L,
        lastRealReadAtMillis: Long = 0L,
    ) = DiscoveredDevice(
        deviceKey = deviceId ?: "raw-address",
        advertisement = null,
        deviceId = deviceId,
        confirmedLineNumber = confirmedLineNumber,
        lastRealReadAtMillis = lastRealReadAtMillis,
    )

    private fun identity(lastLineNumber: Long, name: String = "phone-a") =
        MuleGattProfile.AdvertisedIdentity(lastLineNumber, MuleGattProfile.shortDeviceId(name), name)

    @Test
    fun forceAlwaysConnectsRegardlessOfAnythingElse() {
        val unchanged = device(confirmedLineNumber = 10L, lastRealReadAtMillis = 1_000L)

        assertTrue(shouldConnect(unchanged, identity(10L), nowMillis = 1_000L, verifyIntervalMillis = 60_000L, force = true))
    }

    @Test
    fun aNeverResolvedDeviceAlwaysConnects() {
        val neverResolved = device(deviceId = null, confirmedLineNumber = null)

        assertTrue(shouldConnect(neverResolved, identity(0L), nowMillis = 0L, verifyIntervalMillis = 60_000L, force = false))
    }

    @Test
    fun aResolvedDeviceWithNoConfirmedLineNumberYetAlwaysConnects() {
        // Shouldn't normally happen (mergeDeviceInfo always stamps both together), but nothing
        // here should assume that pairing holds.
        val partiallyResolved = device(deviceId = "phone-a", confirmedLineNumber = null)

        assertTrue(shouldConnect(partiallyResolved, identity(0L), nowMillis = 0L, verifyIntervalMillis = 60_000L, force = false))
    }

    @Test
    fun anUndecodableAdvertisementAlwaysConnects() {
        // Missed scan window, or a peer running an older build that predates this payload
        // entirely — must fail safe by connecting, exactly like this device's pre-redesign
        // behavior.
        val resolved = device(confirmedLineNumber = 10L, lastRealReadAtMillis = 1_000L)

        assertTrue(shouldConnect(resolved, decoded = null, nowMillis = 1_000L, verifyIntervalMillis = 60_000L, force = false))
    }

    @Test
    fun anAdvancedAdvertisedCounterConnects() {
        val resolved = device(confirmedLineNumber = 10L, lastRealReadAtMillis = 1_000L)

        assertTrue(shouldConnect(resolved, identity(11L), nowMillis = 1_000L, verifyIntervalMillis = 60_000L, force = false))
    }

    @Test
    fun anUnchangedCounterWithinTheVerifyIntervalSkipsConnecting() {
        val resolved = device(confirmedLineNumber = 10L, lastRealReadAtMillis = 1_000L)

        assertTrue(!shouldConnect(resolved, identity(10L), nowMillis = 30_000L, verifyIntervalMillis = 60_000L, force = false))
    }

    @Test
    fun theVerifyIntervalElapsingConnectsEvenWithAnUnchangedCounter() {
        // The periodic backstop — also what eventually notices a relay-manifest change, which
        // the advertised counter alone never reflects.
        val resolved = device(confirmedLineNumber = 10L, lastRealReadAtMillis = 1_000L)

        assertTrue(shouldConnect(resolved, identity(10L), nowMillis = 61_001L, verifyIntervalMillis = 60_000L, force = false))
    }

    @Test
    fun aLowerAdvertisedCounterThanConfirmedStillSkipsWithinTheInterval() {
        // Shouldn't normally happen (lastLineNumber only grows), but a stale/rolled-back
        // advertisement must not force an unnecessary connect on its own.
        val resolved = device(confirmedLineNumber = 10L, lastRealReadAtMillis = 1_000L)

        assertTrue(!shouldConnect(resolved, identity(5L), nowMillis = 1_500L, verifyIntervalMillis = 60_000L, force = false))
    }

    @Test
    fun aPendingConfirmationWaitsOutTheShorterRelayIntervalRatherThanConnectingImmediately() {
        // Regression test: an earlier version treated pendingConfirmation as an unconditional
        // "always true", which in a multi-phone mesh (several phones each independently owing
        // a relay for the same source) meant a forced reconnect on literally every tick for as
        // long as any confirmation stayed unrelayed — confirmed in the field as things getting
        // worse, not better. It must still be gated on an interval, just a shorter one than
        // verifyIntervalMillis.
        val resolved = device(confirmedLineNumber = 10L, lastRealReadAtMillis = 1_000L)

        assertTrue(
            !shouldConnect(
                resolved, identity(10L), nowMillis = 5_000L, verifyIntervalMillis = 60_000L, force = false,
                pendingConfirmation = true, confirmationRelayIntervalMillis = 15_000L,
            ),
        )
    }

    @Test
    fun aPendingConfirmationConnectsOnceItsOwnShorterIntervalElapses() {
        val resolved = device(confirmedLineNumber = 10L, lastRealReadAtMillis = 1_000L)

        assertTrue(
            shouldConnect(
                resolved, identity(10L), nowMillis = 16_001L, verifyIntervalMillis = 60_000L, force = false,
                pendingConfirmation = true, confirmationRelayIntervalMillis = 15_000L,
            ),
        )
    }
}
