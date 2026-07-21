#!/usr/bin/env bash
# Stops the local racemaster server started by scripts/dev-server.sh. It's fully detached
# (its own session, reparented to init), so it keeps running across terminal/IDE restarts —
# this is the only way to stop it short of a reboot.
#
# Usage: scripts/stop-dev-server.sh
#
# Idempotent: safe to re-run (or run when the server isn't up) — no-op with a message either way.
set -euo pipefail

PORT="${RACEMASTER_PORT:-3000}"

pids=$(pgrep -f "^node server\.js$" || true)
if [ -z "$pids" ]; then
    echo "No dev server running."
    exit 0
fi

kill $pids
echo "Stopped dev server (pid $(echo "$pids" | tr '\n' ' ')) on port ${PORT}."
