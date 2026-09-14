# ToDo

## Original spec

The section below is the original requirements spec this implementation plan was derived from —
kept verbatim as the requirements source of truth. The **Implementation plan** section further
down is the actionable, checkbox-tracked breakdown; update checkboxes there as work proceeds, not
here.

### web-app mobile-app synchronisation

- mobile app race setup simplification: (phase 1)
  - The notion of a course in the phone is to be dropped, its just another race (that by
    convention may have a seniors or juniors suffix).
  - The first bib number and number of runners setup is also to be dropped.
  - The only required setup is the race name and location.
  - race setup becomes part of setup device as a another button below "Options" and can be
    done before a mode is selected
  - the device file is written to the server (when online) or broadcast (via BT to a mule)
    as soon as setup is complete (i.e. independent of any mode selection) so that the web app
    gets visibility of the device being in the field sooner

There are three scenarios:
- with internet connection,
- with BT connection,
- with neither (standalone)

#### With internet connection (phase 2)

This is the workflow covering both the web-app and the mobile app and how they interact:

##### web-app:

- race setup as now
- registration as now (in particular the creation/updating of progress.json files for all
  courses on the server in mobile/owner/race-course-date)
- add a button - "Activate Race" - on the mobile files page that when selected creates/updates
  the progress.json files on the server such that its timestamp becomes 'now', this becomes a
  signal the mobile app can detect to make appropriate selections
- in internet mode the rest of the web-app is as now, no further changes required

##### mobile-app:

- the user manually enters/selects a location (as now)
- race setup scans the server for progress.json files in the mobile/owner folder (owner in
  the mobile app context is the logged in user), presenting the operator with a timestamp
  ordered list (newest first) of what was found within the stale race threshold (as set in
  Options)
- user selects the appropriate race
- the device becomes that race and deposits its device file in the selected folder
- in internet mode the rest of the mobile app is as now, no further changes required
- should the server become unreachable after this setup, operation should continue as in the
  BT mule case (see below)

#### With only BT connection to a mule (phase 3)

##### web-app:

- connects to a mule phone as now
- device list populated from the mule as now
- device selection as now
- progress update as now
- progress sent to the mule as now but the mule only forwards it to the selected devices
  irrespective of any race name match (the web-app user has 'adopted' the selected devices
  and is telling them "this is your race")
- the rest of the web-app is as now, no further changes required

##### mobile-app:

- the user manually enters/selects a location (as now)
- race setup cannot scan the server as it is not reachable, instead:
- the user manually enters a race name (including a conventional seniors/juniors suffix)
- the mobile app broadcasts this as now to any nearby mule
- the mobile app carries on as now, picks a mode, records events, broadcasts to a mule
- when the app receives a progress.json file via the mule, it is a signal from the web-app
  that this phone should adopt the race implied by that file, it then drops the temporary
  race id it invented and uses the new one with any already recorded events undo the old
  id transferred to the new one, all in app feedback must also change
- this adoption process should be active no matter what screen is up
- should the server become reachable after this the device file placed on the server should
  also reflect this adoption
- the rest of the mobile app is unchanged

#### standalone

The phones just record stuff and some other process makes use of it.
The mobile app and web-app are independent and work as now (with the above mobile race setup
simplification). So only duplicate bib entry detection is possible.

### Bibs and CP mode auto bib allocations (phase 4)

The phones now have regularly updated progress records for every bib on the course.
These records can come from the server or via BT from a mule mode phone or both (as above).
Every phone in the same race contributes to these progress records, and they get distributed to
all that are part of the same race.
The expected bibs at the location of any particular phone can be extracted from this information.
For the purposes of expectation: the course is ordered as CP1 to CPn to Finish.
All starters are expected to arrive at CP1.
Any bib passing, and not retiring, CPn is expected to arrive at CPn+1.
Any bib passing, and not retiring, from the last CP is expected to arrive at the Finish.
Any phone in Bibs or CP mode should have easy access to a list of:
  starters
  retirees
  finishers
  outstanding bibs at this location
The N of M still outstanding and the missing list when <10 remaining in the existing
implementation is to be retained but the information comes from the progress records.
The first bib # echoed on the mode screen is no longer relevant and can be dropped.
Any bib number entered that is not expected is allowed but flagged in a similar manner to the
current range/duplicate check.
Duplicate bib numbers are to be flagged as now.
The current range check is replaced by the expectation check as described above.

---

## Implementation plan

