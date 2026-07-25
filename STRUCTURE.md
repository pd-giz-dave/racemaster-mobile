# STRUCTURE.md

A map of the codebase for anyone unfamiliar with Android app layout. Written for "where do I go
to change X" — not a line-by-line reference.

## The big picture

This is a single-module Gradle Android app (module `:app`) written in Kotlin, UI in Jetpack
Compose. There's no server-side code here, but Mule Mode does talk to two other endpoints: other
nearby phones over Bluetooth LE, and the RaceMaster server over HTTP. Everything a phone records
itself still lives locally first, in a local SQLite database (via Room). For Time/Bibs/CP Mode,
data flows in one direction, top to bottom:

```
Screen (Composable)  <-- observes --  ViewModel  <-- calls -->  Repository  <-- queries -->  DAO / Room DB
     |                                    |
     UI, no logic                   state + business logic
```

- **Screens** (`ui/<feature>/XScreen.kt`) draw pixels and forward user actions. They hold no
  logic of their own — just `collectAsStateWithLifecycle()` on the ViewModel's state and call
  ViewModel functions on button clicks.
- **ViewModels** (`ui/<feature>/XViewModel.kt`) hold UI state (`StateFlow`) and translate user
  intent into repository calls. This is where "what happens when you tap SPLIT" logic lives.
- **Repositories** (`data/repository/`) contain the actual business rules (transactions, counter
  bookkeeping, start/stop semantics) and talk to Room DAOs. No Android/UI types here.
- **DAOs + Entities** (`data/db/`) are the database layer: table schemas and SQL queries.

Mule Mode is different: it's a standalone background engine (`data/mule/MuleSyncEngine.kt`),
started once from `RacemasterApplication`/`PeripheralSyncService` and running for the life of the
process regardless of which screen is on top. `ui/mulemode/MuleModeViewModel` is a thin wrapper
around it, not the owner of the sync loop — closing Mule Mode's screen doesn't stop syncing.

If you're not sure where a change belongs: **does it change what's drawn on screen** → Screen.
**Does it change what happens when the operator does something** → ViewModel. **Does it change a
rule about race data** (e.g. how splits are numbered, what "in progress" means) → Repository.
**Does it change what's stored** → Entity/DAO. **Does it change how/when data reaches another
phone or the server** → `data/mule/`.

## Where things live

