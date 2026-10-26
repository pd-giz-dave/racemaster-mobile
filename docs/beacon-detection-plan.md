# Plan: BLE beacon detection for auto-recording runner bib passes

Status: **parked, not yet implemented** — waiting on real BLE tracker fobs to be acquired.
See TODO.md's "Another idea" section for the originating idea and a condensed checklist; this
doc is the full design.

## How to resume this later

1. Check out the `beacon-tracking` branch (`git checkout beacon-tracking`, or a fresh
   `git worktree`/clone if `main` has moved on since).
2. Read this doc in full — it's self-contained (architecture, exact code sketches, phased
   build-out, verification plan, critical file list).
3. Before writing any code, do phase 1 step 2: capture one real fob's actual raw advertisement
   via nRF Connect and confirm the iBeacon byte layout (`0x02 0x15` prefix) and measured-power
   field actually match what `IBeaconParser` assumes below — cheap clone chipsets sometimes
   deviate. Adjust the parser's assumptions in this doc if they don't match before building on
   top of it.
4. Ask Claude to implement starting from "Phase 1" below, pointing it at this file directly —
   a fresh session has no memory of how this plan was derived, so hand it this doc rather than
   re-describing the feature from scratch.
5. The companion server (`~/racemaster`) needs its own `fobShortCode` field + registration UI
   added before phase 2 (auto-record) can be tested end-to-end against real server data — check
   whether that's landed; if not, phase 1 and most of phase 2 can still be built/tested against
   a hand-crafted local `ProgressEntry` with `fobShortCode` set.

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
   the fob has gone out of range.
2. Measured-power (the iBeacon TX-power field) should be used to estimate distance and enforce
   a max-range threshold, tighter during registration than during race detection — so a fob in
   an adjacent lane, or sitting in a box of unassigned fobs, doesn't get matched.
3. **Bib-to-fob pairing happens at registration in the Racemaster web-app, not on the phone.**
   Each fob has a short-code (a sequence number unique to that physical unit, known to the
   web-app) — the same shape as an orienteering dibber-number allocation. The web-app's
   lookup table is populated at registration and then broadcast to phones piggybacked on the
   **existing bib/name propagation system** (`ProgressEntry`/`ProgressPayload`), not a new
   mechanism.

Point 3 is a significant correction to the design's data flow versus a first pass at this plan:
there is no local "pairing database" to build and no on-phone marshal-tap-to-assign screen as
the primary mechanism — the phone is a consumer of a mapping the server already knows, riding
the sync path that already carries bib/name/course data down to every device. The only
on-device, registration-time need is a way to *read a fob's own hardware ID* so staff can type
it into the web-app — a small read-only utility, not a database.

This also needs to coexist with the existing BLE central-scanning role (`MulePullClient.kt`)
used by Mule Mode, especially on the low-end/old hardware (Samsung XCover 4) separately being
evaluated for this app — that file's own doc comments already document real contention
problems running scan+advertise+GATT-server simultaneously on cheap chipsets, which shapes the
scan-integration decision below.

Confirmed design decisions:
- **Protocol**: assume iBeacon-style advertising (Apple manufacturer ID `0x004C`, 16-byte
  proximity UUID + major + minor + measured power) — parseable from the advertisement alone,
  no GATT connect needed.
- **Auto-record, no manual confirm step** — refined by point 1 above into "auto-record, timed
  at peak signal, committed once the fob is gone."

## Architecture

| Piece | Location | Follows the pattern of |
|---|---|---|
| `IBeaconParser` | `data/beacon/IBeaconParser.kt` | `MuleGattProfile.decodeAdvertisedIdentity` — pure byte decode, never throws |
| `ProgressEntry.fobShortCode` (new field) | `data/mule/MuleGattProfile.kt` | existing `ProgressEntry` fields — rides the existing bib/name propagation, no new sync path |
| `BeaconScanEngine` | `data/beacon/BeaconScanEngine.kt` | `MuleSyncEngine` — process-lifetime, started once from `PeripheralSyncService.onCreate`, gated by a settings flow |
| `IdentifyFobScreen`/`ViewModel` | `ui/beaconidentify/` | a small read-only diagnostic screen, registration-desk use only |

