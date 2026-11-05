"""Builds the board and the river stones as GLB files, in pure Python (no Blender on this machine).

  python3 tools/build_board.py

Writes app/src/main/assets/models/board.glb and stone_0..3.glb. Same numbers as PitLayout.kt:
board 0.66 x 0.24 x 0.03 m, origin on the top centre, +X right, +Z toward the player; 12 spherical pits
(dia 6.0 cm, 1.5 cm deep, pitch 7.2 cm, rows at z = +/-4.5 cm) and two elliptical stores (8 x 14 cm,
1.5 cm deep, x = +/-28 cm). Walnut texture with darker bowls and a carved counterclockwise arrow along
the far edge. Stones: flattened noisy spheres 1.6 x 1.1 x 1.6 cm, 240 triangles, four greys, opaque.
"""
import json
import math
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngio import write_png  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")

W, D, T = 0.66, 0.24, 0.03
PITCH, PIT_R, PIT_D, ROW_Z = 0.072, 0.030, 0.015, 0.045
STORE_X, STORE_A, STORE_B, STORE_D = 0.28, 0.040, 0.070, 0.015
BOWL_R = (PIT_R ** 2 + PIT_D ** 2) / (2 * PIT_D)
PITS = [((i - 2.5) * PITCH, ROW_Z) for i in range(6)] + [((2.5 - i) * PITCH, -ROW_Z) for i in range(6)]
STORES = [(STORE_X, 0.0), (-STORE_X, 0.0)]
WOOD = (0x7A, 0x4A, 0x26)
BOWL = (0x3A, 0x24, 0x12)


def height(x, z):
    h = 0.0
    for (px, pz) in PITS:
        r = math.hypot(x - px, z - pz)
        if r < PIT_R:
            h = min(h, -PIT_D + (BOWL_R - math.sqrt(max(0.0, BOWL_R * BOWL_R - r * r))))
    for (sx, sz) in STORES:
        u2 = ((x - sx) / STORE_A) ** 2 + ((z - sz) / STORE_B) ** 2
        if u2 < 1:
            h = min(h, -STORE_D * math.sqrt(1 - u2))
    # a soft 3 mm round-over on the top edge
    edge = min(W / 2 - abs(x), D / 2 - abs(z))
    if edge < 0.003:
        h -= (1 - edge / 0.003) ** 2 * 0.0025
    return h


def bowl_shade(x, z):
    """0 on the flat top, toward 1 at the bottom of a pit or store (for the texture)."""
    s = 0.0
    for (px, pz) in PITS:
        r = math.hypot(x - px, z - pz) / PIT_R
        if r < 1.08:
            s = max(s, min(1.0, (1.08 - r) / 0.5))
    for (sx, sz) in STORES:
        u = math.sqrt(((x - sx) / STORE_A) ** 2 + ((z - sz) / STORE_B) ** 2)
        if u < 1.06:
            s = max(s, min(1.0, (1.06 - u) / 0.45))
    return s


def arrow_mask(x, z):
    """Counterclockwise arrow carved along the far edge: far row runs right to left (A..F), head at the left."""
    zc = -0.104
    if -0.19 <= x <= 0.20 and abs(z - zc) < 0.0018:
        return 1.0
    # head at x = -0.20: two strokes going back to the right
    for sgn in (-1, 1):
        # segment from (-0.205, zc) to (-0.188, zc + sgn*0.008)
        ax, az, bx, bz = -0.205, zc, -0.187, zc + sgn * 0.008
        t = max(0.0, min(1.0, ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / ((bx - ax) ** 2 + (bz - az) ** 2)))
        dx, dz = x - (ax + t * (bx - ax)), z - (az + t * (bz - az))
        if math.hypot(dx, dz) < 0.0018:
            return 1.0
    return 0.0


