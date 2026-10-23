#!/usr/bin/env python3
"""Build a downloadable glimpse-landmarks-v1 reference pack from 8 kHz mono WAVs.

The input manifest contains id, name, version and songs[{id,title,artist,file}].
Audio files are relative to the manifest. Only fingerprints and metadata enter the pack.
"""

import argparse
import cmath
import hashlib
import json
import math
from pathlib import Path
import struct
import wave

WINDOW = 512
HOP = 128
REVERSE = [int(f"{i:09b}"[::-1], 2) for i in range(WINDOW)]
HANN = [0.5 - 0.5 * math.cos(2 * math.pi * i / (WINDOW - 1)) for i in range(WINDOW)]
ROOTS = {
    length: [cmath.exp(-2j * math.pi * i / length) for i in range(length // 2)]
    for length in (2, 4, 8, 16, 32, 64, 128, 256, 512)
}


def fingerprints(samples):
    peaks = []
    for start in range(0, len(samples) - WINDOW + 1, HOP):
        spectrum = [complex(samples[start + i] / 32768 * HANN[i]) for i in range(WINDOW)]
        spectrum = [spectrum[i] for i in REVERSE]
        for length, roots in ROOTS.items():
            half = length // 2
            for block in range(0, WINDOW, length):
                for offset, root in enumerate(roots):
                    even, odd = block + offset, block + offset + half
                    value = spectrum[odd] * root
                    spectrum[odd] = spectrum[even] - value
                    spectrum[even] += value
        power = [value.real**2 + value.imag**2 for value in spectrum[: WINDOW // 2]]
        candidates = sorted(
            (i for i in range(10, 240) if power[i] > 0.0001 and power[i] > power[i - 1] and power[i] >= power[i + 1]),
            key=lambda i: -power[i],
        )
        selected = []
        for frequency in candidates:
            if all(abs(frequency - other) >= 5 for other in selected) and power[frequency] >= power[candidates[0]] * 0.15:
                selected.append(frequency)
            if len(selected) == 2:
                break
        peaks.append([frequency // 2 for frequency in selected])
    return [
        [(first << 12) | (second << 4) | delta, frame]
        for frame in range(0, len(peaks), 4)
        for delta in (4, 8, 12)
        if frame + delta < len(peaks)
        for first in peaks[frame]
        for second in peaks[frame + delta]
    ]


def build(manifest_path):
    manifest = json.loads(manifest_path.read_text())
    result = {"format": "glimpse-landmarks-v1", **{key: manifest[key] for key in ("id", "name", "version")}, "songs": []}
    total = 0
    for song in manifest["songs"]:
        with wave.open(str(manifest_path.parent / song["file"]), "rb") as audio:
            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 8000):
                raise ValueError(f"{song['file']}: convert to mono, 16-bit PCM, 8000 Hz WAV first")
            if audio.getnframes() > 8000 * 60 * 15:
                raise ValueError("Each reference track must be at most 15 minutes")
            raw = audio.readframes(audio.getnframes())
            values = struct.unpack(f"<{len(raw) // 2}h", raw)
        prints = fingerprints(values)
        if len(prints) < 24:
            raise ValueError(f"{song['file']}: not enough reference audio")
        total += len(prints)
        if total > 1_000_000:
            raise ValueError("Split this catalogue into smaller packs (one million fingerprints maximum)")
        result["songs"].append({**{key: song[key] for key in ("id", "title", "artist")}, "fingerprints": prints})
    if not result["songs"]:
        raise ValueError("A pack needs at least one song")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    data = json.dumps(build(args.manifest), ensure_ascii=False, separators=(",", ":")).encode()
    if len(data) > 64 * 1024 * 1024:
        raise ValueError("Split this catalogue into packs smaller than 64 MB")
    args.output.write_bytes(data)
    print(f"{args.output}: {len(data)} bytes")
    print(f"SHA-256: {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
