#!/usr/bin/env python3
"""Generates the Oguri VPN Switch launcher icon into res/mipmap-*.

Same asset format as the sibling Oguri apps (steps-app, alarm-app):
per-density ic_launcher_foreground.png (RGBA, glyph on transparency,
used as the adaptive-icon foreground over @color/ic_bg) and
ic_launcher.png (the flattened legacy icon). Master art is drawn at
864x864 (2x the xxxhdpi foreground) and downscaled with LANCZOS.

The glyph is VPN Switch's own: a chunky toggle in the ON position,
knob in the app's signal cyan over a deep indigo track, matching the
in-app "midnight indigo" theme. Run from the project root:
    python3 icon-src/generate_icon.py
"""
from PIL import Image, ImageDraw, ImageFilter
import os

ACCENT = (69, 216, 255, 255)        # #45D8FF signal cyan
ACCENT_SOFT = (69, 216, 255, 55)
TRACK = (51, 71, 122, 255)          # #33477A
TRACK_INNER = (38, 56, 99, 255)     # #263863
TRACK_EDGE = (96, 124, 188, 255)    # #607CBC
BG_TOP = (34, 49, 92, 255)          # #22315C
BG_BOTTOM = (18, 27, 51, 255)       # #121B33

MASTER = 864

DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4))


def gradient(size):
    img = Image.new("RGB", (1, size))
    px = img.load()
    for y in range(size):
        c = lerp(BG_TOP, BG_BOTTOM, y / float(size - 1))
        px[0, y] = c[:3]
    return img.resize((size, size))


def foreground(size):
    """Toggle glyph on transparency, centered in the adaptive safe zone."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    # Soft accent glow behind the knob (the ON end).
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = size * 0.645, size * 0.5
    r = size * 0.17
    gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT_SOFT)
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.085))
    img = Image.alpha_composite(img, glow)

    d = ImageDraw.Draw(img)
    tw, th = size * 0.56, size * 0.265
    x0, y0 = (size - tw) / 2.0, (size - th) / 2.0
    x1, y1 = x0 + tw, y0 + th
    rad = th / 2.0
    edge_w = max(2, size // 160)

    # Track and its recessed inner channel.
    d.rounded_rectangle([x0, y0, x1, y1], radius=rad, fill=TRACK,
                        outline=TRACK_EDGE, width=edge_w)
    inset = th * 0.16
    d.rounded_rectangle([x0 + inset, y0 + inset, x1 - inset, y1 - inset],
                        radius=rad - inset, fill=TRACK_INNER)

    # Knob at the right end: ON. Its diameter matches the inner
    # channel height, so it sits inside the track.
    kr = th * 0.335
    kx, ky = x1 - th / 2.0, size * 0.5
    d.ellipse([kx - kr, ky - kr, kx + kr, ky + kr], fill=ACCENT)
    return img


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    fg_master = foreground(MASTER)
    legacy_master = gradient(MASTER).convert("RGBA")
    legacy_master = Image.alpha_composite(legacy_master, fg_master).convert("RGB")

    for dpi, (legacy_size, fg_size) in DENSITIES.items():
        outdir = os.path.join(root, "res", "mipmap-" + dpi)
        os.makedirs(outdir, exist_ok=True)
        fg_master.resize((fg_size, fg_size), Image.LANCZOS).save(
            os.path.join(outdir, "ic_launcher_foreground.png"))
        legacy_master.resize((legacy_size, legacy_size), Image.LANCZOS).save(
            os.path.join(outdir, "ic_launcher.png"))
        print("wrote", outdir)

    preview = legacy_master.resize((432, 432), Image.LANCZOS)
    preview.save(os.path.join(root, "icon-preview-1.9.png"))
    print("wrote icon-preview-1.9.png")


if __name__ == "__main__":
    main()
