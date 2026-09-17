#!/usr/bin/env python3
"""Generate every LemurX brand asset from the master logo.

    tools/brand/gen_brand_assets.py

Master: tools/brand/lemurx_logo_1024.png (the logo from the LemurX website,
lemur-website/assets/logo.png). Everything Chromium ships as "product logo"
is regenerated from it and written into the overlay (src/) so that
tools/apply.py lays it over the pristine checkout:

  src/chrome/lemurx/brand/res/        Android resources: launcher icons, app_name,
                                      and every drawable upstream draws the Chrome
                                      logo with (chrome_logo_24dp, chrome_sync_logo,
                                      chromelogo16, promo illustrations, ...). They are
                                      packaged by //chrome/lemurx/brand:brand_resources
                                      with resource_overlay = true, so they replace the
                                      upstream resources of the same name at aapt2 time
                                      without touching the upstream files.
  src/chrome/lemurx/brand/BUILD.gn    regenerated so the sources list stays complete
  src/chrome/app/theme/lemurx/        product_logo_*.png / .svg (branding_path_component)
  src/chrome/app/theme/default_{100,200}_percent/lemurx/
  src/components/resources/default_{100,200}_percent/lemurx/  chrome://version logo
  src/components/vector_icons/lemurx/ product.icon / product_refresh.icon

Raster assets are plain PIL resizes. Vector assets (VectorDrawable pathData,
Skia .icon, SVG) come from a small built-in tracer: the logo is quantised to
four flat colours (sky, fur, mask, eye), each colour mask is contour-traced and
simplified, and the polygons are emitted in the three formats.

Only PIL is required.
"""
import colorsys
import math
import os
import shutil
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OVERLAY = os.path.join(ROOT, "src")
MASTER = os.path.join(HERE, "lemurx_logo_1024.png")
BRAND = "chrome/lemurx/brand"
RES = BRAND + "/res"

# Corner radius of the master tile, as a fraction of its side (measured).
CORNER = 0.17
# Vertical gradient of the tile background (sampled from the master).
SKY_TOP = (0x14, 0xD5, 0xE6)
SKY_BOTTOM = (0x01, 0x8F, 0xE3)
# Flat palette used by the tracer.
PALETTE = {
    "sky": (0x12, 0xB8, 0xE4),
    "fur": (0xF4, 0xF4, 0xF8),
    "mask": (0x2B, 0x2B, 0x33),
    "eye": (0xF6, 0xA6, 0x23),
}
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/lato/Lato-Bold.ttf",
    "/usr/share/fonts/truetype/ubuntu/Ubuntu-B.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def dp(v, density):
    return int(round(v * density))


def ensure_dir(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)


def save(img, rel):
    path = os.path.join(OVERLAY, rel) if not os.path.isabs(rel) else rel
    ensure_dir(path)
    img.save(path, optimize=True)
    print(f"  {os.path.relpath(path, ROOT)}  {img.size[0]}x{img.size[1]}")


# --------------------------------------------------------------------------
# Raster
# --------------------------------------------------------------------------

_master = None


def master():
    global _master
    if _master is None:
        _master = Image.open(MASTER).convert("RGB")
    return _master


def rounded_mask(size, radius_frac=CORNER, inset=0.0):
    """Anti-aliased rounded-rect alpha mask (4x supersampled)."""
    s = 4
    big = Image.new("L", (size * s, size * s), 0)
    d = ImageDraw.Draw(big)
    r = radius_frac * size * s
    i = inset * s
    d.rounded_rectangle((i, i, size * s - 1 - i, size * s - 1 - i), radius=r, fill=255)
    return big.resize((size, size), Image.LANCZOS)


def tile(size):
    """The logo as an RGBA tile with transparent rounded corners."""
    img = master().resize((size, size), Image.LANCZOS).convert("RGBA")
    # Inset a hair so the black JPEG corners never bleed through the edge AA.
    img.putalpha(rounded_mask(size, inset=max(0.6, size / 400.0)))
    return img


def sky(size):
    """Opaque vertical gradient matching the tile background."""
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(int(round(a + (b - a) * t)) for a, b in zip(SKY_TOP, SKY_BOTTOM)))
    return img.resize((size, size), Image.NEAREST).convert("RGBA")


def tile_on_sky(size, tile_frac):
    """Full-bleed adaptive-icon layer: gradient + centred tile."""
    img = sky(size)
    t = tile(int(round(size * tile_frac)))
    off = (size - t.size[0]) // 2
    img.alpha_composite(t, (off, off))
    return img


