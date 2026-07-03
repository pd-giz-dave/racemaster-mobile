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

## Architecture

- Single Gradle module: `:app`. `settings.gradle.kts` and `build.gradle.kts` (root) hold no other modules today.
- Dependency versions and plugin aliases are centralized in `gradle/libs.versions.toml` (the Gradle version catalog) and referenced from `app/build.gradle.kts` via `libs.*` — add new dependencies there rather than hardcoding coordinates in the module build file.
- UI is Jetpack Compose (Material 3), entry point `MainActivity.kt` under `app/src/main/java/mobile/racemaster/`. Theming (`Color.kt`, `Theme.kt`, `Type.kt`) lives in the `ui/theme` subpackage.
- `minSdk = 24`, `targetSdk`/`compileSdk = 36`, Kotlin `2.2.10`, AGP `9.2.1`.