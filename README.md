# RaceMaster Mobile

An Android companion app for timing races on the day, used alongside the [RaceMaster](https://github.com/pd-giz-dave/racemaster)
web app that a race director uses to manage entries and produce results.

Rather than one person juggling a stopwatch and a bib list as runners cross the finish
line, any number of phones run this app side by side, each logging its own station:

- **Time Mode** — acts as a stopwatch, recording exactly when each finisher crossed the
  line, in order.
- **Bibs Mode** — records which bib number each finisher was wearing, in the same order,
  with a configurable legal bib range, duplicate-entry flagging, and full row editing for
  correcting mis-keyed entries live.
- **CP Mode** — a lighter checkpoint variant of Bibs Mode (a fixed Pass/Retire button pair
  instead of a full event picker) for stations out on the course rather than at the finish
  line.
- **Mule Mode** — has no stopwatch/keypad of its own. Instead it continuously discovers
  every other RaceMaster Mobile device nearby over Bluetooth, pulls whatever data they're
  holding, and pushes it on to the RaceMaster server — this is how data recorded on
  isolated Time/Bibs/CP phones out in the field actually gets back to race control.

Every race records a name, course, and location (e.g. "Finish", "CP1", "CP2") so results
recorded by several stations can be reassembled afterwards. The screen stays on for the
whole race (the app also requests exemption from battery optimization, since some OEM
power-saving policies dim/lock the screen regardless of that), and an external
USB/Bluetooth clicker or camera shutter remote can be used in place of tapping the screen
so the operator can keep their eyes on the finish line.

An in-app Help screen (Mode Picker → Help) covers the full operator workflow.

## Tech stack

- Kotlin 2.2, Jetpack Compose (Material 3), single-module Gradle project (`:app`)
- Room (local SQLite persistence), Navigation Compose, DataStore Preferences
- Kable (BLE central/peripheral) for Mule Mode's phone-to-phone sync, Ktor + kotlinx.serialization
  for its HTTP push to the RaceMaster server
- Manual dependency injection (no Hilt/Koin/Dagger) — see `STRUCTURE.md`
- `minSdk 24`, `targetSdk`/`compileSdk 37`, AGP 9.3.0

## Building

All commands run from the repo root using the Gradle wrapper:

```
./gradlew assembleDebug          # build debug APK
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests (needs a connected device/emulator)
./gradlew installDebug           # install to a connected device
```

## More detail

- [`STRUCTURE.md`](STRUCTURE.md) — file-by-file map of the codebase, "where do I go to
  change X" lookup table.
- [`TODO.md`](TODO.md) — detailed implementation history and progress log, phase by phase.

## License

MIT — see [`LICENSE`](LICENSE).