def silhouette(size, color=(255, 255, 255, 255)):
    """Everything that is not sky, filled with one colour (for mono icons)."""
    cls = classify(size)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    for y in range(size):
        for x in range(size):
            if cls[y][x] != "sky":
                px[x, y] = color
    return img.filter(ImageFilter.GaussianBlur(0.4))


def font(px):
    for f in FONT_CANDIDATES:
        if os.path.exists(f):
            return ImageFont.truetype(f, px)
    return ImageFont.load_default()


def logo_with_name(height, color, gap_frac=0.35):
    """Tile + "LemurX" wordmark, `height` px tall, transparent background."""
    t = tile(height)
    f = font(int(height * 0.78))
    text = "LemurX"
    bbox = f.getbbox(text)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    gap = int(height * gap_frac)
    img = Image.new("RGBA", (height + gap + tw + 1, height), (0, 0, 0, 0))
    img.alpha_composite(t, (0, 0))
    d = ImageDraw.Draw(img)
    d.text((height + gap - bbox[0], (height - th) // 2 - bbox[1]), text, font=f, fill=color)
    return img


# --------------------------------------------------------------------------
# Tracer
# --------------------------------------------------------------------------

_classified = {}


def classify(size):
    """Quantise the master to PALETTE keys; returns rows of class names."""
    if size in _classified:
        return _classified[size]
    img = master().resize((size, size), Image.LANCZOS).filter(ImageFilter.MedianFilter(3))
    px = img.load()
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            r, g, b = px[x, y]
            h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
            h *= 360
            if v < 0.38:
                c = "mask"
            elif 20 <= h <= 50 and s > 0.45 and v > 0.6:
                c = "eye"
            elif 160 <= h <= 230 and s > 0.45:
                c = "sky"
            else:
                c = "fur"
            row.append(c)
        rows.append(row)
    # Outside the rounded tile is sky as well (the JPEG corners are black).
    m = rounded_mask(size).load()
    for y in range(size):
        for x in range(size):
            if m[x, y] < 128:
                rows[y][x] = "sky"
    _classified[size] = rows
    return rows


def clean(mask, size, passes=1):
    """Majority filter on a boolean mask to kill speckles."""
    for _ in range(passes):
        out = [[False] * size for _ in range(size)]
        for y in range(size):
            for x in range(size):
                n = 0
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        xx, yy = x + dx, y + dy
                        if 0 <= xx < size and 0 <= yy < size and mask[yy][xx]:
                            n += 1
                out[y][x] = n >= 5
        mask = out
    return mask


def trace(mask, size):
    """Boundary loops of a boolean mask.

    Edges are oriented with the inside on the right, so outer loops and holes
    come out with opposite winding and NONZERO fill does the right thing.
    """
    def inside(x, y):
        return 0 <= x < size and 0 <= y < size and mask[y][x]

    edges = {}
    for y in range(size):
        for x in range(size):
            if not mask[y][x]:
                continue
            if not inside(x, y - 1):
                edges.setdefault((x, y), []).append((x + 1, y))
            if not inside(x + 1, y):
                edges.setdefault((x + 1, y), []).append((x + 1, y + 1))
            if not inside(x, y + 1):
                edges.setdefault((x + 1, y + 1), []).append((x, y + 1))
            if not inside(x - 1, y):
                edges.setdefault((x, y + 1), []).append((x, y))
    loops = []
    while edges:
        start = next(iter(edges))
        loop = [start]
        cur = start
        while True:
            nxts = edges.get(cur)
            if not nxts:
                break
            nxt = nxts.pop()
            if not nxts:
                del edges[cur]
            if nxt == start:
                break
            loop.append(nxt)
            cur = nxt
        if len(loop) >= 4:
            loops.append(loop)
    return loops


def smooth(loop, passes=2):
    n = len(loop)
    for _ in range(passes):
        loop = [
            ((loop[i - 1][0] + 2 * loop[i][0] + loop[(i + 1) % n][0]) / 4.0,
             (loop[i - 1][1] + 2 * loop[i][1] + loop[(i + 1) % n][1]) / 4.0)
            for i in range(n)
        ]
    return loop


def _dp(points, tol):
    if len(points) < 3:
        return points
    (x0, y0), (x1, y1) = points[0], points[-1]
    dx, dy = x1 - x0, y1 - y0
    L = math.hypot(dx, dy) or 1e-9
    best, idx = 0.0, 0
    for i in range(1, len(points) - 1):
        px, py = points[i]
        d = abs(dy * px - dx * py + x1 * y0 - y1 * x0) / L
        if d > best:
            best, idx = d, i
    if best > tol:
        return _dp(points[: idx + 1], tol)[:-1] + _dp(points[idx:], tol)
    return [points[0], points[-1]]


def simplify(loop, tol):
    # Split the closed loop at the point farthest from its first vertex.
    far = max(range(len(loop)), key=lambda i: (loop[i][0] - loop[0][0]) ** 2 + (loop[i][1] - loop[0][1]) ** 2)
    a = _dp(loop[: far + 1], tol)
    b = _dp(loop[far:] + [loop[0]], tol)
    out = a[:-1] + b[:-1]
    return out if len(out) >= 3 else None


def signed_area(loop):
    return sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(loop, loop[1:] + loop[:1])) / 2.0


def vectorize(res=192, tol=1.3, min_area=6.0):
    """Trace the master into flat-colour polygons.

    Returns [(class, loop), ...] where each loop is an *outer* boundary
    (holes are dropped) with coordinates in 0..1, sorted by decreasing area.
    Because the four classes partition the tile, painting the polygons in that
    order reproduces the picture: whatever sits inside a hole is a smaller
    component of another class and gets painted later, on top. The outermost
    sky component (the tile background itself) is left out; callers draw the
    gradient rounded rect instead.
    """
    cls = classify(res)
    items = []
    for name in PALETTE:
        mask = clean([[c == name for c in row] for row in cls], res)
        for loop in trace(mask, res):
            sp = simplify(smooth(loop), tol)
            if not sp:
                continue
            a = signed_area(sp)
            if a <= 0 or a < min_area:  # holes wind the other way
                continue
            items.append((a, name, [(x / res, y / res) for x, y in sp]))
    items.sort(key=lambda t: -t[0])
    # The first sky loop is the background; the gradient takes care of it.
    out = []
    dropped_bg = False
    for a, name, loop in items:
        if name == "sky" and not dropped_bg:
            dropped_bg = True
            continue
        out.append((name, loop))
    return out


def fmt(v, scale, off=0.0, nd=2):
    s = f"{v * scale + off:.{nd}f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def path_data(loops, scale, off=0.0):
    parts = []
    for loop in loops:
        parts.append("M" + " L".join(f"{fmt(x, scale, off)},{fmt(y, scale, off)}" for x, y in loop) + "Z")
    return " ".join(parts)


def mono_loops(shapes):
    return [loop for name, loop in shapes if name != "sky"]


def hexrgb(c):
    return "#%02X%02X%02X" % c


def vector_drawable(shapes, size_dp, viewport, scale, off=0.0, corner=True, extra_head=""):
    """Android VectorDrawable of the whole tile."""
    r = CORNER * scale
    x0, x1 = off, off + scale
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- LemurX brand mark, generated by tools/brand/gen_brand_assets.py. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        f'    android:width="{size_dp}dp"',
        f'    android:height="{size_dp}dp"',
        f'    android:viewportWidth="{viewport}"',
        f'    android:viewportHeight="{viewport}">',
    ]
    if extra_head:
        lines.append(extra_head)
    if corner:
        rr = (f"M{fmt(x0 + r, 1)},{fmt(x0, 1)} H{fmt(x1 - r, 1)} A{fmt(r, 1)},{fmt(r, 1)} 0 0 1 {fmt(x1, 1)},{fmt(x0 + r, 1)}"
              f" V{fmt(x1 - r, 1)} A{fmt(r, 1)},{fmt(r, 1)} 0 0 1 {fmt(x1 - r, 1)},{fmt(x1, 1)}"
              f" H{fmt(x0 + r, 1)} A{fmt(r, 1)},{fmt(r, 1)} 0 0 1 {fmt(x0, 1)},{fmt(x1 - r, 1)}"
              f" V{fmt(x0 + r, 1)} A{fmt(r, 1)},{fmt(r, 1)} 0 0 1 {fmt(x0 + r, 1)},{fmt(x0, 1)} Z")
        lines += [
            f'    <path android:pathData="{rr}">',
            '        <aapt:attr name="android:fillColor">',
            f'            <gradient android:startX="{fmt(x0, 1)}" android:startY="{fmt(x0, 1)}"',
            f'                android:endX="{fmt(x0, 1)}" android:endY="{fmt(x1, 1)}" android:type="linear">',
            f'                <item android:offset="0" android:color="{hexrgb(SKY_TOP)}"/>',
            f'                <item android:offset="1" android:color="{hexrgb(SKY_BOTTOM)}"/>',
            "            </gradient>",
            "        </aapt:attr>",
            "    </path>",
        ]
    for name, loop in shapes:
        lines.append(f'    <path android:fillColor="{hexrgb(PALETTE[name])}"')
        lines.append(f'        android:pathData="{path_data([loop], scale, off)}"/>')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def mono_vector_drawable(shapes, size_dp, viewport, scale, off):
    """Monochrome (themed icon) silhouette: every non-sky component in one black path."""
    loops = mono_loops(shapes)
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- LemurX monochrome launcher mark, generated by tools/brand/gen_brand_assets.py. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{size_dp}dp"',
        f'    android:height="{size_dp}dp"',
        f'    android:viewportWidth="{viewport}"',
        f'    android:viewportHeight="{viewport}">',
        '    <path android:fillColor="#000000" android:fillType="nonZero"',
        f'        android:pathData="{path_data(loops, scale, off)}"/>',
        "</vector>",
    ]) + "\n"


