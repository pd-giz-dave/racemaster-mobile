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

### Phase 3 — BT-mule-only workflow (both repos)

Highest-risk phase — genuine wire-protocol extension, not just app logic (the browser can only
ever write to the mule it's directly connected to; reaching a relayed device requires the mule to
cache-and-forward on its own next pull cycle, recursively through further mule hops).

#### Web-app (`racemaster`)

- [ ] "Adopted devices" selection state, keyed by `originDeviceId` (never `raceLabel`), modeled
      on `js/mobile-files-shared.js`'s existing `selectedKeys`/`rowKey` pattern. Persist in
      `localStorage` (new key), scoped per logged-in user.
- [ ] UI: extend `js/views/mobile-files-ble.js` to render the relay manifest with a per-entry
      adopt toggle (reuse `mobile-files-devices.js`'s row conventions).
- [ ] Rework `deliverProgress()`/`pullFromConnectedPhone()`/`currentRaceProgressContext()`
      (`js/views/mobile-files-ble.js` ~line 326): for each adopted relay entry, look up that
      origin's true-race payload, diff against its reported checkpoint, write only changed
      entries tagged `targetDeviceId = entry.originDeviceId`, bypassing the `raceLabel`-equality
      gate for adopted targets only.

#### Mobile app (`racemaster-mobile`)

- [ ] Manual race-name entry (offline branch of `SetupRaceScreen`): free-text name field, existing
      `PeripheralSyncService` advertisement (no protocol change needed here).
- [ ] `ProgressRepository.kt`: add an `originDeviceId`-keyed lookup for this phone's own race
      progress, plus a new **targeted-relay inbox** table (mirrors
      `PeripheralSyncService`'s existing pulled-records-inbox pattern) holding progress this
      device is only forwarding, keyed by `targetDeviceId`, stored in delta form.
- [ ] Wire extension: add `targetDeviceId: String?` to the progress-delivery payload on
      `PROGRESS_CHARACTERISTIC_UUID` (`MuleGattProfile.kt`), separate from `PullRequest`. A phone
      receiving a payload targeted at someone else stores it in the inbox and starts advertising
      it via its own relay-manifest mechanism (generalize `relayManifestVersion`/
      `computeRelayManifestPayload` in `PeripheralSyncService.kt`) so it keeps propagating through
      further hops.
- [ ] `MuleSyncEngine.kt` relay loop (~line 882-926): for each `RelayManifestEntry`, check the
      targeted-relay inbox and pass a matching payload as `progressToDeliver`/`progressRaceLabel`.
- [ ] `MulePullClient.kt` (~line 507): bypass the receiving-peripheral's-own-`raceLabel`-match
      gate for targeted delivery.
- [ ] Evict a targeted-relay inbox entry on delivery confirmation (mirror
      `backfillSinkAck`/`markRelayedRecordsSynced`) or after `raceStaleAfterDays`.
- [ ] Adoption trigger: on receiving a self-targeted payload whose identity doesn't match the
      current race, update the existing local `RaceEntity` row in place (same `id`, generalize
      `updateRaceDetails()`/`RaceDao.updateDetails()` to rewrite `label` too) — no history-row
      migration needed (`HistoryLineEntity.raceId` is a stable FK).
- [ ] Confirm `MuleRepository.pushToServer()`'s existing "read label fresh from `RaceEntity`"
      behavior naturally reflects adoption server-side once reachable (no new code expected here
      — verify only).
- [ ] **Verify**: needs ≥3 devices (or emulated BLE) — a mule directly connected to the web-app, a
      second mule relaying through the first, a leaf device on the second mule. Adopting the leaf
      device in the web-app UI eventually delivers progress after two hops; only changed entries
      transmitted per hop; a manually-named race auto-adopts (migrates in place, all screens
      update); targeted-relay inbox entries clear once delivered.
- [ ] Commit phase 3 (local only, no push).

### Phase 4 — bib/CP auto-expectation (mobile-only, depends on phases 1-3)

- [ ] `data/repository/BibValidation.kt`: remove `isBibInLegalRange`/`rangeErrorMessage`/
      `rangeWarningMessage`/`outstandingBibs(entries, rangeStart, rangeCount)`. Add starters /
      retirees / finishers / outstanding-at-this-location functions from `ProgressRepository`'s
      `ProgressEntry` list joined against local `HistoryLineEntity` actions, reusing the join
      shape `distinctAccountedForBibs` already uses.
- [ ] Derive CP1..CPn..Finish order from the distinct set of CP labels already appearing across
      all phones' `ProgressEntry.cpTimes` keys for this race — no new course-definition entity.
- [ ] `util/ExpectedRunnersText.kt`: drop the "First bib X" echo; source outstanding-count/missing
      list from the new set-based logic (keep the existing "N of M / missing list when <10" UI
      shape).
- [ ] `ui/bibsmode/BibsModeViewModel.kt` (~line 184-229) / `ui/cpmode/CpModeViewModel.kt`
      (~line 169-208): replace range-based outstanding computation with the new functions. A bib
      outside the expected set is flagged (not blocked), same treatment as a duplicate. Duplicate
      detection itself unchanged.
- [ ] Room migration: drop `RaceEntity.course`/`courses`/`bibsRangeStart`/`bibsRangeCount` now
      that nothing reads them.
- [ ] **Verify**: outstanding/starters/retirees/finishers lists update live as progress records
      arrive via both HTTP and BLE; an unexpected bib is flagged, not blocked, on entry; full
      `./gradlew check`.
- [ ] Commit phase 4 (local only, no push).

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
