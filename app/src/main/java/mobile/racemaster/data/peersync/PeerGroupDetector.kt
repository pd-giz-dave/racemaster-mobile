package mobile.racemaster.data.peersync

import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.mule.MuleGattProfile
import mobile.racemaster.data.settings.toHistoryMode

/**
 * Same-mode peer discovery: answers "is a same-race, same-mode, same-station peer nearby right
 * now" — the signal [PeerSyncEngine] switches scan/advertise mode and poll cadence on, and merges
 * a peer's rows off of. Deliberately depends on nothing but [PeerSyncRepository] (itself a thin,
 * DB-only wrapper — see its own doc), not [mobile.racemaster.data.repository.RaceRepository]/
 * [mobile.racemaster.data.settings.SettingsRepository] directly: this class only ever sees plain
 * values (a race's label/location, an already-decoded [MuleGattProfile.AdvertisedIdentity]), which
 * is what keeps every function here pure and trivially unit-testable with no BLE/DataStore/Room
 * dependency of its own.
 *
 * Lives in its own package, separate from `data.mule`, even though [MuleGattProfile]'s wire
 * format is reused for the advertised convergence-group fingerprint (`groupRaceId`/
 * `locationCode`) — this is a same-mode-phone convergence concern, not Mule Mode's own
 * pull-and-relay-to-server one, and [PeerSyncEngine] runs independently of whether Mule Mode's own
 * engine is started at all. See [PeerSyncEngine]'s own doc for why that separation matters: Mule
 * Mode's own transport is due to change (TODO.md's "Mule mode tweak" — a local HTTP server), and
 * this feature must keep working regardless of what that becomes.
 */
class PeerGroupDetector(
    private val peerSyncRepository: PeerSyncRepository,
) {
    /** This device's own current convergence-group key for a race labelled [raceLabel],
     *  recording [mode] at [location] — see [PeerSyncRepository.groupKey] for why [location] only
     *  matters for [HistoryMode.BIBS]. The "own" counterpart to [groupKeyOf]. */
    fun ownGroupKey(raceLabel: String, location: String, mode: HistoryMode): String =
        peerSyncRepository.groupKey(MuleGattProfile.groupRaceId(raceLabel), mode, MuleGattProfile.locationCode(location))

    /** The convergence-group key [decoded] advertises, or null if it's advertising no active
     *  race ([MuleGattProfile.AdvertisedIdentity.groupRaceId] 0 — see that field's own doc for
     *  why that's never treated as a real group) or no mode. */
    fun groupKeyOf(decoded: MuleGattProfile.AdvertisedIdentity): String? {
        if (decoded.groupRaceId == 0) return null
        val mode = decoded.mode?.toHistoryMode() ?: return null
        return peerSyncRepository.groupKey(decoded.groupRaceId, mode, decoded.locationCode)
    }

    /** Every key in [decodedByKey] whose advertised identity shares [ownGroupKey] — what
     *  [PeerSyncEngine] drives its LOW_LATENCY switch and accelerated pull cadence from. A
     *  null/absent decoded identity (scan window missed it, or a peer not advertising a race/mode
     *  at all) never counts as a match; neither does a null [ownGroupKey] (this device itself has
     *  nothing to converge on right now). */
    fun sameGroupPeerKeys(ownGroupKey: String?, decodedByKey: Map<String, MuleGattProfile.AdvertisedIdentity?>): Set<String> {
        if (ownGroupKey == null) return emptySet()
        return decodedByKey.filterValues { it != null && groupKeyOf(it) == ownGroupKey }.keys
    }
}