def skia_icon(shapes, canvas=24, mono=False):
    """Skia vector icon (components/vector_icons/*.icon)."""
    r = CORNER * canvas
    out = [
        "// Copyright 2026 The LemurX Authors",
        "// Use of this source code is governed by a BSD-style license that can be",
        "// found in the LICENSE file.",
        "//",
        "// LemurX brand mark, generated by tools/brand/gen_brand_assets.py.",
        "",
        f"CANVAS_DIMENSIONS, {canvas},",
    ]
    if not mono:
        out += [
            "FILL_RULE_NONZERO,",
            "PATH_COLOR_ARGB, 0xFF, 0x%02X, 0x%02X, 0x%02X," % PALETTE["sky"],
            f"ROUND_RECT, 0, 0, {canvas}, {canvas}, {fmt(r, 1)},",
        ]
    if mono:
        out.append("FILL_RULE_NONZERO,")
        for loop in mono_loops(shapes):
            out.append(f"MOVE_TO, {fmt(loop[0][0], canvas)}, {fmt(loop[0][1], canvas)},")
            for x, y in loop[1:]:
                out.append(f"LINE_TO, {fmt(x, canvas)}, {fmt(y, canvas)},")
            out.append("CLOSE,")
        return "\n".join(out) + "\n"
    for name, loop in shapes:
        out.append("NEW_PATH,")
        out.append("FILL_RULE_NONZERO,")
        out.append("PATH_COLOR_ARGB, 0xFF, 0x%02X, 0x%02X, 0x%02X," % PALETTE[name])
        out.append(f"MOVE_TO, {fmt(loop[0][0], canvas)}, {fmt(loop[0][1], canvas)},")
        for x, y in loop[1:]:
            out.append(f"LINE_TO, {fmt(x, canvas)}, {fmt(y, canvas)},")
        out.append("CLOSE,")
    return "\n".join(out) + "\n"


