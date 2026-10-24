# Architecture and extension points

## Modules

- `core/`: app-owned OCR/translation interfaces, engine/model descriptions, immutable recognition results, normalized geometry, selection, URL providers, live-text stabilization, and a bounded session translation cache.
- `engines/mlkit/`: bundled Japanese/Latin OCR, bundled language identification, and downloaded offline translation models. ML Kit classes stay inside this module.
- `app/`: Compose UI, persisted preferences, assistant services, CameraX, imports, crop sharing, model controls, and visual-search WebView handoffs.

`AppGraph` injects an `EngineRegistry` into `GlimpseViewModel`. Settings selects an engine by stable ID. The shared workspace accepts images from `ImageSession`, never a Binder-sized bitmap extra.

## Coordinate contract

Images passed to OCR are upright. Every returned `ImageRegion` is normalized to that upright image, not to the screen. `ImagePlacement.fit()` handles display letterboxing and maps touches back into image coordinates. The same boxes drive text selection, crop export, static translation overlays, and live-camera overlays.

A freehand circle/lasso becomes an adjustable bounding crop. A tap selects a text line. Span-center selection permits copying part of a line without inserting Western spaces into Japanese. Crop exports are re-encoded JPEGs, excluding the original EXIF metadata.

## Add an OCR backend

1. Create an Android library under `engines/` and implement `OcrEngine`.
2. Return `Recognition`/`TextLine`/`TextSpan` values using normalized upright coordinates and the backend's reading order.
3. Declare supported `OcrScript` values; add script descriptors when introducing a new writing system.
4. Add the implementation to `AppGraph.engines`. The workspace, assistant, camera, and settings consume the same interface.
5. Exercise mixed-language recognition and geometry using the device suite.

The default automatic mode uses the Japanese recognizer, which handles mixed Japanese/Latin text. Latin-only recognition is separately selectable. New translation languages can be downloaded now; new OCR writing systems require a compatible OCR adapter/model.

## Add translation models or engines

Implement `TranslationEngine` with supported BCP-47 language tags, language identification, model listing/download/removal, and translation. Missing local models raise `ModelRequiredException`; the UI offers download and browser translation. Model formats are backend-specific: an ONNX model requires an ONNX-capable adapter, while ML Kit's model manager accepts its own language packs.

The initial adapter serializes translator use and maintains a small warm translator set. The app caches translations by engine, source, target, and exact text. Model removal clears cached translations.

## Search providers

`SearchProvider` separates text queries from image-upload capability. `TemplateSearchProvider` validates HTTPS URLs and encodes selected text. Users can add search/AI templates containing `{query}` without a rebuild. More involved API or native-app providers can implement the interface.

Google and Bing visual search open provider-owned upload pages. Android WebView's file chooser receives the prepared crop when the user chooses image upload; Glimpse does not host the image or call undocumented upload endpoints. Kagi text search uses the user's browser and its existing sign-in. Kagi/custom engines do not advertise reverse-image capability without a supported image adapter.

Browser translation is separate from local translation engines and opens Google Translate or Kagi Translate with the selected text. The browser handles authentication.

## Live translation

CameraX keeps only the latest analysis frame. Analysis is throttled, upright images use the same OCR engine, and repeated text must stabilize before translation. A bounded cache avoids retranslating unchanged text. Live processing is on-device; frame-by-frame cloud uploads are not part of the pipeline.

Live screen translation captures the whole display through a per-session MediaProjection after platform consent, without an app picker. Capture/motion tracking is separate from clean, settled-frame OCR and translation: screen movement invalidates the current presentation, and analysis resumes after the content settles, including when switching apps. The service suppresses its labels and edge presentation while acquiring clean input; the control dock remains available, with its footprint excluded from OCR and motion sampling. System bars are excluded too, so the recording timer does not continuously invalidate a still scene. A marked-to-unmarked stream transition fences clean input; reader/session epochs reject stale frames. Generations reject stale analysis and translation results after content or session changes.

Translated regions are presented in place through a pass-through label window. A separate compact dock provides language, original-text, pause/resume and stop controls rather than a large translation-text panel. The label window stays within Android's untrusted-touch opacity limit so scrolling remains possible.

Compose and native controls resolve the same saved light/dark/system and wallpaper-color preferences, with explicit surface/foreground pairs. Translation labels retain the clean scene at native resolution: their backing pixels compensate for Android's touch-through window alpha so the original lettering is covered rather than showing through. Target label colors are constrained to the attainable high-contrast range; OCR still uses a downscaled image. Scene/geometry invalidation hides those patches as soon as their source is no longer current.

## Android integration

`GlimpseVoiceService`/`GlimpseSessionService` receive screenshots through the selected default-assistant role. The unbranded capture bridge and translucent `AssistantActivity` share a native `AssistantActionStrip` with labeled TextView actions: Mark to search, Translate, Music and Camera. These actions are available before recognition completes; screenshot selection is enabled explicitly by Mark to search. Translate goes directly to whole-display capture setup and platform consent. The activity supplies the Compose lifecycle, keyboard and result launchers.

The full-screen snapshot and selection geometry preserve the underlying screen context. A stationary, two-tone blurred inset ring follows rounded display corners; the bridge, assistant workspace and live screen presentation share its renderer. Back fades out the assistant and ring together, with no X close control. Close music dismisses only the music panel. The platform-required speech recognition component remains visual-only; microphone recording is exclusively an explicitly started music feature.

Assistant images are routed to an assistant-scoped ViewModel, so opening/closing the overlay does not replace or retain its screenshot in a background main-application workspace.

The assistant overlay and Compose workspace expose the camera. Quick Settings uses immutable activity PendingIntents. The screen shortcut first dismisses its transparent entry activity so the assistant captures the underlying app. Protected/disabled screenshots leave search, music and camera actions available.

`LiveTranslationController` in `core/` supersedes older analyses, stabilizes individual text occurrences and geometry, bounds the translation cache, and rejects stale results after invalidation. Motion invalidation retains reusable string translations; engine/language/model resets clear them. Camera analyses use a fair 24-occurrence work budget; an already-settled screen drains all batches from one OCR result. Typed missing-model requirements and per-line failures allow other eligible regions to continue. The camera ViewModel and `ScreenTranslationService` own separate controllers. The screen service owns ImageReader latest-frame capture and the clean-input/presentation lifecycle described above; it does not require app-only capture to avoid self-recognition.

## Music

`core/Music.kt` defines songs, input evidence, replaceable `MusicRecognitionEngine` adapters, source-preserving result merging, portable FFT landmark extraction and offset-consistent offline matching. `MusicCaptureService` owns independent AudioRecord streams for microphone and allowed phone playback; it never mixes them or requests audio focus. The service bounds capture duration and supports cancellation, projection revocation and input-specific errors.

The offline-first engine list tries `OfflinePackRecognition` before `AudDRecognition`. Offline-only sessions contain no network engine. Credentials are encrypted with Android Keystore. Pack downloads are checksummed, bounded, validated and atomically installed. Optional notification access supplies reported playing-app metadata; it is not presented as verified per-UID capture. See [pack format and catalogue findings](song-packs.md).
