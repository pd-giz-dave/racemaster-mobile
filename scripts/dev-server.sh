#!/usr/bin/env bash
# Starts the local racemaster server (if it isn't already running) and points every
# currently-connected device's "localhost" at it via `adb reverse`, so a debug build can log
# in to http://127.0.0.1:3000 with zero manual steps — no starting the server by hand, no
# adb reverse per phone, no cleartext-HTTP dialog (app/src/debug's network security config
# already permits cleartext to 127.0.0.1/localhost in debug builds).
#
# Usage: scripts/dev-server.sh
#
# Idempotent: safe to re-run any time (e.g. after phones reconnect or reboot, since
# `adb reverse` mappings don't survive either). Also ensures a disposable local test account
# (mobiletest / test1234, see DEV_SERVER_* in app/build.gradle.kts) exists on the local
# server, matching the debug build's auto-filled Setup Server defaults.
set -euo pipefail

RACEMASTER_DIR="${RACEMASTER_DIR:-/home/dave/racemaster}"
PORT="${RACEMASTER_PORT:-3000}"
DEV_USERNAME="mobiletest"
DEV_PASSWORD="test1234"

# Physical devices only ship adb on the PC, but it's not always on PATH.
if ! command -v adb >/dev/null 2>&1; then
    for candidate in "$HOME/Android/Sdk/platform-tools" "${ANDROID_HOME:-}/platform-tools" "${ANDROID_SDK_ROOT:-}/platform-tools"; do
        if [ -n "$candidate" ] && [ -x "$candidate/adb" ]; then
            export PATH="$candidate:$PATH"
            break
        fi
    done
fi

ping_ok() {
    curl -s -m 2 "http://127.0.0.1:${PORT}/api/ping" 2>/dev/null | grep -q '"ok":true'
}

if ping_ok; then
    echo "Server already running on port ${PORT}."
else
    echo "Starting racemaster server (${RACEMASTER_DIR})..."
    # `exec` inside the backgrounded subshell replaces that subshell process with setsid itself
    # instead of forking a further child from it — a forked-but-not-exec'd subshell here still
    # holds its own inherited copy of this script's stdout/stderr, and a plain `( cmd & )` (with
    # the `&` on the inner command, not the subshell) waits around long enough for that copy to
    # keep a caller reading this script's output blocked forever waiting for EOF (notably
    # Gradle's Exec task, see the `devServer` task in app/build.gradle.kts). `disown` drops it
    # from this shell's job table so script exit doesn't touch it either.
    ( cd "$RACEMASTER_DIR" && exec setsid nohup node server.js > server-dev.log 2>&1 < /dev/null ) &
    disown
    for _ in $(seq 1 20); do
        sleep 0.5
        if ping_ok; then
            echo "Server is up."
            break
        fi
    done
    if ! ping_ok; then
        echo "Server did not come up — check ${RACEMASTER_DIR}/server-dev.log" >&2
        exit 1
    fi
fi

# Idempotent: a 409 (already exists) is expected and fine on every run after the first.
curl -s -X POST "http://127.0.0.1:${PORT}/api/auth/create" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${DEV_USERNAME}\",\"password\":\"${DEV_PASSWORD}\"}" >/dev/null || true

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found on PATH — skipping adb reverse (server is up, but phones won't reach it until you reverse tcp:${PORT} manually)." >&2
    exit 0
fi

devices=$(adb devices | tail -n +2 | awk '$2 == "device" {print $1}')
if [ -z "$devices" ]; then
    echo "No connected devices — skipping adb reverse."
else
    while IFS= read -r serial; do
        adb -s "$serial" reverse "tcp:${PORT}" "tcp:${PORT}"
        echo "  adb reverse set up for $serial"
    done <<< "$devices"
fi

echo "Done. Debug builds default to http://127.0.0.1:${PORT} / ${DEV_USERNAME} on a fresh install."