def svg(shapes, size=256):
    r = CORNER * size
    body = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}">',
        "  <!-- LemurX brand mark, generated by tools/brand/gen_brand_assets.py. -->",
        "  <defs>",
        '    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">',
        f'      <stop offset="0" stop-color="{hexrgb(SKY_TOP)}"/>',
        f'      <stop offset="1" stop-color="{hexrgb(SKY_BOTTOM)}"/>',
        "    </linearGradient>",
        "  </defs>",
        f'  <rect width="{size}" height="{size}" rx="{fmt(r, 1)}" fill="url(#sky)"/>',
    ]
    for name, loop in shapes:
        body.append(f'  <path fill="{hexrgb(PALETTE[name])}" d="{path_data([loop], size)}"/>')
    body.append("</svg>")
    return "\n".join(body) + "\n"


def write_text(rel, text):
    path = os.path.join(OVERLAY, rel) if not os.path.isabs(rel) else rel
    ensure_dir(path)
    with open(path, "w") as f:
        f.write(text)
    print(f"  {os.path.relpath(path, ROOT)}  {len(text)} bytes")


# --------------------------------------------------------------------------
# Targets
# --------------------------------------------------------------------------


def gen_launcher(shapes):
    print(f"launcher icons -> src/{RES}")
    for name, d in DENSITIES.items():
        save(tile(dp(48, d)), f"{RES}/mipmap-{name}/app_icon.png")
        # Adaptive layers are 108dp; the launcher shows the centre 72dp.
        layer = tile_on_sky(dp(108, d), 84 / 108.0)
        save(layer, f"{RES}/mipmap-{name}/layered_app_icon.png")
        save(layer, f"{RES}/mipmap-{name}/layered_app_icon_background.png")
    # Monochrome themed icon: 90dp canvas, mark inside the centre 36dp box.
    write_text(f"{RES}/drawable/themed_app_icon.xml",
               mono_vector_drawable(shapes, 90, 90, 36 * 0.92, 27 + 36 * 0.04))


