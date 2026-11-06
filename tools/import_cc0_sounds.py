"""Imports the app's event sounds from Kenney's CC0 packs (real recordings), replacing the old synthesised set.

  python3 tools/import_cc0_sounds.py [~/Downloads/cc0-sounds]

For every entry of MAP: decode the .ogg with macOS afconvert (mono, 16-bit, 44.1 kHz), low-pass at 4 kHz
(no harsh top end, profile D51), trim silence, lift quiet ones with a gentle soft clip until RMS >= -19 dBFS
(profile B20), set the peak to -1.5 dBFS, write app/src/main/assets/sounds/<name>.wav. Prints the source table
for build/sound-sources.md. Variants: <kind>.wav, <kind>_2.wav, <kind>_3.wav (SoundPlayer picks one at random
and adds a ±5 % pitch jitter).
"""
import math
import os
import struct
import subprocess
import sys
import tempfile
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
RATE = 44100
PEAK_DB = -1.5
MIN_RMS_DB = -19.0
MAX_DRIVE = 2.5   # a gentle soft clip at most: harder drive starts to sound processed (D51)
TAIL_DB = -38    # trim the quiet decay below this

# (asset name, Kenney pack, file, real recording of a physical event (phys) or interface cue (ui))
MAP = [
    ("drop", "kenney_casino-audio", "chip-lay-1.ogg", "phys"),
    ("drop_2", "kenney_casino-audio", "chip-lay-3.ogg", "phys"),
    ("drop_3", "kenney_casino-audio", "chips-collide-2.ogg", "phys"),
    ("store", "kenney_impact-sounds", "impactWood_light_000.ogg", "phys"),
    ("store_2", "kenney_impact-sounds", "impactWood_light_001.ogg", "phys"),
    ("store_3", "kenney_impact-sounds", "impactWood_light_002.ogg", "phys"),
    ("grab", "kenney_casino-audio", "chips-handle-6.ogg", "phys"),
    ("grab_2", "kenney_casino-audio", "chips-handle-5.ogg", "phys"),
    ("capture", "kenney_casino-audio", "chips-stack-4.ogg", "phys"),
    ("capture_2", "kenney_casino-audio", "chips-stack-6.ogg", "phys"),
    ("sweep", "kenney_casino-audio", "chips-stack-3.ogg", "phys"),
    ("sweep_2", "kenney_casino-audio", "chips-stack-2.ogg", "phys"),
    ("illegal", "kenney_impact-sounds", "impactPlank_medium_000.ogg", "phys"),
    ("illegal_2", "kenney_impact-sounds", "impactPlank_medium_001.ogg", "phys"),
    ("win", "kenney_casino-audio", "chips-stack-5.ogg", "phys"),
    ("again", "kenney_interface-sounds", "confirmation_001.ogg", "ui"),
    ("lose", "kenney_interface-sounds", "minimize_002.ogg", "ui"),
    ("draw", "kenney_interface-sounds", "pluck_001.ogg", "ui"),
]


def decode(path):
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False).name
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@%d" % RATE, "-c", "1", path, tmp], check=True)
    raw = open(tmp, "rb").read()
    os.remove(tmp)
    # Minimal RIFF reader (afconvert may write WAVE_FORMAT_EXTENSIBLE, which the wave module rejects).
    pos, ch, data = 12, 1, b""
    while pos + 8 <= len(raw):
        cid, size = raw[pos:pos + 4], struct.unpack("<I", raw[pos + 4:pos + 8])[0]
        if cid == b"fmt ":
            ch = struct.unpack("<H", raw[pos + 10:pos + 12])[0]
        elif cid == b"data":
            data = raw[pos + 8:pos + 8 + size]
        pos += 8 + size + (size & 1)
    v = struct.unpack("<%dh" % (len(data) // 2), data)
    if ch > 1:
        v = [sum(v[i:i + ch]) / ch for i in range(0, len(v) - ch + 1, ch)]
    return [s / 32768 for s in v]


def lowpass(x, fc, q=0.707):
    w0 = 2 * math.pi * fc / RATE
    cw, sw = math.cos(w0), math.sin(w0)
    alpha = sw / (2 * q)
    b0, b1, b2 = (1 - cw) / 2, 1 - cw, (1 - cw) / 2
    a0, a1, a2 = 1 + alpha, -2 * cw, 1 - alpha
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y = [0.0] * len(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o
        y[i] = o
    return y


def rms_db(x):
    return 20 * math.log10(max(1e-9, math.sqrt(sum(v * v for v in x) / max(1, len(x)))))


# Drops come every 70–110 ms while sowing: their tails are cut so consecutive stones do not pile up into clipping.
MAX_LEN = {"drop": 0.12, "store": 0.22}
# Short clicks lose loudness to their own brevity: the drops may be driven a little harder (still a soft clip).
DRIVE_FOR = {"drop": 4.0}
RMS_FOR = {"drop": -16.5}


def process(x, max_len=None, max_drive=None, min_rms=None):
    max_drive = max_drive or MAX_DRIVE
    min_rms = min_rms or MIN_RMS_DB
    x = lowpass(lowpass(x, 4000), 4000)
    m = max(abs(v) for v in x) or 1.0
    x = [v / m for v in x]
    start = next((i for i, v in enumerate(x) if abs(v) > 10 ** (-50 / 20)), 0)
    start = max(0, start - int(0.002 * RATE))
    end = len(x)
    while end > start + int(0.06 * RATE) and abs(x[end - 1]) < 10 ** (TAIL_DB / 20):
        end -= 1
    x = x[start:end]
    if max_len:
        n = int(max_len * RATE)
        if len(x) > n:
            x = x[:n]
            fade = int(0.025 * RATE)
            for i in range(fade):
                x[-1 - i] *= i / fade
    drive = 1.0
    while True:
        y = [math.tanh(v * drive) for v in x]
        m = max(abs(v) for v in y) or 1.0
        y = [v / m * 10 ** (PEAK_DB / 20) for v in y]
        if rms_db(y) >= min_rms or drive >= max_drive:
            break
        drive *= 1.2
    f = int(0.004 * RATE)
    for i in range(min(f, len(y))):
        y[-1 - i] *= i / f
    return y, drive


def main():
    src = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/cc0-sounds")
    os.makedirs(OUT, exist_ok=True)
    keep = {name + ".wav" for name, *_ in MAP}
    for f in os.listdir(OUT):
        if f.endswith(".wav") and f not in keep:
            os.remove(os.path.join(OUT, f))
    for name, pack, fname, kind in MAP:
        y, drive = process(decode(os.path.join(src, pack, "Audio", fname)), MAX_LEN.get(name.split("_")[0]), DRIVE_FOR.get(name.split("_")[0]), RMS_FOR.get(name.split("_")[0]))
        with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", max(-32767, min(32767, int(v * 32767)))) for v in y))
        print("%-10s %-24s %-28s %s %.2fs rms %.1f dBFS drive %.2f" % (name, pack, fname, kind, len(y) / RATE, rms_db(y), drive))


if __name__ == "__main__":
    main()
