# Plan: BLE beacon detection for auto-recording runner bib passes

## 2026-11-05 update: real-hardware scan-reliability findings and resulting policy

A separate investigation (on the `beacon-tracking` branch, using the now-built Setup Beacons
screen and real DX-CP27 hardware — see "staleness flag" below) measured, via real HCI snoop
captures on a Samsung XCover 4 and XCover 7, how reliably Android's BLE scanner actually
receives a beacon's advertisements over time. This matters directly to `BeaconScanEngine`
below, since it's the same `BluetoothLeScanner`/Kable receive path this plan depends on.

**What was found and fixed already** (both now live in `BeaconProgrammerViewModel.kt`, worth
porting into `BeaconScanEngine` rather than rediscovering) (NB: duplication not allowed, 
re-use where possible):
- A second, concurrently-registered app-level BLE scanner (this app's own Mule Mode, both its
  scan *and* advertise/GATT-server roles) measurably degrades reception of a third-party
  beacon — confirmed via `dumpsys bluetooth_manager`'s "App Scan Info"/logcat `BLE_GAP` lines
  showing two live scanners and an active incoming GATT connection from a Mule peer during a
  test. Fixed for Setup Beacons via `SettingsRepository.bluetoothOff` (suspends *both* Mule
  roles, not just `muleSyncEnabled` which only covers scanning).
- A single, unbroken Kable scan session degrades over time even with nothing else running —
  confirmed as a clean time-trend in an HCI capture (clean for ~230s, then increasingly long
  gaps for the rest of a 30+ minute session). This is the exact same failure
  `MuleSyncEngine.SCAN_REFRESH_INTERVAL` (90s teardown/restart) already exists to work around
  for Mule's own scanning — Setup Beacons' scan didn't have it and now does.

**What remains after both fixes — an irreducible tail, not further fixable from app code**:
on a clean 23-minute post-fix capture, a beacon advertising nominally every ~300ms still showed
gaps up to 9.5s (median ~509ms); one nominally every 1.8s showed gaps up to 39.6s (median right
on target, no further time-trend). `dumpsys bluetooth_manager` shows why: Samsung's
`com.samsung.android.beaconmanager` and Google Play Services both register their own permanent
background BLE scanners on stock Android — the radio's single scan window is shared/
time-multiplexed across every registered scanner system-wide, not just this app's, and no
Android API exists for one app to get scan priority over another. This is a platform ceiling.

**Commercial precedent** (direct research, not guesswork): Atlas Live Tracking is a real
race-timing product using ordinary Android phones as BLE checkpoint/finish-line scanners —
the same architecture as this plan. It claims ~0.5s accuracy but explicitly caps event size
(~3,000-4,000 participants) specifically because of BLE scanning throughput limits, and
recommends **2-3 phones per line**, not one, as its own mitigation for exactly the missed-
advertisement problem measured above. Even its vendor concedes RFID remains the gold standard
for scale/precision, since RFID antennas listen continuously at the physical layer with no OS
scan scheduler involved at all. A separate academic BLE race-timing paper found that shortening
a beacon's advertising interval (not scanner-side tuning) has the largest effect on worst-case
discovery latency, and that adding a second scanner materially cuts mean discovery time.

