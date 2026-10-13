#!/usr/bin/env python3
"""Generate the app's short UI sound effects as 16-bit PCM WAVs.

Deterministic and dependency-free (Python stdlib only), mirroring the desktop's
fixture approach: the generator is committed alongside its output so the assets
are reproducible and reviewable. Offline -- nothing is downloaded.

Run from the repo root:
    python3 android-app/tools/gen_sfx.py

Outputs into android-app/app/src/main/res/raw/.
"""
import math
import os
import struct
import wave

SR = 44100
OUT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "res", "raw",
)


def write_wav(name, samples):
    path = os.path.join(OUT_DIR, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = bytearray()
        for s in samples:
            v = max(-1.0, min(1.0, s))
            frames += struct.pack("<h", int(v * 32767))
        w.writeframes(bytes(frames))
    print(f"wrote {path} ({len(samples)} frames, {len(samples) / SR * 1000:.0f} ms)")


def rng(seed):
    """Tiny deterministic LCG so noise bursts are reproducible."""
    while True:
        seed = (1103515245 * seed + 12345) & 0x7FFFFFFF
        yield seed / 0x7FFFFFFF * 2.0 - 1.0


def fade_edges(buf, in_ms=1.0, out_ms=6.0):
    n_in = int(SR * in_ms / 1000)
    n_out = int(SR * out_ms / 1000)
    for i in range(min(n_in, len(buf))):
        buf[i] *= i / max(1, n_in)
    for i in range(min(n_out, len(buf))):
        buf[len(buf) - 1 - i] *= i / max(1, n_out)


def tap():
    """A soft, woody tick: a short noise transient plus two quick partials."""
    dur = 0.055
    n = int(SR * dur)
    out = []
    noise = rng(1337)
    for i in range(n):
        t = i / SR
        body = (0.6 * math.sin(2 * math.pi * 880 * t)
                + 0.3 * math.sin(2 * math.pi * 1320 * t))
        body *= math.exp(-t / 0.011)
        trans = next(noise) * math.exp(-t / 0.003) * 0.4
        out.append((body + trans) * 0.5)
    fade_edges(out, in_ms=0.8, out_ms=8.0)
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    write_wav("sfx_tap.wav", tap())


if __name__ == "__main__":
    main()
