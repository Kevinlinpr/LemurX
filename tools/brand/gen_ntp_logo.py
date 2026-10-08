#!/usr/bin/env python3
# Copyright 2026 The LemurX Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
"""Renders the "LemurX" wordmark shown above the new-tab-page search box.

Mirrors Lemur's `lemur_ic_ntp_logo.png` treatment (a black rounded-bold
wordmark, 42dp tall, white in dark mode) but with the LemurX name. The PNG is
pure black on transparent so Chromium's existing night-mode tint
(`setTint(Color.WHITE)` in NtpCustomizationUtils) turns it white without a
separate dark asset.

Output: src/chrome/lemurx/ntp/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
        lemurx_ntp_wordmark.png
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_ROOT = os.path.join(HERE, "..", "..", "src", "chrome", "lemurx", "ntp", "res")

TEXT = "LemurX"
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/lato/Lato-Black.ttf",
    "/usr/share/fonts/truetype/lato/Lato-Heavy.ttf",
    "/usr/share/fonts/truetype/lato/Lato-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]
HEIGHT_DP = 42          # same as lemur_ntp_logo_height
GLYPH_DP = 30           # cap height of the wordmark inside the 42dp box
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SUPERSAMPLE = 4


def pick_font():
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return path
    sys.exit("no suitable bold font found; install fonts-lato")


def render(scale, font_path):
    """Draws the wordmark at `scale` px/dp with supersampling, returns RGBA image."""
    ss = SUPERSAMPLE
    height = int(round(HEIGHT_DP * scale * ss))
    target_glyph = GLYPH_DP * scale * ss

    # Find a font size whose cap height ("L") matches target_glyph.
    size = int(target_glyph)
    for _ in range(12):
        font = ImageFont.truetype(font_path, size)
        l, t, r, b = font.getbbox("L")
        cap = b - t
        if abs(cap - target_glyph) < 1:
            break
        size = max(4, int(size * target_glyph / max(cap, 1)))
    font = ImageFont.truetype(font_path, size)

    l, t, r, b = font.getbbox(TEXT)
    text_w, text_h = r - l, b - t
    pad = int(2 * scale * ss)
    width = text_w + pad * 2
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    y = (height - text_h) // 2 - t
    draw.text((pad - l, y), TEXT, font=font, fill=(0, 0, 0, 255))
    # Downsample for smooth edges.
    return img.resize((max(1, width // ss), max(1, height // ss)), Image.LANCZOS)


def main():
    font_path = pick_font()
    for name, scale in DENSITIES.items():
        img = render(scale, font_path)
        out_dir = os.path.join(OUT_ROOT, "drawable-" + name)
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, "lemurx_ntp_wordmark.png")
        img.save(out, optimize=True)
        print("wrote %s (%dx%d)" % (os.path.relpath(out, HERE), img.width, img.height))


if __name__ == "__main__":
    main()
