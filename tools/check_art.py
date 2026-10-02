#!/usr/bin/env python3
"""Geometry / rendering checks for the generated artwork.

We cannot eyeball the icons here, so instead we rasterise them with Inkscape
and assert the things that actually break: glyph outside the mask safe area,
glyph off-centre, background not covering the bleed, stroke icons collapsing
to nothing, and asymmetric shapes.
"""
import os
import subprocess
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import designspec as ds
from make_assets import SVG_DIR, DESIGN, glyph_path, transform_path, FOLDER_24

TMP = "/tmp/opencode/artcheck"
os.makedirs(TMP, exist_ok=True)
fails = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'  ' + detail if detail else ''}")
    if not ok:
        fails.append(label)


def render(svg, width):
    png = os.path.join(TMP, os.path.basename(svg).replace(".svg", ".png"))
    subprocess.run(["inkscape", svg, f"--export-filename={png}",
                    f"--export-width={width}", f"--export-height={width}",
                    "--export-background-opacity=0", "--export-type=png"],
                   capture_output=True, timeout=120)
    return Image.open(png).convert("RGBA")


# ---------------------------------------------------------------------------
print("launcher icon (1024 source, 512 render)")
img = render(os.path.join(DESIGN, "launcher-legacy.svg"), 512)
a = np.array(img)
rgb, alpha = a[..., :3].astype(int), a[..., 3]

check("canvas is square 512", img.size == (512, 512), str(img.size))
corners = [alpha[2, 2], alpha[2, -3], alpha[-3, 2], alpha[-3, -3]]
check("corners transparent (squircle mask)", all(c < 16 for c in corners),
      f"alpha={corners}")
center_opaque = alpha[256, 256]
check("centre opaque", center_opaque > 250, f"alpha={center_opaque}")

# top of the icon should be the lighter blue, bottom the darker
top = rgb[40, 256]
bot = rgb[472, 256]
check("vertical blue gradient", top[2] > bot[2] and top[2] - bot[2] > 12,
      f"top={tuple(top)} bottom={tuple(bot)} delta_blue={top[2] - bot[2]}")

# white glyph: find near-white pixels inside the icon
white = (rgb[..., 0] > 235) & (rgb[..., 1] > 235) & (rgb[..., 2] > 235) & (alpha > 200)
ys, xs = np.nonzero(white)
check("white folder glyph present", white.sum() > 8000, f"px={white.sum()}")
if white.sum():
    w = xs.max() - xs.min() + 1
    h = ys.max() - ys.min() + 1
    check("glyph fits legacy safe zone (8..504)", xs.min() > 40 and xs.max() < 472
          and ys.min() > 40 and ys.max() < 472, f"x[{xs.min()},{xs.max()}] y[{ys.min()},{ys.max()}]")
    cx, cy = xs.mean(), ys.mean()
    off = np.hypot(cx - 256, cy - 256)
    check("glyph visually centred", off < 14, f"offset={off:.1f}px")
    # optical centre sits a touch above geometric centre, never below it
    check("glyph optical centre not too low", cy <= 256 + 6, f"cy={cy:.1f}")

# ---------------------------------------------------------------------------
print("\nadaptive foreground (108 source, 432 render)")
fg = render(os.path.join(DESIGN, "launcher-foreground.svg"), 432)
fa = np.array(fg)
falpha = fa[..., 3]
check("only the glyph is drawn (no bg)", falpha.max() > 200)
ys, xs = np.nonzero(falpha > 40)
check("glyph inside 66dp/72dp safe zone", xs.min() > 432 * 0.18 and xs.max() < 432 * 0.82
      and ys.min() > 432 * 0.18 and ys.max() < 432 * 0.82,
      f"x[{xs.min()},{xs.max()}] y[{ys.min()},{ys.max()}] of 432")

