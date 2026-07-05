# Racemaster Mobile — Implementation TODO

Tracks progress against `/home/dave/.claude/plans/sunny-weaving-planet.md`.

## Phase 1 — Data layer, Time mode, Bibs mode

- [x] Gradle: add Room, KSP, Navigation Compose, DataStore Preferences, coroutines, bumped lifecycle to version catalog + build files
- [x] Data layer: `RaceEntity`, `FinishSplitEntity`, `BibEntryEntity`/`BibEntryType`, `Converters`, DAOs, `RacemasterDatabase`
- [x] Repositories: `TimeModeRepository`, `BibsModeRepository`, `RaceRepository` (transactional counters), `SettingsRepository` (DataStore: `AppMode`, active race id)
- [x] Manual DI: `AppContainer`/`DefaultAppContainer`, `RacemasterApplication`, registered in `AndroidManifest.xml`
- [x] Navigation: `Routes`, `RacemasterNavHost` (mode-persistence-driven start destination), `ModePickerScreen`
- [x] Time mode: `TimeModeViewModel` + `TimeModeScreen` (big FINISH button, undo-last with confirm dialog, newest-first list, auto-scrolls to show new entries)
- [x] Bibs mode: `BibsModeViewModel` + `BibsModeScreen` (`DigitKeypad`, `UndoLastButton`, Start/Finish/Retire submit buttons, newest-first list, auto-scrolls to show new entries)
- [x] Mule mode: placeholder `MuleModeScreen` ("coming soon"), wired into nav/mode picker
- [x] Tests: `TimeModeRepositoryTest`, `BibsModeRepositoryTest`, `SettingsRepositoryTest` (in-memory Room DB, androidTest) — 9/9 passing
- [x] Build verification: `./gradlew assembleDebug` succeeds
- [x] Test verification: `./gradlew connectedDebugAndroidTest` passes (verified on a local AVD)
- [x] Manual smoke test: Time mode finish/undo, Bibs mode Start/Finish/Retire/undo sequencing, mode switching, New Race — all verified on-device

## Phase 1.1 — Stopwatch semantics, race naming, Stop, race history

- [x] Time mode reworked as a real stopwatch: Start screen before the clock begins, live-updating elapsed display, splits recorded relative to start (not wall-clock), FINISH renamed to SPLIT
- [x] Stop capability: `timeModeStoppedAtMillis` on `RaceEntity`, freezes the display, disables SPLIT and relabels it STOPPED, screen stays for review
- [x] Race naming: `NewRaceDialog` (text field) used everywhere a race is created (first mode pick and every "New Race" action); stored label is `{name}-{yy-MM-dd}`
- [x] Review Past Races: new picker action → `RaceHistoryScreen` (list of races) → `RaceHistoryDetailScreen` (read-only Time splits + Bib entries for that race)
- [x] Room schema bumped to v3 (`timeModeStartedAtMillis`, `timeModeStoppedAtMillis`) with `fallbackToDestructiveMigration` — fine pre-release, no real user data at stake
- [x] Tests: 9/9 still passing; manually verified naming dialog, live stopwatch, Stop freeze/disable, and race history on-device

## Phase 1.2 — Start/Stop marker splits and per-row label editing

