package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DominantFailurePeerTest {

    @Test
    fun noFailuresIsNoDominantPeer() {
        assertNull(dominantFailurePeer(emptyList()))
    }

    @Test
    fun tooFewFailuresIsNoDominantPeerEvenIfAllAgainstOnePeer() {
        // 2 failures, both against "dusty-stork" — 100% of failures, but too little data to
        // call it a pattern yet (below DOMINANT_FAILURE_MIN_COUNT).
        assertNull(dominantFailurePeer(listOf("dusty-stork", "dusty-stork")))
    }

    @Test
    fun failuresConcentratedAgainstOnePeerIsDominant() {
        // 4/5 failures against "dusty-stork" (80%) — well past both the minimum count and the
        // fraction threshold.
        val failures = listOf("dusty-stork", "vivid-viper", "dusty-stork", "dusty-stork", "dusty-stork")
        assertEquals("dusty-stork", dominantFailurePeer(failures))
    }

    @Test
    fun failuresSpreadAcrossSeveralPeersIsNoDominantPeer() {
        // 6 failures spread evenly across 3 peers (2 each, ~33% each) — no single peer
        // responsible for most of them, which is the actual signal of this phone's own radio
        // struggling rather than one bad peer.
        val failures = listOf(
            "dusty-stork", "vivid-viper", "brave-reef",
            "dusty-stork", "vivid-viper", "brave-reef",
        )
        assertNull(dominantFailurePeer(failures))
    }

    @Test
    fun exactlyAtTheFractionThresholdIsDominant() {
        // 7/10 failures against "dusty-stork" (70%) — right at the boundary.
        val failures = List(7) { "dusty-stork" } + List(3) { "vivid-viper" }
        assertEquals("dusty-stork", dominantFailurePeer(failures))
    }

    @Test
    fun justBelowTheFractionThresholdIsNoDominantPeer() {
        // 6/10 failures against "dusty-stork" (60%) — below the 70% threshold.
        val failures = List(6) { "dusty-stork" } + List(4) { "vivid-viper" }
        assertNull(dominantFailurePeer(failures))
    }
}
