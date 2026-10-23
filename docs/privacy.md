# Data and privacy

Glimpse is local-first. It has no Glimpse account or application backend.

- Assistant screenshots and camera frames are processed in memory. Starting a new session/closing an image releases the app's current session reference.
- Camera and OCR processing occur only while using those features. Live translation processes the latest frame locally and caches text translations in session memory. Live screen translation runs in an explicitly started foreground service with Android's capture indicator and stop controls.
- Bundled ML Kit OCR and downloaded translation models perform inference on-device without uploading the images/text being processed.
- Google's SDKs can transmit operational/diagnostic data. Downloading translation language packs contacts Google's model servers. See [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
- Search/online translation sends the selected text or crop to the chosen provider. Browser text searches/translation use your browser; visual search uses a provider-owned page in Android WebView and that page's upload control. WebView cookies/site data can persist in app-private storage.
- Shared/visual-search crops are JPEG-encoded in app-private cache, exposed through a narrowly scoped FileProvider, and may remain until cache cleanup. Old abandoned crops are removed when preparing a later crop. Exported images are written only to the location or app you choose.
- Preferences and downloaded models live in app-private storage. Android backup is disabled. Clearing app data removes them.
- Music recording starts only when you press Listen. Microphone and playback streams stay separate; bounded clips are kept in session memory. Offline-only music recognition never calls the online engine. Online-enabled recognition sends short WAV clips to AudD using your API key. Glimpse does not operate a music backend.
- AudD API keys are encrypted in app-private storage using Android Keystore. Offline song packs contain reference fingerprints and metadata and are fetched from the publisher URL you enter. An optional notification listener reads active media-session metadata to label playing apps; Glimpse does not save notification contents.

Permissions: **Camera** for camera capture; **Microphone/audio recording** for music inputs (Android also requires it for playback capture); **Internet/network state** for models, song packs, online recognition and search. Live screen translation uses optional **display over other apps** access. Screen and playback capture use Android's per-session MediaProjection grant and foreground services. Optional **notification access** enables active-media-session app attribution. The photo/document pickers grant access to selected files without broad storage permission. Contacts, location, and accessibility-service access are not required.

GrapheneOS's Network permission can block Glimpse's network access after model download; local OCR/translation continues to work. External browser actions use the browser's own network permission.
