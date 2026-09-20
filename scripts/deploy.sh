#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: scripts/deploy.sh DEVICE_SERIAL}
./gradlew assembleDebug
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" shell am start -W -n dev.aurora.tv/.MainActivity