```
app/src/main/java/mobile/racemaster/
├── MainActivity.kt          Single Activity, hosts the whole Compose UI tree; keeps the screen
│                             on, requests the battery-optimization exemption, and dispatches
│                             external HID trigger key events (clicker/shutter remote) to
│                             whichever screen is currently listening
├── RacemasterApplication.kt Application subclass — builds the DI container once at startup and
│                             starts Mule's background PeripheralSyncService
│
├── di/                      Manual dependency injection (no Hilt/Koin/Dagger)
│   ├── AppContainer.kt        Constructs the Room DB + every repository/engine, exposes them
│   └── ViewModelFactorySupport.kt  Small helpers so ViewModels can pull the container
│
├── navigation/               Screen routing
│   ├── Routes.kt               Route string constants + route-building helpers
│   ├── RacemasterNavHost.kt     The NavHost: which route shows which Screen, mode-picker-as-root
│   │                             back-stack handling
│   └── AppEntryViewModel.kt     Decides the start destination (resumes an in-progress mode)
│
├── data/
│   ├── db/                    Room database layer — the schema
│   │   ├── RacemasterDatabase.kt   @Database: lists entities + version, exposes DAOs
│   │   ├── Converters.kt           Room type converters (for non-primitive column types)
│   │   ├── entity/                 One file per table (@Entity data class = one row)
│   │   │   ├── RaceEntity.kt          A race: label, course, location, per-mode start/stop
│   │   │   │                          timestamps and display counters
│   │   │   ├── HistoryLineEntity.kt   One row per logged event (split/bib/checkpoint entry) —
│   │   │   │                          Time, Bibs, and CP all write into this same table
│   │   │   ├── HistoryMode.kt         enum TIME/BIBS/CP — which family a HistoryLineEntity row
│   │   │   │                          belongs to, plus the L-number/S-number display formatters
│   │   │   ├── HistoryAction.kt       enum of loggable actions (START, SPLIT, FINISH, PASS,
│   │   │   │                          RETIRE, STOP, RESET, UNDO, CLOCK, ...)
│   │   │   ├── PulledRecordEntity.kt  Mule Mode's local inbox — records pulled from other
│   │   │   │                          devices over BLE, pending push to the server
│   │   │   ├── LineSyncEntity.kt      Per-line "synced to server" bookkeeping for this device's
│   │   │   │                          own history
│   │   │   └── KnownDeviceEntity.kt   Durable roster of Mule peers this phone has ever
│   │   │                              identified over BLE (survives past the current scan)
│   │   └── dao/                    One @Dao interface per table
│   │       ├── RaceDao.kt, HistoryLineDao.kt
│   │       └── PulledRecordDao.kt, LineSyncDao.kt, KnownDeviceDao.kt  (Mule Mode's tables)
│   │
│   ├── repository/            Business logic, one file per concern (not 1:1 with tables)
│   │   ├── RaceRepository.kt         Creating/updating/deleting races, race-level queries
│   │   ├── EntryLogModeEngine.kt     Shared segmented entry-logging core (record/edit/undo/
│   │   │                             stop/reset) that Bibs and CP are both built on
│   │   ├── BibsModeRepository.kt     Bibs Mode's thin wrapper over EntryLogModeEngine, plus its
│   │   │                             own Clock-marker start
│   │   ├── CpModeRepository.kt       CP Mode's thin wrapper over EntryLogModeEngine, plus its
│   │   │                             own timestamp-only (no marker row) start
│   │   ├── TimeModeRepository.kt     Stopwatch start/stop/split/undo/reset logic (its own, not
│   │   │                             on EntryLogModeEngine — Time has no bib/duplicate concept)
│   │   ├── HistoryFold.kt            `foldLatestVisible()` — collapses the append-only raw rows
│   │   │                             (edits/undos never delete, they append) down to "what's
│   │   │                             actually visible right now"
│   │   ├── BibValidation.kt          Legal bib range + duplicate-flagging rules, shared by Bibs
│   │   │                             and CP
│   │   ├── RaceProgress.kt           `isRaceActive()` — shared "can I start/clear a race?" rule
│   │   └── RaceLabels.kt             `buildRaceLabel()` — turns operator input into the stored
│   │                                 race label
│   │
│   ├── mule/                  Mule Mode: BLE mesh sync between phones + HTTP push to the server
│   │   ├── MuleSyncEngine.kt         Owns the whole background scan/pull/push loop for the life
│   │   │                             of the process, independent of any screen being open
│   │   ├── MuleRepository.kt         Orchestrates pulling (BLE) into a local inbox and pushing
│   │   │                             (HTTP) to the server; owns login/server-URL state
│   │   ├── MulePullClient.kt         BLE central: scans/connects/pulls unsynced records from a
│   │   │                             nearby Time/Bibs/CP/Mule phone
│   │   ├── MuleGattProfile.kt        Shared GATT service/characteristic UUIDs + wire record shape
│   │   ├── PeripheralSyncService.kt  Foreground service every device runs regardless of mode,
│   │   │                             advertising itself and answering pull requests
│   │   ├── MuleSyncClient.kt         Ktor HTTP client for the RaceMaster server: login, ping, push
│   │   ├── ServerStatusRepository.kt Interprets a raw ping outcome as OFFLINE/INVALID/OK
│   │   ├── SyncRecordMapping.kt      Maps a unified HistoryLineEntity row to the server's wire
│   │   │                             record shape
│   │   └── BluetoothStateRepository.kt  Whether the Bluetooth radio is currently on
│   │
│   └── settings/              App-wide preferences (DataStore, not Room — small key/value state)
│       ├── SettingsRepository.kt     Current AppMode, active race id, device name, server
│       │                             login/URL, and race name/course/location autofill history
│       └── AppMode.kt                 enum TIME / BIBS / CP / MULE
│
├── ui/                        Compose UI, one subpackage per screen/feature
│   ├── modepicker/             Mode Picker (choose Time/Bibs/CP/Mule, resume in-progress race)
│   │                            + Name Device screen
│   ├── timemode/                Time mode: stopwatch screen + `ElapsedTimeFormat.kt` helper
│   ├── bibsmode/                 Bibs mode: keypad entry screen + `HistoryActionLabels.kt`
│   ├── cpmode/                    CP mode: same screen shell as Bibs (shared components), a
│   │                              fixed Pass/Retire button pair instead of Bibs' event picker
│   ├── mulemode/                  Mule mode: nearby-devices list, Force sync/Stop auto-sync,
│   │                              Setup Server screen (device-wide server URL + login)
│   ├── racedetails/               Shared "create/edit a race" form (name, course, location,
│   │                              bib range) used by every mode
│   ├── racehistory/               Race History list + read-only detail screen, and Mule Source
│   │                              Detail (a specific pulled-in device's own records)
│   ├── help/                      In-app Help screen (Mode Picker → Help)
│   ├── components/              Shared widgets used across screens:
│   │   AppBanner (top bar), ModeScreenTopBar, DigitKeypad, EntryLogList/EntryLogEditing/
│   │   EditEntryPanel, HistoryLineRow, SplitRow, BibEntryRow, EntryModeHeaderInfo,
│   │   SyncStatusLine, StopOrResetButton, UndoLastButton, HistoryTextField
│   └── theme/                   Material 3 theme: Color.kt, Theme.kt, Type.kt (app-wide look
│                                 and feel — edit here for colors/typography, not per-screen)
│
└── util/                      Small standalone helpers not tied to one feature
    ├── Beeper.kt                Audible confirmation tone (AudioTrack-based)
    ├── ClickSound.kt             Button-press click tone that bypasses the system "Touch
    │                             sounds" setting (some phones silently mute the default one)
    ├── TickerFlow.kt             Flow emitting every 100ms, drives live elapsed-time displays
    ├── ClockTimeFormat.kt        Parses/formats Bibs Mode's Clock-row minutes:seconds offset
    ├── WallClockFormat.kt        Full human-readable timestamp, for reviewing past records
    ├── DeviceNameGenerator.kt    Generates a memorable "adjective-noun" device name
    ├── ExpectedRunnersText.kt    Bibs/CP "N still out" feedback line
    └── NetworkStatus.kt          One-shot internet-connectivity check (Mule's Setup Server
                                  prompt)

app/src/main/res/              Android resources (non-code assets)
├── values/strings.xml           App name / any hardcoded UI strings
├── values/themes.xml            XML-side theme definition (Compose theme in ui/theme/ is primary)
├── mipmap-*/, drawable*/         Launcher icon (per density) and the app banner image
└── xml/                         Backup rules (Android-required boilerplate, rarely touched)

app/src/main/AndroidManifest.xml   App/Activity declaration — edit to add permissions, change
                                    launcher activity, or register new components

app/schemas/                     Room-exported schema history (auto-generated on build when the
                                  DB version bumps — never hand-edit, don't delete old versions)

app/build.gradle.kts             Module config: applicationId, versionCode/versionName, SDK
                                  versions, dependency list, the local dev-server Gradle wiring
gradle/libs.versions.toml        Dependency version catalog — add new libraries here, not
                                  hardcoded in build.gradle.kts
```