def board_texture(tw=1024, th=384):
    rnd = random.Random(7)
    phases = [(rnd.uniform(0, 6.28), rnd.uniform(18, 60), rnd.uniform(0.02, 0.08)) for _ in range(6)]
    rows = []
    for j in range(th):
        z = -D / 2 + (j + 0.5) / th * D
        row = bytearray(tw * 3)
        for i in range(tw):
            x = -W / 2 + (i + 0.5) / tw * W
            # grain: stripes along X, wobbling
            g = 0.0
            for (ph, fr, amp) in phases:
                g += math.sin(z * fr * 6.28 * 3 + math.sin(x * fr * 0.9 + ph) * 1.6 + ph) * amp
            g += math.sin((z + 0.003 * math.sin(x * 40)) * 900) * 0.035
            k = 1.0 + g
            r, gg, b = WOOD[0] * k, WOOD[1] * k, WOOD[2] * k
            s = bowl_shade(x, z)
            r, gg, b = r + (BOWL[0] - r) * s, gg + (BOWL[1] - gg) * s, b + (BOWL[2] - b) * s
            if arrow_mask(x, z) > 0:
                r, gg, b = r * 0.55, gg * 0.55, b * 0.55
            o = i * 3
            row[o] = max(0, min(255, int(r)))
            row[o + 1] = max(0, min(255, int(gg)))
            row[o + 2] = max(0, min(255, int(b)))
        rows.append(row)
    return write_png(None, tw, th, rows)


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, png):
        return self._view(png)

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "seed-pits tools/build_board.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


