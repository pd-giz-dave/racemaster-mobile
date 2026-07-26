#!/usr/bin/env bash
# Bumps app/build.gradle.kts's versionName/versionCode, then builds and republishes the debug
# APK for download from the racemaster web app (see the publishApkToWebApp Gradle task).
# versionCode always increments by 1; versionName bumps by semver part, or can be set exactly.
#
# Usage:
#   scripts/bump-and-publish.sh              # bump patch (0.0.2 -> 0.0.3), the default
#   scripts/bump-and-publish.sh patch|minor|major
#   scripts/bump-and-publish.sh 1.2.0        # set versionName exactly
#
# Not idempotent by design — each run always advances the version, since re-publishing the
# same version would leave testers unable to tell whether they're on the latest build (a
# sideloaded APK has no update-check of its own; the version bump is what makes "did I get the
# new build" answerable at all — see the in-app Help screen's own v-number caption).
#
# Leaves the version bump as an uncommitted change in app/build.gradle.kts for you to review
# and commit yourself — this script never commits.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
GRADLE_FILE="${REPO_ROOT}/app/build.gradle.kts"

BUMP="${1:-patch}"

CURRENT_VERSION_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$GRADLE_FILE" | head -1)
CURRENT_VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1)

if [ -z "$CURRENT_VERSION_NAME" ] || [ -z "$CURRENT_VERSION_CODE" ]; then
    echo "Couldn't find versionName/versionCode in ${GRADLE_FILE}" >&2
    exit 1
fi

if [[ "$BUMP" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    NEW_VERSION_NAME="$BUMP"
else
    IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION_NAME"
    case "$BUMP" in
        major) NEW_VERSION_NAME="$((MAJOR + 1)).0.0" ;;
        minor) NEW_VERSION_NAME="${MAJOR}.$((MINOR + 1)).0" ;;
        patch) NEW_VERSION_NAME="${MAJOR}.${MINOR}.$((PATCH + 1))" ;;
        *)
            echo "Usage: $0 [major|minor|patch|X.Y.Z]  (default: patch)" >&2
            exit 1
            ;;
    esac
fi
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

sed -i \
    -e "s/versionCode = ${CURRENT_VERSION_CODE}/versionCode = ${NEW_VERSION_CODE}/" \
    -e "s/versionName = \"${CURRENT_VERSION_NAME}\"/versionName = \"${NEW_VERSION_NAME}\"/" \
    "$GRADLE_FILE"

echo "versionName ${CURRENT_VERSION_NAME} -> ${NEW_VERSION_NAME}  (versionCode ${CURRENT_VERSION_CODE} -> ${NEW_VERSION_CODE})"

cd "$REPO_ROOT"
./gradlew publishApkToWebApp

echo "Published racemaster-mobile-debug.apk at v${NEW_VERSION_NAME}."
echo "Review and commit the version bump in app/build.gradle.kts when ready — not committed automatically."
