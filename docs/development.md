# Development

## Reproducible environment

The pinned Nix flake provides JDK 21, Android SDK 36/37, build tools 36.0.0, ADB, scrcpy, Node.js, Python, and a Google-free API 36 x86_64 emulator. Gradle's wrapper is pinned by SHA-256. The app compiles/targets API 37 with a minimum API of 35.

```sh
nix develop
npm ci --ignore-scripts
./gradlew :app:assembleDebug
```

Without Nix, install the corresponding SDK/JDK tools and set `ANDROID_HOME`.

## Emulator

```sh
nix run .#emulator
```

Inside the development shell, the same command is `glimpse-emulator`. It creates the persistent `glimpse-aosp-api-36` AVD using the pinned **default AOSP** image, without Google Play services. Its Pixel 6 hardware profile is an emulator display/camera profile; device-specific GrapheneOS behavior is checked on the real Pixel 10 Pro.

Linux needs hardware virtualization and read/write access to `/dev/kvm`. Data lives under `~/.android/avd`; override `ANDROID_AVD_HOME` to isolate a test run. The launcher does not overwrite incomplete or unrelated AVDs. It preserves device data when the Nix SDK path changes.

Options: `--headless`, `--gpu MODE`, `--port PORT`, and `--boot-timeout SECONDS`. A command after `--` runs after boot with `ANDROID_SERIAL` set, its exit status is preserved, and the emulator is stopped afterward. Ctrl+C/SIGTERM also clean up child processes. Logs go to stderr and command output to stdout.

Install from a second shell after **Ready**:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -W -n app.glimpse/.MainActivity
```

For a connected phone, use `./gradlew :app:installDebug`. Keep `ANDROID_SERIAL` set when multiple devices are connected.

## Checks

```sh
nix flake check
npm run format:check
./gradlew spotlessCheck :app:lintDebug :core:testDebugUnitTest
python3 -m unittest discover -s scripts/tests -v
```

Format with `npm run format` and `./gradlew spotlessApply`.

```sh
nix develop --command glimpse-emulator --headless -- bash -c '
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest &&
  python3 scripts/run-device-tests.py app/build/outputs/apk
'
```

Add `--download-models` to run the real Japanese model setup followed by networking-disabled translation tests. Ordinary CI skips this network-dependent setup; the release verification workflow enables it. Device reports, logcat, and light/dark screenshots are collected in `build/reports/device-tests/`. The runner checks JUnit completion rather than trusting ADB's exit code.

The assistant test temporarily selects Glimpse as the assistant and restores previous role holders. The model test temporarily disables Wi-Fi/mobile data and restores them. Run these on the development emulator.

## Fixtures and debugging

**Try an example** generates a deterministic English/Japanese image using Android's own fonts. It exercises the real OCR/translation pipeline. The live-frame test sends the same bitmap through the production live analyzer, including stabilization and caching.

Camera capture tests use the emulator's back camera. For real camera focus, lighting, Japanese vertical text/furigana, launch gestures, thermal behavior, and latency, use the Pixel 10 Pro. Measure cold/warm launch separately; emulator timings do not represent Tensor hardware or GrapheneOS process spawning.

Use `python3 scripts/device_ui.py inspect` to inspect accessible labels, or `tap LABEL` to activate one. The optimized APK smoke helper is documented in [Releasing](../RELEASING.md).