def gen_theme(shapes):
    print("chrome/app/theme -> src/chrome/app/theme/lemurx")
    base = "chrome/app/theme/lemurx"
    for n in (16, 24, 48, 64, 128, 256):
        save(tile(n), f"{base}/product_logo_{n}.png")
    save(silhouette(22), f"{base}/product_logo_22_mono.png")
    write_text(f"{base}/product_logo.svg", svg(shapes))
    write_text(f"{base}/product_logo_animation.svg", svg(shapes))
    for pct, k in (("100", 1), ("200", 2)):
        b = f"chrome/app/theme/default_{pct}_percent/lemurx"
        save(tile(16 * k), f"{b}/product_logo_16.png")
        save(tile(32 * k), f"{b}/product_logo_32.png")
        save(logo_with_name(22 * k, (0x3C, 0x40, 0x43, 0xFF)), f"{b}/product_logo_name_22.png")
        save(logo_with_name(22 * k, (0xFF, 0xFF, 0xFF, 0xFF)), f"{b}/product_logo_name_22_white.png")


def gen_version_ui(shapes):
    print("chrome://version -> src/components/resources/default_*_percent/lemurx")
    for pct, k in (("100", 1), ("200", 2)):
        b = f"components/resources/default_{pct}_percent/lemurx"
        save(logo_with_name(32 * k, (0x3C, 0x40, 0x43, 0xFF)), f"{b}/product_logo.png")
        save(logo_with_name(32 * k, (0xFF, 0xFF, 0xFF, 0xFF)), f"{b}/product_logo_white.png")
        save(tile(16 * k), f"{b}/favicon_product.png")


def gen_vector_icons(shapes):
    print("vector icons -> src/components/vector_icons/lemurx")
    write_text("components/vector_icons/lemurx/product.icon", skia_icon(shapes))
    write_text("components/vector_icons/lemurx/product_refresh.icon", skia_icon(shapes))


def gen_android_drawables(shapes):
    """Every upstream Android drawable that *is* the Chrome logo, by name."""
    print(f"android drawables -> src/{RES}")
    for name, d in DENSITIES.items():
        # Footer "provided by Chrome" marks (payments / touch-to-fill sheets).
        save(tile(dp(24, d)), f"{RES}/drawable-{name}/chrome_logo_blue.png")
        # Default favicon for chrome:// pages, tab grid and page info.
        save(tile(dp(16, d)), f"{RES}/drawable-{name}/chromelogo16.png")
    # Bookmark / history toolbars, sign-in sheets, survey and educational tips.
    write_text(f"{RES}/drawable/chrome_logo_24dp.xml", vector_drawable(shapes, 24, 24, 24))
    write_text(f"{RES}/drawable/chrome_sync_logo.xml", vector_drawable(shapes, 24, 24, 24))
    # 31dp white disc with the mark in the middle (AppInstallMenuHandler).
    disc = ('    <path android:fillColor="@android:color/white"\n'
            '        android:pathData="M15.5,0.5 A15,15 0 1 1 15.5,30.5 A15,15 0 1 1 15.5,0.5 Z"/>')
    write_text(f"{RES}/drawable/chrome_logo_on_circular_background.xml",
               vector_drawable(shapes, 31, 31, 20, 5.5, extra_head=disc))
    # Promo illustrations built around the Chrome logo: centred mark, same
    # intrinsic size as upstream so the layouts do not move.
    for fname, w, h, vw, vh in (
        ("drawable/default_browser_promo_fre_logo_illustration.xml", 200, 138, 375, 260),
        ("drawable/signin_logo.xml", 164, 150, 164, 150),
        ("drawable-night/signin_logo.xml", 164, 150, 164, 150),
        ("drawable/tips_promo_signin_logo.xml", 401, 295, 401, 295),
    ):
        s = min(vw, vh) * 0.62
        # Draw the mark horizontally centred in a vw x vw viewport, then fix up
        # the height and shift everything down so it is centred vertically too.
        text = vector_drawable(shapes, w, vw, s, (vw - s) / 2.0)
        text = text.replace(f'android:height="{w}dp"', f'android:height="{h}dp"')
        text = text.replace(f'android:viewportHeight="{vw}"', f'android:viewportHeight="{vh}"')
        dy = (vh - s) / 2.0 - (vw - s) / 2.0
        write_text(f"{RES}/{fname}", _translate_y(text, dy))


