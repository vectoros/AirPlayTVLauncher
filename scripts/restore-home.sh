#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: scripts/restore-home.sh DEVICE_SERIAL [ORIGINAL_COMPONENT]}
component=${2:-$(cat artifacts/previous-home.txt 2>/dev/null || true)}
if [[ ! "$component" =~ ^[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+$ ]]; then
    printf 'Supply the original launcher component or restore artifacts/previous-home.txt\n' >&2
    exit 1
fi
adb -s "$serial" shell cmd package set-home-activity "$component"
adb -s "$serial" shell input keyevent KEYCODE_HOME
