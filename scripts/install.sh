#!/usr/bin/env bash
# Install the SUB/WAVE Auto release APK onto the one connected phone.
# Usage: scripts/install.sh [path/to.apk]   (default: the assembleRelease output)
set -euo pipefail

cd "$(dirname "$0")/.."

ADB="C:/Users/Mike/AppData/Local/Android/Sdk/platform-tools/adb"
if [ ! -x "$ADB" ] && [ ! -x "$ADB.exe" ]; then ADB="adb"; fi

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found: $APK" >&2
    echo "Run scripts/build-release.sh first, or pass an APK path." >&2
    exit 1
fi

# Exactly one authorized device, or bail with what we saw.
DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
COUNT="$(printf '%s' "$DEVICES" | grep -c . || true)"
if [ "$COUNT" -ne 1 ]; then
    echo "ERROR: need exactly ONE authorized device, found $COUNT." >&2
    echo "adb devices output:" >&2
    "$ADB" devices >&2
    echo "(unauthorized = accept the USB-debugging prompt on the phone)" >&2
    exit 1
fi

echo "Installing $APK onto $DEVICES ..."
"$ADB" install -r "$APK"
