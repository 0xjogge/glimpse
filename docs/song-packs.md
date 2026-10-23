# Offline music recognition and song packs

Glimpse can identify songs entirely on device using installed reference packs. In **Settings → Music recognition**, choose **Offline first**, **Offline only**, or **Online only**. Online recognition uses your own [AudD API key](https://dashboard.audd.io/). Keys are encrypted with Android Keystore and are not included in Android backups.

Offline matching requires reference fingerprints for the recording being identified. A small matching engine alone does not provide a popular-song catalogue. **Glimpse currently has no bundled or hosted popular-song pack.** You can install packs from a publisher using a direct HTTPS URL and SHA-256 checksum. Downloads honor the Wi-Fi-only preference. In Offline only mode no online engine is instantiated.

## Catalogue research

- [ACRCloud offline recognition](https://docs.acrcloud.com/get-started/tutorials/recognize-custom-content-offline) supports a downloadable database built from custom uploaded content. It does not automatically license/distribute ACRCloud's broad online catalogue.
- [Ambient Music Mod](https://github.com/KieronQuinn/AmbientMusicMod) and [Now Playing](https://github.com/KieronQuinn/NowPlaying) depend on Shizuku/Sui and a modified Android System Intelligence APK. They are not drop-in libraries for an ordinary GrapheneOS application.
- [Olaf](https://github.com/JorenSix/Olaf) demonstrates lightweight local acoustic fingerprinting, but also needs a reference catalogue. No Olaf code or Google proprietary recognition/database components are included in Glimpse's music matcher.

The included version-1 matcher provides a practical small-catalogue path while a distributable popular-song catalogue is investigated. Recognition quality, storage, and false positives need evaluation with a representative real-music dataset before offering broad packs. A stored online result contains metadata, not a full-song reference; recognizing one excerpt online does not make every part of that song recognizable offline.

## Build a pack

Use reference recordings you can provide in your catalogue. The pack contains fingerprints and track metadata, not the original audio.

1. Convert reference tracks to 8 kHz, mono, 16-bit PCM WAV, for example:

   ```sh
   ffmpeg -i track.flac -ar 8000 -ac 1 -c:a pcm_s16le track.wav
   ```

2. Create a manifest alongside the WAV files:

   ```json
   {
     "id": "my-catalogue",
     "name": "My catalogue",
     "version": 1,
     "songs": [
       {
         "id": "track-1",
         "title": "Track title",
         "artist": "Artist",
         "file": "track.wav"
       }
     ]
   }
   ```

3. Build the pack with the standard-library-only tool:

   ```sh
   python3 scripts/build-song-pack.py manifest.json my-catalogue.json
   ```

   The tool prints the SHA-256 checksum. Host the resulting JSON at a direct HTTPS URL, and publish the checksum alongside it. Redirect/download landing pages are not supported.

4. In **Settings → Music recognition → Download a song pack**, enter the URL and checksum. Installation validates the format, IDs, counts, and checksum before replacing a previous version. Removal is available beside each installed pack.

## Version-1 format

`format` is `glimpse-landmarks-v1`; `id` contains 1–80 ASCII letters, digits, underscores or hyphens. `version` is a positive integer. `songs` contains `id`, `title`, `artist`, and `fingerprints`, an array of `[hash, frame]` pairs.

The fingerprint parameters are 8000 Hz mono PCM; 512-sample Hann FFT; 128-sample hop; two separated spectral peaks in bins 10–239, quantized by 2; peak-pair offsets of 4, 8 and 12 frames. Reference anchors use a stride of 4; queries use every anchor so the query does not have to start at a reference boundary. Matching votes for consistent recording-time offsets and rejects insufficient or ambiguous results.

Downloads are bounded to 64 MiB. Installation is limited to three packs and one million total reference fingerprints to bound the in-memory index. These are small-catalogue packs; the current uncompressed JSON representation is not intended for a global music database.

## Input and result attribution

Microphone and phone audio are captured independently and never mixed before recognition. Equal song results merge their input evidence; different songs remain separate. Silent/blocked playback capture is reported separately from a catalogue miss. Some players disable Android playback capture, including for protected media.

Optional notification access enables active-media-session lookup. An app name is shown only when a single locally playing media session reports the recognized title. It is labeled as **reported by media session**, not as a verified per-app audio capture. No app is guessed from the foreground screen. Captured audio remains in session memory and is released when recognition ends. Online-enabled modes send bounded WAV clips to AudD; offline-only mode does not send clips.
