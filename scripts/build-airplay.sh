#!/usr/bin/env bash
# Builds the GPL companion's Kotlin changes; native ABI stays pinned to upstream 0.0.31.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
source_dir=third_party/airplay-server
work=artifacts/airplay-resident-build
expected_source=c8defdd70d7e6a04f4f1b71d353653682d594106
expected_apk=c5dce5c29ab52157bdaa406e485f9bfaab7d8d59c523d93622a33b253a28f410
apk=artifacts/airplay-server-0.0.31.apk
if [[ ! -f "$source_dir/app/build.gradle.kts" ]]; then
    git submodule update --init -- third_party/airplay-server
fi
[[ $(git -C "$source_dir" rev-parse HEAD) == "$expected_source" ]] || { echo 'Unexpected upstream revision' >&2; exit 1; }
mkdir -p "$work"
# Export only tracked upstream files, then apply our reviewable source patch.
git -C "$source_dir" archive "$expected_source" | tar -x -C "$work"
patch --batch --forward -p1 -d "$work" < patches/airplay-resident.patch
if [[ ! -f "$apk" ]]; then
    curl --fail --location --retry 2 --connect-timeout 15 --max-time 600 \
        https://f-droid.org/repo/io.github.jqssun.airplay_31.apk -o "$apk.part"
    mv "$apk.part" "$apk"
fi
[[ $(shasum -a 256 "$apk" | awk '{print $1}') == "$expected_apk" ]] || { echo 'Upstream APK checksum mismatch' >&2; exit 1; }
python3 - "$apk" "$work" <<'PY'
import sys
from pathlib import Path
from zipfile import ZipFile
out = Path(sys.argv[2]) / 'app/src/main/jniLibs/armeabi-v7a'
out.mkdir(parents=True, exist_ok=True)
with ZipFile(sys.argv[1]) as archive:
    for name in ('libairplay_native.so', 'libcrypto.so', 'libc++_shared.so', 'liboboe.so'):
        (out / name).write_bytes(archive.read('lib/armeabi-v7a/' + name))
PY
"$work/gradlew" -p "$work" assembleDebug -PprebuiltNative=true
cp "$work/app/build/outputs/apk/debug/app-debug.apk" "$root/artifacts/Aurora-AirPlay-0.0.31-aurora.1-debug.apk"
