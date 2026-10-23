import importlib.util
import json
import math
from pathlib import Path
import struct
import tempfile
import unittest
import wave

spec = importlib.util.spec_from_file_location("song_pack", Path(__file__).resolve().parents[1] / "build-song-pack.py")
song_pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(song_pack)


class SongPackTests(unittest.TestCase):
    def test_reference_pack_contains_fingerprints_and_metadata_without_audio_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with wave.open(str(root / "track.wav"), "wb") as audio:
                audio.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
                audio.writeframes(b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / 8000))) for i in range(16000)))
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"id": "test", "name": "Test", "version": 1, "songs": [{"id": "track", "title": "Track", "artist": "Artist", "file": "track.wav"}]}))
            pack = song_pack.build(manifest)
            self.assertEqual("glimpse-landmarks-v1", pack["format"])
            self.assertEqual("Track", pack["songs"][0]["title"])
            self.assertNotIn("file", pack["songs"][0])
            self.assertGreater(len(pack["songs"][0]["fingerprints"]), 24)
            # Portable reference format: a 440 Hz tone has bin 28, quantized to 14.
            self.assertIn([(14 << 12) | (14 << 4) | 8, 0], pack["songs"][0]["fingerprints"])

    def test_incompatible_audio_is_rejected_instead_of_silently_building_an_invalid_pack(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with wave.open(str(root / "track.wav"), "wb") as audio:
                audio.setparams((2, 2, 44100, 0, "NONE", "not compressed"))
                audio.writeframes(bytes(44100 * 4))
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"id": "test", "name": "Test", "version": 1, "songs": [{"id": "track", "title": "Track", "artist": "Artist", "file": "track.wav"}]}))
            with self.assertRaisesRegex(ValueError, "8000 Hz"):
                song_pack.build(manifest)


if __name__ == "__main__":
    unittest.main()
