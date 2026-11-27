package mobile.racemaster.data.peersync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerSyncEngineSelectionTest {
    @Test
    fun withExactlyOneNearbyItIsAutoPicked() {
        val result = recomputeOwnPick(previousPick = null, nearbyIds = setOf(1L))
        assertEquals(1L, result)
    }

    @Test
    fun withNoOneNearbyThereIsNoPick() {
        val result = recomputeOwnPick(previousPick = null, nearbyIds = emptySet())
        assertNull(result)
    }

    @Test
    fun withMoreThanOneNearbyAndNothingPickedYetNothingIsAutoPicked() {
        // Not a cap-fill — a genuine choice exists, so this stays null until the operator (and
        // the other side) explicitly agree; see resolvePairingOutcome.
        val result = recomputeOwnPick(previousPick = null, nearbyIds = setOf(5L, 1L, 3L))
        assertNull(result)
    }

    @Test
    fun aPickThatLeftRangeWithNoOneElseNearbyIsCleared() {
        val result = recomputeOwnPick(previousPick = 2L, nearbyIds = emptySet())
        assertNull(result)
    }

    @Test
    fun aPickThatLeftRangeFallsBackToAutoPickWhenExactlyOneRemains() {
        val result = recomputeOwnPick(previousPick = 2L, nearbyIds = setOf(5L))
        // Same case as above, named for the auto-pick angle: once only one candidate is left,
        // it's unambiguous again regardless of what was picked before.
        assertEquals(5L, result)
    }

    @Test
    fun anExplicitPickIsKeptEvenAsMoreNearbyPeersAppear() {
        val result = recomputeOwnPick(previousPick = 1L, nearbyIds = setOf(1L, 3L, 7L))
        assertEquals(1L, result)
    }

    @Test
    fun resolvePairingOutcomeIsUndecidedWithNoMutualPickAnywhere() {
        val outcome = resolvePairingOutcome(selfId = 1L, selfPick = 2L, peerPicks = mapOf(2L to null))
        assertEquals(PairingOutcome.Undecided, outcome)
    }

    @Test
    fun resolvePairingOutcomeIsPairedWhenBothSidesPickEachOther() {
        val outcome = resolvePairingOutcome(selfId = 1L, selfPick = 2L, peerPicks = mapOf(2L to 1L))
        assertEquals(PairingOutcome.Paired(2L), outcome)
    }

    @Test
    fun resolvePairingOutcomeIgnoresAOneSidedPick() {
        // selfId picked 2L, but 2L picked someone else entirely — not mutual, so still undecided.
        val outcome = resolvePairingOutcome(selfId = 1L, selfPick = 2L, peerPicks = mapOf(2L to 3L, 3L to null))
        assertEquals(PairingOutcome.Undecided, outcome)
    }

    @Test
    fun resolvePairingOutcomeIsExcludedWhenTwoOthersHavePairedWithoutSelf() {
        // 3 phones nearby: 2L and 3L have mutually picked each other; self (1L) hasn't picked
        // anyone (or picked someone who didn't reciprocate) — self is the odd one out.
        val outcome = resolvePairingOutcome(selfId = 1L, selfPick = null, peerPicks = mapOf(2L to 3L, 3L to 2L))
        assertEquals(PairingOutcome.Excluded(2L, 3L), outcome)
    }

    @Test
    fun resolvePairingOutcomePrefersSelfsOwnPairOverAnUnrelatedOne() {
        // 4 phones: self(1L)<->4L mutually paired, AND 2L<->3L mutually paired at the same time
        // — self must resolve its OWN pair, not get confused by the other, unrelated one.
        val outcome = resolvePairingOutcome(selfId = 1L, selfPick = 4L, peerPicks = mapOf(2L to 3L, 3L to 2L, 4L to 1L))
        assertEquals(PairingOutcome.Paired(4L), outcome)
    }
}
