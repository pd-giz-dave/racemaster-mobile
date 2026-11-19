# Plan: BLE beacon detection and race-day beacon operations

Rationalized 2026-11-18 per TODO.md's "Update beacon plan" note (the doc had become a confusing
mess) — structure now mirrors TODO.md's own outline directly: a top-level checklist, then one
checkable section per remaining work area. Design detail and research that's still accurate is
kept in the Reference section below, re-homed under the area it actually supports.

## Status

- [x] Racemaster web-app sends beacon numbers as well as bib numbers to the mobile app
- [x] Mobile app programs the beacons
- [x] Mobile app detects and records beacons passing checkpoints (time not critical) — bibs mode
      at a CP location
  - [x] At a checkpoint the detection algorithm is "first seen" — specifically: debounced
        reliable-detection + presence-based suppression, checkpoint-only. See *`BeaconScanEngine`*
        under Reference below for the corrected description of what actually shipped (commit
        `b0c0701`) — the doc's previous §4 sketch (peak-RSSI tracking, commit-on-silence) was
        never built that way.

## Finish line

- [ ] Mobile app detects, records and times beacons crossing the finish line (bibs mode at the
      finish location)
  - [ ] Timing is critical, so two co-ordinated phones are required — **policy decided
        (2026-11-05)**: one phone either side of the finish line, both running the same
        `BeaconScanEngine`. Not yet built — the shipped engine is checkpoint-only (see Status
        above); lifting that gate for the finish is exactly this item.
  - [ ] Finish time needs to be set when the strongest signal is seen, on either phone —
        peak-RSSI/closest-approach timing was deliberately deferred from the checkpoint engine
        (accuracy wasn't trusted yet without real RSSI-vs-distance data); the Range Calibration
        facility (Reference below) exists to produce that data before this is attempted.
  - [ ] Runners will mill about in-range after finishing, so a means of knowing they've actually
        finished is required — the finish line is typically 3m wide, the funnel ~5m long, a phone
        either side puts a runner within 1.5m three times nearer than the funnel's end, which
        should create a characteristic (bell-curve-like) power profile; the detector needs to
        tolerate false local peaks in that profile, not just take the global max.
  - [ ] **Open design question, not yet resolved**: two independent phones will each try to
        record the same runner's finish — needs de-duplication. Current best guess is to ride the
        existing Mule-sync + `BibValidation.findDuplicateSplitRefs` path (which already tolerates
        near-simultaneous entries for the same bib) rather than new plumbing, but this needs
        confirming it actually resolves to one sensible timestamp (e.g. picks the stronger/closer
        peak-RSSI reading) rather than just "first synced wins."

## Start line

- [ ] Mobile app needs to be able to record the mass start time as well as individual start times
      (bibs mode at a start location)
  - [ ] At the start location the mass start time is a manual action
  - [ ] At the start location an individual start time is also a manual action
  - [ ] This is the same as the current bibs mode at the start location, with the addition that it
        knows the runner's beacon number — the operator needs feedback that the beacon is visible
        for the runner being started; it not being visible could mean the runner isn't wearing the
        beacon, and if so they should not be allowed to start
  - [ ] When bibs mode is at the start location, the operator needs to be able to "prepare" a bib
        for starting by entering its number, but it must not be committed until the operator
        confirms it — so the actual start time can be recorded with sub-second accuracy
  - [ ] Also, the operator needs to be able to "prepare" for the mass start (by selecting the
        event, e.g. "seniors"), but it must not be committed until confirmed, for the same reason
        as above
  - [ ] The confirm action should be available via volume up/down, et al, like time mode
  - [ ] This behaviour change needs to be gated against a new option in Setup Race — "Using
        beacons"

## Close mode

- [x] A new mode is required to "close" a beacon — **done, reworked (2026-11-18) per direct
      feedback**: Close is now a full Setup Race mode (`CloseModeScreen`/`CloseModeViewModel`,
      backed by `CloseModeRepository`), not a bespoke beacons.json/snapshot-table mechanism. Each
      close lands as an ordinary `CLOSE` history row (beacon number + battery in its `note`),
      riding the exact same device-sync pipeline every other row already uses, with no dedicated
      server call. The Beacons hub's own "Close" button is kept as a standing shortcut to the
      same screen, usable regardless of the active race's own mode. A Close-mode race's own
      location is forced to `"Close"` (not shared with Time's `"Finish"`) so a dedicated
      close-station phone — which never logs a Bib/Time entry of its own — shows up identifiably
      in `~racemaster`'s own device list.
  - [x] In this mode each visible beacon is shown nearest-first; when selected and confirmed, the
        beacon's battery level is read and it's then turned off, with the battery level and
        turn-off time logged for later transfer to `~racemaster` for analysis — **done** on the
        mobile side (logged via the sync pipeline above); `~racemaster`'s own `progress.json`
        handling for `CLOSE` rows is still open (see Part E of the rework plan, superseding this
        doc's own now-stale server-side claims below).
  - [x] This mode needs to be aware of the issued beacons and show a list of the outstanding
        (not-yet-closed) ones in bib number order
  - [x] allow for a beacon to be re-presented because it was turned on again (this could happen
        if a runner went straight to the close station and forgot to cross the finish line) —
        **done**: `CLOSE` rows are deliberately never upserted/deduplicated — a re-presented
        beacon is just a second row, so "closed ×N" is simply "how many `CLOSE` rows this race
        has for this beacon."

## Count mode

- [x] Another new mode is required to count beacons before the start — race-day practice is to
      count runners into a pen before the start to ensure they're all present; the objective is to
      note the bib number as runners pass one or more phones walking into the pen, many at a time;
      the phones need to show a count of unique beacons seen and a list of bib numbers yet to be
      seen; only close-range beacons should be counted — **done**: Count is a full Setup Race mode
      (`CountModeScreen`/`CountModeViewModel`, backed by `CountModeRepository`), purely passive
      (no tap, no confirmation), deduped by beacon number rather than resolved bib (a beacon can
      be counted before its bib pairing is known), reusing `BeaconScanEngine`'s own extracted
      debounce functions rather than that engine itself. Its own location is forced to `"Pen"`
      for the same device-list-identifiability reason Close's is forced to `"Close"`.

## Sweep

- [ ] Whenever seen, beacon number 000 is a sweep — a runner who follows on behind the pack to
      ensure nobody goes off-course; they don't count as a competitor, they're a safety feature
  - [x] Count mode should ignore sweeps — `CountModeViewModel` ignores beacon 000 outright.
  - [ ] Other modes should record a sweep's passage but not give it a split number — its nominal
        bib number is always 000 — still open, unrelated to the Count/Close mode rework.

## Reference: design detail, research, and already-implemented work

### Hardware / scan-reliability findings (2026-11-05)

A separate investigation (on the `beacon-tracking` branch, using the Setup Beacons screen and real
DX-CP27 hardware) measured, via real HCI snoop captures on a Samsung XCover 4 and XCover 7, how
reliably Android's BLE scanner actually receives a beacon's advertisements over time.

**Found and fixed already** (live in `BeaconProgrammerViewModel.kt`):
- A second, concurrently-registered app-level BLE scanner (this app's own Mule Mode, both scan
  *and* advertise/GATT-server roles) measurably degrades reception of a third-party beacon —
  confirmed via `dumpsys bluetooth_manager`/logcat `BLE_GAP` lines showing two live scanners and an
  active incoming GATT connection from a Mule peer during a test. Fixed via
  `SettingsRepository.bluetoothOff` (suspends both Mule roles, not just `muleSyncEnabled` which
  only covers scanning).
- A single, unbroken Kable scan session degrades over time even with nothing else running —
  confirmed as a clean time-trend in an HCI capture (clean for ~230s, then increasingly long gaps).
  The exact same failure `MuleSyncEngine.SCAN_REFRESH_INTERVAL` (90s teardown/restart) already
  works around for Mule's own scanning — Setup Beacons' scan didn't have it and now does.

**What remains — an irreducible tail, not further fixable from app code**: on a clean 23-minute
post-fix capture, a beacon advertising nominally every ~300ms still showed gaps up to 9.5s (median
~509ms); one nominally every 1.8s showed gaps up to 39.6s. `dumpsys bluetooth_manager` shows why:
Samsung's `com.samsung.android.beaconmanager` and Google Play Services both register their own
permanent background BLE scanners on stock Android — the radio's single scan window is
time-multiplexed across every registered scanner system-wide, not just this app's, and no Android
API exists for one app to get scan priority over another. This is a platform ceiling.

**Commercial precedent**: Atlas Live Tracking, a real race-timing product using ordinary Android
phones as BLE checkpoint/finish-line scanners (the same architecture as this plan), claims ~0.5s
accuracy but caps event size (~3,000–4,000 participants) specifically because of BLE scanning
throughput limits, and recommends 2–3 phones per line as its own mitigation for the missed-
advertisement problem measured above. A separate academic BLE race-timing paper found that
shortening a beacon's advertising interval (not scanner-side tuning) has the largest effect on
worst-case discovery latency, and that adding a second scanner materially cuts mean discovery time.

**Resulting policy** (operator decision, captures this app's actual accuracy needs):
- **Checkpoints**: timing isn't critical — a runner only needs to be caught once while passing.
  Single-scanner design is sufficient; no redundancy needed.
- **Finish line**: timing does matter — at least 2 phones, one either side of the finish line
  (see the Finish line checklist above).
- **Beacon radio tuning — done, applies to every beacon**: rather than a Checkpoint/Finish role
  split, the operator raised the floor for every beacon this app programs.
  `DxCp27Protocol`'s `RACEMASTER_TX_POWER_DBM`/`RACEMASTER_ADV_INTERVAL_MS` constants are now
  **-1.0dBm** (was -7.0dBm) and **200ms** (was 300ms) — live in `BeaconRepository.programForRace`.

**Architecture implications for `BeaconScanEngine`**: it already runs from
`PeripheralSyncService.onCreate()`, i.e. already inside a foreground service — the single most
important prerequisite for sustained BLE scanning on Android. Still open: requesting exemption
from battery optimization (Samsung's Device Care in particular has reports of throttling
foreground-service BLE scanning after sustained use) — see Cross-cutting hardening below. Also
still open: porting the 90s scan-restart mitigation into `BeaconScanEngine` itself (see below).
Confirmed-safe to leave alone: `setReportDelay`/batching (evidence points to it making
latency-sensitive detection worse) and extended-advertising/Coded PHY (no reliability benefit for
this use case).

### `BeaconScanDecoder` — done

`data/beacon/BeaconScanDecoder.kt` decodes DX-CP27 advertisements (device-name-encoded beacon
number + measured power, via `DxCp27Protocol.parseRaceDeviceName`) into a
`DetectedRaceBeacon(address, beaconNumber, measuredPowerDbm, rssi)` with a computed
`estimatedDistanceMeters` property — see "Interim formula swap" below for the distance model
currently in use. `decode(name, rssi, address)` is the pure, unit-tested core;
`decodeFromAdvertisement(advertisement)` is a thin wrapper for real Kable `Advertisement`s.
`BeaconScanDecoderTest.kt` covers a real race-programmed name, a factory/unrelated name, a missing
name (never throws), and the distance formula against known measuredPower/rssi pairs.

### Shared BLE scan — done

`MulePullClient`'s one Kable `Scanner` is shared, not duplicated: `scanner.advertisements` is
exposed via `shareIn(scanScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 2_000), replay =
0)` as `sharedAdvertisements`, with `scanForDevices()` (Mule's own filtered view) and
`scanForAdvertisements()` (the raw stream, used by beacon detection) both reading from it.
`MuleRepository.scanForAdvertisements()` exposes this to consumers. Each downstream `.collect{}`
runs in its own coroutine with the `catch (CancellationException) { throw e } catch (Exception) {
Log.w(...) }` shape `MuleSyncEngine.startScan()` uses, so one collector's failure can't kill
another. This avoided opening a second native `BluetoothLeScanner` registration, which would have
risked compounding the exact contention problem `MulePullClient.kt`'s own doc comments already
document on cheap/old chipsets (explicitly including the XCover 4).

### Bib lookup — done

Per the registration-time pairing design (fob/beacon ↔ bib mapping lives in the Racemaster
web-app, not on the phone), `ProgressEntry` carries a new field,
`data/mule/MuleGattProfile.kt`:
```kotlin
val beaconNumber: Int? = null // NEW — default null keeps old payloads/servers wire-compatible
```
and `data/mule/ProgressRepository.kt`:
```kotlin
suspend fun getBibForBeacon(raceId: Long, beaconNumber: Int): Int? =
    existingEntries(raceId).firstOrNull { it.beaconNumber == beaconNumber }?.bibNumber
```
No Room migration was needed — `ProgressEntity.entriesJson` already stores the whole entry list as
an opaque JSON blob. Covered by `ProgressRepositoryTest.kt` (with/without a matching
`beaconNumber`, and an unknown raceId).

**Cross-repo dependency, tracked in `~/racemaster` not here**: the companion server
(`server/routes/mobile.js`) needs the matching `beaconNumber` field (an integer) added to its
entry shape and registration UI, with its own bib↔beacon lookup table populated at registration
the same way bib allocations already are.

### `BeaconScanEngine` — done for checkpoints (corrected description)

`data/beacon/BeaconScanEngine.kt`, wired into `PeripheralSyncService.onCreate()`/`onDestroy()`
next to `MuleSyncEngine`, shipped in commit `b0c0701`. **This replaces an earlier sketch in this
doc that was never actually built this way** — no peak-RSSI tracking, no commit-on-silence, no
fixed-duration cooldown:

- **Debounced reliable-detection, not peak-RSSI-until-silent**: a beacon advertises roughly every
  200ms; `REQUIRED_CONSECUTIVE_READINGS` (3) consecutive in-range readings count as "reliably
  detected," and the engine commits at that moment — not once the beacon goes silent. This behaves
  like an operator who just typed the bib in: the row is a completely ordinary
  `BibsModeRepository.recordEntry` row, editable afterward like any other.
- **Presence-based suppression, not a fixed cooldown**: once committed, a `committed` flag on the
  tracking entry suppresses every further commit for as long as the beacon keeps being seen
  continuously. Only once it's gone quiet for `MAX_GAP_MILLIS` (5s) does the next sighting start a
  fresh, uncommitted entry — re-arming detection for a genuinely later pass. This is what lets a
  retiring/chatting runner linger at a checkpoint indefinitely without re-triggering; a fixed
  cooldown would eventually time out and fire again while they're still standing there.
- **Checkpoint-only gate**: `commitDetection` requires
  `LocationValidation.isValidCpLocation(race.location)` — the engine is structurally a no-op at
  the finish. This is deliberate, not a missing setting: the finish is where runners are most
  likely to mill about chatting right next to the phone, a much harder de-duplication problem than
  one runner lingering at a checkpoint. Lifting this gate is exactly the first Finish-line
  checklist item above.
- **Immediate feedback**: on commit, speaks the bib aloud via the shared `bibSpeaker` (same
  instance `BibsModeViewModel` uses) if `speakBibsAloud` is on, else beeps via a dedicated
  `Beeper` — exactly like `BibsModeViewModel.onDigit`'s own manual-entry feedback.
- **Timestamp = commit moment**, not a tracked peak — `recordEntry`'s own
  `System.currentTimeMillis()` default is used unmodified. Accuracy of the auto-filled time is
  explicitly not relied upon yet; peak-RSSI timing is the open Finish-line item above.
- **Tuning constants** (verify against real field data — this is exactly the "Cross-cutting
  hardening" tuning item below): `BEACON_MAX_DISTANCE_METERS = 3.0`, `MAX_GAP_MILLIS = 5_000L`,
  `REQUIRED_CONSECUTIVE_READINGS = 3`, `SCAN_RETRY_DELAY = 5.seconds` (collector-resilience: a
  transient upstream scan failure logs and retries rather than silently ending detection for the
  rest of a race).

Tested via two pure functions (`updatedDetectionTracking`, `shouldCommit`) in
`BeaconScanEngineTest.kt` — no BLE/coroutine machinery needed to test the decision logic.

### `IdentifyFobScreen` / Beacon Operations hub — done

The one on-device UI the pairing design needs — not a pairing/assignment screen (pairing is data
entry in the web-app); this screen's only job is letting registration staff read a beacon's number
off itself to verify it's working and matches its label.

`ui/beaconidentify/IdentifyFobViewModel.kt` subscribes to `muleRepository.scanForAdvertisements()`,
decodes via `BeaconScanDecoder`, and tracks each beacon with three layers of smoothing/hysteresis
found necessary during real indoor testing: an EMA (`alpha = 0.15`) over raw RSSI rather than the
latest reading; an asymmetric enter/exit distance band (`beaconIdentifyDistanceMeters` to enter,
1.8× that to exit); and a consecutive-reading debounce (`pendingFlips`, 2 to enter / 6 to exit)
before `inRange` actually changes state. Entries are pruned after 8s with no advertisement, with a
separate, tighter `RESET_SMOOTHING_AFTER_GAP_MILLIS` (2s) that resets smoothing (not eviction) so a
freshly-returned beacon doesn't have to out-vote stale history. Exposes
`IdentifyFobUiState(beaconsInRange: List<DetectedRaceBeacon>)`, nearest-first.

Reached via the Beacon Operations hub (`ui/beaconops/BeaconOperationsScreen.kt`,
`Routes.BEACON_OPERATIONS`), displayed as "Check Beacon" — the hub also has "Close" (placeholder,
see Close mode checklist above) and "Program" (`BeaconProgrammerScreen`, retitled "Program
Beacon"). `ModePickerScreen`'s "Beacons" button opens this hub rather than going straight to
programming.

### Settings — partially done

`data/settings/SettingsRepository.kt`:
- `beaconIdentifyDistanceMeters: Flow<Double>` — **done**, default `0.5` (tightened from an
  initial 1.0 — testing with multiple fobs present showed registration needs a tighter radius).
  No `SetupOptionsScreen` control yet; fixed at the default pending field-test data.
- `beaconScanningEnabled`, `beaconMaxDistanceMeters` — **not added**. `BeaconScanEngine` currently
  runs unconditionally once `PeripheralSyncService` starts, with `BEACON_MAX_DISTANCE_METERS`
  fixed as a constant rather than a per-race setting. See Cross-cutting hardening below.
- A fixed-duration `beaconCooldownSeconds` setting was considered at one point but is superseded
  by `BeaconScanEngine`'s presence-based suppression (`MAX_GAP_MILLIS`) — if any cooldown-shaped
  value ends up needing exposure, it should be that constant, not a reintroduced fixed cooldown.

### Range calibration data collection — done

TODO.md's "Bluetooth Beacon range estimation" section asked for a facility to record real
RSSI-vs-distance data across three field contexts (indoor/crowded registration, outdoor/sparse CP,
outdoor/crowded finish), because the original fixed-exponent distance formula was "grossly
inaccurate." Built as **Beacons > Range Calibration**
(`ui/beaconops/RangeCalibrationScreen.kt`/`RangeCalibrationViewModel.kt`), a 4th hub button
alongside Check/Close/Program.

**Research behind this**: AltBeacon/iBeacon use an empirically curve-fitted piecewise function
rather than a theoretical path-loss formula, fitted against real measured (RSSI, distance) pairs;
per-receiver-phone-model calibration is a first-class variable in that project (this app has two
very different phones in its test fleet, XCover 4 and XCover 7); nobody trusts a single raw RSSI
sample (AltBeacon/iOS both smooth over a trimmed-mean window); human bodies are a huge RSSI
attenuator at 2.4GHz (~20–40dB for a single body blocking the path), likely the dominant error
source at registration/finish rather than distance decay alone; and the industry consensus is that
RSSI-only ranging is inherently coarse — true phase-based ranging (BLE Channel Sounding / UWB)
needs hardware this project's beacons/phones don't have, a ceiling not a bug.

**What's logged** (`data/beacon/RangeSampleLogger.kt`, CSV, append-only, shared via
`FileProvider`): timestamp, context (`RangeContext`), operator-entered real distance, beacon
number, the beacon's own `measuredPowerDbm`, median/min/max/count of a raw RSSI burst, plus
`Build.MODEL`/`Build.VERSION.SDK_INT`. Deliberately doesn't fit or replace the formula itself —
that's left for later, informed by whatever this produces (a per-context exponent, a refit
AltBeacon-style curve against this app's own data, separate per-phone-model coefficients, or
context-based thresholds to account for crowd/body-shadowing).

**Interim formula swap (2026-11-12)**: while the three field sessions are still pending,
`BeaconScanDecoder.estimateDistanceMeters` has been switched from the fixed-`n=2` free-space
formula to AltBeacon's own curve-fit function (still using AltBeacon's borrowed Nexus 4/iPhone 4S
constants, not a refit against this app's own data). Not a final model, but a curve actually
fitted against real measurements beats a textbook exponent as a default. Note
`SettingsRepository.DEFAULT_BEACON_IDENTIFY_DISTANCE_METERS` (currently `0.5`) wasn't recalibrated
against the new curve's scale and should be re-checked once real readings exist.

### Interaction with `BibValidation` — no changes needed

`BibValidation`'s duplicate/unexpected-bib checks are purely derived from
`BibsModeRepository.observeCurrentSegmentEntries(raceId)`'s live flow, never insert-path-specific.
Since `BeaconScanEngine` writes through the exact same `BibsModeRepository.recordEntry` →
`EntryLogModeEngine.recordEntry` → `HistoryLineDao.insert` path a manual entry uses, a
beacon-triggered row is indistinguishable downstream except via `note` and `timestampMillis`.
Still needs confirming during Finish-line dual-phone testing rather than new plumbing.

## Cross-cutting hardening (not in TODO.md's own outline — kept here so it isn't lost)

- [ ] Expose `beaconScanningEnabled`/`beaconMaxDistanceMeters` as real settings (currently fixed
      constants), with `SetupOptionsScreen` controls, and gate `BeaconScanEngine` on the toggle
      instead of running unconditionally
- [ ] Port `MuleSyncEngine`'s `SCAN_REFRESH_INTERVAL` (90s scan teardown/restart) into
      `BeaconScanEngine` — it runs for an entire multi-hour race, so the single-scan-session
      degradation found in the 2026-11-05 investigation is more certain to occur here than in the
      admin screens that originally surfaced it
- [ ] Prompt for battery-optimization exemption at setup time (not silently assumed) — Samsung
      Device Care in particular has reports of throttling foreground-service BLE scanning
- [ ] Field-tune `MAX_GAP_MILLIS`/`REQUIRED_CONSECUTIVE_READINGS`/default distance thresholds
      against real multi-beacon field data (e.g. a start line with several runners close together)
- [ ] Field-validate the shared-scan design (§ "Shared BLE scan" above) under combined Mule-sync +
      GATT-advertise + beacon-scan load specifically on the Samsung XCover 4 — the one genuinely
      open empirical question about the scan-sharing architecture itself

## Critical files

- `app/src/main/java/mobile/racemaster/data/beacon/BeaconScanDecoder.kt`
- `app/src/main/java/mobile/racemaster/data/beacon/BeaconScanEngine.kt`
- `app/src/main/java/mobile/racemaster/data/beacon/DxCp27Protocol.kt`
- `app/src/main/java/mobile/racemaster/data/beacon/RangeSampleLogger.kt`
- `app/src/main/java/mobile/racemaster/data/mule/MulePullClient.kt`
- `app/src/main/java/mobile/racemaster/data/mule/MuleRepository.kt`
- `app/src/main/java/mobile/racemaster/data/mule/MuleGattProfile.kt`
- `app/src/main/java/mobile/racemaster/data/mule/ProgressRepository.kt`
- `app/src/main/java/mobile/racemaster/data/mule/PeripheralSyncService.kt`
- `app/src/main/java/mobile/racemaster/data/repository/EntryLogModeEngine.kt`
- `app/src/main/java/mobile/racemaster/data/repository/BibsModeRepository.kt`
- `app/src/main/java/mobile/racemaster/data/repository/LocationValidation.kt`
- `app/src/main/java/mobile/racemaster/data/settings/SettingsRepository.kt`
- `app/src/main/java/mobile/racemaster/di/AppContainer.kt`
- `app/src/main/java/mobile/racemaster/navigation/Routes.kt` /
  `app/src/main/java/mobile/racemaster/navigation/RacemasterNavHost.kt`
- `app/src/main/java/mobile/racemaster/ui/beaconidentify/IdentifyFobScreen.kt` /
  `IdentifyFobViewModel.kt`
- `app/src/main/java/mobile/racemaster/ui/beaconops/BeaconOperationsScreen.kt` /
  `RangeCalibrationScreen.kt` / `RangeCalibrationViewModel.kt`
- `app/src/main/java/mobile/racemaster/ui/closemode/CloseModeScreen.kt` /
  `CloseModeViewModel.kt`, `app/src/main/java/mobile/racemaster/data/repository/CloseModeRepository.kt`
- `app/src/main/java/mobile/racemaster/ui/countmode/CountModeScreen.kt` /
  `CountModeViewModel.kt`, `app/src/main/java/mobile/racemaster/data/repository/CountModeRepository.kt`
- `app/src/main/java/mobile/racemaster/ui/modepicker/SetupDeviceScreen.kt` (no longer has a
  beacon-related button — moved to the Beacon Operations hub)
