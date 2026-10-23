# Glimpse

A native, local-first visual assistant for Android and GrapheneOS. Circle something on your screen, copy its text, translate it, or search with it. Open the camera from the assistant, a launcher shortcut, or Quick Settings.

Built for the Pixel 10 Pro, with Android 15+ support, Jetpack Compose, Material 3, wallpaper colors, light/dark themes, and on-device English/Japanese recognition and translation.

## What you can do

- **Scan your screen:** choose Glimpse as your digital assistant, invoke your system assistant gesture, then circle an area or tap text. Refine the selection with its corner handles.
- **Copy and edit text:** recognize English, Japanese, and mixed-language images locally; copy selected words or entire passages.
- **Translate photos and screenshots:** download language packs, translate on device, toggle translated overlays, or open a browser translator.
- **Scan with the camera:** take a picture or switch to **Live translate**. Stable text is translated locally and overlaid on the preview.
- **Search:** Google, Bing, and Kagi text searches; Google/Bing visual-search upload pages receive your selected crop. Add custom search or AI URL templates in Settings.
- **Import and share:** Android photo picker, image share targets, Android's text-selection menu, and save/share selected crops.

The camera icon is available directly in the assistant workspace. Quick Settings includes **Scan camera** and **Scan screen**; long-press the app icon for the same launcher shortcuts.

## Install and get started

Download an APK from [Releases](https://github.com/0xjogge/glimpse/releases). Use **ARM64** on the Pixel 10 Pro, or the universal APK if unsure. All architecture builds share one signing identity and version code.

1. Open Glimpse and choose **Make it one gesture away** to select it as your digital assistant.
2. Enable screenshot access in Android's digital-assistant settings if disabled.
3. Open **Settings → Offline languages** and download **Japanese** for English ↔ Japanese translation. English/Japanese OCR is bundled and immediately available.
4. Invoke the assistant over another app, open the camera, choose a photo, or try the built-in example.

See [Usage](docs/usage.md), [Development](docs/development.md), [Architecture](docs/architecture.md), and [Releasing](RELEASING.md).

## Local-first, replaceable engines

`core/` defines OCR/translation contracts, image-relative text geometry, selection, search capabilities, stabilization, and caching. `engines/mlkit/` supplies the initial implementations. `app/` owns the Android integrations and shared UI.

The initial models are Google's proprietary ML Kit models. They work without installed Google Play services. OCR is bundled; translation models download into app-private storage. The model manager lists all translation languages supported by the selected engine. More OCR backends/scripts and inference runtimes can be added through adapters without rewriting the UI.

On-device inference does not upload images or text. Browser translation and visual/text search use the service you select. ML Kit model downloads and operational telemetry can contact Google; see [Privacy](docs/privacy.md) and [third-party notices](NOTICE.md).

## Develop

```sh
nix develop
npm ci --ignore-scripts
./gradlew :app:assembleDebug
```

Run the persistent Google-free Android 16 emulator:

```sh
nix run .#emulator
```

Build and exercise the app with automatic emulator startup/shutdown:

```sh
nix develop --command glimpse-emulator --headless -- bash -c '
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest &&
  python3 scripts/run-device-tests.py app/build/outputs/apk --download-models
'
```

The opt-in model test downloads Japanese, disables networking, and verifies both translation directions and live-frame translation. The ordinary suite also verifies actual assistant screenshot delivery, camera still capture, English/Japanese OCR, selection/copy, settings, and theme screenshots on an AOSP image without Google Play services.

## License

Glimpse source is licensed under [GPL-3.0](LICENSE). Third-party libraries and models retain their respective licenses and terms. Build, emulator, signing, and release conventions are adapted from [Frem](https://github.com/0xjogge/frem).
