#!/usr/bin/env python3
"""Deterministically generates all 15 Android launcher icon PNGs from
android/icon/ic_launcher_master.webp.b64 using Pillow."""

from __future__ import annotations

import base64
import io
import os
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
MASTER_B64 = ROOT / "android" / "icon" / "ic_launcher_master.webp.b64"
RES_DIR = ROOT / "android" / "app" / "src" / "main" / "res"

DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def load_master() -> Image.Image:
    raw_b64 = MASTER_B64.read_text(encoding="ascii")
    raw_bytes = base64.b64decode(raw_b64)
    img = Image.open(io.BytesIO(raw_bytes)).convert("RGBA")
    return img


def make_round_icon(master: Image.Image, size: int) -> Image.Image:
    # Crop inside the outer rounded-square gold rim (x=23..489 on 512x512 master)
    inner = master.crop((23, 23, master.width - 23, master.height - 23))
    inner = inner.resize((size, size), Image.Resampling.LANCZOS)

    # Anti-aliased supersampled circle mask + metallic gold outer ring (#D4AF5A)
    ss = 4
    S = size * ss
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse((0, 0, S - 1, S - 1), fill=255)
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(inner, (0, 0), mask)

    ring = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    border_w = max(ss * 2, round(S * 0.045))
    rd.ellipse((0, 0, S - 1, S - 1), outline=(212, 175, 90, 255), width=border_w)
    # Subtle dark outer hairline + inner highlight for crisp contrast on white/dark launchers
    rd.ellipse((0, 0, S - 1, S - 1), outline=(92, 62, 18, 220), width=max(2, ss))
    ring = ring.resize((size, size), Image.Resampling.LANCZOS)
    return Image.alpha_composite(out, ring)


def make_foreground_icon(master: Image.Image, fg_size: int) -> Image.Image:
    # For Adaptive Icons (Android 8+), launchers apply their own squircle/circle mask
    # over a 108dp canvas (center 66dp..72dp visible). Cropping inside the baked gold frame
    # and softly feathering into #190418 avoids double-border clipping on Pixel/MIUI/OneUI.
    crop_pad = max(1, round(master.width * (46.0 / 512.0)))
    inner = master.crop((crop_pad, crop_pad, master.width - crop_pad, master.height - crop_pad))
    art_size = round(fg_size * 0.59)
    art = inner.resize((art_size, art_size), Image.Resampling.LANCZOS)

    # Feather the outer 12% of the square patch smoothly to transparent so it melts seamlessly
    # into ic_launcher_background (#2C0818 -> #12030D) under every adaptive shape mask.
    edge_px = max(4, round(art_size * 0.12))
    apx = art.load()
    for y in range(art_size):
        dy = min(y, art_size - 1 - y)
        ay = 1.0 if dy >= edge_px else (dy / edge_px)
        for x in range(art_size):
            dx = min(x, art_size - 1 - x)
            ax = 1.0 if dx >= edge_px else (dx / edge_px)
            w = ax * ay
            if w < 1.0:
                r, g, b, a = apx[x, y]
                apx[x, y] = (r, g, b, round(a * w))

    canvas = Image.new("RGBA", (fg_size, fg_size), (0, 0, 0, 0))
    off = (fg_size - art_size) // 2
    canvas.paste(art, (off, off), art)
    return canvas


def main() -> None:
    master = load_master()
    count = 0
    for density, (legacy_sz, fg_sz) in DENSITIES.items():
        mip_dir = RES_DIR / f"mipmap-{density}"
        mip_dir.mkdir(parents=True, exist_ok=True)

        legacy = master.resize((legacy_sz, legacy_sz), Image.Resampling.LANCZOS)
        legacy.save(mip_dir / "ic_launcher.png", format="PNG", optimize=True)

        rnd = make_round_icon(master, legacy_sz)
        rnd.save(mip_dir / "ic_launcher_round.png", format="PNG", optimize=True)

        fg = make_foreground_icon(master, fg_sz)
        fg.save(mip_dir / "ic_launcher_foreground.png", format="PNG", optimize=True)
        count += 3

    print(f"Generated {count} Android launcher PNG icons across {len(DENSITIES)} densities.")


if __name__ == "__main__":
    main()