Derived from the spec above, cross-repo (this repo `racemaster-mobile` + the web-app at
`~/racemaster`), with three corrections made after reviewing the code and confirming with the
user (kept here so intent isn't lost on resume):

1. TODO's "the user manually enters/selects a location (as now)" is the *existing*
   `RaceEntity.location` field (which physical point this device stands at — Start/Finish/CP2 —
   already carried per-record on every `SyncRecord`), **not** a new race-wide location field. No
   new schema needed anywhere for it.
2. Dropping "course" does **not** drop the ability to resume a race this device already
   recorded against (e.g. Stop pressed by mistake while runners are still out) — that recovery
   path is kept, just re-homed onto Race History instead of the Start-time course picker.
3. "Activate Race" only ever applies to the *currently loaded event* in Event Settings, never an
   arbitrary historical race row. The server-race-scan needs a new staleness-filtering server
   endpoint (not client-side filtering of the full listing). Phase 3's relay-onward delivery must
   support mule-to-mule chains, not just one hop. **All BT/internet traffic must be
   delta/minimal, never full-payload replaces** (unreliable field connectivity + metered mobile
   data).

Execution rules for whoever (or whichever session) picks this up:
- Work phase by phase, in order. Don't start phase *N+1* until phase *N*'s checkboxes are all
  checked **and** its Verification step (below) passes.
- Check off an item and commit the corresponding change together (small, one-checkbox-ish
  commits) — **commit locally after each phase's items, do not push**. `git log` + these
  checkboxes together are the resume state after any interruption.
- If a verification step fails and the fix isn't obvious, stop and flag it rather than pushing
  into the next phase on top of something broken.

### Cross-cutting: progress payloads must be deltas, not full replaces (build first, in phase 2) — DONE 2026-09-14

`progress.json` is currently full-replace both directions (`server/mobile.js` `writeProgress`;
`GET .../progress` only shortcuts a whole-payload-unchanged case). The BLE
`PROGRESS_CHARACTERISTIC_UUID` write (`js/mule-ble.js:571` `deliverProgress()`) sends the full
payload every time too. Device-*record* sync is already delta-based (merge by `recordUuid`/
lineNumber) — progress needs the same treatment before phase 3's relay work can build on it.

- [x] Give each `ProgressEntry` a per-entry change marker (`updatedAt`, ISO string), stamped
      server-side (same "server, not the client, is authoritative" precedent `generatedAt`
      already set) in `server/mobile.js`'s new `mergeProgress()`. Mirrored on the mobile side as
      `ProgressEntry.updatedAt: String?` (`MuleGattProfile.kt`).
- [x] Push (web-app → server): `js/progress-sync.js` diffs a freshly built payload against an
      in-memory per-owner+raceLabel snapshot of its own last successful push
      (`lastPushedByRaceLabel`, deliberately not persisted — see its own doc for why a page
      reload should self-heal by re-sending everything) and sends only `{entries: changed,
      removed: [bibNumber...]}`. New route `POST .../progress` body shape change +
      `mergeProgress()` (`server/mobile.js`) upserts by `bibNumber` and drops anything in
      `removed` — mirrors `POST /api/mobile/:raceLabel`'s existing merge-by-`recordUuid` shape.
      A course that had entries and now has none still pushes (to carry `removed`), fixed after
      an initial version wrongly skipped it — see `js/progress-sync.js`'s own doc.
- [x] Fetch (`GET /api/mobile/:raceLabel/progress`): `knownGeneratedAt` renamed to `since`
      throughout (server + `MuleSyncClient.getProgress`) and now doubles as the delta cursor —
      `entries` is filtered to `updatedAt > since` server-side, keeping the existing
      `{unchanged:true}` shortcut for the zero-changed case. `ProgressRepository.refreshFromServer`
      merges the response into the *Room-persisted* copy (not just in-memory `current`) by
      `bibNumber` — see `mergeEntries`'s own doc for the one known gap this doesn't close (a bib
      removed server-side has no signal on this read path yet, only the push path's `removed`
      list — accepted as a rare, low-stakes limitation for now, not built out further).
- [x] New route `GET /api/mobile/races?maxAgeDays=N` + `getAvailableRacesForUser()`
      (`server/mobile.js`) — lean, server-filtered, feeds Setup Race's online branch (see phase 2
      below) — grouped here since it shares the same "server does the filtering, not the client"
      principle as the delta work, even though it's not itself a delta mechanism.
- [x] BLE delivery: `deliverProgress()`'s call site in `pullFromConnectedPhone`
      (`js/mule-ble.js`) diffs against the receiver's own `DeviceInfo.progressGeneratedAt` before
      sending, transmitting only entries with `updatedAt` newer than it. Receiving side
      (`ProgressRepository.storeFromBle`) merges by `bibNumber` via the same `mergeEntries` the
      HTTP fetch path uses.
- [x] Unit tests for the new merge/diff logic on both sides: `test/server/mobile.test.js`
      (`mergeProgress`/`touchProgress`/`getAvailableRacesForUser`), `test/progress-sync.test.js`
      (delta push + `removed`), `test/mule-ble.test.js` (delta BLE delivery),
      `ProgressRepositoryTest.kt` (delta merge on receipt, both transports share the same path).