def build_board():
    step = 0.0044
    nx = int(round(W / step)) + 1
    nz = int(round(D / step)) + 1
    pos, nor, uv, idx = [], [], [], []
    H = [[height(-W / 2 + i * (W / (nx - 1)), -D / 2 + j * (D / (nz - 1))) for i in range(nx)] for j in range(nz)]
    dx, dz = W / (nx - 1), D / (nz - 1)
    for j in range(nz):
        for i in range(nx):
            x, z = -W / 2 + i * dx, -D / 2 + j * dz
            hl = H[j][max(0, i - 1)]; hr = H[j][min(nx - 1, i + 1)]
            hb = H[max(0, j - 1)][i]; hf = H[min(nz - 1, j + 1)][i]
            gx = (hr - hl) / (dx * (min(nx - 1, i + 1) - max(0, i - 1)))
            gz = (hf - hb) / (dz * (min(nz - 1, j + 1) - max(0, j - 1)))
            n = (-gx, 1.0, -gz); l = math.sqrt(sum(c * c for c in n))
            pos.append((x, H[j][i], z)); nor.append(tuple(c / l for c in n)); uv.append((i / (nx - 1), j / (nz - 1)))
    for j in range(nz - 1):
        for i in range(nx - 1):
            a = j * nx + i; b = a + 1; c = a + nx; d = c + 1
            idx += [a, c, b, b, c, d]
    # walls + bottom
    def quad(p0, p1, p2, p3, n, uvs):
        base = len(pos)
        for p, t in zip((p0, p1, p2, p3), uvs):
            pos.append(p); nor.append(n); uv.append(t)
        idx.extend([base, base + 1, base + 2, base, base + 2, base + 3])
    # front (+Z) and back (-Z) walls along the edge vertices
    for (j, nzv, zz) in ((nz - 1, 1.0, D / 2), (0, -1.0, -D / 2)):
        for i in range(nx - 1):
            x0, x1 = -W / 2 + i * dx, -W / 2 + (i + 1) * dx
            y0, y1 = H[j][i], H[j][i + 1]
            u0, u1 = i / (nx - 1), (i + 1) / (nx - 1)
            if nzv > 0:
                quad((x0, -T, zz), (x1, -T, zz), (x1, y1, zz), (x0, y0, zz), (0, 0, 1), [(u0, 0.98), (u1, 0.98), (u1, 1.0), (u0, 1.0)])
            else:
                quad((x1, -T, zz), (x0, -T, zz), (x0, y0, zz), (x1, y1, zz), (0, 0, -1), [(u1, 0.02), (u0, 0.02), (u0, 0.0), (u1, 0.0)])
    for (i, nxv, xx) in ((nx - 1, 1.0, W / 2), (0, -1.0, -W / 2)):
        for j in range(nz - 1):
            z0, z1 = -D / 2 + j * dz, -D / 2 + (j + 1) * dz
            y0, y1 = H[j][i], H[j + 1][i]
            v0, v1 = j / (nz - 1), (j + 1) / (nz - 1)
            if nxv > 0:
                quad((xx, -T, z1), (xx, -T, z0), (xx, y0, z0), (xx, y1, z1), (1, 0, 0), [(0.99, v1), (0.99, v0), (1.0, v0), (1.0, v1)])
            else:
                quad((xx, -T, z0), (xx, -T, z1), (xx, y1, z1), (xx, y0, z0), (-1, 0, 0), [(0.01, v0), (0.01, v1), (0.0, v1), (0.0, v0)])
    quad((-W / 2, -T, -D / 2), (W / 2, -T, -D / 2), (W / 2, -T, D / 2), (-W / 2, -T, D / 2), (0, -1, 0), [(0, 0), (1, 0), (1, 1), (0, 1)])
    g = Glb()
    pa = g.floats(pos, 3, minmax=True); na = g.floats(nor, 3); ta = g.floats(uv, 2); ia = g.indices(idx)
    img = g.image(board_texture())
    gltf = {
        "scene": 0, "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "Board", "mesh": 0}],
        "meshes": [{"name": "Board", "primitives": [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}, "indices": ia, "material": 0}]}],
        "materials": [{"name": "Walnut", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0, "roughnessFactor": 0.72}, "alphaMode": "OPAQUE", "doubleSided": False}],
        "textures": [{"source": 0, "sampler": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"bufferView": img, "mimeType": "image/png"}],
    }
    path = os.path.join(OUT, "board.glb")
    g.write(path, gltf)
    print("board.glb", len(idx) // 3, "triangles", os.path.getsize(path), "bytes")


STONE_GREYS = [(0xC9, 0xC4, 0xBC), (0xAF, 0xA9, 0xA0), (0x8E, 0x88, 0x80), (0x6E, 0x69, 0x62)]


def srgb_to_linear(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def build_stones():
    rings, segs = 10, 12
    rx, ry, rz = 0.008, 0.0055, 0.008
    rnd = random.Random(3)
    noise = {}
    pos = []
    for r in range(rings + 1):
        th = math.pi * r / rings
        for s in range(segs):
            ph = 2 * math.pi * s / segs
            key = (r, s % segs) if 0 < r < rings else (r, 0)
            k = noise.setdefault(key, 1.0 + rnd.uniform(-0.06, 0.06))
            pos.append((rx * k * math.sin(th) * math.cos(ph), ry * k * math.cos(th), rz * k * math.sin(th) * math.sin(ph)))
    idx = []
    for r in range(rings):
        for s in range(segs):
            a = r * segs + s; b = r * segs + (s + 1) % segs
            c = (r + 1) * segs + s; d = (r + 1) * segs + (s + 1) % segs
            if r != 0:
                idx += [a, b, c]
            if r != rings - 1:
                idx += [b, d, c]
    acc = [[0.0, 0.0, 0.0] for _ in pos]
    for t in range(0, len(idx), 3):
        a, b, c = (pos[idx[t]], pos[idx[t + 1]], pos[idx[t + 2]])
        u = [b[k] - a[k] for k in range(3)]; v = [c[k] - a[k] for k in range(3)]
        n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        for k in idx[t:t + 3]:
            for q in range(3):
                acc[k][q] += n[q]
    nor = []
    for (p, n) in zip(pos, acc):
        l = math.sqrt(sum(c * c for c in n)) or 1.0
        n = [c / l for c in n]
        # make sure normals point outward
        if sum(n[k] * p[k] for k in range(3)) < 0:
            n = [-c for c in n]
        nor.append(tuple(n))
    # the winding above is clockwise seen from outside for half the strips; flip to CCW where needed
    fixed = []
    for t in range(0, len(idx), 3):
        a, b, c = idx[t], idx[t + 1], idx[t + 2]
        pa, pb, pc = pos[a], pos[b], pos[c]
        u = [pb[k] - pa[k] for k in range(3)]; v = [pc[k] - pa[k] for k in range(3)]
        n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        cen = [(pa[k] + pb[k] + pc[k]) / 3 for k in range(3)]
        fixed += [a, b, c] if sum(n[k] * cen[k] for k in range(3)) > 0 else [a, c, b]
    for n, color in enumerate(STONE_GREYS):
        g = Glb()
        pa = g.floats(pos, 3, minmax=True); na = g.floats(nor, 3); ia = g.indices(fixed)
        lin = [srgb_to_linear(c) for c in color] + [1.0]
        gltf = {
            "scene": 0, "scenes": [{"nodes": [0]}],
            "nodes": [{"name": "Stone", "mesh": 0}],
            "meshes": [{"name": "Stone", "primitives": [{"attributes": {"POSITION": pa, "NORMAL": na}, "indices": ia, "material": 0}]}],
            "materials": [{"name": "RiverStone%d" % n, "pbrMetallicRoughness": {"baseColorFactor": lin, "metallicFactor": 0.0, "roughnessFactor": 0.6}, "alphaMode": "OPAQUE"}],
        }
        path = os.path.join(OUT, "stone_%d.glb" % n)
        g.write(path, gltf)
    print("stone_N.glb", len(fixed) // 3, "triangles each")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    build_stones()
    build_board()
