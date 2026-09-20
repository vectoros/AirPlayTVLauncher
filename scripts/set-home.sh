#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: scripts/set-home.sh DEVICE_SERIAL}
mkdir -p artifacts
previous=$(adb -s "$serial" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)
if [[ "$previous" != 'dev.aurora.tv/.MainActivity' && "$previous" == */* ]]; then
    printf '%s\n' "$previous" > artifacts/previous-home.txt
    printf 'Previous Home saved: %s\n' "$previous"
fi
adb -s "$serial" shell cmd package set-home-activity dev.aurora.tv/.MainActivity
adb -s "$serial" shell input keyevent KEYCODE_HOME
