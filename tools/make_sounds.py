"""Synthesises the app's event sounds (16-bit mono WAV, 22.05 kHz) in pure Python: stones on wood.

  python3 tools/make_sounds.py           writes app/src/main/assets/sounds/*.wav
  python3 tools/make_sounds.py --check   prints peak and RMS (dBFS) of every file

No ambience, only event sounds (profile B24). Everything is low-passed (nothing much above ~3 kHz) and
normalised to a per-sound peak between -4 and -8 dBFS, so the defaults are loud enough without clipping
(profile B20: peaks inside -1 dBFS).
"""
import math
import os
import random
import struct
import sys
import wave

RATE = 22050
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "sounds")


def n(sec):
    return int(RATE * sec)


def lowpass(x, fc):
    a = math.exp(-2 * math.pi * fc / RATE)
    y, p = [], 0.0
    for v in x:
        p = (1 - a) * v + a * p
        y.append(p)
    return y


def highpass(x, fc):
    lp = lowpass(x, fc)
    return [a - b for a, b in zip(x, lp)]


def env(i, attack, decay):
    t = i / RATE
    if t < attack:
        return t / attack
    return math.exp(-(t - attack) / decay)


def mix(*tracks):
    m = max(len(t) for t in tracks)
    out = [0.0] * m
    for t in tracks:
        for i, v in enumerate(t):
            out[i] += v
    return out


def pad(x, offset):
    return [0.0] * n(offset) + x


def click(rnd, length, f0, decay, noise=0.5, partials=(1.0, 2.76, 5.4)):
    """A stone touching wood: a short noise burst plus a few damped modes."""
    out = []
    for i in range(n(length)):
        t = i / RATE
        e = env(i, 0.0015, decay)
        tone = sum(math.sin(2 * math.pi * f0 * p * t + p) / (k + 1) for k, p in enumerate(partials))
        out.append(e * (tone * (1 - noise) + rnd.uniform(-1, 1) * noise * math.exp(-t / (decay * 0.4))))
    return lowpass(out, 3000)


def thud(rnd, length, f0, decay):
    out = []
    for i in range(n(length)):
        t = i / RATE
        e = env(i, 0.003, decay)
        f = f0 * (1 + 0.6 * math.exp(-t / 0.02))
        out.append(e * (math.sin(2 * math.pi * f * t) * 0.8 + rnd.uniform(-1, 1) * 0.25 * math.exp(-t / 0.015)))
    return lowpass(out, 1400)


def rustle(rnd, length):
    out = []
    for i in range(n(length)):
        t = i / RATE
        e = math.sin(math.pi * min(1.0, t / length)) ** 0.7
        grain = rnd.uniform(-1, 1) if rnd.random() < 0.25 else 0.0
        out.append(e * grain)
    return lowpass(highpass(out, 500), 2800)


def normalise(x, peak_db):
    m = max(abs(v) for v in x) or 1.0
    k = 10 ** (peak_db / 20) / m
    # 5 ms fade out
    out = [v * k for v in x]
    f = n(0.005)
    for i in range(min(f, len(out))):
        out[-1 - i] *= i / f
    return out


def write(name, x):
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", max(-32767, min(32767, int(v * 32767)))) for v in x))


def build():
    os.makedirs(OUT, exist_ok=True)
    rnd = random.Random(11)
    # drop: three variants of a pebble on wood ("嗒")
    for k, f0 in enumerate((1180, 1320, 1050)):
        name = "drop" if k == 0 else "drop_%d" % (k + 1)
        write(name, normalise(click(rnd, 0.12, f0, 0.025, noise=0.45), -5))
    # store: duller and lower ("咚")
    for k, f0 in enumerate((210, 235, 190)):
        name = "store" if k == 0 else "store_%d" % (k + 1)
        x = mix(thud(rnd, 0.22, f0, 0.06), [v * 0.4 for v in click(rnd, 0.08, 700, 0.015, noise=0.6)])
        write(name, normalise(x, -4))
    # grab: stones rubbing (200 ms)
    write("grab", normalise(rustle(rnd, 0.22), -8))
    # again: a short bright chime, two pebbles in a fifth
    x = mix(click(rnd, 0.35, 880, 0.12, noise=0.05, partials=(1.0, 2.0)), pad(click(rnd, 0.35, 1320, 0.12, noise=0.05, partials=(1.0, 2.0)), 0.07))
    write("again", normalise(x, -6))
    # capture: a handful swept over the wood, then the store thud
    sweep = rustle(rnd, 0.32)
    x = mix(sweep, pad(thud(rnd, 0.25, 200, 0.07), 0.3), pad(click(rnd, 0.1, 900, 0.02), 0.31))
    write("capture", normalise(x, -4))
    # illegal: a low wooden knock
    write("illegal", normalise(thud(rnd, 0.18, 140, 0.04), -6))
    # sweep (collect leftovers): dense thuds over 600 ms
    parts = [pad(thud(rnd, 0.15, 190 + rnd.uniform(-20, 20), 0.035), i * 0.06) for i in range(10)]
    write("sweep", normalise(mix(*parts), -5))
    # win: five stones dropping into the store, rising
    parts = [pad(mix(thud(rnd, 0.2, 200 + i * 25, 0.05), [v * 0.5 for v in click(rnd, 0.1, 900 + i * 120, 0.03, noise=0.3)]), i * 0.13) for i in range(5)]
    write("win", normalise(mix(*parts), -4))
    # lose: two low knocks falling
    parts = [pad(thud(rnd, 0.22, 180 - i * 30, 0.06), i * 0.18) for i in range(2)]
    write("lose", normalise(mix(*parts), -6))
    # draw: two symmetric taps
    parts = [pad(click(rnd, 0.12, 1000, 0.025), 0.0), pad(click(rnd, 0.12, 1000, 0.025), 0.2)]
    write("draw", normalise(mix(*parts), -6))


def check():
    for f in sorted(os.listdir(OUT)):
        with wave.open(os.path.join(OUT, f)) as w:
            data = w.readframes(w.getnframes())
        s = struct.unpack("<%dh" % (len(data) // 2), data)
        peak = max(abs(v) for v in s) / 32767
        rms = math.sqrt(sum(v * v for v in s) / len(s)) / 32767
        print("%-14s %5.2fs peak %6.1f dBFS  rms %6.1f dBFS" % (f, len(s) / RATE, 20 * math.log10(peak), 20 * math.log10(rms)))


if __name__ == "__main__":
    if "--check" not in sys.argv:
        build()
    check()
