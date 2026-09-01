package mobile.racemaster.data.mule

import kotlin.time.Duration.Companion.days

/** True when [touchedAtMillis] is older than [maxAgeDays] ago — the one cutoff rule shared by
 *  [MuleRepository.pushToServer]'s own server-sync reconciliation, [PeripheralSyncService]'s
 *  relay-manifest serving (see its own `freshRelayManifest`), and
 *  [mobile.racemaster.ui.racehistory.isSkippedAsStale]'s "too old, no longer checked/relayed"
 *  display badge — see [mobile.racemaster.data.settings.SettingsRepository.raceStaleAfterDays]'s
 *  own doc for the setting this reads. null (no activity signal at all — a Mule-pulled label
 *  never seen, or a local race with no history yet) is never stale; only a genuine timestamp
 *  older than the cutoff counts. */
internal fun isRaceStale(touchedAtMillis: Long?, maxAgeDays: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (touchedAtMillis == null) return false
    return touchedAtMillis < nowMillis - maxAgeDays.days.inWholeMilliseconds
}
