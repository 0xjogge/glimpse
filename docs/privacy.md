# Data and privacy

Glimpse is local-first. It has no Glimpse account or application backend.

- Assistant screenshots and camera frames are processed in memory. Starting a new session/closing an image releases the app's current session reference.
- Camera and OCR processing occur only while using those features. Live translation processes the latest frame locally and caches text translations in session memory.
- Bundled ML Kit OCR and downloaded translation models perform inference on-device without uploading the images/text being processed.
- Google's SDKs can transmit operational/diagnostic data. Downloading translation language packs contacts Google's model servers. See [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
- Search/online translation sends the selected text or crop to the chosen provider. Browser text searches/translation use your browser; visual search uses a provider-owned page in Android WebView and that page's upload control. WebView cookies/site data can persist in app-private storage.
- Shared/visual-search crops are JPEG-encoded in app-private cache, exposed through a narrowly scoped FileProvider, and may remain until cache cleanup. Old abandoned crops are removed when preparing a later crop. Exported images are written only to the location or app you choose.
- Preferences and downloaded models live in app-private storage. Android backup is disabled. Clearing app data removes them.

Permissions: **Camera** for camera capture; **Internet/network state** for model downloads and visual-search pages. The photo picker/document picker grant access to selected files without broad storage permission. Microphone, contacts, location, and accessibility-service access are not required.

GrapheneOS's Network permission can block Glimpse's network access after model download; local OCR/translation continues to work. External browser actions use the browser's own network permission.