No new Room entity, no DB version bump, no new foreground service, no new Kable `Scanner`, no
AndroidManifest changes. This is a meaningfully smaller surface than a local-pairing-database
design would have needed, precisely because point 3 moves the mapping authority server-side.

### 1. `IBeaconParser`

New package `data/beacon/` (not `data/mule/` — this is Apple's iBeacon format, unrelated to
`MuleGattProfile`'s own custom protocol).

```kotlin
object IBeaconParser {
    const val APPLE_MANUFACTURER_ID = 0x004C
    private const val IBEACON_TYPE: Byte = 0x02
    private const val IBEACON_SUBLENGTH: Byte = 0x15 // 21 bytes: 16 UUID + 2 major + 2 minor + 1 power

    data class IBeaconAdvertisement(
        val proximityUuid: String, val major: Int, val minor: Int,
        val measuredPower: Int, val rssi: Int,
    ) {
        // What's looked up against ProgressEntry.fobShortCode (§3) and shown on
        // IdentifyFobScreen (§5) for transcription into the web-app at registration. Exact
        // composition (minor alone vs. the full triple) depends on whether the fob batch
        // shares one UUID/major — confirm against real hardware (see phase 1, step 2) before
        // fixing this format, since it's also the format staff will be typing by hand.
        val fobShortCode: String get() = "$major-$minor"
    }

    // Pure decode, never throws — null for anything too short/not iBeacon-shaped, mirroring
    // MuleGattProfile.decodeAdvertisedIdentity's fail-safe convention.
    fun decode(bytes: ByteArray?, rssi: Int): IBeaconAdvertisement? {
        if (bytes == null || bytes.size < 23) return null
        if (bytes[0] != IBEACON_TYPE || bytes[1] != IBEACON_SUBLENGTH) return null
        val uuid = formatUuid(bytes.copyOfRange(2, 18))
        val major = ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)
        val minor = ((bytes[20].toInt() and 0xFF) shl 8) or (bytes[21].toInt() and 0xFF)
        return IBeaconAdvertisement(uuid, major, minor, measuredPower = bytes[22].toInt(), rssi = rssi)
    }

    // Formats 16 raw bytes into canonical lowercase 8-4-4-4-12 UUID form.
    private fun formatUuid(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    fun decodeFromAdvertisement(advertisement: Advertisement): IBeaconAdvertisement? =
        decode(advertisement.manufacturerData(APPLE_MANUFACTURER_ID), advertisement.rssi)

    // distance ≈ 10^((measuredPower - rssi) / (10*N)), N≈2, tune empirically against real fobs.
    fun estimateDistanceMeters(measuredPower: Int, rssi: Int, pathLossExponent: Double = 2.0): Double =
        Math.pow(10.0, (measuredPower - rssi) / (10.0 * pathLossExponent))
}
```

**Flag for phase 1**: cheap clone fobs aren't guaranteed to emit the canonical `0x02 0x15`
prefix byte-for-byte, and the path-loss exponent needs empirical tuning per fob model/mounting
(worn on a runner, not held in open air). Before building anything on top of this, capture one
real fob's actual raw advertisement via nRF Connect and sanity-check both the byte layout and
roughly how `estimateDistanceMeters` tracks real distance at a few paces.

### 2. Scan integration — key architectural call

**Share the one Kable `Scanner` `MulePullClient` already owns; do not open a second one.**
`MulePullClient.kt`'s own doc comments (around lines 44–73) already document, from real field
evidence, that cheap/old chipsets (explicitly including the XCover 4) handle simultaneous
scan+advertise+GATT-server roles poorly, and that scan/advertise settings were already tuned
down to reduce radio contention. A second independent native `BluetoothLeScanner.startScan()`
registration (what a second Kable `Scanner` means) risks compounding that exact failure mode on
precisely the hardware this project is trying to validate as cheap dedicated units.

Sharing is safe and free: `MuleRepository.scanForDevices()` already filters one unfiltered
`scanner.advertisements` stream downstream in Kotlin (`MulePullClient.kt:242`). Fan that same
raw stream out to a second downstream consumer (iBeacon matching) — pure in-process `Flow`
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

### 3. Bib lookup — piggybacked on existing progress propagation, not a new mapping store

Per TODO.md point 3, add one new field to the payload that already carries bib/name/course data
to every phone over both transports:

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
    val fobShortCode: String? = null, // NEW — matches IBeaconParser.IBeaconAdvertisement.fobShortCode
)
```
Default `null` keeps this wire-compatible with older payloads/servers (mirrors `updatedAt`'s own
"null only for a payload from before this field existed" precedent in the same file). Because
`ProgressEntity.entriesJson` already stores the whole `List<ProgressEntry>` as an opaque
kotlinx.serialization JSON blob (not individual Room columns), **this needs no Room migration,
no DB version bump** — it's purely a Kotlin data-class field. Same for `TargetedProgressEntity`,
which stores the full payload verbatim already.

`data/mule/ProgressRepository.kt`: add
```kotlin
suspend fun getBibForFob(raceId: Long, fobShortCode: String): Int? =
    getStored(raceId)?.entries?.firstOrNull { it.fobShortCode == fobShortCode }?.bibNumber
