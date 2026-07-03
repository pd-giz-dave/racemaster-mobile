# STRUCTURE.md

A map of the codebase for anyone unfamiliar with Android app layout. Written for "where do I go
to change X" — not a line-by-line reference.

## The big picture

This is a single-module Gradle Android app (module `:app`) written in Kotlin, UI in Jetpack
Compose. There's no networking and no server-side code here — everything lives on the phone in
a local SQLite database (via Room). Data flows in one direction, top to bottom:

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

If you're not sure where a change belongs: **does it change what's drawn on screen** → Screen.
**Does it change what happens when the operator does something** → ViewModel. **Does it change a
rule about race data** (e.g. how splits are numbered, what "in progress" means) → Repository.
**Does it change what's stored** → Entity/DAO.

## Where things live

```
app/src/main/java/mobile/racemaster/
├── MainActivity.kt          Single Activity, hosts the whole Compose UI tree
├── RacemasterApplication.kt Application subclass, builds the DI container once at startup
│
├── di/                      Manual dependency injection (no Hilt/Koin/Dagger)
│   ├── AppContainer.kt        Constructs the Room DB + all repositories, exposes them
│   └── ViewModelFactorySupport.kt  Small helpers so ViewModels can pull the container
│
├── navigation/               Screen routing
│   ├── Routes.kt               Route string constants
│   ├── RacemasterNavHost.kt     The NavHost: which route shows which Screen, back/lock-task handling
│   └── AppEntryViewModel.kt     Decides the start destination (resumes an in-progress mode) + app-wide "race in progress" flag
│
├── data/
│   ├── db/                    Room database layer — the schema
│   │   ├── RacemasterDatabase.kt   @Database: lists entities + version, exposes DAOs
│   │   ├── Converters.kt           Room type converters (for non-primitive column types)
│   │   ├── entity/                 One file per table (@Entity data class = one row)
│   │   │   ├── RaceEntity.kt          A race: label, created time, per-mode counters/clock state
│   │   │   ├── FinishSplitEntity.kt   One row per recorded split (Time mode)
│   │   │   ├── BibEntryEntity.kt      One row per bib event (Bibs mode)
│   │   │   └── BibEntryType.kt        Enum: Start/Finish/Retire
│   │   └── dao/                    One file per table, @Dao interface = the SQL for that table
│   │       ├── RaceDao.kt, FinishSplitDao.kt, BibEntryDao.kt
│   │
│   ├── repository/            Business logic, one file per concern (not 1:1 with tables)
│   │   ├── RaceRepository.kt         Creating races, race-level queries
│   │   ├── TimeModeRepository.kt     Stopwatch start/stop/split/undo/reset logic
│   │   ├── BibsModeRepository.kt     Bib start/finish/retire/undo logic
│   │   ├── RaceProgress.kt           `isRaceInProgress()` — shared "can I start a new race?" rule
│   │   └── RaceLabels.kt             `buildRaceLabel()` — turns operator input into the stored label
│   │
│   └── settings/              App-wide preferences (DataStore, not Room — small key/value state)
│       ├── SettingsRepository.kt     Reads/writes current AppMode + active race id
│       └── AppMode.kt                 enum TIME / BIBS / MULE
│
├── ui/                        Compose UI, one subpackage per screen/feature
│   ├── modepicker/             Mode Picker (choose Time/Bibs/Mule, resume in-progress race)
│   ├── timemode/                Time mode: stopwatch screen + `ElapsedTimeFormat.kt` helper
│   ├── bibsmode/                 Bibs mode: keypad entry screen
│   ├── mulemode/                  Mule mode (placeholder — not built yet)
│   ├── racehistory/               Race History list + read-only detail screen
│   ├── components/              Shared widgets used across screens:
│   │   AppBanner (top bar), NewRaceDialog, DigitKeypad, UndoLastButton, ModeScreenTopBar
│   └── theme/                   Material 3 theme: Color.kt, Theme.kt, Type.kt (app-wide look
│                                 and feel — edit here for colors/typography, not per-screen)
│
└── util/                      Small standalone helpers not tied to one feature
    ├── Beeper.kt                Audible confirmation tone (AudioTrack-based)
    └── TickerFlow.kt             Flow that emits on an interval, used to drive the live clock

app/src/main/res/              Android resources (non-code assets)
├── values/strings.xml           App name / any hardcoded UI strings
├── values/colors.xml, themes.xml   XML-side theme definitions (Compose theme in ui/theme/ is primary)
├── mipmap-*/, drawable*/         Launcher icon (per density) and the app banner image
└── xml/                         Backup rules (Android-required boilerplate, rarely touched)

app/src/main/AndroidManifest.xml   App/Activity declaration — edit to add permissions, change
                                    launcher activity, or register new components

app/schemas/                     Room-exported schema history (auto-generated on build when the
                                  DB version bumps — never hand-edit, don't delete old versions)

app/build.gradle.kts             Module config: applicationId, SDK versions, dependency list
gradle/libs.versions.toml        Dependency version catalog — add new libraries here, not
                                  hardcoded in build.gradle.kts
```

## Test layout

- `app/src/test/` — plain JVM unit tests (`./gradlew testDebugUnitTest`, no device needed).
- `app/src/androidTest/` — instrumented tests requiring a device/emulator
  (`./gradlew connectedDebugAndroidTest`). Currently used for repository tests against a real
  in-memory Room database (`data/repository/*Test.kt`, `data/settings/SettingsRepositoryTest.kt`).

## Common "I want to..." lookups

| I want to... | Look in... |
|---|---|
| Change a screen's layout/buttons/colors | `ui/<feature>/XScreen.kt` |
| Change what happens on a button tap | `ui/<feature>/XViewModel.kt` |
| Change how splits/bibs are numbered, or start/stop/undo rules | `data/repository/TimeModeRepository.kt` / `BibsModeRepository.kt` |
| Add a column to the database | `data/db/entity/`, bump `version` in `RacemasterDatabase.kt`, add a DAO query if needed |
| Add a new screen | new `ui/<feature>/` package + a route in `navigation/Routes.kt` + a `composable {}` in `RacemasterNavHost.kt` |
| Change app-wide theme colors/fonts | `ui/theme/Color.kt`, `Theme.kt`, `Type.kt` |
| Change the launcher icon or app banner image | `res/mipmap-*/`, `res/drawable-nodpi/ic_racemaster_banner.png` |
| Add a new dependency | `gradle/libs.versions.toml` then reference via `libs.*` in `app/build.gradle.kts` |
| Change what's remembered across app restarts (current mode, active race) | `data/settings/SettingsRepository.kt` |
| Wire up a new repository so screens can use it | `di/AppContainer.kt` |