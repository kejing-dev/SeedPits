"""Synthesises the app's event sounds (16-bit mono WAV, 22.05 kHz) in pure Python: stones on wood.

  python3 tools/make_sounds.py --synth   writes the OLD synthesised set (not used since v0.1.5: real CC0 recordings,
                                         see tools/import_cc0_sounds.py)
  python3 tools/make_sounds.py --check   prints peak and RMS (dBFS) of every file

No ambience, only event sounds (profile B24). Everything is low-passed (nothing much above ~3 kHz).
Loudness (profile B20, v0.1.1): every file is soft-saturated until its RMS is at least -19 dBFS, its silent tail
is trimmed, and its peak is set to PEAK_DB (-1.5 dBFS, inside the -1 dBFS ceiling).

  python3 tools/make_sounds.py --loudness out.wav [seconds]   renders the app's default-volume mix of a played
                                                               game (same timings as BoardScene) and prints its
                                                               peak / RMS
"""
import math
import os
import random
import struct
import sys
import wave

RATE = 22050
DUCK_S = 0.13
# How long each kind keeps ringing (s), same table as SoundPlayer.RING
RING = {"grab": 0.6, "capture": 0.25, "sweep": 0.35, "win": 0.2, "again": 0.25}
DUCK = 0.5
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


PEAK_DB = -1.5
MIN_RMS_DB = -19.0


def rms_db(x):
    return 20 * math.log10(max(1e-9, math.sqrt(sum(v * v for v in x) / max(1, len(x)))))


def normalise(x, peak_db=None):
    """Loud but clean: trim the tail below -45 dB, soft-saturate (tanh) until RMS >= MIN_RMS_DB, peak to PEAK_DB."""
    m = max(abs(v) for v in x) or 1.0
    x = [v / m for v in x]
    # trim the silent tail (keeps at least 60 ms)
    thr = 10 ** (-45 / 20)
    end = len(x)
    while end > n(0.06) and abs(x[end - 1]) < thr:
        end -= 1
    x = x[:end]
    drive = 1.0
    y = x
    while True:
        y = [math.tanh(v * drive) for v in x]
        m = max(abs(v) for v in y) or 1.0
        y = [v / m * 10 ** (PEAK_DB / 20) for v in y]
        if rms_db(y) >= MIN_RMS_DB or drive > 40:
            break
        drive *= 1.25
    f = n(0.005)
    for i in range(min(f, len(y))):
        y[-1 - i] *= i / f
    return y


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


def read(name):
    with wave.open(os.path.join(OUT, name + ".wav")) as w:
        data = w.readframes(w.getnframes())
    return [v / 32767 for v in struct.unpack("<%dh" % (len(data) // 2), data)]


def resample(x, rate):
    if abs(rate - 1) < 1e-3:
        return x
    out = []
    t = 0.0
    while t < len(x) - 1:
        i = int(t); f = t - i
        out.append(x[i] * (1 - f) + x[i + 1] * f)
        t += rate
    return out


def loudness(path, seconds=30.0):
    global RATE
    with wave.open(os.path.join(OUT, "drop.wav")) as w:
        RATE = w.getframerate()   # the imported recordings are 44.1 kHz
    """The app's sounds at default volume (Volume.HIGH, gain 1.0, SoundPool volume 1) in the order and timing a
    game plays them: grab, a drop every 110 ms (AI 70 ms), store thuds, extra turn, capture, illegal tap, sweep, win."""
    rnd = random.Random(5)
    bank = {k: read(k) for k in ("grab", "drop", "drop_2", "drop_3", "store", "store_2", "store_3", "again", "capture", "illegal", "sweep", "win")}
    total = n(seconds)
    mix = [0.0] * total
    # Per-kind play volume, the same table as SoundPlayer.BASE (overlapping drops leave headroom).
    base = {"drop": 1.0, "store": 1.0, "grab": 1.0, "capture": 1.0, "sweep": 1.0}
    busy = [-1.0]
    def put(name, t, rate=1.0):
        kind = name.split("_")[0]
        k = base.get(kind, 1.0)
        if t < busy[0]:
            k *= DUCK         # same overlap rule as SoundPlayer: starts while another sound is still ringing → DUCK
        busy[0] = max(busy[0], t + RING.get(kind, DUCK_S))
        s = [v * k for v in resample(bank[name], rate)]
        o = n(t)
        for i, v in enumerate(s):
            if o + i < total:
                mix[o + i] += v
    t = 0.3
    move = 0
    while t < seconds - 3:
        ai = move % 2 == 1
        per = 0.07 if ai else 0.11
        stones = rnd.randint(3, 9)
        put("grab", t); t += 0.2
        for k in range(stones):
            last = k == stones - 1
            store = (k == stones - 3) or (last and move % 3 == 0)
            if stones <= 10 or k % 2 == 1 or last:
                if store:
                    put(rnd.choice(["store", "store_2", "store_3"]), t + per * 1.6, rnd.uniform(0.93, 1.07))
                else:
                    put(rnd.choice(["drop", "drop_2", "drop_3"]), t + per * 1.6, (0.9 + min(k + 4, 12) * 0.02) * rnd.uniform(0.93, 1.07))
            t += per
        t += 0.2
        if move % 3 == 0:
            put("again", t); t += 0.6
        elif move % 4 == 1:
            put("capture", t); t += 0.45
        if move % 5 == 2:
            put("illegal", t); t += 0.3
        t += 0.45 if not ai else 0.6   # the player looks / the AI thinks (>= 400 ms)
        move += 1
    put("sweep", seconds - 2.8)
    put("win", seconds - 1.9)
    peak = max(abs(v) for v in mix)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", max(-32767, min(32767, int(v * 32767)))) for v in mix))
    active = [v for v in mix]
    print("loudness %s: %.1fs peak %.1f dBFS (%s) rms %.1f dBFS" % (path, seconds, 20 * math.log10(peak), "CLIPS" if peak > 1 else "no clipping", rms_db(active)))
    return peak, rms_db(active)


def check():
    for f in sorted(os.listdir(OUT)):
        with wave.open(os.path.join(OUT, f)) as w:
            data = w.readframes(w.getnframes())
            rate = w.getframerate()
        s = struct.unpack("<%dh" % (len(data) // 2), data)
        peak = max(abs(v) for v in s) / 32767
        rms = math.sqrt(sum(v * v for v in s) / len(s)) / 32767
        print("%-14s %5.2fs peak %6.1f dBFS  rms %6.1f dBFS" % (f, len(s) / rate, 20 * math.log10(peak), 20 * math.log10(rms)))


if __name__ == "__main__":
    if "--loudness" in sys.argv:
        i = sys.argv.index("--loudness")
        loudness(sys.argv[i + 1], float(sys.argv[i + 2]) if len(sys.argv) > i + 2 else 30.0)
        sys.exit(0)
    if "--synth" in sys.argv:
        build()   # the old synthesised set; v0.1.5 ships Kenney CC0 recordings (tools/import_cc0_sounds.py)
    check()
