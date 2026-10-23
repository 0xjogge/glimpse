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

## Android integration

`GlimpseVoiceService`/`GlimpseSessionService` receive screenshots through the selected default-assistant role. The platform also requires a recognition-service component in assistant metadata; the visual-only implementation rejects speech requests and requests no microphone permission.

The assistant's native opening panel and Compose workspace expose the camera. Quick Settings uses immutable activity PendingIntents. The screen shortcut first dismisses its transparent entry activity so the assistant captures the underlying app. Protected/disabled screenshots produce an explanatory message while image/camera entry points remain usable.
