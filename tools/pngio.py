"""Tiny pure-Python PNG writer / rasteriser helpers (no Pillow on the build machines)."""
import struct
import zlib


def write_png(path, width, height, rows, alpha=False):
    """rows: list of bytearrays, each width * (4 if alpha else 3) bytes."""
    raw = b"".join(b"\x00" + bytes(r) for r in rows)
    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6 if alpha else 2, 0, 0, 0)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    if path:
        with open(path, "wb") as f:
            f.write(data)
    return data


def read_png_rgba(path):
    """Reads an 8-bit RGB/RGBA non-interlaced PNG (what write_png makes) into (w, h, rows of RGBA bytearrays)."""
    data = open(path, "rb").read()
    pos = 8
    idat = b""
    w = h = ctype = 0
    while pos < len(data):
        n = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            w, h, _, ctype = struct.unpack(">IIBB", body[:10])
        elif kind == b"IDAT":
            idat += body
        pos += 12 + n
    raw = zlib.decompress(idat)
    bpp = 4 if ctype == 6 else 3
    stride = w * bpp
    rows = []
    prev = bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        prev = line
        if bpp == 3:
            rgba = bytearray(w * 4)
            for x in range(w):
                rgba[x * 4:x * 4 + 3] = line[x * 3:x * 3 + 3]; rgba[x * 4 + 3] = 255
            rows.append(rgba)
        else:
            rows.append(line)
    return w, h, rows
