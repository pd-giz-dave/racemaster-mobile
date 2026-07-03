# RaceMaster Mobile

An Android companion app for timing races on the day, used alongside the [RaceMaster](https://github.com/pd-giz-dave/racemaster)
web app that a race director uses to manage entries and produce results.

Rather than one person juggling a stopwatch and a bib list as runners cross the finish
line, two phones run this app side by side:

- **Time Mode** — acts as a stopwatch, recording exactly when each finisher crossed the
  line, in order.
- **Bibs Mode** — records which bib number each finisher was wearing, in the same order,
  with a configurable legal bib range, duplicate-entry flagging, and full row editing for
  correcting mis-keyed entries live.

The two logs are later matched up by position to produce finishing times per runner. The
screen stays on and (where supported) pinned in place for the whole race, and an external
USB/Bluetooth clicker or camera shutter remote can be used in place of tapping the screen
so the operator can keep their eyes on the finish line.

An in-app Help screen (Mode Picker → Help) covers the full operator workflow.

## Tech stack

- Kotlin 2.2, Jetpack Compose (Material 3), single-module Gradle project (`:app`)
- Room (local SQLite persistence), Navigation Compose, DataStore Preferences
- Manual dependency injection (no Hilt/Koin/Dagger) — see `STRUCTURE.md`
- `minSdk 24`, `targetSdk`/`compileSdk 36`, AGP 9.2.1

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