def gen_strings():
    print(f"app name -> src/{RES}/values")
    write_text(f"{RES}/values/lemurx_brand_strings.xml", "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!--",
        "Copyright 2026 The LemurX Authors",
        "Use of this source code is governed by a BSD-style license that can be",
        "found in the LICENSE file.",
        "",
        "Overrides chrome/android/java/res_chromium_base/values/channel_constants.xml",
        "(resource_overlay, see BUILD.gn). Generated by tools/brand/gen_brand_assets.py.",
        "-->",
        "",
        '<resources xmlns:android="http://schemas.android.com/apk/res/android">',
        "    <!-- The application name displayed to the user. -->",
        '    <string name="app_name" translatable="false">LemurX</string>',
        '    <string name="bookmark_widget_title" translatable="false">LemurX bookmarks</string>',
        '    <string name="search_widget_title" translatable="false">LemurX search</string>',
        '    <string name="quick_action_search_widget_title" translatable="false">LemurX quick action search</string>',
        "</resources>",
    ]) + "\n")


def gen_build_gn():
    """android_resources() overlay target listing every file under res/."""
    res_dir = os.path.join(OVERLAY, RES)
    files = sorted(
        os.path.relpath(os.path.join(b, f), os.path.join(OVERLAY, BRAND))
        for b, _d, fs in os.walk(res_dir) for f in fs
    )
    lines = [
        "# Copyright 2026 The LemurX Authors",
        "# Use of this source code is governed by a BSD-style license that can be",
        "# found in the LICENSE file.",
        "#",
        "# Generated by tools/brand/gen_brand_assets.py -- do not edit by hand.",
        "",
        'import("//build/config/android/rules.gni")',
        "",
        "assert(is_android)",
        "",
        "# LemurX branding. resource_overlay makes aapt2 take these instead of the",
        "# upstream resources with the same names (launcher icons, app_name, every",
        "# drawable that is the Chrome logo), so no upstream res/ file is patched.",
        "# Wired in from chrome/android/BUILD.gn (chrome_app_java_resources deps).",
        "#",
        "# Upstream ships its own overlay for icon + app_name",
        "# (chrome_public_apk_base_module_resources). Among overlays the dependent",
        "# wins, so depend on it to be sorted after it in aapt2's -R list.",
        'android_resources("brand_resources") {',
        "  resource_overlay = true",
        '  deps = [ "//chrome/android:chrome_public_apk_base_module_resources" ]',
        "  sources = [",
    ] + [f'    "{f}",' for f in files] + [
        "  ]",
        "}",
    ]
    write_text(f"{BRAND}/BUILD.gn", "\n".join(lines) + "\n")


def _translate_y(text, dy):
    """Wrap all <path> children in a <group android:translateY>."""
    cut = text.index('">\n', text.index("<vector")) + 3
    head, rest = text[:cut], text[cut:]
    body = rest.rsplit("</vector>", 1)[0]
    return (head + f'    <group android:translateY="{fmt(dy, 1)}">\n'
            + "".join("    " + l + "\n" for l in body.splitlines()) + "    </group>\n</vector>\n")


def main():
    if not os.path.exists(MASTER):
        sys.exit(f"missing master logo {MASTER}")
    print("tracing master ...")
    shapes = vectorize()
    print(f"  {len(shapes)} polygons, {sum(len(l) for _, l in shapes)} points")
    if os.path.isdir(os.path.join(OVERLAY, RES)):
        shutil.rmtree(os.path.join(OVERLAY, RES))
    gen_launcher(shapes)
    gen_android_drawables(shapes)
    gen_strings()
    gen_build_gn()
    gen_theme(shapes)
    gen_version_ui(shapes)
    gen_vector_icons(shapes)


if __name__ == "__main__":
    main()
