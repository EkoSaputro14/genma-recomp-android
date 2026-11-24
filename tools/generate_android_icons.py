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
    # Fit inner art inside a dark crimson circle backing (~74% of diameter, matching inscribed
    # rounded square 1/sqrt(2)) so the metallic gold ring never clips "github/ekosaputro14".
    inner_src = master.crop((24, 24, master.width - 24, master.height - 24))
    inset_sz = round(size * 0.74)
    inner = inner_src.resize((inset_sz, inset_sz), Image.Resampling.LANCZOS)

    backed = Image.new("RGBA", (size, size), (0x18, 0x04, 0x10, 255))
    pad = (size - inset_sz) // 2
    backed.paste(inner, (pad, pad), inner)

    # Anti-aliased supersampled circle mask + metallic multi-stop gold border ring
    ss = 4
    S = size * ss
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse((0, 0, S - 1, S - 1), fill=255)
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(backed, (0, 0), mask)

    ring = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    border_w = max(ss * 2, round(S * 0.055))
    rd.ellipse((0, 0, S - 1, S - 1), outline=(198, 151, 56, 255), width=border_w)
    # Bright inner gold highlight + dark bronze outer rim to match master frame
    inset = max(ss, border_w // 4)
    rd.ellipse((inset, inset, S - 1 - inset, S - 1 - inset), outline=(245, 224, 142, 235), width=max(ss, border_w // 3))
    rd.ellipse((0, 0, S - 1, S - 1), outline=(74, 46, 10, 245), width=max(2, ss))
    ring = ring.resize((size, size), Image.Resampling.LANCZOS)
    return Image.alpha_composite(out, ring)


def make_foreground_icon(master: Image.Image, fg_size: int) -> Image.Image:
    # Adaptive Icons (Android 8+) apply Circle / Squircle / Rounded-Square / Teardrop masks
    # over a 108dp canvas (72dp visible aperture, 66dp guaranteed safe zone).
    # art_size = 0.48 * fg_size preserves 100% of all 4 gold corners even under strict 66dp Circle masks.
    art_size = round(fg_size * 0.48)
    art = master.resize((art_size, art_size), Image.Resampling.LANCZOS)

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