## Test layout

- `app/src/test/` — plain JVM unit tests (`./gradlew testDebugUnitTest`, no device needed).
  Covers pure logic: `HistoryFoldTest`, `BibValidationTest`, `RaceProgressTest`,
  `HistoryModeTest`, `ClockTimeFormatTest`, `DeviceNameGeneratorTest`, `HistoryActionLabelsTest`,
  and Mule's `MuleRepositoryTest`/`MuleSyncEngineTest`/`ServerStatusRepositoryTest`/
  `SyncRecordMappingTest`.
- `app/src/androidTest/` — instrumented tests requiring a device/emulator
  (`./gradlew connectedDebugAndroidTest`). Used for repository/DAO tests against a real
  in-memory Room database (`data/repository/*Test.kt`, `data/db/dao/*Test.kt`,
  `data/settings/SettingsRepositoryTest.kt`) and any ViewModel test that needs that same real DB.

## Common "I want to..." lookups

| I want to... | Look in... |
|---|---|
| Change a screen's layout/buttons/colors | `ui/<feature>/XScreen.kt` |
| Change what happens on a button tap | `ui/<feature>/XViewModel.kt` |
| Change record/edit/undo/stop/reset rules shared by Bibs and CP | `data/repository/EntryLogModeEngine.kt` |
| Change Time Mode's stopwatch start/stop/split/undo/reset rules | `data/repository/TimeModeRepository.kt` |
| Change bib range/duplicate-flagging rules | `data/repository/BibValidation.kt` |
| Add a column to the database | `data/db/entity/`, bump `version` in `RacemasterDatabase.kt`, add a DAO query if needed |
| Add a new screen | new `ui/<feature>/` package + a route in `navigation/Routes.kt` + a `composable {}` in `RacemasterNavHost.kt` |
| Change app-wide theme colors/fonts | `ui/theme/Color.kt`, `Theme.kt`, `Type.kt` |
| Change the launcher icon or app banner image | `res/mipmap-*/`, `res/drawable-nodpi/ic_racemaster_banner.png` |
| Add a new dependency | `gradle/libs.versions.toml` then reference via `libs.*` in `app/build.gradle.kts` |
| Change what's remembered across app restarts (current mode, active race, server login) | `data/settings/SettingsRepository.kt` |
| Change how/when a phone syncs with other phones or the server | `data/mule/MuleSyncEngine.kt` (the loop) / `MuleRepository.kt` (pull+push orchestration) |
| Change the wire format sent to the RaceMaster server | `data/mule/SyncRecordMapping.kt`, `MuleSyncClient.kt` |
| Change the app version | `versionCode`/`versionName` in `app/build.gradle.kts` |
| Wire up a new repository so screens can use it | `di/AppContainer.kt` |
| Update the in-app Help screen | `ui/help/HelpScreen.kt` |
