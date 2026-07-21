# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

This is a freshly scaffolded Android application (default Android Studio "Empty Activity" Compose template, package `mobile.racemaster`). There is no custom architecture, dependency injection, networking, or data layer yet — just the generated `MainActivity`, theme files, and default tests. Treat any structural decisions (module layout, DI framework, navigation, etc.) as open, not established convention.

## Commands

All commands run from the repo root using the Gradle wrapper.

- Build debug APK: `./gradlew assembleDebug`
- Build release APK: `./gradlew assembleRelease`
- Run unit tests (JVM, `app/src/test`): `./gradlew testDebugUnitTest`
- Run a single unit test class: `./gradlew testDebugUnitTest --tests "mobile.racemaster.ExampleUnitTest"`
- Run instrumented tests (`app/src/androidTest`, requires a connected device/emulator): `./gradlew connectedDebugAndroidTest`
- Lint: `./gradlew lint`
- Full check (lint + tests): `./gradlew check`
- Local Mule/server testing against `localhost` (no manual per-session setup): every debug build/install and `connectedDebugAndroidTest` run automatically starts the racemaster server at `/home/dave/racemaster` (if it isn't already running) and `adb reverse`s port 3000 to every connected device — see the `devServer` task dependency in `app/build.gradle.kts` and `scripts/dev-server.sh`. Run `./gradlew devServer` / `./gradlew stopDevServer` (or `scripts/dev-server.sh` / `scripts/stop-dev-server.sh` directly) to start or stop it by hand; it's detached from Gradle so it keeps running across builds until stopped or rebooted. A debug build's Setup Server screen self-fills `http://127.0.0.1:3000` / `mobiletest` on a fresh install (see `DEV_SERVER_*` in `app/build.gradle.kts` — empty in release builds). Cleartext HTTP to `127.0.0.1`/`localhost` is permitted in debug builds only (`app/src/debug/res/xml/network_security_config_debug.xml`).

## Architecture

- Single Gradle module: `:app`. `settings.gradle.kts` and `build.gradle.kts` (root) hold no other modules today.
- Dependency versions and plugin aliases are centralized in `gradle/libs.versions.toml` (the Gradle version catalog) and referenced from `app/build.gradle.kts` via `libs.*` — add new dependencies there rather than hardcoding coordinates in the module build file.
- UI is Jetpack Compose (Material 3), entry point `MainActivity.kt` under `app/src/main/java/mobile/racemaster/`. Theming (`Color.kt`, `Theme.kt`, `Type.kt`) lives in the `ui/theme` subpackage.
- `minSdk = 24`, `targetSdk`/`compileSdk = 36`, Kotlin `2.2.10`, AGP `9.2.1`.