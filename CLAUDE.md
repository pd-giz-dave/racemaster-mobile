# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

This is an actively-developed Android app (package `mobile.racemaster`) for race timing, 
with local SQLite persistence, a BLE mesh sync feature between phones ("Mule Mode"), 
and HTTP sync to a companion Node.js server. It has a full layered architecture already in place — 
Screen (Compose) → ViewModel → Repository → Room DAO — with a hand-rolled manual DI container 
(no Hilt/Koin/Dagger), Jetpack Navigation-Compose, Ktor for HTTP, and Kable for BLE central/scanning. 
See `STRUCTURE.md` for the detailed architecture writeup and `TODO.md` for open work. Treat 
structural decisions already reflected in the code (module layout, DI approach, navigation) as 
established convention, not open — any change to them should be deliberate, not incidental.

## Commands

All commands run from the repo root using the Gradle wrapper.

- Build debug APK: `./gradlew assembleDebug`
- Build release APK: `./gradlew assembleRelease`
- Run unit tests (JVM, `app/src/test`): `./gradlew testDebugUnitTest`
- Run a single unit test class: `./gradlew testDebugUnitTest --tests "mobile.racemaster.ExampleUnitTest"`
- Run instrumented tests (`app/src/androidTest`, requires a connected device/emulator): `./gradlew connectedDebugAndroidTest`
- Lint: `./gradlew lint`
- Full check (lint + tests): `./gradlew check`
- Local Mule/server testing against `localhost` (no manual per-session setup): 
  every debug build/install and `connectedDebugAndroidTest` run automatically starts the racemaster 
  server at `/home/dave/racemaster` (if it isn't already running) and `adb reverse`s port 3000 to 
  every connected device — see the `devServer` task dependency in `app/build.gradle.kts` and 
  `scripts/dev-server.sh`. Run `./gradlew devServer` / `./gradlew stopDevServer` 
  (or `scripts/dev-server.sh` / `scripts/stop-dev-server.sh` directly) to start or stop it by hand; 
  it's detached from Gradle so it keeps running across builds until stopped or rebooted. A debug 
  build's Setup Server screen self-fills `http://127.0.0.1:3000` / `mobiletest` on a fresh install 
  (see `DEV_SERVER_*` in `app/build.gradle.kts` — empty in release builds). Cleartext HTTP to 
  `127.0.0.1`/`localhost` is permitted in debug builds only 
  (`app/src/debug/res/xml/network_security_config_debug.xml`).

## Architecture

- Single Gradle module: `:app`. `settings.gradle.kts` and `build.gradle.kts` (root) hold no other modules today.
- Dependency versions and plugin aliases are centralized in `gradle/libs.versions.toml` (the Gradle 
  version catalog) and referenced from `app/build.gradle.kts` via `libs.*` — add new dependencies 
  there rather than hardcoding coordinates in the module build file.
- UI is Jetpack Compose (Material 3) with Navigation-Compose (`navigation/RacemasterNavHost.kt`, 
  `Routes.kt`). Entry point `MainActivity.kt` and `RacemasterApplication.kt` under 
  `app/src/main/java/mobile/racemaster/`. Theming (`Color.kt`, `Theme.kt`, `Type.kt`) lives in 
  `ui/theme`; the other 11 feature packages under `ui/` each follow Screen → ViewModel → Repository → DAO.
- DI is hand-rolled, not a framework: `di/AppContainer.kt` defines an `AppContainer` interface of 
  lazily-constructed repositories, implemented by `DefaultAppContainer`; ViewModels resolve it via 
  `di/ViewModelFactorySupport.kt`'s `CreationExtras.appContainer()` extension and a 
  `companion object Factory` per ViewModel.
- Persistence is Room (`data/db/`: `RacemasterDatabase.kt`, DAOs, entities) plus 
  DataStore-Preferences for settings (`data/settings/SettingsRepository.kt`, JSON-serialized via kotlinx.serialization).
- Networking is Ktor (`data/mule/MuleSyncClient.kt`) talking to the companion racemaster server's 
  bearer-token HTTP API; BLE sync ("Mule Mode", `data/mule/`) uses Kable for the 
  central/scanning role (`MulePullClient.kt`) and raw `android.bluetooth` GATT-server/advertiser 
  APIs plus a foreground `Service` for the peripheral/hosting role (`PeripheralSyncService.kt`).
- `minSdk = 24`, `targetSdk`/`compileSdk = 37`, Kotlin `2.2.10`, AGP `9.3.2`.
