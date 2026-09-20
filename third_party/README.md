# AirPlay companion

`airplay-server` is an unmodified Git submodule of https://github.com/jqssun/android-airplay-server at v0.0.31 / c8defdd70d7e6a04f4f1b71d353653682d594106.

It is a separate GPL-3.0-only Android application (`io.github.jqssun.airplay`), not linked into the MIT Launcher APK. Its LICENSE and notices are retained in the submodule; its own native dependencies carry their respective licenses. The Launcher only opens its exported launch Activity using Android intents.

`scripts/install-airplay.sh` installs the upstream-signed F-Droid APK, not a locally rebuilt or rebranded binary. SHA-256: `c5dce5c29ab52157bdaa406e485f9bfaab7d8d59c523d93622a33b253a28f410`. Signing certificate SHA-256: `64ca1fa39ddf9e0e89c57d73fb681d2a5b35ae20f071771e2eb401b5032ef1a9`.

Download: https://f-droid.org/repo/io.github.jqssun.airplay_31.apk
Source / release: https://github.com/jqssun/android-airplay-server/tree/v0.0.31
F-Droid source archive: https://f-droid.org/repo/io.github.jqssun.airplay_31_src.tar.gz

For a source build, initialize this submodule and its nested dependencies (`git submodule update --init --recursive`), then follow its README and CI. The upstream build requires SDK36, NDK27.0.12077973, CMake, JDK17 and downloads further native dependencies. This is optional and independent of `./gradlew assembleDebug` for Launcher. If redistributing the receiver binary, retain license notices and provide its corresponding source including dependencies; do not describe the receiver as MIT or as original Aurora code.