```
following the file's existing `existingEntries`/`getStored` helpers.

**Cross-repo dependency, out of scope for this plan but required for the end-to-end feature**:
the companion server (`~/racemaster/server/routes/mobile.js`, which builds/merges `progress.json`
per its own `mergeProgress`/entry-shape comments around line 71) needs the matching
`fobShortCode` field added to its entry shape and registration UI, and its own lookup table
(bib ↔ fob short-code, assigned at registration) populated the same way bib allocations already
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
    val fobShortCode: String,
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
    private val tracking = ConcurrentHashMap<String, PassTracking>()
    private val cooldownUntilMillis = ConcurrentHashMap<String, Long>()

    fun start() {
        engineScope.launch { observeScanningEnabled() } // flatMapLatest-restart on toggle, like MuleSyncEngine's bluetooth-state loop
        engineScope.launch { finalizeStalePasses() }     // periodic tick, e.g. every 1s
    }

    private suspend fun handleAdvertisement(advertisement: Advertisement) {
        val beacon = IBeaconParser.decodeFromAdvertisement(advertisement) ?: return
        val maxDistance = settingsRepository.beaconMaxDistanceMeters.first()
        val distance = IBeaconParser.estimateDistanceMeters(beacon.measuredPower, beacon.rssi)
        if (distance > maxDistance) return
        val now = System.currentTimeMillis()
        if ((cooldownUntilMillis[beacon.fobShortCode] ?: 0) > now) return
        tracking.compute(beacon.fobShortCode) { _, existing ->
            if (existing == null || beacon.rssi > existing.peakRssi)
                PassTracking(beacon.fobShortCode, beacon.rssi, now, now)
            else existing.apply { lastSeenAtMillis = now }
        }
    }

    // Runs periodically: any tracked fob not seen for GONE_SILENT_MS is finalized — the pass
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
        val bib = progressRepository.getBibForFob(raceId, t.fobShortCode) ?: return
        val action = settingsRepository.bibsAutoSaveAction.first()
        val cooldownMs = settingsRepository.beaconCooldownSeconds.first() * 1000L
        cooldownUntilMillis[t.fobShortCode] = System.currentTimeMillis() + cooldownMs
        bibsModeRepository.recordEntry(
            raceId, action, bib,
            note = "beacon:${t.fobShortCode} rssi=${t.peakRssi}",
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
- **`note = "beacon:<fobShortCode> rssi=<peakRssi>"`** is the only field distinguishing an
  auto-recorded row from a manual one in `HistoryLineEntity` — enough for audit/debugging without
  needing a new column. If near-simultaneous passes ever need forensic review, extend this to
  include a short RSSI trace (e.g. last few `(t, rssi)` samples as compact JSON) rather than
  building a separate synced table — it rides the exact same existing history-line → HTTP push →
  web-app path with no protocol change. Treat that as a later refinement, not phase-1 scope.
- **Gated on `AppMode.BIBS` and `race.bibsModeStartedAtMillis != null`**, same as before.
- **Cooldown is applied at commit time**, keyed per fob, to stop the same runner re-triggering
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

### 5. `IdentifyFobScreen` — registration-desk read-only utility

The one on-device UI this feature needs. Not a pairing/assignment screen — per point 3, pairing
is data entry in the web-app. This screen's only job is letting registration staff read a
specific physical fob's `fobShortCode` off itself (e.g. picked out of a box of unassigned
fobs) so they can type it into the web-app's registration form.

`ui/beaconidentify/IdentifyFobViewModel.kt` (new): subscribes to
`muleRepository.scanForAdvertisements()`, decodes via `IBeaconParser`, filters to the single
closest beacon currently within `settingsRepository.beaconIdentifyDistanceMeters` (a
deliberately **tighter** threshold than race-time `beaconMaxDistanceMeters`, per point 2's "much
tighter at registration" — avoids reading the wrong fob out of a cluster), and exposes
`fobShortCode`/RSSI/estimated distance, or "no fob in range" / "multiple fobs in range, move
closer" when the tight threshold still admits more than one.

`ui/beaconidentify/IdentifyFobScreen.kt` (new): large, simple display of the current reading —
this is a hold-the-phone-next-to-one-fob workflow, not a list.

Navigation: `Routes.kt` add `IDENTIFY_FOB`; `navigation/RacemasterNavHost.kt` add the
`composable(...)`. Entry point: `ui/modepicker/SetupDeviceScreen.kt` gets a new "Identify Fob"
button alongside its existing device-setup actions.

### 6. New settings

`data/settings/SettingsRepository.kt` additions, following the existing scalar conventions
(e.g. `pingIntervalSeconds` at line ~393, boolean `muleSyncEnabled` at line ~101):
- `beaconScanningEnabled: Flow<Boolean>` / `setBeaconScanningEnabled` — default `false` (opt-in).
- `beaconMaxDistanceMeters: Flow<Double>` / setter — race-time detection threshold.
- `beaconIdentifyDistanceMeters: Flow<Double>` / setter — tighter registration-time threshold,
  default meaningfully smaller than the race-time one (e.g. a fraction of it).
- `beaconCooldownSeconds: Flow<Int>` / setter — default `30`.

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

**Phase 1 — parsing + distance estimation + identify tool (no auto-record yet)**
1. `IBeaconParser` (decode + `estimateDistanceMeters`) + unit tests against synthetic byte
   arrays and known measuredPower/rssi pairs.
2. Capture one real fob's actual advertisement via nRF Connect; confirm/adjust the `0x02 0x15`
   layout and sanity-check distance estimates at a few measured paces, worn vs. held in open air.
3. `MulePullClient.scanForAdvertisements()` / `MuleRepository` passthrough (the `shareIn`
   change) — manually verify Mule Mode's own device discovery still works unchanged.
4. `ProgressEntry.fobShortCode` field + `ProgressRepository.getBibForFob` — unit-testable
   against a synthetic stored payload, no server change needed yet to build/test this side.
5. `IdentifyFobScreen`/`ViewModel`, nav wiring, `SetupDeviceScreen` button.
6. Manual test on a real device + real fob: `IdentifyFobScreen` correctly reads one fob's code
   at close range and reports ambiguity when two fobs are both in range.

**Phase 2 — auto-record integration**
7. `BeaconScanEngine` (fixed internal constants for cooldown/gone-silent, no settings UI yet),
   wired into `PeripheralSyncService.onCreate()` unconditionally enabled for now. Requires
   coordinating with the `~/racemaster` server change (§3) landing, or a hand-written test
   `ProgressEntry` with `fobShortCode` set, to exercise end-to-end locally before the server
   side exists.
8. Manual device test: start Bibs Mode at a CP/Finish location, walk a paired fob past the
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

## Verification

**Unit tests (`app/src/test/`, JVM)**:
- `data/beacon/IBeaconParserTest.kt`: round-trip a synthetic iBeacon byte array; reject
  too-short/wrong-type-byte inputs (never throws, always null); confirm `fobShortCode`
  composition; sanity-check `estimateDistanceMeters` against known measuredPower/rssi pairs.
- `data/beacon/BeaconScanEngineTest.kt`: pull the pass-tracking state transitions (peak-update,
  gone-silent finalization, cooldown gating) into pure testable functions and test each
  transition independently of any BLE/coroutine machinery.
- `data/mule/ProgressRepositoryTest.kt` (extend if present, else new): `getBibForFob` against a
  stored payload with and without a matching `fobShortCode`.
- Extend `data/repository/BibValidationTest.kt`: confirm a `note`-tagged beacon row is flagged
  by `findDuplicateSplitRefs`/`unexpectedBibNumbers` identically regardless of `note` content,
  and regardless of `timestampMillis` being backdated to a peak-RSSI moment.

**Instrumented tests (`app/src/androidTest/`)**:
- A `BibsModeRepository`-level test confirming `recordEntry(raceId, action, bib, note =
  "beacon:...", timestampMillis = <backdated>)` produces a row structurally identical to a
  manual entry, with the backdated timestamp actually persisted (not silently overwritten).

**Requires real BLE hardware + a real fob (can't be simulated)**:
- The actual iBeacon byte layout and realistic distance-estimate accuracy for the specific fob
  model being bought (phase 1, step 2).
- Whether peak-RSSI timing is actually precise enough to resolve close finishes in practice —
  this is the plan's core empirical bet and can only be validated with real runners/fobs moving
  at race speed, not a stationary bench test.
- Behavior with multiple fobs lingering/crossing near the phone at once (a start-line scenario).
- Whether the shared-scan change holds up under combined Mule-sync + GATT-advertise +
  beacon-scan load on the XCover 4 specifically (phase 3, step 11).
- Foreground-service BLE scanning continuing correctly with the screen backgrounded/display off.

**Cross-repo, tracked separately**: the `~/racemaster` server needs a `fobShortCode` field added
to its progress-entry shape and a registration-time bib↔fob lookup UI, before phase 2's
end-to-end auto-record path can be exercised against real server data rather than a hand-crafted
test payload.

### Critical files
- `app/src/main/java/mobile/racemaster/data/mule/MulePullClient.kt`
- `app/src/main/java/mobile/racemaster/data/mule/MuleRepository.kt`
- `app/src/main/java/mobile/racemaster/data/mule/MuleGattProfile.kt`
- `app/src/main/java/mobile/racemaster/data/mule/ProgressRepository.kt`
- `app/src/main/java/mobile/racemaster/data/repository/EntryLogModeEngine.kt`
- `app/src/main/java/mobile/racemaster/data/repository/BibsModeRepository.kt`
- `app/src/main/java/mobile/racemaster/data/settings/SettingsRepository.kt`
- `app/src/main/java/mobile/racemaster/data/mule/PeripheralSyncService.kt`
- `app/src/main/java/mobile/racemaster/di/AppContainer.kt`
