"""Builds Seed Pits' layered PICO icon and the adaptive launcher icon, in pure Python.

  python3 tools/build_icon.py [--export path/icon.png]

Design (design-package appIcon): ochre ground (#A0522D, not used by another factory app), a short light-wood
board (#E8C9A0) with two dark pits, three pale river stones (#D9D4CC) in the right pit and one in mid-air.
Writes app/src/main/res:
  drawable/icon_3d_layer_0..2.png + icon_3d_sdf_0..2.png   PICO OS 6 layered icon (1024 px, SDF = inward
                                                            edge distance x 8, as in the Cloud Air template)
  mipmap-xxxhdpi/ic_spatial_launcher.png                    flattened round icon
  drawable/ic_launcher_fg.xml + values/ic_launcher_bg.xml   adaptive icon: vector foreground on a flat colour
--export writes a 512 px flattened PNG (the factory's apps/<id>/icon.png).
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngio import write_png  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
OCHRE = (0xA0, 0x52, 0x2D)
OCHRE_LIGHT = (0xB4, 0x63, 0x3A)
WOOD = (0xE8, 0xC9, 0xA0)
WOOD_EDGE = (0xC9, 0xA4, 0x76)
PIT = (0x5A, 0x3A, 0x22)
STONE = (0xD9, 0xD4, 0xCC)
STONE_SHADE = (0xB9, 0xB2, 0xA8)

# shapes in 1024 px icon space (y down)
BOARD = (512, 600, 396, 122, 104)       # cx, cy, half-w, half-h, radius
PITS = [(366, 590, 104, 64), (658, 590, 104, 64)]  # bowls seen at an angle
STONES = [(622, 602, 42, 29), (694, 598, 40, 28), (658, 572, 41, 29)]
AIR = (530, 360, 44, 32)
ARC = [(420, 470, 9), (462, 420, 10), (498, 388, 11)]  # the path the stone flies along


def d_round_rect(x, y, cx, cy, hw, hh, r):
    qx = abs(x - cx) - (hw - r)
    qy = abs(y - cy) - (hh - r)
    return math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - r


def d_circle(x, y, cx, cy, r):
    return math.hypot(x - cx, y - cy) - r


def d_ellipse(x, y, cx, cy, rx, ry):
    k = math.hypot((x - cx) / rx, (y - cy) / ry)
    return (k - 1) * min(rx, ry)


def cov(d):
    return max(0.0, min(1.0, 0.5 - d))


def layer(fn):
    """fn(x, y) -> (r, g, b, a in 0..1). Returns RGBA rows."""
    rows = []
    for j in range(S):
        row = bytearray(S * 4)
        y = j + 0.5
        for i in range(S):
            r, g, b, a = fn(i + 0.5, y)
            o = i * 4
            row[o] = int(r); row[o + 1] = int(g); row[o + 2] = int(b); row[o + 3] = int(round(a * 255))
        rows.append(row)
    return rows


def bg(x, y):
    t = min(1.0, math.hypot(x - 430, y - 380) / 720)
    c = [OCHRE_LIGHT[k] + (OCHRE[k] - OCHRE_LIGHT[k]) * t for k in range(3)]
    return c[0], c[1], c[2], 1.0


def board(x, y):
    cx, cy, hw, hh, r = BOARD
    if abs(x - cx) > hw + 2 or abs(y - cy) > hh + 2:
        return 0, 0, 0, 0.0
    a = cov(d_round_rect(x, y, cx, cy, hw, hh, r))
    if a <= 0:
        return 0, 0, 0, 0.0
    col = list(WOOD)
    # a darker lip along the bottom edge
    e = cov(d_round_rect(x, y, cx, cy + 14, hw, hh - 14, r))
    if e < 1:
        col = [WOOD_EDGE[k] + (WOOD[k] - WOOD_EDGE[k]) * e for k in range(3)]
    for (px, py, prx, pry) in PITS:
        p = cov(d_ellipse(x, y, px, py, prx, pry))
        if p > 0:
            col = [col[k] + (PIT[k] - col[k]) * p for k in range(3)]
    return col[0], col[1], col[2], a


def stones(x, y):
    a = 0.0
    col = list(STONE)
    for (cx, cy, r) in ARC:
        c = cov(d_circle(x, y, cx, cy, r))
        if c > 0:
            a = max(a, c * 0.9)
            col = list(STONE)
    for (cx, cy, rx, ry) in STONES + [AIR]:
        if abs(x - cx) > rx + 2 or abs(y - cy) > ry + 2:
            continue
        c = cov(d_ellipse(x, y, cx, cy, rx, ry))
        if c > 0:
            sh = cov(d_ellipse(x, y, cx - rx * 0.18, cy - ry * 0.22, rx * 0.95, ry * 0.9))
            local = [STONE_SHADE[k] + (STONE[k] - STONE_SHADE[k]) * sh for k in range(3)]
            col = [col[k] * (1 - c) + local[k] * c if a > 0 else local[k] for k in range(3)]
            a = max(a, c)
    return col[0], col[1], col[2], a


def sdf(rows):
    """Inward distance to the layer edge (chamfer 3-4), x 8, capped at 255."""
    INF = 10 ** 9
    d = [[0 if row[i * 4 + 3] < 128 else INF for i in range(S)] for row in rows]
    for j in range(S):
        dj = d[j]; up = d[j - 1] if j else None
        for i in range(S):
            if dj[i] == 0:
                continue
            v = dj[i]
            if i: v = min(v, dj[i - 1] + 3)
            if up is not None:
                v = min(v, up[i] + 3)
                if i: v = min(v, up[i - 1] + 4)
                if i < S - 1: v = min(v, up[i + 1] + 4)
            dj[i] = v
    for j in range(S - 1, -1, -1):
        dj = d[j]; dn = d[j + 1] if j < S - 1 else None
        for i in range(S - 1, -1, -1):
            if dj[i] == 0:
                continue
            v = dj[i]
            if i < S - 1: v = min(v, dj[i + 1] + 3)
            if dn is not None:
                v = min(v, dn[i] + 3)
                if i < S - 1: v = min(v, dn[i + 1] + 4)
                if i: v = min(v, dn[i - 1] + 4)
            dj[i] = v
    out = []
    for j in range(S):
        row = bytearray(S * 4)
        for i in range(S):
            v = d[j][i]
            g = 0 if v == 0 else min(255, (v // 3) * 8)
            o = i * 4
            row[o] = row[o + 1] = row[o + 2] = row[o + 3] = g
        out.append(row)
    return out


def flatten(layers, size=S, round_mask=True):
    out = []
    step = S / size
    for j in range(size):
        row = bytearray(size * 4)
        for i in range(size):
            sx = int((i + 0.5) * step); sy = int((j + 0.5) * step)
            c = [0.0, 0.0, 0.0]
            for L in layers:
                o = sx * 4
                a = L[sy][o + 3] / 255
                for k in range(3):
                    c[k] = c[k] * (1 - a) + L[sy][o + k] * a
            alpha = 1.0
            if round_mask:
                alpha = cov(math.hypot(i + 0.5 - size / 2, j + 0.5 - size / 2) - size / 2 + 0.5)
            o = i * 4
            row[o:o + 3] = bytes(int(v) for v in c)
            row[o + 3] = int(alpha * 255)
        out.append(row)
    return out


def vector_foreground():
    """Adaptive-icon foreground (108 dp viewport, content inside the 66 dp safe zone): same shapes, scaled."""
    k = 66 / 1024
    off = 21
    def X(v): return off + v * k
    cx, cy, hw, hh, r = BOARD
    rr = r * k
    def rrect(x0, y0, x1, y1, rad):
        return ("M%.2f,%.2f h%.2f a%.2f,%.2f 0 0 1 %.2f,%.2f v%.2f a%.2f,%.2f 0 0 1 %.2f,%.2f h%.2f a%.2f,%.2f 0 0 1 %.2f,%.2f v%.2f a%.2f,%.2f 0 0 1 %.2f,%.2f z"
                % (x0 + rad, y0, x1 - x0 - 2 * rad, rad, rad, rad, rad, y1 - y0 - 2 * rad, rad, rad, -rad, rad, -(x1 - x0 - 2 * rad), rad, rad, -rad, -rad, -(y1 - y0 - 2 * rad), rad, rad, rad, -rad))
    def circ(x, y, rx, ry=None):
        ry = ry or rx
        return "M%.2f,%.2f a%.2f,%.2f 0 1 0 %.2f,0 a%.2f,%.2f 0 1 0 %.2f,0 z" % (x - rx, y, rx, ry, 2 * rx, rx, ry, -2 * rx)
    paths = [
        ("#FFE8C9A0", rrect(X(cx - hw), X(cy - hh), X(cx + hw), X(cy + hh), rr)),
    ] + [("#FF5A3A22", circ(X(px), X(py), prx * k, pry * k)) for (px, py, prx, pry) in PITS] + [
        ("#E6D9D4CC", circ(X(cx_), X(cy_), r_ * k)) for (cx_, cy_, r_) in ARC] + [
        ("#FFD9D4CC", circ(X(sx), X(sy), rx * k, ry * k)) for (sx, sy, rx, ry) in STONES + [AIR]
    ]
    body = "\n".join('    <path android:fillColor="%s" android:pathData="%s"/>' % (c, p) for c, p in paths)
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n' + body + "\n</vector>\n")


def main():
    export = None
    if "--export" in sys.argv:
        export = sys.argv[sys.argv.index("--export") + 1]
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
    os.makedirs(os.path.join(RES, "mipmap-xxxhdpi"), exist_ok=True)
    os.makedirs(os.path.join(RES, "mipmap-anydpi"), exist_ok=True)
    L = [layer(bg), layer(board), layer(stones)]
    for i, rows in enumerate(L):
        write_png(os.path.join(RES, "drawable", "icon_3d_layer_%d.png" % i), S, S, rows, alpha=True)
        write_png(os.path.join(RES, "drawable", "icon_3d_sdf_%d.png" % i), S, S, sdf(rows), alpha=True)
        print("layer", i)
    flat = flatten(L, 432)
    write_png(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"), 432, 432, flat, alpha=True)
    with open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w") as f:
        f.write(vector_foreground())
    with open(os.path.join(RES, "values", "ic_launcher_bg.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_bg">#A0522D</color>\n</resources>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(RES, "mipmap-anydpi", name), "w") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    '    <background android:drawable="@color/ic_launcher_bg" />\n    <foreground android:drawable="@drawable/ic_launcher_fg" />\n'
                    '    <monochrome android:drawable="@drawable/ic_launcher_fg" />\n</adaptive-icon>\n')
    if export:
        write_png(export, 512, 512, flatten(L, 512), alpha=True)
        print("exported", export)


if __name__ == "__main__":
    main()
