#!/usr/bin/env bash
# Build the SUB/WAVE Auto release APK.
# Usage: scripts/build-release.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# Android Studio's bundled JBR (JDK 21) — same JDK Studio builds with.
JBR_WIN='C:\Program Files\Android\Android Studio\jbr'
JBR_POSIX='/c/Program Files/Android/Android Studio/jbr'
if [ -d "$JBR_POSIX" ]; then
    export JAVA_HOME="$JBR_POSIX"
elif [ -d "$JBR_WIN" ]; then
    export JAVA_HOME="$JBR_WIN"
fi

./gradlew :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
echo ""
echo "APK: $(pwd)/$APK"
if [ -f "keystore.properties" ]; then
    echo "Signing: RELEASE key (keystore.properties found)"
else
    echo "Signing: DEBUG key fallback (keystore.properties missing — see docs/SIGNING.md)"
fi
