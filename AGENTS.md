# Repository Guidelines

## Project Structure & Module Organization

- `app/src/main/java/dev/aurora/tv/`: native Java Launcher UI, app discovery, weather, photos, glass effects, and AirPlay coordination.
- `app/src/main/res/`: styles and vector assets; wallpapers are drawn programmatically.
- `app/src/androidTest/`: device regression suite.
- `scripts/`: deployment, HOME selection/restoration, and companion builds.
- `third_party/airplay-server/`: pinned upstream submodule; keep Aurora modifications in `patches/airplay-resident.patch`.
- `docs/`: design, verification, experiments, and release review. `artifacts/` holds ignored local outputs.

## Build, Test, and Development Commands

Use JDK 17 and Android SDK 35; set `ANDROID_HOME` or local `sdk.dir`. Launcher supports API 26+.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
./scripts/deploy.sh SERIAL
./scripts/set-home.sh SERIAL
./scripts/restore-home.sh SERIAL ORIGINAL_COMPONENT
```

These build both APKs and run Android Lint, deploy/start Launcher, select HOME, and restore HOME respectively. Verify remote navigation before changing HOME; record the original component.

`./scripts/build-airplay.sh` builds the separate companion using SDK 36 and pinned prebuilt native libraries. See `docs/AIRPLAY.md` for prerequisites and paired installation.

## Coding Style & Naming Conventions

Follow existing Java: four-space indentation, same-line opening braces, `PascalCase` classes, `camelCase` methods/fields, and `UPPER_SNAKE_CASE` constants. Use lowercase underscore resource names. Prefer small native Android implementations; keep network and image work off the UI thread and guard newer APIs. Android Lint is configured; no standalone formatter is enforced.

## Testing Guidelines

Tests use custom Android `Instrumentation`, without JUnit. Add focused `testXxx` methods to `RegressionInstrumentation` and invoke them from the runner. Use isolated fixtures, never personal albums/preferences. No coverage percentage is enforced.

After building, run:

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w dev.aurora.tv.test/dev.aurora.tv.RegressionInstrumentation
```

CI compiles tests but does not execute them. Record relevant device checks—focus, HDMI, photos, weather, and AirPlay—in `docs/VERIFICATION.md`.

## Commit & Pull Request Guidelines

History uses concise imperative `feat:` and `docs:` subjects. Keep commits focused. PRs should explain behavior, link relevant issues, report commands/results and device/API tested, and include sanitized screenshots for UI changes. Update affected documentation; distinguish verified behavior from assumptions.

## Security & Licensing

Never commit credentials, signing keys, private device addresses, APKs, or raw logs. Keep private notes in ignored `docs/*_LOCAL.md`. Launcher is MIT; the separate AirPlay companion and patches are GPL. Preserve license boundaries and consult `docs/OPEN_SOURCE_REVIEW.md` before publishing.