- [x] `startStopwatch()` inserts a fixed split `#0` labeled "Start" (elapsed 0, doesn't consume the counter)
- [x] `stopStopwatch()` inserts a final split labeled "Stop" (consumes the next counter number, matches the frozen elapsed time)
- [x] Undoing the Stop marker resumes the race (clears `timeModeStoppedAtMillis`); undoing the Start marker (only reachable once every real split is undone) returns to the Start screen
- [x] Inline, non-modal label editing on any split row (tap row → in-row text field + Save/Cancel) — verified SPLIT stays usable while a label is being edited
- [x] Room schema bumped to v4 (`FinishSplitEntity.label`), Race History detail screen shows labels too
- [x] Editor pinned above the list (not inline in the row): shows "Editing #N <time>" so the original line stays visible, and new splits recorded mid-edit appear underneath as usual instead of disturbing the row being edited
- [x] Tests: 14/14 passing (5 new repository tests for Start/Stop marker + undo semantics); manually verified live on-device

## Phase 1.3 — Always-visible app banner

- [x] `AppBanner` composable: solid brand green (`#1A6E3C`, matched to the `/home/dave/racemaster` web app's `--accent`), white icon + "RaceMaster Mobile" wordmark, ~48dp tall, extends behind the status bar
- [x] Icon reused directly from the web app (`icon-192.png` — runner ascending a hill), copied into `res/drawable-nodpi/ic_racemaster_banner.png`
- [x] Wired as the outer `Scaffold`'s `topBar` in `MainActivity`, so it's always visible above every screen (Mode Picker, Time/Bibs/Mule mode, Race History) rather than per-screen
- [x] Removed `ModePickerScreen`'s now-redundant "Racemaster Mobile" title bar (the app banner covers that); per-screen bars (race name, New Race/Mode, Back) still render below it for context
- [x] Tests: 14/14 still passing; verified live on-device across Mode Picker and Time Mode — banner stays put, no double status-bar padding or color mismatch

## Phase 1.4 — Layout fixes, unified stopwatch screen, RESET, launcher icon

- [x] Fixed double status-bar padding: every per-screen `TopAppBar` (Time/Bibs mode, Mule mode, Race History screens) now passes `windowInsets = WindowInsets(0,0,0,0)` since the persistent `AppBanner` already reserves that space
- [x] Keyboard no longer covers the split label editor: `imePadding()` on the screen content plus a scrollable header that auto-scrolls the editor into view when it opens
- [x] SPLIT button shrunk (160dp → 96dp); Stop Race + Undo Last merged into one row
- [x] Dropped the separate Start screen — Time mode is now one unified layout: blank clock + "START" button before starting, ticker + "SPLIT" once running, exactly like before otherwise
- [x] STOP → RESET: pressing Stop now keeps the secondary button enabled and relabels it "RESET" (was hidden before); pressing Reset wipes all splits for the race and returns to the pristine pre-start state, confirmed via dialog (`TimeModeRepository.resetStopwatch`, new `FinishSplitDao.deleteAllForRace` + `RaceDao.resetTimeMode` queries)
- [x] Custom launcher icon: generated adaptive icon (foreground PNG + monochrome silhouette + solid brand-green background) and legacy mipmap icons (square + round, all densities) from the web app's `icon-512.png`, replacing the default Android Studio template icon
- [x] Tests: 15/15 passing (added `resetStopwatchClearsSplitsAndClockState`); manually verified full Start→Split→Stop→Reset flow and the new launcher icon on-device

## Phase 1.5 — Guard against orphaning an in-progress race

- [x] `isRaceInProgress()` helper: a race is "in progress" if Time mode's stopwatch is started-but-not-stopped, or Bibs mode has any recorded entries (Bibs has no stop signal of its own)
- [x] "New Race" is now disabled (greyed out, not just hidden) on both Time Mode and Bibs Mode while their shared active race is in progress, so it can't be orphaned from either screen
- [x] Removed the confirm dialog on "Mode" — tapping it now switches back to the Mode Picker immediately
- [x] Mode Picker shows an in-progress race's status when relevant: race name, split/bib counts recorded, and "You can continue by going back to `<mode>`." (the mode the operator came from)
- [x] Tests: 15/15 passing; manually verified New Race greyed out mid-race, instant mode switch, and the picker's in-progress summary card on-device

## Phase 1.6 — Keep screen awake, block exit mid-race

- [x] `MainActivity` sets `FLAG_KEEP_SCREEN_ON` on the window at `onCreate`, so the screen never sleeps while the app is in the foreground, on any screen
- [x] `AppEntryViewModel.raceInProgress`: app-wide version of `isRaceInProgress`, observing the active race regardless of which screen is showing
- [x] `RacemasterNavHost` registers a `BackHandler(enabled = raceInProgress)` ahead of the `NavHost` (so in-app back navigation, e.g. Race History detail → list, is untouched) that swallows the back press and shows a toast once there's nothing left for the NavHost to pop — exactly the case that would otherwise exit the app
- [x] Tests: 15/15 passing; manually verified back press is blocked (with toast) while the stopwatch is running, and exits normally once Stop is pressed

## Phase 1.7 — Lock task mode, external trigger, audible beep

- [x] Verified no double-tap/long-press gesture disambiguation exists anywhere near the SPLIT button (grepped for `combinedClickable`/`onLongClick`/`detectTapGestures`/debounce — none found); plain `Button.onClick` plus the already-transactional `recordSplit()` means two fast taps always produce two distinct splits, confirmed live (two `adb input tap`s ~30ms apart → splits #3/#4, each its own timestamp)
- [x] Home/Overview blocked while a race is in progress: `RacemasterNavHost` calls `Activity.startLockTask()`/`stopLockTask()` off the same `raceInProgress` signal used for the back-press guard (Android's built-in Screen Pinning — no device-owner/MDM enrollment needed); verified via `adb shell input keyevent KEYCODE_HOME`/`KEYCODE_APP_SWITCH` that focus stays on `MainActivity` while pinned, and returns to normal once Stop is pressed
- [x] Race data already persists with the app not running — confirmed `DefaultAppContainer` uses `Room.databaseBuilder(context, ..., "racemaster.db")` (disk-backed, not in-memory), so splits/bibs survive app restarts, backgrounding, and reboots with no code change needed
- [x] External trigger support: `MainActivity.onExternalSplitTrigger` + a `dispatchKeyEvent` override recognize a curated set of common HID keycodes (Enter, Space, Page Up/Down, DPad Center, Volume Up/Down); `TimeModeScreen` registers itself as the target while shown, only firing while the race is actually running. This means any USB (via OTG) or Bluetooth clicker/pedal that enumerates as a HID keyboard — presenter remotes, camera shutter remotes, foot switches — works immediately with no pairing code of our own; confirmed live via `adb shell input keyevent KEYCODE_ENTER` recording a real split
- [x] Audible beep: `util/Beeper.kt` generates a real 150ms 1kHz sine tone (rapid rise/solid sustain/rapid fall via a 3ms fade, no asset file needed) via `AudioTrack`, played on `AudioAttributes.USAGE_ALARM` and forced to max `STREAM_ALARM` volume every time — exempt from silent/vibrate mode and media-volume limits, and audible over outdoor wind/crowd noise; 1kHz keeps it below where age-related hearing loss usually bites first. Wired into `TimeModeViewModel.recordSplit()` and `BibsModeViewModel.submit()`, released in each ViewModel's `onCleared()`. Went through a few iterations live with the user: `ToneGenerator.TONE_PROP_BEEP` turned out to be a fixed-envelope click that ignores requested duration; `TONE_DTMF_1` is a true continuous tone but is two simultaneous frequencies (that's what DTMF is) and sounded doubled/warbly — a hand-generated single-frequency `AudioTrack` buffer was the fix
- [x] Tests: 15/15 passing

## Phase 2 — Bibs Mode rework: legal bib range, duplicate flagging, unified Event/Log, editable rows

- [x] `BibEntryType` extended from `{START, FINISH, RETIRE}` to add `IGNORE, SENIORS, JUNIORS, MALE, FEMALE, CLOCK, STOP`; `BIB_REQUIRED_TYPES` constant marks which types carry a real bib number (Start/Finish/Retire only)
- [x] `BibEntryEntity.bibNumber` now nullable (null for the no-bib types), `splitNumber` now non-nullable (every row gets one, including Retire — a deliberate behavior change from before), added `note`; `RaceEntity` gains `bibsRangeStart`/`bibsRangeCount`/`bibsModeStoppedAtMillis`; Room bumped to v5
- [x] `BibsModeRepository.createRaceWithClockMarker()` atomically inserts the race row + a fixed "Clock" split #0 (mirrors `TimeModeRepository.startStopwatch`'s pattern) — the only entry point for creating a Bibs race; `recordEntry`/`updateEntry`/`deleteMostRecent` reworked around the new always-has-a-splitNumber model; added `stopBibsMode`/`resetBibsMode`
- [x] `NewBibsRaceDialog`: prompts for race name + first bib number + runner count (defines the legal range) instead of just a name; `ModeScreenTopBar` reworked to take a dialog **slot** so Time/Mule mode's plain `NewRaceDialog` needed zero changes
- [x] `BibValidation.kt`: `isBibInLegalRange`, `findDuplicateSplitRefs` (cross-references every entry sharing a bib+type), `countDuplicateExtras` (pair=1, three-of-a-kind=2, two separate pairs=2 — "extra occurrence" semantics, not raw flagged-row count)
- [x] Bibs Mode screen reworked: single amalgamated Log button (label reflects the pending event, resets to "Finish" after every log) + Event picker (Finish/Start/Retire/Ignore/Seniors/Juniors/Male/Female — Clock and Stop deliberately excluded, they're not operator-selectable) + Stop/Reset (extracted `StopOrResetButton` now shared with Time Mode) + Undo, all on one row; tap any row to edit its bib/event/note, or its offset time for the Clock row
- [x] Tests: new `BibValidationTest` (JVM, range boundaries + dup cross-refs + extras-counting semantics), rewrote `BibsModeRepositoryTest` for the new counter/undo model (Retire now consumes the counter, Clock/Stop have special undo handling, `createRaceWithClockMarker` atomicity)
- [x] Manual verification live on-device: range rejection, duplicate cross-referencing and un-flagging on edit, Start+Finish same bib not flagged, Stop/Reset/Undo-resumes-logging all confirmed

## Phase 2.1 — Bibs Mode UX fixes, Time Mode parity, external trigger

- [x] Keyboard no longer covers the row-edit panel: same scrollable-header-with-auto-scroll pattern as Time Mode's label editor, confirmed live (edit panel's Save/Cancel stay visible above the keyboard)
- [x] Button row wrapping fixed properly: first tried shrinking the Undo Last font, but on the physical phone's display density even "Finish" was wrapping — root cause was Material's default 24dp/side button content padding, not font size; fixed by adding a `contentPadding` override to `StopOrResetButton`/`UndoLastButton` and cutting it to 4dp for the whole Bibs button row instead
- [x] `DigitKeypad` buttons enlarged 44dp → 52dp (the backspace glyph was being clipped at 44dp)
- [x] "Next: #N" caption added to Time Mode too, matching Bibs Mode; Bibs Mode's line also gained a right-aligned running duplicate count ("N dups")
- [x] Clock row edit label reads "Offset time (m:ss or ss)"; `parseMinutesSeconds` now also accepts a single number as seconds (not capped at 59 — "90" → "1:30"), matching the reference web app's offset convention
- [x] External trigger extended to Bibs Mode (same `MainActivity.onExternalSplitTrigger` mechanism Time Mode already used, registered while Bibs Mode is showing, gated on `canSubmit`). User reported volume-down and their Bluetooth camera trigger not working on the physical phone; captured raw input via `adb shell getevent -lt` and confirmed the trigger genuinely sends `KEY_VOLUMEDOWN`, then added temporary `Log.d` diagnostics to `MainActivity.dispatchKeyEvent` to rule out a code bug — a clean reinstall resolved it (stale app/Bluetooth state, not a bug; the dispatch code was untouched throughout), confirmed working in both modes, diagnostics removed afterward

## Phase 2.2 — Click sound on every button press

- [x] First attempt: centralized `LocalIndication` override (a custom `Indication`/`IndicationNodeFactory` delegating to the real ripple, playing a click sound on `PressInteraction.Release`) wired once in `RacemasterMobileTheme`. Compiled fine but produced zero sound; temporary diagnostic logging confirmed Material3's `Button`/`OutlinedButton`/`TextButton` don't consult `LocalIndication` in this Compose BOM (2026.02.01) — they hardcode their own ripple internally
- [x] Reverted to a `withClickSound()` composable helper (`util/ClickSound.kt`, wraps `onClick` with `View.playSoundEffect(SoundEffectConstants.CLICK)`) applied explicitly at every `onClick`/`clickable` call site across every screen, dialog, and shared component (~30 sites) — confirmed audible live on-device in all modes; respects the phone's system Touch Sounds setting like any other Android button

## Phase 2.3 — Race History duplicate display, bib number bounds, Help screen

- [x] Race History detail screen now reuses `findDuplicateSplitRefs` and shows the same "dup of #N" flag on archived bib entries as the live Bibs Mode screen (previously read-only display had no dup indication)
- [x] Bib numbers capped at 1–999 (3 digits): `BibsModeViewModel.MAX_BIB_DIGITS` 4 → 3, edit-panel bib field capped to 3 digits, `NewBibsRaceDialog` validates first-bib-number and the resulting range end both fall within 1–999 before enabling Create
- [x] Help screen added: `ui/help/HelpScreen.kt`, new `Routes.HELP`, opened via a "Help" button next to "Review past races" on the Mode Picker; covers an app overview (what the two-phone Time+Bibs workflow is for) plus Time Mode, Bibs Mode (starting a race, logging, duplicates, editing rows, stop/reset), external triggers, and general navigation

## Phase 3 — Mule Mode Phase 1: BLE pickup from Time/Bibs phones + internet sync

Tracks progress against `/home/dave/.claude/plans/eager-coalescing-quilt.md`. Deliberately
scoped to Mule ↔ Time/Bibs over BLE + Mule → racemaster server over HTTP; mule-to-mule
"chain home" relay and a BLE SYNC receiver are follow-up work (see "Later phases" below).

- [x] Terminology rationalization: Time Mode's `FinishSplitEntity.label` renamed to `note`
  throughout (`TimeModeRepository.updateNote`, `TimeModeViewModel`, `TimeModeScreen`'s
  editor field, `RaceHistoryDetailViewModel`/`Screen`) to match Bibs Mode's existing `note`
  field, since both are carried over the same sync wire format
- [x] Data model: `FinishSplitEntity`/`BibEntryEntity` gain a `recordUuid` (stable
  cross-device identifier — local Room autoincrement ids collide once records from
  multiple phones are merged) and `syncedAtMillis`; `RaceEntity` gains `deviceRole`
  (`"TIME"`/`"BIBS"`/`"MULE"`, threaded through every race-creation call site); a
  `deviceId` UUID persisted once per phone via `SettingsRepository`; Room bumped to v6
- [x] New dependencies: **Kable 0.42.0** for BLE central (pinned below the latest 0.43.1,
  which requires Kotlin 2.4.0 and broke compilation against this project's Kotlin 2.2.10),
  **Ktor 3.4.3** client + **kotlinx.serialization 1.11.0** for HTTP/JSON (Ktor 3.5.x pulled
  the same newer-stdlib conflict, so pinned to the last compatible release)
- [x] `data/mule/` package: `MuleGattProfile` (custom GATT service + 4 characteristics —
  device-info read, control write, chunked-notify data stream terminated by a single
  zero-byte marker, ack write), `SyncRecordMapping` (maps `FinishSplitEntity`/`BibEntryEntity`
  into the wire/server record shape, elapsed-time-relative-to-race-start formatted
  `HH:MM:SS` to match the racemaster server's existing finisher convention),
  `PeripheralSyncService` (foreground service every mode runs — GATT server + BLE
  advertising, answers a pull request by streaming this device's own unsynced
  splits/entries), `MulePullClient` (Kable-based central: scan/connect/pull/ack),
  `MuleSyncClient` (Ktor: login/list-datasets/push), `MuleRepository` (orchestrates
  pull→local inbox→push, exposes unsynced-count/last-synced status Flows),
  `PulledRecordEntity`/`PulledRecordDao` (Mule's local inbox, dedup-by-`recordUuid` via a
  unique index + `OnConflictStrategy.IGNORE`)
- [x] Server (`/home/dave/racemaster/server.js`): new `POST /api/data/:owner/:fullName/finishers`
  — additive-only (not the whole-blob `PUT`, which would risk clobbering concurrent web-UI
  edits), idempotent by `recordUuid`, same bearer-token auth as every other route; carries
  the `note` field through even though the existing web UI doesn't display it yet
- [x] UI: shared `SyncStatusLine` ("N unsynced · last synced HH:MM") wired into Time, Bibs,
  and Mule mode; Mule Mode screen now real — nearby-device discovery list with per-device
  pull, login form, dataset picker, push action
- [x] Runtime Bluetooth permission request flow in `MainActivity` (none existed anywhere in
  the app before this), gates `PeripheralSyncService` startup on API 31+ scan/connect/advertise
  grants (and legacy `ACCESS_FINE_LOCATION` pre-31)
- [x] Tests: `SyncRecordMappingTest` (JVM, entity→wire mapping + elapsed-time formatting),
  `PulledRecordDaoTest` (androidTest, dedup-on-insert + unsynced-count + mark-synced)
- [x] Verification: full unit test suite passing; live on an emulator confirmed the GATT
  server registers with the exact expected service/characteristic UUIDs and starts
  advertising with zero errors (via logcat), the foreground service runs without crashing,
  Room's v6 migration is correct (confirmed via direct `sqlite3` inspection over `run-as`),
  and the sync status line correctly reflects live database state while creating a race and
  logging entries through the real UI. **Not verified**: actual BLE communication between
  two devices, and the end-to-end pull→push flow — attempted a two-emulator cross-instance
  BLE test (recent Android Emulator versions support this) but the second instance wouldn't
  finish booting in this environment after ~25 minutes, likely resource contention; cleaned
  up all emulator/test-server processes afterward. **A real two-phone field test (one Bibs
  Mode, one Mule Mode, log some entries, pull, log in, push) should be done before relying
  on this for a real event.**

## Later phases (not started)

- [ ] Mule-to-mule "chain home" relay: multi-hop store-and-forward between mule devices
  (stable dedup, loop prevention) so mules can pass data to each other, not just pull from
  Time/Bibs phones and push to the internet
- [ ] BLE SYNC receiver: either a Web-Bluetooth page in the racemaster web app (Chrome/Edge
  only — browsers can't act as a BLE peripheral, so the phone would have to be the
  peripheral and the browser the central, with an unavoidable manual "Connect" click) or a
  separate small receiver app writing a file for later import (racemaster's own `ToDo.MD`
  already lists CSV import as planned separately, so this could piggyback on that)
- [ ] Add CP mode (checkpoint) like BIBS mode but has a CP name, also logs time as well as bib 
  (2-in-1), time not so critical at CP but useful for runners to compare, also needs a 
  Racemaster tweak to show CP times in results
