#!/usr/bin/env bash
# Install the pinned, unmodified upstream receiver; Launcher remains independent.
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: scripts/install-airplay.sh DEVICE_SERIAL}
apk=artifacts/airplay-server-0.0.31.apk
expected=c5dce5c29ab52157bdaa406e485f9bfaab7d8d59c523d93622a33b253a28f410
mkdir -p artifacts
if [[ ! -f "$apk" ]]; then
    curl --fail --location --retry 2 --connect-timeout 15 --max-time 600 \
        https://f-droid.org/repo/io.github.jqssun.airplay_31.apk -o "$apk.part"
    mv "$apk.part" "$apk"
fi
actual=$(shasum -a 256 "$apk" | awk '{print $1}')
if [[ "$actual" != "$expected" ]]; then
    echo 'Receiver APK checksum mismatch; remove the cached APK and retry.' >&2
    exit 1
fi
adb -s "$serial" install -r "$apk"
# Allows the receiving screen to open when a sender connects from the background.
adb -s "$serial" shell appops set io.github.jqssun.airplay SYSTEM_ALERT_WINDOW allow
adb -s "$serial" shell am start -W -n io.github.jqssun.airplay/.MainActivity
printf '%s\n' 'In receiver Settings, set Server name to Aurora TV. Boot and background defaults are on.'
