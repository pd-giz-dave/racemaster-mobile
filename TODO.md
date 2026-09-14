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

### Cross-cutting: progress payloads must be deltas, not full replaces (build first, in phase 2)

`progress.json` is currently full-replace both directions (`server/mobile.js` `writeProgress`;
`GET .../progress` only shortcuts a whole-payload-unchanged case). The BLE
`PROGRESS_CHARACTERISTIC_UUID` write (`js/mule-ble.js:571` `deliverProgress()`) sends the full
payload every time too. Device-*record* sync is already delta-based (merge by `recordUuid`/
lineNumber) — progress needs the same treatment before phase 3's relay work can build on it.

- [ ] Give each `ProgressEntry` a per-entry change marker (e.g. `updatedAt`), stamped by
      whichever side computes a real change to that entry.
- [ ] Push (web-app → server): `js/progress-sync.js` sends only entries changed since its own
      last successful push; server merges by `bibNumber` into the stored `progress.json`
      (mirrors `POST /api/mobile/:raceLabel`'s existing merge-by-`recordUuid` shape).
- [ ] Fetch (`GET /api/mobile/:raceLabel/progress`): extend `knownGeneratedAt` into a real
      `?since=<cursor>` that returns only entries with `updatedAt > since` (keep today's
      `{unchanged:true}` shortcut for zero-changed). `ProgressRepository.refreshFromServer`
      merges by `bibNumber` instead of replacing wholesale.
- [ ] BLE delivery: `deliverProgress()` diffs against the receiver's `DeviceInfo.progressGeneratedAt`
      before sending; receiver merge uses the same by-`bibNumber` logic as the HTTP path (one
      merge function, two transports).
- [ ] Unit tests for the new merge/diff logic on both sides.

### Phase 1 — mobile race setup simplification (mobile-only)

- [ ] Add `SETUP_RACE` route/button to `SetupDeviceScreen.kt` (`navigation/Routes.kt`,
      `RacemasterNavHost.kt` ~line 103-111), alongside Setup Name / Setup Server / Options.
- [ ] New `ui/racesetup/SetupRaceScreen.kt` + `SetupRaceViewModel.kt` (model on
      `NameDeviceScreen.kt`/`NameDeviceViewModel.kt`): race name (free text incl. seniors/juniors
      suffix, reuse `isValidRaceName`) + location (existing field/validation). No course chips,
      no first-bib/runner-count fields.
- [ ] `SetupRaceViewModel.save()` calls `RaceRepository.startNewRace(name, course = "", location, ...)`
      directly — no course resolution step, no `courses`/`bibsRangeStart`/`bibsRangeCount`
      populated.
- [ ] Remove `RaceRepository.resolveCourseRace()`, `cloneTemplate()`'s course-switch use,
      `ui/components/CoursePickerDialog.kt` and its 3 Start-button call sites
      (`TimeModeScreen.kt:123`, `BibsModeScreen.kt:113`, `CpModeScreen.kt:113`).
- [ ] Remove the "End recording" choice in `StopOrResetButton.kt` (~line 97),
      `RaceRepository.endRecordingForCourse()`, and its 3 call sites (`TimeModeViewModel.kt:255`,
      `BibsModeViewModel.kt:405`, `CpModeViewModel.kt:354`). Update `HelpScreen.kt` (~line 127,
      194) which documents "End recording" to operators.
- [ ] Keep the resume-in-place check (`if (target.xModeStartedAtMillis != null) resumeXMode(id)
      else startXMode(id)`, e.g. `BibsModeViewModel.kt:279`) — re-home it so pressing Start on
      the device's current active race (no dialog) goes through this check directly.
- [ ] New "Resume" action on `RaceHistoryScreen.kt`'s `LocalRace` rows for a non-active, stopped
      (not reset) past race on this device → `RaceRepository.switchActiveRace(raceId)` then the
      resume-or-start check on next Start press.
- [ ] Narrow `RaceDetailsScreen`/`RaceDetailsViewModel` to a rename-only "edit this race" screen
      (name + location only), reusing `identityFieldsEnabled`/`updateRaceDetails()`.
- [ ] Add the device-file-write-on-setup-complete call site in `SetupRaceViewModel.save()` (built
      together with phase 2's actual push implementation, see below — this checkbox is the hook,
      phase 2 is the mechanism).
- [ ] Leave `RaceEntity.course`/`courses`/`bibsRangeStart`/`bibsRangeCount` columns in place,
      unpopulated (Room migration deferred to end of phase 4).
- [ ] Grep for any remaining `resolveCourseRace`/`cloneTemplate`/`endRecordingForCourse`/
      `CoursePickerDialog` references before considering this phase done.
- [ ] **Verify**: `./gradlew testDebugUnitTest`/`./gradlew check` pass; manually verify Setup
      Race → Start → Stop → Race History "Resume" → Start again resumes mid-segment (not a fresh
      start) for all three modes.
- [ ] Commit phase 1 (local only, no push).

### Phase 2 — internet-mode workflow (both repos)

#### Web-app (`racemaster`)

- [ ] New route `POST /api/mobile/:owner/:raceLabel/progress/touch` (`server/routes/mobile.js`)
      + `touchProgress(username, raceLabel)` in `server/mobile.js` — rewrites existing
      `progress.json` with only `generatedAt` refreshed, no `entries` re-sent, 404 if none exists
      yet.
- [ ] "Activate Race" button on `js/views/event.js` (Event Settings) — one touch-route call per
      course this event has, via `deriveRaceLabel(state.event, course)`
      (`js/mobile-files-shared.js`). Must never touch a race outside the currently loaded event.
- [ ] New route `GET /api/mobile/races?maxAgeDays=N` (`server/routes/mobile.js`) +
      `getAvailableRacesForUser(username, maxAgeDays, adminAccess)` in `server/mobile.js` — lean
      shape `{raceLabel, raceName, raceDate, generatedAt}[]`, filtered server-side to
      `generatedAt` within `maxAgeDays`, sorted newest-first. No `devices`/`lines`/`recordCount`.

#### Mobile app (`racemaster-mobile`)

- [ ] `data/mule/MuleSyncClient.kt`: add `getAvailableRaces(baseUrl, token, maxAgeDays)` calling
      the new route, passing `SettingsRepository.raceStaleAfterDays`.
- [ ] Extend `SetupRaceScreen`/`SetupRaceViewModel` with an online branch: after location entry,
      call `getAvailableRaces`, present as a picker (reuse `CoursePickerDialog.kt`'s list-picker
      chrome as the template before it's deleted in phase 1, or recreate the same shape).
- [ ] Race-id-then-progress sequencing: only call `startNewRace()` at the moment of picking (not
      while browsing), then `ProgressRepository.refreshFromServer(...)` under the new `raceId`.
- [ ] Device-file-on-setup: trigger `MuleRepository.pushToServer()`'s existing push
      (`POST /api/mobile/{raceLabel}`) once with an empty record array, immediately on
      setup-complete (both picked-from-server and typed-manually cases).
- [ ] Server-unreachable fallback: share one "server unreachable → flip to mule broadcast"
      function between the setup-time scan/push and the ongoing sync loop's existing
      401/403-reauth path (`MuleRepository.pushToServer()` ~line 412).
- [ ] **Verify**: end-to-end against the local dev server (`./gradlew devServer`) — Activate Race
      bumps `generatedAt` only for the current event's race label(s); `GET /api/mobile/races`
      returns only non-stale races with no device/line payload; setup picker shows results
      newest-first; picking one creates the local race + device file server-side with an empty
      initial record array. Confirm via devtools/BLE logs that only changed entries move once
      real data exists.
- [ ] Commit phase 2 (local only, no push).

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