# ---------------------------------------------------------------------------
print("\nvector drawables parse + geometry sanity")
res = os.path.join(os.path.dirname(DESIGN), "app", "src", "main", "res", "drawable")
import xml.etree.ElementTree as ET
AND = "{http://schemas.android.com/apk/res/android}"
npaths = 0
for name in ds.ICONS:
    p = os.path.join(res, f"ic_{name}.xml")
    root = ET.parse(p).getroot()
    assert root.get(AND + "viewportWidth") == "24", name
    for path in root.findall("path"):
        d = path.get(AND + "pathData")
        npaths += 1
        if path.get(AND + "fillColor"):
            continue
        if not d or len(d) < 4:
            fails.append(f"{name}: empty stroke path")
check("all stroke paths carry path data", True, f"{npaths} paths total")

# spot-check that the stroke icons actually produce ink in Inkscape
print("\nstroke weight / ink coverage (24x24 render, expect 2%-45%)")
thin, empty = [], []
for name in ("folder", "search", "trash", "share", "file_image", "grid",
             "home", "gear", "copy", "pencil", "sliders", "list"):
    im = render(os.path.join(SVG_DIR, f"{name}.svg"), 96)
    arr = np.array(im)[..., 3] > 30
    cov = arr.sum() / arr.size
    if cov < 0.02:
        thin.append((name, cov))
    if cov > 0.45:
        empty.append((name, cov))
check("no icon renders empty or near-empty", not thin, str(thin))
check("no icon renders solid", not empty, str(empty))

# symmetry: mirror-symmetric shapes must render symmetric
# Only shapes that are genuinely symmetric about the vertical axis.
for name, expect in (("folder", False), ("search", False), ("close", True),
                     ("plus", True), ("grid", True), ("home", True),
                     ("trash", True), ("file", False), ("sort", False),
                     ("info", True), ("lock", True), ("clock", False),
                     ("gear", True), ("download", True), ("star", True),
                     ("check", False), ("eye", True), ("sliders", False)):
    im = render(os.path.join(SVG_DIR, f"{name}.svg"), 96)
    arr = (np.array(im)[..., 3].astype(float) > 30).astype(float)
    diff = np.abs(arr[:, ::-1] - arr).mean()
    sym = diff < 0.02
    check(f"{name} {'is' if sym else 'is not'} mirror-symmetric (as designed)",
          sym == expect, f"mean|diff|={diff:.4f}")

# ---------------------------------------------------------------------------
print("\nglyph path maths")
g = glyph_path(108, 0.62)
nums = [float(x) for x in
        __import__("re").findall(r"-?\d*\.?\d+", g)]
xs2, ys2 = nums[0::2], nums[1::2]
check("108 glyph stays on canvas", min(xs2) > 0 and max(xs2) < 108
      and min(ys2) > 0 and max(ys2) < 108,
      f"x[{min(xs2):.1f},{max(xs2):.1f}] y[{min(ys2):.1f},{max(ys2):.1f}]")
check("glyph horizontally centred on 54",
      abs((min(xs2) + max(xs2)) / 2 - 54) < 0.6,
      f"cx={(min(xs2) + max(xs2)) / 2:.2f}")

# same for the legacy 1024 artwork
g2 = glyph_path(1024, 0.565, optical_nudge=-0.010)
n2 = [float(x) for x in __import__("re").findall(r"-?\d*\.?\d+", g2)]
x2, y2 = n2[0::2], n2[1::2]
check("1024 glyph stays on canvas", min(x2) > 0 and max(x2) < 1024 and min(y2) > 0 and max(y2) < 1024)
check("1024 glyph horizontally centred on 512",
      abs((min(x2) + max(x2)) / 2 - 512) < 1.0,
      f"cx={(min(x2) + max(x2)) / 2:.1f}")

# round-trip: transform of the identity must be a no-op
check("transform_path is lossless at scale 1",
      transform_path("M1,2 L3,4", 1, 0, 0) == "M1.00 2.00 L3.00 4.00",
      transform_path("M1,2 L3,4", 1, 0, 0))

print("\n" + ("ALL CHECKS PASSED" if not fails else f"{len(fails)} FAILURES: {fails}"))
sys.exit(1 if fails else 0)