**Resulting policy for this app** (operator decision, not a technical requirement — captures
the actual accuracy needs of this app's two use cases):
- **Checkpoints**: timing is *not* critical — a runner only needs to be caught once while
  passing, at whatever moment the phone's duty cycle happens to be listening. The single-
  scanner design in §2/§4 below is sufficient as originally planned; no redundancy needed here.
- **Finish line**: timing *does* matter — require **at least 2 phones, one positioned either
  side of the finish line**, both running the same `BeaconScanEngine`, so a missed window on
  one phone doesn't cost the whole race its finish timestamp for that runner. Mirrors Atlas's
  own field practice directly. **Open design question this plan doesn't yet resolve**: two
  independent phones will each independently try to `commitPass`/`recordEntry` for the same
  runner's finish — needs de-duplication, presumably riding the existing Mule-sync +
  `BibValidation` duplicate-split detection path (`findDuplicateSplitRefs`) that already
  tolerates near-simultaneous entries for the same bib, rather than new plumbing. Confirm this
  actually resolves sensibly (e.g. picks the stronger/closer peak-RSSI reading, not just
  "first synced wins") before shipping the dual-phone finish setup.
- **Beacon radio tuning — done, applies to every beacon, not just finish-line ones**: rather
  than building a Checkpoint/Finish role split, the operator decided to just raise the floor for
  every beacon this app programs — simpler than maintaining two profiles, and checkpoint
  detection is only better off for it even though it doesn't strictly need it. `DxCp27Protocol`'s
  `RACEMASTER_TX_POWER_DBM`/`RACEMASTER_ADV_INTERVAL_MS` constants are now **-1.0dBm** (was
  -7.0dBm, `TX_POWER_DBM_BY_RAW` index 2 → index 4) and **200ms** (was 300ms) — live in
  `BeaconRepository.programForRace` as of 2026-11-05. No Setup Beacons UI change was needed
  (no role selector, no second profile) — this was a one-line constant change.

**Architecture implications for `BeaconScanEngine` (§4) specifically**:
- It already runs from `PeripheralSyncService.onCreate()` — i.e. already inside a genuine
  foreground service, which the research flagged as the single most important prerequisite for
  sustained BLE scanning on Android. Good — no change needed there.
- **New action item**: request exemption from battery optimization for this app (Samsung's
  Device Care/"Put unused apps to sleep" in particular has repeated, if unofficial, reports of
  throttling or killing foreground-service BLE scanning after sustained use regardless of the
  foreground-service status) — surface this as an onboarding/setup-time prompt, not something
  silently assumed.
- **Port the 90-second scan-restart mitigation into `BeaconScanEngine` from day one**, not as a
  later fix — it'll run for an entire race (hours), far longer than the admin screen session
  that originally surfaced the degradation, so the failure mode is if anything more certain to
  occur here.
- Confirmed-safe to leave alone: `setReportDelay`/batching (evidence points to it making
  latency-sensitive detection *worse*, not better — stay at `0`/no batching, which §2 already
  does implicitly) and extended-advertising/Coded PHY (no reliability benefit found for this
  use case, just range/throughput, not worth the compatibility risk).

**Staleness flag for whoever resumes this plan**: §1 below (`IBeaconParser`) still assumes a
generic Apple-iBeacon-manufacturer-data fob, written when this plan was parked waiting on
AliExpress fobs to be acquired. The hardware has since settled on DX-CP27 beacons programmed
via this app's own Setup Beacons screen, which encodes the bib/power identity in the **device
name** AD field (`"RM." + minor + sign + measuredPower`, parsed by
`DxCp27Protocol.parseRaceDeviceName`), not an iBeacon manufacturer-data frame. `BeaconScanEngine`
should almost certainly decode advertisements via `DxCp27Protocol`'s existing parser rather than
building a new `IBeaconParser` against a format that was never actually adopted — confirm this
before starting phase 1, and treat §1's code sketch as superseded, not as a spec to implement.

**2026-11-05 — resolved, phase 1 implemented**: §1 is replaced by
`data/beacon/BeaconScanDecoder.kt` (`DetectedRaceBeacon`/`decodeFromAdvertisement`/`decode`/
`estimateDistanceMeters`), built on `DxCp27Protocol.parseRaceDeviceName` as predicted above — see
§1's own rewritten text below. Also while implementing phase 1, the generic "fob short code"
vocabulary throughout this doc (and §3/§4's original code sketches) has been replaced with
**`beaconNumber: Int`** end to end — matching the real identity model (the same 0-999 "Beacon
Number" an operator already enters on Setup Beacons, not an opaque string), not a leftover from
when the hardware was an unspecified AliExpress fob. `ProgressEntry.fobShortCode: String?` is now
`ProgressEntry.beaconNumber: Int?`, and `ProgressRepository.getBibForFob` is now `getBibForBeacon`
— §3/§4/§6 below are updated to match; the **cross-repo server-side field name** (§3's own
"out of scope" note) must use this same `beaconNumber` name, not `fobShortCode`, once that work
starts. Phase 1 steps 1, 3, 4, and 5 (parser, shared-scan plumbing, bib-lookup field, and
IdentifyFobScreen/nav wiring) are done; step 6 (manual field test against a real beacon) is the
one thing only achievable with real hardware in hand, left for the operator.

## Context

TODO.md's "Another idea" (being explored on the `beacon-tracking` branch) proposes extending
the app to recognize BLE beacons worn by runners, so a checkpoint/finish-line phone can
auto-record a runner's bib number and timestamp without a marshal typing it in. Cheap BLE
tracker fobs (~£3–£7 from AliExpress/Alibaba, 1–6 year battery life) are the target hardware.
TODO.md's own "Implementation notes" spell out three requirements that shape this plan directly:

1. A detection must be timed at the fob's **closest approach** (peak signal strength), not at
   first-seen or last-seen — because the fob is meant to double as a *timing* device (resolving
   photo-finishes), not just a bib-ID reader. The signal profile isn't a simple bell curve, so
   the engine must track a running best-RSSI-so-far for a candidate pass and only commit once
   the fob has gone out of range. At the finish, runners tend to hang about, so repeat detections
   are almost certain, these need to be ignored.
2. Measured-power at 1m (as encoded in the device name - see elsewhere) should be used to estimate 
   distance and enforce a max-range threshold, tight during registration (<1m), loose at CPs and
   not so loose at the finish (<3m).
3. **Bib-to-fob pairing happens at registration in the Racemaster web-app, not on the phone.**
   Each fob has a short-code (a sequence number unique to that physical unit, known to the
   web-app) — the same shape as an orienteering dibber-number allocation. The web-app's
   lookup table is populated at registration and then broadcast to phones piggybacked on the
   **existing bib/name propagation system** (`ProgressEntry`/`ProgressPayload`), not a new
   mechanism. Fobs are normally allocated from 1 upwards for each race just like dibbers.

Point 3 is a significant correction to the design's data flow versus a first pass at this plan:
there is no local "pairing database" to build and no on-phone marshal-tap-to-assign screen as
the primary mechanism — the phone is a consumer of a mapping the server already knows, riding
the sync path that already carries bib/name/course data down to every device. The only
on-device, registration-time need is a way to verify the fobs ID is as expected (matches it label).

This also needs to coexist with the existing BLE central-scanning role (`MulePullClient.kt`)
used by Mule Mode, especially on the low-end/old hardware (Samsung XCover 4) separately being
evaluated for this app — that file's own doc comments already document real contention
problems running scan+advertise+GATT-server simultaneously on cheap chipsets, which shapes the
scan-integration decision below.

Confirmed design decisions:
- **Protocol**: assume device-info advertising (ID + measured power encoded in the device
  name) — parseable from the advertisement alone, no GATT connect needed.
- **Auto-record, no manual confirm step** — refined by point 1 above into "auto-record, timed
  at peak signal, committed once the fob is gone."

## Architecture

Original plan item here deleted as no longer accurate.

No new Room entity, no DB version bump, no new foreground service, no new Kable `Scanner`, no
AndroidManifest changes. This is a meaningfully smaller surface than a local-pairing-database
design would have needed, precisely because point 3 moves the mapping authority server-side.

### 1. Device info parser — implemented as `BeaconScanDecoder`

`data/beacon/BeaconScanDecoder.kt` (not `IBeaconParser` — see the staleness flag above):
- `DetectedRaceBeacon(address, beaconNumber, measuredPowerDbm, rssi)`, with a computed
  `estimatedDistanceMeters` property.
- `decode(name: String?, rssi: Int, address: String): DetectedRaceBeacon?` — the real, pure,
  unit-tested logic: `null` for no name or a name not shaped like
  `DxCp27Protocol.raceDeviceName`'s format, otherwise built from
  `DxCp27Protocol.parseRaceDeviceName(name)` plus the passed-through rssi/address.
- `decodeFromAdvertisement(advertisement: Advertisement): DetectedRaceBeacon?` — a thin wrapper
  calling `decode(advertisement.name, advertisement.rssi, advertisement.identifier)`, kept
  separate so `decode` itself stays testable against plain synthetic values without needing a
  real or mocked Kable `Advertisement` (confirmed: this JVM-only test module has no way to
  construct one, since it wraps a platform `ScanResult`).
- `estimateDistanceMeters(measuredPowerDbm: Int, rssi: Int): Double` — log-distance path-loss
  model, `10 ^ ((measuredPowerDbm - rssi) / (10 * n))`, fixed `n = 2` (free-space exponent) for
  now — see phase 3 step 12's own "tune path-loss exponent against real field data".

`BeaconScanDecoderTest.kt` covers: a real race-programmed name decodes correctly; a factory/
unrelated name and a missing name both return `null`; the distance formula is exactly 1m when
rssi matches measuredPower, and scales correctly (10x/0.1x for a ±20dB offset at n=2).

### 2. Scan integration — key architectural call — implemented as described below

**Share the one Kable `Scanner` `MulePullClient` already owns; do not open a second one.**
`MulePullClient.kt`'s own doc comments (around lines 44–73) already document, from real field
evidence, that cheap/old chipsets (explicitly including the XCover 4) handle simultaneous
scan+advertise+GATT-server roles poorly, and that scan/advertise settings were already tuned
down to reduce radio contention. A second independent native `BluetoothLeScanner.startScan()`
registration (what a second Kable `Scanner` means) risks compounding that exact failure mode on
precisely the hardware this project is trying to validate as cheap dedicated units.

Sharing is safe and free: `MuleRepository.scanForDevices()` already filters one unfiltered
`scanner.advertisements` stream downstream in Kotlin (`MulePullClient.kt:242`). Fan that same
raw stream out to a second downstream consumer (device info matching) — pure in-process `Flow`
fan-out, no extra radio cost.

`MulePullClient.kt` change:
```kotlin
private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val sharedAdvertisements: SharedFlow<Advertisement> = scanner.advertisements
    .onEach { /* existing loggedRawAddresses logging, unchanged */ }
    .shareIn(scanScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 2_000), replay = 0)

fun scanForDevices(): Flow<Advertisement> =
    sharedAdvertisements.filter { MuleGattProfile.SERVICE_UUID.toKotlinUuid() in it.uuids }

fun scanForAdvertisements(): Flow<Advertisement> = sharedAdvertisements
```
`MuleRepository.kt` (line ~112 already has `fun scanForDevices(): Flow<Advertisement> = pullClient.scanForDevices()`):
add `fun scanForAdvertisements(): Flow<Advertisement> = pullClient.scanForAdvertisements()` right
next to it. Both `BeaconScanEngine` and `IdentifyFobScreen`'s ViewModel depend on `MuleRepository`
(already in `AppContainer`), keeping `MuleRepository` as the one façade over BLE central scanning.

`shareIn` isolation: each downstream `.collect{}` runs in its own coroutine — an exception in
one collector won't kill another, **provided** every new collector uses the same
`catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(...) }` shape
`MuleSyncEngine.startScan()` already uses — copy it verbatim. `stopTimeoutMillis = 2_000` (not
Kotlin's 5000 default) absorbs a brief subscriber gap without a full scan restart; revisit
empirically during real multi-device field testing, same as this file's other tuned constants.

### 3. Bib lookup — piggybacked on existing progress propagation, not a new mapping store — implemented

Per TODO.md point 3, added one new field to the payload that already carries bib/name/course
data to every phone over both transports — `beaconNumber: Int?`, not the originally-sketched
`fobShortCode: String?` (see the 2026-11-05 staleness-flag update above for why: the real
identity is the same 0-999 "Beacon Number" integer the operator already enters on Setup Beacons,
not an opaque string short-code):

`data/mule/MuleGattProfile.kt`'s `ProgressEntry`:
```kotlin
@Serializable
data class ProgressEntry(
    val bibNumber: Int = 0,
    val name: String = "",
    val category: String = "",
    val course: String = "",
    val startTime: String = "",
    val finishTime: String = "",
    val cpTimes: Map<String, String> = emptyMap(),
    val updatedAt: String? = null,
    val beaconNumber: Int? = null, // NEW
)
```
Default `null` keeps this wire-compatible with older payloads/servers (mirrors `updatedAt`'s own
"null only for a payload from before this field existed" precedent in the same file). Because
`ProgressEntity.entriesJson` already stores the whole `List<ProgressEntry>` as an opaque
kotlinx.serialization JSON blob (not individual Room columns), **this needs no Room migration,
no DB version bump** — it's purely a Kotlin data-class field. Same for `TargetedProgressEntity`,
which stores the full payload verbatim already.

`data/mule/ProgressRepository.kt`:
```kotlin
suspend fun getBibForBeacon(raceId: Long, beaconNumber: Int): Int? =
    existingEntries(raceId).firstOrNull { it.beaconNumber == beaconNumber }?.bibNumber
```
Reads the Room-persisted copy via the file's existing `existingEntries` helper (not just
`current`), so a lookup resolves against whatever's stored for the race regardless of which
device most recently received it — covered by `ProgressRepositoryTest.kt`.

**Cross-repo dependency, out of scope for this plan but required for the end-to-end feature**:
the companion server (`~/racemaster/server/routes/mobile.js`, which builds/merges `progress.json`
per its own `mergeProgress`/entry-shape comments around line 71) needs the matching
`beaconNumber` field (an integer, not a string) added to its entry shape and registration UI, and
its own lookup table
(bib ↔ beacon number, assigned at registration) populated the same way bib allocations already
are. That work is tracked in the `~/racemaster` repo, not here — this plan only ensures the
mobile app correctly consumes the field once the server emits it.

### 4. `BeaconScanEngine` — peak-RSSI pass detection + auto-record

**A process-lifetime engine, not ViewModel-scoped** — same reasoning as `MuleSyncEngine` not
being owned by a ViewModel: it must keep working with the screen backgrounded, for as long as
the process is alive (already kept alive via `PeripheralSyncService`'s foreground service).

Per-beacon state machine (in-memory only — no persistence needed; losing one in-flight pass to
an app restart is an acceptable edge case, not a correctness requirement):

```kotlin
private data class PassTracking(
    val beaconNumber: Int,
    var peakRssi: Int,
    var peakAtMillis: Long,
    var lastSeenAtMillis: Long,
)

class BeaconScanEngine(
    private val muleRepository: MuleRepository,
    private val progressRepository: ProgressRepository,
    private val settingsRepository: SettingsRepository,
    private val raceRepository: RaceRepository,
    private val bibsModeRepository: BibsModeRepository,
) {
    private val tracking = ConcurrentHashMap<Int, PassTracking>()
    private val cooldownUntilMillis = ConcurrentHashMap<Int, Long>()

    fun start() {
        engineScope.launch { observeScanningEnabled() } // flatMapLatest-restart on toggle, like MuleSyncEngine's bluetooth-state loop
        engineScope.launch { finalizeStalePasses() }     // periodic tick, e.g. every 1s
    }

    private suspend fun handleAdvertisement(advertisement: Advertisement) {
        val beacon = BeaconScanDecoder.decodeFromAdvertisement(advertisement) ?: return
        val maxDistance = settingsRepository.beaconMaxDistanceMeters.first()
        if (beacon.estimatedDistanceMeters > maxDistance) return
        val now = System.currentTimeMillis()
        if ((cooldownUntilMillis[beacon.beaconNumber] ?: 0) > now) return
        tracking.compute(beacon.beaconNumber) { _, existing ->
            if (existing == null || beacon.rssi > existing.peakRssi)
                PassTracking(beacon.beaconNumber, beacon.rssi, now, now)
            else existing.apply { lastSeenAtMillis = now }
        }
    }

    // Runs periodically: any tracked beacon not seen for GONE_SILENT_MS is finalized — the pass
    // is over, commit using the peak moment, not "now".
    private suspend fun finalizeStalePasses() {
        val now = System.currentTimeMillis()
        tracking.entries.removeAll { (_, t) ->
            val stale = now - t.lastSeenAtMillis > GONE_SILENT_MS
            if (stale) engineScope.launch { commitPass(t) }
            stale
        }
    }

    private suspend fun commitPass(t: PassTracking) {
        if (settingsRepository.appMode.first() != AppMode.BIBS) return
        val raceId = settingsRepository.activeRaceId.first() ?: return
        val race = raceRepository.getRace(raceId) ?: return
        if (race.bibsModeStartedAtMillis == null) return
        val bib = progressRepository.getBibForBeacon(raceId, t.beaconNumber) ?: return
        val action = settingsRepository.bibsAutoSaveAction.first()
        val cooldownMs = settingsRepository.beaconCooldownSeconds.first() * 1000L
        cooldownUntilMillis[t.beaconNumber] = System.currentTimeMillis() + cooldownMs
        bibsModeRepository.recordEntry(
            raceId, action, bib,
            note = "beacon:${t.beaconNumber} rssi=${t.peakRssi}",
            timestampMillis = t.peakAtMillis, // the closest-approach moment, not "now" — the whole point of tracking peak RSSI
        )
    }
}
```

Key points:
- **Timestamp is the peak-RSSI moment** (`t.peakAtMillis`), passed straight through
  `BibsModeRepository.recordEntry`'s existing `timestampMillis` parameter (already defaults to
  `System.currentTimeMillis()` but accepts an override) — this directly satisfies "use the fob
  for timing as well as bib recognition" with no change needed to the recording path itself.
- **Distance threshold** (`beaconMaxDistanceMeters`) is checked before a pass is even started,
  so a fob in an adjacent lane or still sitting in the registration box never begins tracking.
- **Reuses `settingsRepository.bibsAutoSaveAction`** (the same flow `BibsModeViewModel.onDigit`'s
  auto-save already reads, seeded via `LocationValidation.defaultActionForLocation`) rather than
  hardcoding an action — a beacon-triggered record and a manual entry can't disagree about what
  a crossing means right now.
- **`note = "beacon:<beaconNumber> rssi=<peakRssi>"`** is the only field distinguishing an
  auto-recorded row from a manual one in `HistoryLineEntity` — enough for audit/debugging without
  needing a new column. If near-simultaneous passes ever need forensic review, extend this to
  include a short RSSI trace (e.g. last few `(t, rssi)` samples as compact JSON) rather than
  building a separate synced table — it rides the exact same existing history-line → HTTP push →
  web-app path with no protocol change. Treat that as a later refinement, not phase-1 scope.
- **Gated on `AppMode.BIBS` and `race.bibsModeStartedAtMillis != null`**, same as before.
- **Cooldown is applied at commit time**, keyed per beacon number, to stop the same runner re-triggering
  if they linger or double back near the line within the window — separate from (and after)
  the peak-tracking/gone-silent logic, not a replacement for it.
- **`GONE_SILENT_MS`** (e.g. 3000ms — a fob advertises roughly once a second, so a few missed
  cycles means it's actually left, not just a dropped packet) is a tuning constant to verify
  empirically once a real fob is in hand; start as a constant, promote to a setting only if
  field testing shows it needs per-race tuning.
- **Beep feedback**: give `BeaconScanEngine` its own `Beeper` instance (process-lifetime,
  constructed once) so a committed pass gets the same audible confirmation a manual entry does —
  matters operationally since nobody's watching the screen when a runner passes.

`PeripheralSyncService.kt`: add `container.beaconScanEngine.start()` to `onCreate()` (line ~266,
right next to the existing `container.muleSyncEngine.start()`), same "started once, gated
internally by its own settings flow" pattern.

`di/AppContainer.kt`: add `beaconScanEngine` to the interface and `DefaultAppContainer`,
following the existing `by lazy { ... }` pattern (`override val beaconScanEngine by lazy {
BeaconScanEngine(muleRepository, progressRepository, settingsRepository, raceRepository,
bibsModeRepository) }`).

### 5. `IdentifyFobScreen` — registration-desk read-only utility — implemented

The one on-device UI this feature needs. Not a pairing/assignment screen — per point 3, pairing
is data entry in the web-app. This screen's only job is letting registration staff read a
specific physical beacon's number off itself so it can be verified as working and its number
matches the label on it.

`ui/beaconidentify/IdentifyFobViewModel.kt`: subscribes to
`muleRepository.scanForAdvertisements()`, decodes via `BeaconScanDecoder`, keeps a
`ConcurrentHashMap<address, BeaconTracking>` pruned every 300ms of anything not refreshed within
the last 8s (initially 2s, found too tight in testing — indoor BLE scanning drops the occasional
packet even from a beacon sitting right next to the phone, and evicting on that wiped the
beacon's smoothing/hysteresis state and dropped the screen to "Hold a beacon close to the phone"
on nothing more than one missed packet; 8s is generous enough that only the beacon actually being
taken away reaches it, at the cost of making a reappearing beacon's old, possibly stale
`smoothedRssi` linger for up to 8s before a fresh advertisement even gets a vote — so a second
knob, `RESET_SMOOTHING_AFTER_GAP_MILLIS = 2_000L`, resets `smoothedRssi` to the bare new reading
(instead of blending into the old average) whenever a beacon's gap since its last packet exceeds
2s, which is what actually makes a just-switched-on or freshly-returned fob show up promptly
rather than having to slowly out-vote several seconds of someone else's history; eviction and
detection speed are deliberately tuned independently this way). Real-hardware testing (indoors,
the actual registration-desk environment) also showed raw per-packet RSSI swinging 5-10dB with
nothing moving, enough to flip a beacon in
and out of range every recompute, and that two layers of defense against it (smoothing + a
hysteresis band) still wasn't quite enough — `BeaconTracking` now stacks three: `smoothedRssi` is
an EMA
(`alpha = 0.15`) of every raw reading rather than the latest one; `inRange` enters at
`settingsRepository.beaconIdentifyDistanceMeters` (a deliberately **tighter** threshold than
race-time `beaconMaxDistanceMeters` per point 2's "much tighter at registration") but only exits
past `1.8×` that distance; and on top of that band, `pendingFlips` requires the *other* state to
keep winning for several consecutive 300ms recomputes in a row (2 to enter, 6 to exit —
asymmetric: a fob just presented should register fairly quickly, but one held steadily shouldn't
blink out on a single bad reading) before `inRange` actually changes. Registration can afford that
extra second or so of lag per direction since a fob sits there for several seconds anyway, and
this consecutive-reading requirement is what finally stopped the flicker the EMA and the band
alone didn't. Exposes `IdentifyFobUiState(beaconsInRange: List<DetectedRaceBeacon>)`, nearest-first,
empty when nothing's in range — no separate "exactly one"
vs. "ambiguous" status; the screen is always just that one list.

`ui/beaconidentify/IdentifyFobScreen.kt`: a `LazyColumn` of whatever's in `beaconsInRange`
(nearest first), each row showing its beacon number at `MaterialTheme.typography.displayLarge`
(matching TODO.md's own "shown as big as possible") plus RSSI/distance; "Hold a beacon close to
the phone" when the list is empty. Deliberately always the same list shape regardless of count —
an earlier version special-cased "exactly one" vs. "ambiguous" into different layouts, which made
the screen visibly unstable as a second fob wandered in and out of range.

Navigation (2026-11-05 update — moved, see "Beacon Operations hub" below): `Routes.kt`'s
`IDENTIFY_FOB` is reached via a new `ui/beaconops/BeaconOperationsScreen.kt` hub's "Check"
button, not directly off `SetupDeviceScreen.kt` as originally built — the screen's own displayed
title changed from "Identify Fob" to "Check Beacon" to match, though the Kotlin file/class names
(`IdentifyFobScreen`, `IdentifyFobViewModel`, `HelpTopics.IdentifyFob`) were left as-is to avoid
unnecessary rename churn.

### Beacon Operations hub — new, 2026-11-05

Mode Picker's "Beacons" button used to go straight to `PROGRAM_BEACONS` (then titled "Setup
Beacons"); it now goes to a new `Routes.BEACON_OPERATIONS` hub screen
(`ui/beaconops/BeaconOperationsScreen.kt`, following `SetupDeviceScreen`'s own plain-list-of-
buttons shape) with three buttons matching TODO.md's "Race-day usage" section's own vocabulary:
- **Check** → `IDENTIFY_FOB` (this section's `IdentifyFobScreen`, now titled "Check Beacon").
- **Close** → new `Routes.CLOSE_BEACONS` / `ui/beaconops/CloseBeaconsScreen.kt` — TODO.md's
  "finish mode" (post-race battery read / power-off / return-tracking). Not yet implemented; the
  button and route exist (placeholder screen, `HelpTopics.CloseBeacons`) so the hub's three
  operations are all in place up front rather than appearing piecemeal as each is built.
- **Program** → `PROGRAM_BEACONS` (`BeaconProgrammerScreen`, retitled from "Setup Beacons" to
  "Program Beacon" to stop colliding with the hub's own "Beacons" title; `HelpTopics.ProgramBeacons`
  retitled to match).

`ModePickerScreen`'s own callback renamed `onSetupBeacons` → `onBeaconOperations` to match.
`HelpTopics.all` gained `BeaconOperations` (the hub's own topic) and `CloseBeacons`, reordered to
`..., SetupRace, BeaconOperations, IdentifyFob, ProgramBeacons, CloseBeacons, Options, ...` —
the order an operator actually meets each screen in, hub first then its three children.

### 6. New settings — `beaconIdentifyDistanceMeters` implemented now; the rest is phase 2's job

`data/settings/SettingsRepository.kt` additions, following the existing scalar conventions
(e.g. `pingIntervalSeconds`, boolean `muleSyncEnabled`):
- `beaconIdentifyDistanceMeters: Flow<Double>` / `setBeaconIdentifyDistanceMeters` — **done**,
  needed by phase 1's `IdentifyFobScreen` itself; default
  `DEFAULT_BEACON_IDENTIFY_DISTANCE_METERS = 0.5` (tightened from an initial 1.0 once multiple
  fobs showing up at once in testing made clear registration needs a tighter radius than that).
  No `SetupOptionsScreen` control yet — fixed at the default until real field testing has a value
  worth exposing (phase 3 step 10's job).
- `beaconScanningEnabled: Flow<Boolean>` / `setBeaconScanningEnabled` — default `false` (opt-in).
  Not yet added — `BeaconScanEngine` doesn't exist yet to gate (phase 2).
- `beaconMaxDistanceMeters: Flow<Double>` / setter — race-time detection threshold. Not yet
  added, same reason (phase 2).
- `beaconCooldownSeconds: Flow<Int>` / setter — default `30`. Not yet added, same reason (phase 2).

Surfaced in `ui/mulemode/SetupOptionsScreen.kt`, following `PingIntervalControl`'s exact shape
(`OutlinedTextField` + `canSave`/`save()` guard), added below the existing controls. Simplest
wiring: extend `MuleModeViewModel` with the passthrough properties/functions, matching how it
already exposes settings that aren't strictly "Mule" ones (`raceStaleAfterDays`,
`pingIntervalSeconds`), so `SetupOptionsScreen`'s single-ViewModel wiring stays unchanged.

### 7. Interaction with `BibValidation.kt` — no changes needed

`BibValidation`'s duplicate/unexpected-bib checks are purely derived from
`BibsModeRepository.observeCurrentSegmentEntries(raceId)`'s live `Flow<List<HistoryLineEntity>>`
inside `BibsModeViewModel.uiState` — never insert-path-specific. Since `BeaconScanEngine` writes
through the exact same `BibsModeRepository.recordEntry` → `EntryLogModeEngine.recordEntry` →
`HistoryLineDao.insert` path a manual entry uses, a beacon-triggered row is indistinguishable to
every downstream consumer except via `note` and (now) a historically-accurate `timestampMillis`.
Confirm this holds during phase 2 testing rather than adding new plumbing.

## Phased build-out

**Phase 1 — parsing + distance estimation + identify tool (no auto-record yet) — done except step 6**
1. **Done.** `BeaconScanDecoder` (decode + `estimateDistanceMeters`) + unit tests against
   synthetic name/rssi values and known measuredPower/rssi pairs — see §1 above.
3. **Done.** `MulePullClient.scanForAdvertisements()` / `MuleRepository` passthrough (the
   `shareIn` change) — compiles/tests/lints clean; manually verifying Mule Mode's own device
   discovery still works unchanged (it only gained a second downstream consumer of the same
   shared flow, Mule's own filtered path is untouched) is folded into step 6's field test below.
4. **Done.** `ProgressEntry.beaconNumber` field + `ProgressRepository.getBibForBeacon` —
   unit-tested against a synthetic stored payload (`ProgressRepositoryTest.kt`), no server change
   needed yet to build/test this side.
5. **Done.** `IdentifyFobScreen`/`ViewModel`, nav wiring (`Routes.IDENTIFY_FOB`), `HelpTopics.IdentifyFob`
   (title "Check Beacon"). Originally reached via a `SetupDeviceScreen` "Identify Fob" button;
   moved 2026-11-05 to the new Beacon Operations hub's "Check" button (see that section above) —
   `SetupDeviceScreen` no longer has a beacon-related button at all.
6. **Done**, including the real-hardware part, after three rounds of field-testing against two
   real DX-CP27s side by side indoors (the actual registration-desk environment) surfaced and
   fixed real display-stability bugs — see `IdentifyFobViewModel`'s own doc (`smoothedRssi` EMA,
   the enter/exit hysteresis band, `pendingFlips`' consecutive-reading debounce, and
   `RESET_SMOOTHING_AFTER_GAP_MILLIS` vs. `STALE_AFTER_MILLIS` being tuned independently) for the
   full account of what raw indoor BLE scanning needed beyond the naive threshold this step
   originally called for. `./gradlew assembleDebug testDebugUnitTest lintDebug installDebug` all
   passed clean throughout.

**Phase 2 — auto-record integration**
7. `BeaconScanEngine` (fixed internal constants for cooldown/gone-silent, no settings UI yet),
   wired into `PeripheralSyncService.onCreate()` unconditionally enabled for now. Requires
   coordinating with the `~/racemaster` server change (§3) landing, or a hand-written test
   `ProgressEntry` with `beaconNumber` set, to exercise end-to-end locally before the server
   side exists.
8. Manual device test: start Bibs Mode at a CP/Finish location, walk a paired beacon past the
   phone repeatedly at varying speed/distance — confirm exactly one record per pass, recorded
   timestamp matches the closest-approach moment (not first/last seen), correct action/note/beep,
   and `BibValidation`'s flags behave identically to a manual entry.
9. Confirm scanning survives the screen backgrounded and the app idle for several minutes.

**Phase 3 — settings/polish + field validation**
10. `beaconScanningEnabled`/`beaconMaxDistanceMeters`/`beaconIdentifyDistanceMeters`/
    `beaconCooldownSeconds` settings + `SetupOptionsScreen` controls; wire the engine's gate onto
    the toggle instead of always-on.
11. Field test on the Samsung XCover 4 specifically, to validate §2's shared-scan recommendation
    under simultaneous Mule syncing + beacon scanning + Bibs recording on budget hardware — the
    one genuinely open empirical question in this plan.
12. Tune `GONE_SILENT_MS`/path-loss exponent/default distance thresholds against real multi-fob
    field data (e.g. a start line with several runners passing close together).
13. Port `MuleSyncEngine`'s `SCAN_REFRESH_INTERVAL` (90s teardown/restart) into
    `BeaconScanEngine` and prompt for battery-optimization exemption at setup — see the
    2026-11-05 update above; both are now known prerequisites, not speculative hardening.
14. Field-validate the finish-line policy specifically: two phones either side of the line (all
    beacons already at -1.0dBm/200ms — see above), and confirm the dual-phone
    duplicate-finish-entry question (flagged above) actually resolves to one sensible timestamp
    per runner rather than two conflicting ones.

## Verification

**Unit tests (`app/src/test/`, JVM)**:
- **Done.** `data/beacon/BeaconScanDecoderTest.kt`: decodes a real race-programmed name into
  `beaconNumber`/`measuredPowerDbm`; returns `null` for a factory/unrelated name and for no name
  at all (never throws); `estimatedDistanceMeters` checked against known measuredPower/rssi pairs.
- `data/beacon/BeaconScanEngineTest.kt` (phase 2, not yet written): pull the pass-tracking state
  transitions (peak-update, gone-silent finalization, cooldown gating) into pure testable
  functions and test each transition independently of any BLE/coroutine machinery.
- **Done.** `data/mule/ProgressRepositoryTest.kt` (extended): `getBibForBeacon` against a
  stored payload with and without a matching `beaconNumber`, and against an unknown raceId.
- Extend `data/repository/BibValidationTest.kt` (phase 2): confirm a `note`-tagged beacon row is
  flagged by `findDuplicateSplitRefs`/`unexpectedBibNumbers` identically regardless of `note`
  content, and regardless of `timestampMillis` being backdated to a peak-RSSI moment.

**Instrumented tests (`app/src/androidTest/`)**:
- A `BibsModeRepository`-level test confirming `recordEntry(raceId, action, bib, note =
  "beacon:...", timestampMillis = <backdated>)` produces a row structurally identical to a
  manual entry, with the backdated timestamp actually persisted (not silently overwritten).

**Requires real BLE hardware + a real beacon (can't be simulated)**:
- Realistic distance-estimate accuracy for the real DX-CP27 hardware (phase 1, step 6 —
  everything else code-side is done; this is the one remaining phase-1 item).
- Whether peak-RSSI timing is actually precise enough to resolve close finishes in practice —
  this is the plan's core empirical bet and can only be validated with real runners/fobs moving
  at race speed, not a stationary bench test.
- Behavior with multiple fobs lingering/crossing near the phone at once (a start-line scenario).
- Whether the shared-scan change holds up under combined Mule-sync + GATT-advertise +
  beacon-scan load on the XCover 4 specifically (phase 3, step 11).
- Foreground-service BLE scanning continuing correctly with the screen backgrounded/display off.
- Already partly answered, for the underlying Android scan reliability (not yet for this
  engine's own logic): a 2026-11-05 HCI-snoop investigation on real XCover 4/7 hardware found
  an irreducible tail of multi-second-to-tens-of-seconds reception gaps even after fixing every
  app-level cause found (concurrent Mule scanning/advertising, one long-lived scan session) —
  see the 2026-11-05 update above for the full numbers and the resulting checkpoint-vs-finish
  policy split. `BeaconScanEngine` inherits this ceiling; phase 3 step 14's dual-phone finish
  test is what actually validates whether the policy adopted in response is sufficient.

**Cross-repo, tracked separately**: the `~/racemaster` server needs a `beaconNumber` field added
to its progress-entry shape and a registration-time bib↔beacon lookup UI, before phase 2's
end-to-end auto-record path can be exercised against real server data rather than a hand-crafted
test payload.

### Critical files
- `app/src/main/java/mobile/racemaster/data/beacon/BeaconScanDecoder.kt` (phase 1, new)
- `app/src/main/java/mobile/racemaster/data/beacon/DxCp27Protocol.kt` (`raceDeviceName`/
  `parseRaceDeviceName` — what `BeaconScanDecoder` is built on)
- `app/src/main/java/mobile/racemaster/ui/beaconidentify/IdentifyFobViewModel.kt` (phase 1, new)
- `app/src/main/java/mobile/racemaster/ui/beaconidentify/IdentifyFobScreen.kt` (phase 1, new)
- `app/src/main/java/mobile/racemaster/data/mule/MulePullClient.kt`
- `app/src/main/java/mobile/racemaster/data/mule/MuleRepository.kt`
- `app/src/main/java/mobile/racemaster/data/mule/MuleGattProfile.kt`
- `app/src/main/java/mobile/racemaster/data/mule/ProgressRepository.kt`
- `app/src/main/java/mobile/racemaster/data/repository/EntryLogModeEngine.kt`
- `app/src/main/java/mobile/racemaster/data/repository/BibsModeRepository.kt`
- `app/src/main/java/mobile/racemaster/data/settings/SettingsRepository.kt`
- `app/src/main/java/mobile/racemaster/data/mule/PeripheralSyncService.kt`
- `app/src/main/java/mobile/racemaster/di/AppContainer.kt`
- `app/src/main/java/mobile/racemaster/navigation/Routes.kt` /
  `app/src/main/java/mobile/racemaster/navigation/RacemasterNavHost.kt` (`IDENTIFY_FOB`/
  `BEACON_OPERATIONS`/`CLOSE_BEACONS` wiring)
- `app/src/main/java/mobile/racemaster/ui/beaconops/BeaconOperationsScreen.kt` /
  `CloseBeaconsScreen.kt` (2026-11-05, new — see "Beacon Operations hub" above)
- `app/src/main/java/mobile/racemaster/ui/modepicker/SetupDeviceScreen.kt` (no longer has a
  beacon-related button — moved to the hub above)
