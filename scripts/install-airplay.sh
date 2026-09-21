#!/usr/bin/env bash
# Deploy the paired debug builds; signature protection requires the same signing key.
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: scripts/install-airplay.sh DEVICE_SERIAL}
scripts/build-airplay.sh
./gradlew assembleDebug
adb -s "$serial" install -r artifacts/Aurora-AirPlay-0.0.31-aurora.1-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" shell appops set dev.aurora.airplay SYSTEM_ALERT_WINDOW allow
# Retire the previous companion's running service to avoid port/name collisions.
if adb -s "$serial" shell pm list packages | tr -d '\r' | grep -qx 'package:io.github.jqssun.airplay'; then
    adb -s "$serial" shell am force-stop io.github.jqssun.airplay
fi
adb -s "$serial" shell am start -W -n dev.aurora.tv/.MainActivity
printf '%s\n' 'Launcher now starts Aurora AirPlay in the background; no receiver screen is required.'
