package mobile.racemaster.data.mule

/**
 * Decides when a pending "the advertised identity changed" signal should actually turn into one
 * real advertising restart — the general mechanism TODO.md's own peer-sync protocol proposal asks
 * for ("the advert only changes when this seq counter changes, changes can be coalesced over a
 * short period to minimise advert changes") replacing what used to be two separate, ad-hoc
 * band-aids inside [PeripheralSyncService]: a plain trailing debounce
 * (`scheduleAdvertisedIdentityRefresh`) and a reactive "suppress while a same-group peer happens
 * to be nearby" special case bolted on top of it (`observePeerSyncCatchUpRefresh`). Both existed
 * because every advertising restart tears the whole advertising set down and brings up a new one,
 * which gets issued a brand-new random BLE address immediately (see TODO.md's own "advert
 * stop+start" entry) — breaking same-group peer sync's ability to hold a stable address across
 * repeated reconnects to the same peer. The two band-aids together are what actually caused one of
 * this session's confirmed bugs: the catch-up trigger bypassing the debounce and hammering
 * `startAdvertising()`/`stopAdvertising()` with no rate limit once peer-nearby started flapping
 * (22 restarts in 15 seconds, then the advertiser went dark for 7+ minutes). This class is the one
 * mechanism that replaces both, kept deliberately free of Android/coroutine dependencies (an
 * explicit [nowMillis] parameter throughout, no `delay()`/`Job` of its own) so its policy can be
 * unit tested directly rather than only exercised live over BLE.
 *
 * [PeripheralSyncService]'s own tx actor loop is the sole caller — see that class's own
 * `startTxActorLoop` — polling [nextCheckDelayMillis] to know how long to wait before either the
 * next command arrives or the coalescing window elapses on its own.
 */
internal class AdvertisingRefreshCoalescer(private val coalesceWindowMillis: Long) {
    // Null = nothing currently owed. Set to the *first* unresolved dirty signal's timestamp, not
    // the latest — deliberately not a trailing debounce. A trailing debounce (restart the timer
    // on every single dirty signal) is exactly the shape that let the field bug above reach 22
    // restarts in 15 seconds once a flapping signal kept re-triggering it: each restart itself
    // produces more signal activity downstream, so a trailing window can never fully drain while
    // the flapping continues. Anchoring to the first signal instead still collapses a whole burst
    // into one eventual restart (anything else that arrives before the window elapses just waits
    // for the same deadline — see [onDirty]), but that deadline can't be pushed out indefinitely
    // by a pathologically flappy signal.
    private var pendingSinceMillis: Long? = null
    private var suppressed: Boolean = false

    /** The advertised identity changed (or needs re-evaluating — e.g. the low-latency scan mode
     *  flag flipped) at [nowMillis]. A no-op if something is already pending — see this class's
     *  own doc for why the window anchors to the first signal, not the latest. */
    fun onDirty(nowMillis: Long) {
        if (pendingSinceMillis == null) pendingSinceMillis = nowMillis
    }

    /** Mirrors [PeripheralSyncService.refreshAdvertisedIdentity]'s old inline
     *  `hasSameGroupPeerNearby` guard, now structural rather than an early-return buried in one
     *  function: while [suppressed] is true, [nextCheckDelayMillis] never fires, however long
     *  something has been pending. Becoming un-suppressed with nothing already pending behaves
     *  exactly like a fresh [onDirty] at [nowMillis] — this is what lets the old, separate
     *  "catch up once the peer leaves" trigger (`observePeerSyncCatchUpRefresh`) be deleted
     *  entirely: leaving suppression just routes through this same one mechanism. Becoming
     *  suppressed with nothing pending is a no-op — it must not itself create a pending refresh
     *  that would then fire the instant suppression lifts for no real reason. */
    fun onSuppressionChanged(suppressed: Boolean, nowMillis: Long) {
        val wasSuppressed = this.suppressed
        this.suppressed = suppressed
        if (wasSuppressed && !suppressed && pendingSinceMillis == null) {
            pendingSinceMillis = nowMillis
        }
    }

    /** Called once the pending refresh has actually been carried out — clears the pending state
     *  so the next [onDirty] starts a fresh window. */
    fun onRefreshed() {
        pendingSinceMillis = null
    }

    /** Null = nothing to do right now (nothing pending, or suppressed). Otherwise, how many
     *  milliseconds from [nowMillis] the caller should wait before re-checking — `0` meaning the
     *  window has already elapsed and a refresh is owed immediately. */
    fun nextCheckDelayMillis(nowMillis: Long): Long? {
        val since = pendingSinceMillis ?: return null
        if (suppressed) return null
        return (since + coalesceWindowMillis - nowMillis).coerceAtLeast(0)
    }
}
