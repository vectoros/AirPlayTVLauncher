# Aurora AirPlay patch

`airplay-resident.patch` modifies android-airplay-server v0.0.31 (`c8defdd70d7e6a04f4f1b71d353653682d594106`). These changes are distributed under the upstream GPL-3.0-only license, available in `third_party/airplay-server/LICENSE`; they are not covered by the Launcher's MIT license.

Changes (Aurora companion `0.0.31-aurora.2`): separately named application, signature-protected control provider and start service, persistent master switch shared with Launcher settings, boot/package-update startup, sticky foreground lifetime and retry, network recovery, default Aurora TV name, private playback entry with automatic return after disconnect, and optional use of pinned upstream native binaries. Java/Kotlin namespace stays upstream so JNI entry points match.

Build: `scripts/build-airplay.sh`, with JDK17+, Android SDK36 and matching accepted SDK licenses. The script compiles modified Kotlin/Java/resources and links the exact four native libraries from the SHA-256-verified upstream APK, targeting the Sony armeabi-v7a architecture. It does not rebuild the native protocol stack. Gradle resolves the original Android dependencies. Both this debug APK and Launcher use the same local Android debug signing key; production builds must also use the same protected signing key for both APKs.

To rebuild native code as well: clone the pinned upstream commit with recursive submodules, apply this patch (`git apply /path/to/airplay-resident.patch`), install upstream NDK/CMake prerequisites, and build without `-PprebuiltNative=true`. Native source and licenses remain in the upstream submodules; no protocol source was changed. See `third_party/README.md` for source and release links. Retain the upstream source including pinned submodules, dependency sources and license notices alongside any redistributed companion binary.

Chinese UI resources are included in the same GPL patch; English remains upstream. See `docs/LOCALIZATION.md` for coverage and validation.

Startup and background controls are managed by the Launcher master switch. The patch also includes an opt-in custom device instrumentation runner for synthetic projection callbacks; see `docs/AIRPLAY.md` for commands and limitations. DiPlay was an architectural reference only; no DiPlay source or authentication material is bundled.