- [x] **Verify**: `npm test` (647 passing, up from 633) and `./gradlew testDebugUnitTest`/`check`/
      `assembleDebug` all pass. HTTP-level sanity check against the real local dev server (see
      phase 2's own verify note below) confirmed touch/merge/removal/delta-fetch all behave
      exactly as specified, using a throwaway test account cleaned up afterward — no real
      `mobile/giz`/`mobile/mercia` data touched.

### Phase 1 — mobile race setup simplification (mobile-only) — DONE 2026-09-14

- [x] Add `SETUP_RACE` route/button to `SetupDeviceScreen.kt` (`navigation/Routes.kt`,
      `RacemasterNavHost.kt`), alongside Setup Name / Setup Server / Options.
- [x] New `ui/racesetup/SetupRaceScreen.kt` + `SetupRaceViewModel.kt` (modelled on
      `NameDeviceScreen.kt`/`NameDeviceViewModel.kt`): race name (free text incl. seniors/juniors
      suffix, reuse `isValidRaceName`) + location (existing field/validation, no CP-specific
      pattern enforced here — see next bullet). No course chips, no first-bib/runner-count
      fields. Disabled (with explanatory text) while a race is already active, same guard
      `NameDeviceScreen` uses for renaming.
- [x] `SetupRaceViewModel.save()` calls `RaceRepository.startNewRace(name, course = "", location)`
      directly, then `switchActiveRace()` — no course resolution step, no `courses`/
      `bibsRangeStart`/`bibsRangeCount` populated.
- [x] Remove `RaceRepository.resolveCourseRace()`, `cloneTemplate()`, `ui/components/CoursePickerDialog.kt`
      and its 3 Start-button call sites (Time/Bibs/CpModeScreen.kt) — `startXMode` now always
      resolves against the device's single active race directly, no course dialog.
- [x] Remove the "End recording" choice in `StopOrResetButton.kt` (now a plain single-choice
      Reset confirm dialog), `RaceRepository.endRecordingForCourse()`, and its 3 call sites
      (`endRecording()` removed from Time/Bibs/CpModeViewModel.kt). Rewrote `HelpScreen.kt`'s
      Setup Race / Time Mode / Bibs Mode (starting a race, duplicates, stop and reset) / CP Mode
      / Setup Device / General sections accordingly.
- [x] Keep the resume-in-place check (`if (target.xModeStartedAtMillis != null) resumeXMode(id)
      else startXMode(id)`) — re-homed directly into each mode's `startXMode()`/`startXxxxMode()`
      function so pressing Start on the device's current active race (no dialog) always goes
      through it.
- [x] New "Resume" action on `RaceHistoryScreen.kt`'s `LocalRace` rows, offered when a race
      `isActive` (an un-Reset started mode) but is **not** the device's current
      `activeRaceId` (added `isCurrentActiveRace` to `HistoryItemUi.LocalRace`, sourced from
      `SettingsRepository.activeRaceId`, now a `RaceHistoryViewModel` dependency) →
      `RaceRepository.switchActiveRace(raceId)` (`RaceHistoryViewModel.resumeRace`), landing back
      on the Mode Picker; the resume-or-start check above then does the rest on next Start press.
- [x] Narrow `RaceDetailsScreen`/`RaceDetailsViewModel` to a rename-only "This Race" screen (name
      + location only, `existingRaceId` now non-nullable — no more create path), reusing
      `identityFieldsEnabled`/`updateRaceDetails()`. Dropped "Clear race" too (Race History's own
      delete, with its force-reset backstop, already covers this — keeping a second deletion path
      on a screen now named for renaming only added scope, not safety). `RaceDao.updateDetails`
      narrowed to `(raceId, name, location, label)` — course/courses/bib-range columns are simply
      left alone by this query now, not re-written with stale values.
- [x] Device-file-write-on-setup: implemented for real (not just a hook) — `SetupRaceViewModel.save()`
      calls new `MuleRepository.announceRaceSetup(raceLabel)`, a best-effort, silently-swallowed
      explicit empty-record push via the existing `MuleSyncClient.pushRecords`. Necessary because
      `pushToServer()`'s own regular reconciliation loop skips any race with zero activity at all
      (its own staleness rule), so a brand-new race would otherwise never get a device file until
      the first real split — confirmed by reading that function before assuming it could just be
      called directly. Offline/not-logged-in is a silent no-op; the BT-mule broadcast side of this
      signal is genuinely phase 3's job, not built here.
- [x] Leave `RaceEntity.course`/`courses`/`bibsRangeStart`/`bibsRangeCount` columns in place,
      unpopulated (Room migration deferred to end of phase 4). Also removed now-dead
      `RaceDao.setCourseAndLabel`, `SettingsRepository.courseHistory`/`addCourseToHistory`/
      `DEFAULT_COURSES`/`Keys.COURSE_HISTORY`, and `isValidCourseName` — all had no remaining
      caller once the course-chips UI was gone (kept `bibsRangeStart`/`bibsRangeCount`/`courses`
      *columns* per the plan; only the now-orphaned course-history/validation code was removed).
- [x] Fixed a latent bug this phase's own change would otherwise have introduced:
      `RaceRepository.switchActiveRace()`'s "delete the old race if it was just clutter" cleanup
      used to key off `old.course.isBlank()` — harmless when course was sometimes non-blank, but
      with course *always* blank now, that condition would have deleted the previous active race
      on every single switch, even ones with substantial real history. Now keyed off whether the
      old race has ever recorded any history line at all
      (`observeLastActivityAtMillis(oldRaceId).first() != null`), which is what the condition was
      actually trying to mean.
- [x] Added CP-location-format enforcement (`isValidCpLocation`) at CP Mode's own Start button
      instead — the old race-details form enforced this at creation time, but Setup Race no
      longer knows the mode at setup time to do the same. Blocks Start with an inline error
      pointing at "This Race" until fixed, rather than silently dropping the check. Not itemized
      in this checklist originally — added while implementing CP Mode's screen changes, since
      dropping a correctness check silently seemed worse than a small, scoped addition.
- [x] `ModePickerScreen.kt`'s `handleModeTap`: a mode tap with no active race now navigates
      straight to Setup Race (`onSetupRaceNeeded`) instead of the old `RaceDetailsScreen` create
      flow — mirrors the existing "Mule Mode off routes through Options first" precedent already
      in this file, rather than adding a separate error dialog.
- [x] `ModeScreenTopBar.kt`: dropped the "New Race" button entirely (starting over now means
      going back to Setup Device) — kept "This Race" (→ the narrowed rename screen) and "Mode".
- [x] Grepped for remaining `resolveCourseRace`/`cloneTemplate`/`endRecordingForCourse`/
      `CoursePickerDialog`/`onNewRace`/`newRaceEnabled`/`coursePickerOptions`/`DEFAULT_COURSES`/
      `courseHistory` references across `app/src/main` and `app/src/test` — none left.
- [x] **Verify (automated)**: `./gradlew testDebugUnitTest`, `./gradlew check` (lint included),
      and `./gradlew assembleDebug` all pass clean.
- [~] **Verify (manual, on-device)**: partially done — 3 real phones turned out to be attached
      via adb (`8a0f61d4`/Mi 9 SE, `A756XXCM9A2200A5`/KING_KONG_3, `BH900MSDC8`/G8441). Installed
      the phase-1 debug build on the first two (with the user's explicit go-ahead, since debug
      and release share `applicationId` and would overwrite whatever's already on the device) and
      confirmed, via screenshots, on real devices each already mid-race with real recorded data:
      app launches cleanly, no crash, on both; the mode screens' top bar correctly shows "This
      Race"/"Mode" (no "New Race"); Setup Device → Setup Race renders exactly per spec (race name
      + location only, no course chips/bib fields) and correctly refuses to set up a new race
      while one is active, pointing at Races (Progress) to resume a stopped one instead; the
      Races page correctly shows the current race as "Active in Bibs Mode, can't be deleted".
      **Not exercised** (would have required stopping/resetting one of these devices' real
      in-progress races, which wasn't worth risking): actually creating a race via Setup Race,
      the new Race History "Resume" action on a genuinely stopped-not-reset race, and CP Mode's
      new location-validity gate. A future session with a spare/disposable test device (or by
      deliberately sacrificing one of these three, if the user's OK with that) should complete
      that last leg.
- [x] Commit phase 1 (local only, no push).

### Phase 2 — internet-mode workflow (both repos) — DONE 2026-09-14

#### Web-app (`racemaster`)

- [x] New route `POST /api/mobile/:owner/:raceLabel/progress/touch` (`server/routes/mobile.js`)
      + `touchProgress(username, raceLabel)` in `server/mobile.js` — rewrites existing
      `progress.json` with only `generatedAt` refreshed, no `entries` re-sent, 404 if none exists
      yet.
- [x] "Activate Race" button — on `js/views/event.js` (Event Settings, not the Mobile Files "All
      Files" tab — deliberate, see correction #1), one touch-route call per course this event
      has via `deriveRaceLabel(state.event, course)` (`js/mobile-files-shared.js`). New
      `apiTouchProgress()` in `js/storage.js`. Only ever touches this event's own race label(s).
      No dedicated test file — matches this codebase's existing convention that `js/views/*.js`
      DOM-wiring modules aren't unit tested (confirmed: no `test/views/` directory exists at all,
      no other view module has a test file either).
- [x] New route `GET /api/mobile/races?maxAgeDays=N` (`server/routes/mobile.js`) +
      `getAvailableRacesForUser(username, maxAgeDays, adminAccess)` in `server/mobile.js` — lean
      shape `{raceLabel, raceName, raceDate, generatedAt}[]`, filtered server-side to
      `generatedAt` within `maxAgeDays`, sorted newest-first. No `devices`/`lines`/`recordCount`.

#### Mobile app (`racemaster-mobile`)

- [x] `data/mule/MuleSyncClient.kt`: `getAvailableRaces(baseUrl, token, maxAgeDays)` calling the
      new route. New `AvailableRace` model.
- [x] `data/mule/MuleRepository.kt`: `getAvailableRaces(maxAgeDays)` wraps the client call with
      the same one-shot 401/403 reauthenticate `pushToServer()` already has, returning `null`
      ("couldn't ask at all — not logged in, or truly unreachable") vs. an empty list
      ("reachable, nothing recent") so the screen can say why, though both degrade to the same
      manual-entry fallback.
- [x] Extended `SetupRaceScreen`/`SetupRaceViewModel` with an online branch: "Scan Server for
      Recent Races" button (enabled once location is entered) → `AvailableRacesState`
      (`NotChecked`/`Loading`/`Found`/`Unavailable`) → a picker `AlertDialog` listing
      `raceName`/`raceLabel` per result; `Unavailable` shows inline text pointing at the manual
      name field, which stays visible either way — this *is* the "server-unreachable fallback"
      (see below), not a separate mechanism.
- [x] Race-id-then-progress sequencing: new `RaceRepository.adoptRaceLabel(raceLabel, location)`
      (deliberately not `startNewRace` + hoping `buildRaceLabel` reconstructs the same date —
      the picked race may have been registered on an earlier date, so the label is adopted
      exactly, not rebuilt — see its own doc) creates the local race row *at the moment of
      picking*; `SetupRaceViewModel.pickAvailableRace` then calls
      `ProgressRepository.refreshFromServer(...)` under the new `raceId` before announcing.
- [x] Device-file-on-setup: already implemented in phase 1 via `MuleRepository.announceRaceSetup`
      — confirmed it's called from both the manual (`save()`) and online (`pickAvailableRace()`)
      paths; no new work needed here.
- [x] Server-unreachable fallback: `MuleRepository.getAvailableRaces` returning `null`/empty
      naturally degrades the screen to the always-present manual name field (see above) — no
      separate "flip to mule broadcast" mechanism built, since that's genuinely phase 3's own
      job (manual entry today just means typing a name, same as offline), not this phase's.
- [x] New tests: `RaceLabelsTest.kt` (`raceNameFromLabel`, `buildRaceLabel`'s own inverse),
      `ProgressRepositoryTest.kt` additions (delta merge on `storeFromBle`, covering the same
      path `refreshFromServer` shares). `MuleRepository`/`RaceRepository` methods needing real
      network/DB dependencies are *not* unit tested — matches this codebase's existing pattern
      (confirmed: no `MuleSyncClientTest.kt`/`RaceRepositoryTest.kt` exist either; `MuleRepositoryTest.kt`
      only ever covered pure free functions, never the class's own DB/network methods).
- [x] **Verify**: `./gradlew testDebugUnitTest`/`check`/`assembleDebug` all pass. HTTP-level
      sanity check against the real local dev server (`./gradlew devServer`), using a throwaway
      `deltatest` account created via `POST /api/auth/create` and fully cleaned up afterward
      (`mobile/deltatest/` deleted, its `users.txt`/`sessions.txt` lines removed) — confirmed:
      touch 404s with no `progress.json` yet, bumps only `generatedAt` once one exists; a delta
      push merges by `bibNumber` and honors `removed`; `GET .../progress?since=` returns only the
      entries changed since that cursor, `unchanged:true` when it matches current `generatedAt`;
      `GET /api/mobile/races?maxAgeDays=` filters correctly (0.0000001 days → empty,
      30 days → the test race) and 400s with no `maxAgeDays` at all. Installed the build on a
      real device (`A756XXCM9A2200A5`) already mid-race with real data — launched clean, no
      crash, upgrade preserved its existing race untouched, new "Scan Server for Recent Races"
      button renders correctly on Setup Race (disabled, since that device already has an active
      race — the same reason the actual scan/pick flow, and Activate Race's own web-UI, weren't
      exercised live: doing so would have required disrupting real in-progress race data on the
      only devices available). That live exercise is genuinely outstanding — noted, not silently
      skipped.
- [x] Commit phase 2 (local only, no push).

### Phase 3 — BT-mule-only workflow (both repos) — DONE 2026-09-14

Highest-risk phase — genuine wire-protocol extension, not just app logic (the browser can only
ever write to the mule it's directly connected to; reaching a relayed device requires the mule to
cache-and-forward on its own next pull cycle, recursively through further mule hops).

#### Web-app (`racemaster`)

- [x] "Adopted devices" selection state, keyed by `originDeviceId` (never `raceLabel`) —
      `getAdoptedDevices`/`setAdoptedDevice`/`removeAdoptedDevice` in `js/mule-ble.js`
      (`{[deviceId]: {raceLabel, deviceName}}`, not a plain Set — adoption needs to remember
      *which* race a device was assigned to, not just that it's selected), persisted in
      `localStorage` under a new `racemaster-ble-adopted-devices` key, plain/unscoped the same
      way the existing `racemaster-ble-known-devices` already is (this codebase has no
      per-username-scoped localStorage precedent to follow instead).
- [x] UI: new `#mf-relay-devices` section on the Mobile Files page (`index.html`), rendered by
      `renderRelayDevices()` in `js/views/mobile-files-ble.js` after every completed pull, from
      `mule-ble.js`'s new `getCachedRelayEntries()` (exposes the module's own already-existing
      relay-manifest cache read-only, rather than changing `pullFromConnectedPhone`'s return
      shape, which every existing caller treats as a bare array). One checkbox per relayed
      device; ticking assigns it to whichever course (Seniors, falling back to Juniors) the
      currently loaded event derives a race label for. **Simplified from the original plan**: a
      plain functional checkbox list, not the full `mobile-files-devices.js`-style themed table —
      this is a small, occasional-use control, and there was no existing "relay manifest" UI
      surface at all to extend (confirmed: relay entries were purely internal to `mule-ble.js`'s
      own pull logic before this).
- [x] Reworked `pullFromConnectedPhone()` (`js/mule-ble.js`) to accept a new `adoptedTargets:
      [{deviceId, raceLabel, progress}]` option, built by `currentRaceProgressContext()`
      (`js/views/mobile-files-ble.js`) from `getAdoptedDevices()` + `getLastKnownRaces()` the same
      way `currentRaceCandidates` already is. Two delivery legs, both tagging the payload with
      `targetDeviceId`/`targetRaceLabel` and bypassing the `raceLabel`-equality gate entirely:
      **direct** (the connected phone itself is an adopted target — diffed against its own
      `deviceInfo.progressGeneratedAt`, same mechanism the existing own-race leg uses) and
      **relay-forward** (an adopted target found in the connected phone's own relay manifest —
      proof that phone can reach it). **Simplified from the original plan**: the relay-forward leg
      does not diff against the deep origin's own checkpoint before sending (`RelayManifestEntry`
      carries no `progressGeneratedAt`, only `lastLineNumber`, which is about record sync, not
      progress — adding one would mean threading a second checkpoint through the whole
      relay-manifest pipeline purely for this). Accepted as a real, deliberate simplification: a
      possible redundant re-send at worst, never an incorrect one — each hop's own receiver still
      only re-adopts/re-caches on a genuine content change (see the mobile-side adoption/cache
      logic below).
- [x] Tests: `test/mule-ble.test.js` — direct targeted delivery + its own generatedAt-match
      skip, relay-forward delivery, "never adopted → never delivered", plus
      `getAdoptedDevices`/`setAdoptedDevice`/`removeAdoptedDevice`/`getCachedRelayEntries`
      localStorage round-trips.

#### Mobile app (`racemaster-mobile`)

- [x] Manual race-name entry (offline branch of `SetupRaceScreen`): already fully built by phases
      1-2 (`SetupRaceViewModel.save()` is exactly this path — the manual name field "stays visible
      either way" per phase 2's own note) — confirmed, no new work needed here.
- [x] New `TargetedProgressEntity`/`TargetedProgressDao` (`data/db/entity`, `data/db/dao`) —
      mirrors `PulledRecordEntity`'s pulled-records-inbox shape (a flat holding table, unique
      index on `targetDeviceId` so a fresher delta simply replaces an older one — see the
      entity's own doc). Wired into `RacemasterDatabase` (version 28 → 29,
      `fallbackToDestructiveMigration` already in place — no manual migration needed) and
      `AppContainer`. `ProgressRepository.kt` gained
      `cacheTargetedProgress`/`pendingTargetedProgress(targetDeviceId, maxAgeDays)`/
      `evictTargetedProgress` on top of it. (The "originDeviceId-keyed lookup for this phone's
      own race progress" originally itemized here turned out unnecessary — `current`/
      `ProgressEntity`'s existing per-raceId storage already covers that; only the *forwarding*
      inbox was actually new.)
- [x] Wire extension: `ProgressPayload` (`MuleGattProfile.kt`) gained `targetDeviceId`/
      `targetRaceLabel` (both null-default, every existing own-race delivery path untouched) —
      on the payload type itself, not `PullRequest` (opposite direction — pulling records vs.
      delivering progress, deliberately not conflated). `PeripheralSyncService.handleProgressPayload`
      now branches: `targetDeviceId` set and not this device's own id → cache in the targeted
      inbox (never adopted, never merged into this device's own race); set and matching → the
      **adoption trigger** (see below); null → the original own-race path, unchanged.
- [x] **Delivery/relay wiring** — new `MulePullClient.deliverTargetedProgress`/
      `MuleRepository.deliverTargetedProgress` (a standalone connect→write→disconnect,
      reusing the exact same `connectOrEvict`/`endConnection`/mutex machinery every other
      connection in this file already uses — no bespoke ad-hoc GATT handling). New
      `MuleSyncEngine.deliverTargetedProgressIfPending(advertisement, targetDeviceId, peerLabel)`
      helper, called from three places: `refreshDeviceInfo` (first-sighting) and the periodic
      loop's own direct-peer check (both: "is this peer itself a pending target?"), plus a new
      loop over the periodic loop's own freshly-fetched `relayEntries` ("is any of *these*
      origins a pending target?" — this is the actual mule-to-mule recursion: a further hop's own
      identical logic keeps propagating it onward, with no hop-count limit needed, mirroring
      exactly how record-relay chaining already has no hop limit either). Evicts on successful
      handoff; a failed one is simply retried next tick.
- [x] Adoption trigger + in-place migration: new `RaceRepository.adoptRaceIdentity(raceId,
      raceLabel)`, generalizing `updateRaceDetails`/`RaceDao.updateDetails` (already
      `(raceId, name, location, label)` post-Phase-1) to rewrite identity from an adopted
      `raceLabel` (via `raceNameFromLabel`, the same helper Setup Race's online-pick path already
      uses) rather than from operator-typed fields. Confirmed `HistoryLineEntity.raceId` is still
      a stable Room FK post-Phase-1 (no migration needed) and that every screen still observes
      race state reactively off `raceId` via Room `Flow`s (no per-screen wiring needed) — both
      exactly as the plan assumed, not just re-asserted.
- [x] Confirmed (not just assumed) `MuleRepository.pushToServer()` still reads each race's label
      fresh from `RaceEntity` on every attempt post Phase 1/2 — adoption needs no new code to
      reach the server once reachable.
- [x] New tests: `ProgressRepositoryTest.kt` — targeted-inbox cache/read/replace/evict/staleness
      (a real 10-day-old seeded row, not a same-millisecond race against the wall clock).
      `RaceRepository`/`MuleSyncEngine`'s own new methods are *not* unit tested — matches this
      codebase's already-established convention (no `RaceRepositoryTest.kt`/
      `MuleSyncEngineTest.kt` exist for any of their existing DB/BLE-dependent methods either).
- [x] **Verify (automated)**: `./gradlew testDebugUnitTest`, `./gradlew check`,
      `./gradlew assembleDebug` (mobile) and `npm test` (web-app, 657/657 passing, up from 647)
      all pass clean.
- [~] **Verify (real multi-hop field test)**: genuinely NOT attempted, and could not honestly be —
      this needs 3 physical phones in real Bluetooth proximity plus a real browser doing real Web
      Bluetooth OS-level pairing, which isn't reliably automatable and can't be faked from
      code-reading alone. What *was* done instead: installed the build on one real device
      (`8a0f61d4`, already tested in phase 1) — launched clean, no crash (including surviving the
      Room version 28→29 destructive-migration upgrade), confirmed via screenshot. The actual
      multi-hop relay/adoption flow — a mule relaying through a second mule to a leaf device,
      adopted from the web-app UI — is a genuine integration test that needs a human with
      hardware and is left for that. Leaving this explicitly unchecked rather than claiming
      confidence the code-level work doesn't actually support.
- [x] Commit phase 3 (local only, no push).

### Phase 4 — bib/CP auto-expectation (mobile-only, depends on phases 1-3) — DONE 2026-09-14

- [x] `data/repository/BibValidation.kt`: removed `isBibInLegalRange`/`rangeErrorMessage`/
      `rangeWarningMessage`/`outstandingBibs(entries, rangeStart, rangeCount)`/`MIN_BIB_NUMBER`/
      `MAX_BIB_NUMBER` (all dead once the callers below were updated), and the now-unused
      `accountedForRecordCount` (its only callers were the removed `finishedCount` fields — see
      below). Added `starters`/`finishers`/`retirees`/`observedCpOrder`/`expectedBibsAtLocation`/
      `outstandingAtLocation`/`unexpectedBibNumbers`/`unexpectedBibWarning`, sourced from
      `ProgressEntry` lists joined against local `HistoryLineEntity` actions via the existing
      `distinctAccountedForBibs` (unchanged, reused as-is).
- [x] CP ordering: `observedCpOrder` derives the full CP1..CPn set from `ProgressEntry.cpTimes`
      keys (numeric sort, so "CP10" sorts after "CP2", not before) — used only for `Finish`'s own
      "last CP" lookup. CPn's own predecessor is plain `n - 1` arithmetic instead of a position
      derived from what's been observed — deliberate: an unobserved CP(n-1) must mean "nobody's
      expected here yet" (empty), not "silently treat CPn as if it were CP1" just because nothing
      earlier happens to be in the data yet. Only CP1 itself (literally numbered 1) is ever the
      unconditional "expects all starters" case — including when *nothing* has been observed
      yet for this race at all (a race that's only just started), which needed its own explicit
      fallback and cost a real test failure to catch (see the CP1-with-no-cpTimes-yet test cases).
- [x] `util/ExpectedRunnersText.kt`: dropped the "First bib X" echo everywhere, including Time
      Mode's own `formatTimeSplitsText` (which never had a real per-bib concept to begin with —
      simplified to just the split-count tally rather than carrying dead nullable params).
      `formatBibsExpectedText(expectedCount, outstandingCount)` replaces the old nullable-range
      signature; "N of M / Missing: ... when <10 remain" UI shape unchanged
      (`ui/components/EntryModeHeaderInfo.kt`).
- [x] `ui/bibsmode/BibsModeViewModel.kt`/`ui/cpmode/CpModeViewModel.kt`: both gained
      `ProgressRepository` (new constructor param, wired via `AppContainer`/their `Factory`s) and
      combine its `current` `StateFlow` alongside race/entries (packed into the existing
      `combine()`'s already-paired 4th slot, now a `Triple`, to stay under kotlinx coroutines'
      5-arg typed `combine` limit — same trick the surrounding code already used). Outstanding/
      expected/unexpected are recomputed from `ProgressEntry` + local history on every emission —
      live, not cached. A bib outside the expected set is flagged via `EntryLogUi.expectationWarning`
      (renamed from `rangeWarning` throughout `EntryLogList.kt`/`BibEntryRow.kt` — the old name no
      longer meant anything), same non-blocking treatment as a duplicate. `EditEntryViewModel.kt`/
      `EditEntryScreen.kt` (the dedicated per-entry editor) got the identical treatment, loading
      `expectedBibs` once alongside the entry via `ProgressRepository.getStored`. Duplicate
      detection (`findDuplicateSplitRefs`) itself untouched.
- [x] Room migration: dropped `RaceEntity.course`/`courses`/`bibsRangeStart`/`bibsRangeCount`
      (schema v29→v30 — `fallbackToDestructiveMigration` already in place per Phase 3, so no
      explicit `Migration` object needed, confirmed via a real device upgrade, see below). Also
      removed the now-dead `Converters.fromStringList`/`toStringList` (existed only for the
      removed `courses: List<String>` column — grepped first to confirm no other entity used it).
- [x] `ui/help/HelpScreen.kt`: added a new "Bibs Mode — who's expected" section documenting the
      progress-record-derived expectation model for operators (this didn't exist even in the old
      range-based form), and fixed CP Mode's own description (was still saying "out-of-range
      flagging").
- [x] New tests: `BibValidationTest.kt` — starters/finishers/retirees, `observedCpOrder`'s numeric
      sort (a CP1/CP2/CP10 case that would fail under a plain string sort), `expectedBibsAtLocation`
      for CP1 (unconditional starters, including with zero progress data yet — this is the case
      that caught the arithmetic-vs-observed-order bug above), CP2 (passed-CP1-not-retired),
      Finish (passed-last-CP, and its own no-CPs-observed-yet fallback to starters), an
      unrecognised location and a genuinely-never-observed CP both correctly expecting nobody,
      a "CP1-Bridge"-style suffix still matching by number, `outstandingAtLocation`,
      `unexpectedBibNumbers`, `unexpectedBibWarning`. Removed the now-obsolete range-based tests.
      305 tests total (up from 291), all passing.
- [x] **Verify (automated)**: `./gradlew testDebugUnitTest`, `./gradlew check`,
      `./gradlew assembleDebug` all pass clean. Confirmed the Room schema export actually dropped
      the four columns (`app/schemas/.../30.json` grepped for `course`/`bibsRange` — none found).
- [~] **Verify (live, real device + real progress data)**: partially done. Installed the build on
      a real device (`8a0f61d4`) that Phase 3's own schema-version upgrade had already emptied of
      local race data (confirmed safe to use) — launched clean, no crash, Mode Picker renders with
      no active-race card as expected. **Could not go further**: this device refuses adb input
      injection (`SecurityException: Injecting to another application requires INJECT_EVENTS
      permission` — hit this in phase 1 too), so the actual Setup Race → Bibs Mode → live
      expectation-list flow couldn't be driven on it. `A756XXCM9A2200A5` (the device that *does*
      accept injected input) has real in-progress race data from actual use — not touched, to
      avoid disrupting it. A third device (`BH900MSDC8`) turned out to be a personal phone (locked
      screen, Facebook/Spotify/personal Google account visible, not dedicated race hardware) —
      the debug build was installed on it (harmless — an app install, not a data-destructive
      action) but no further interaction was attempted once that became clear. **What this means
      concretely is still unverified**: a real "outstanding/starters/retirees/finishers lists
      update live as progress records arrive via both HTTP and BLE" exercise, end to end, with
      actual progress.json data flowing from the web app. The reactive wiring is code-reviewed and
      correct (`ProgressRepository.current` is updated by both `storeFromBle` and
      `refreshFromServer`, and both ViewModels' `combine()` chains include it), and the pure
      set-computation logic has thorough unit coverage, but a genuine live multi-device exercise —
      like phase 3's own multi-hop relay test — needs a spare device and a real race's worth of
      progress data, which wasn't available here. Left honestly unchecked.
- [x] Commit phase 4 (local only, no push).

**All four phases are now implemented, unit-tested, and committed locally.** What's left across
the whole plan is exclusively the hardware/field verification each phase's own Verify section
already flags as unchecked and explains why: phase 3's real multi-hop BLE relay/adoption chain,
and phase 4's live cross-device expectation-list exercise — both need actual race-day-style
conditions (multiple phones, real Bluetooth pairing, real progress data flowing) that a background
session can't fabricate confidence about. Nothing has been pushed to `origin`.

### Critical files

**racemaster-mobile**: `data/repository/RaceRepository.kt`, `data/repository/RaceLabels.kt`,
`data/db/entity/RaceEntity.kt`, `ui/modepicker/SetupDeviceScreen.kt` +
`navigation/Routes.kt`/`RacemasterNavHost.kt`, new `ui/racesetup/SetupRaceScreen.kt`/
`SetupRaceViewModel.kt`, `ui/components/CoursePickerDialog.kt`, `ui/components/StopOrResetButton.kt`,
`ui/racehistory/RaceHistoryScreen.kt`/`RaceHistoryViewModel.kt`, `data/mule/MuleSyncClient.kt`,
`data/mule/MuleRepository.kt`, `data/mule/MuleSyncEngine.kt`, `data/mule/ProgressRepository.kt`,
`data/mule/MuleGattProfile.kt`, `data/mule/MulePullClient.kt`, `data/mule/PeripheralSyncService.kt`,
`data/repository/BibValidation.kt`, `util/ExpectedRunnersText.kt`, `ui/bibsmode/BibsModeViewModel.kt`,
`ui/cpmode/CpModeViewModel.kt`.

**racemaster**: `js/views/event.js`, `js/progress-sync.js`, `js/mobile-files-shared.js`,
`server/mobile.js`, `server/routes/mobile.js`, `js/mule-ble.js`, `js/views/mobile-files-ble.js`.
