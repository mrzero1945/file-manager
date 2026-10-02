#!/usr/bin/env python3
"""
Build every visual resource for File Manager from tools/designspec.py.

Outputs
-------
  design/svg/<icon>.svg             Inkscape sources (one layered file per icon)
  design/svg/launcher*.svg          launcher artwork sources
  app/src/main/res/drawable/*.xml   VectorDrawables (identical path data)
  app/src/main/res/drawable/tile_*.xml   coloured rounded-square tiles
  app/src/main/res/mipmap-*dpi/     launcher PNGs rasterised by the Inkscape CLI
  app/src/main/res/values/colors.xml
"""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import designspec as ds

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESIGN = os.path.join(ROOT, "design")
SVG_DIR = os.path.join(DESIGN, "svg")
RES = os.path.join(ROOT, "app", "src", "main", "res")
DRAWABLE = os.path.join(RES, "drawable")
VALUES = os.path.join(RES, "values")
INKSCAPE_BIN = "inkscape"

# Icon tile gradients: (start, end).
TILES = {
    "folder":   ("#4BA4FF", "#0A6EDB"),
    "image":    ("#45E684", "#12A150"),
    "video":    ("#D595FF", "#8B32D6"),
    "audio":    ("#FF85A6", "#E0335F"),
    "pdf":      ("#FF8279", "#E03328"),
    "archive":  ("#FFC96F", "#EE8A00"),
    "code":     ("#9694FF", "#4A48D8"),
    "apk":      ("#74E894", "#1FA84A"),
    "text":     ("#A9AAAF", "#6B6C72"),
    "file":     ("#A9AAAF", "#6B6C72"),
}
TILE_RADIUS_DP = 9.5

# Raw 24-unit folder outline used for the launcher glyph.
FOLDER_24 = (
    "M2.4,9.1 C2.4,7.7 3.6,6.5 5,6.5 H9 C9.8,6.5 10.5,6.9 11,7.5 "
    "L12.2,9 H19 C20.4,9 21.6,10.2 21.6,11.6 V17.4 "
    "C21.6,18.8 20.4,20 19,20 H5 C3.6,20 2.4,18.8 2.4,17.4 Z"
)

NUM = re.compile(r"-?\d*\.?\d+(?:e-?\d+)?")


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def transform_path(d, scale, tx, ty):
    """Scale + translate an absolute path (M/L/C/H/V/Z) into new coordinates."""
    toks = [t for t in re.split(r"([A-Za-z])|(-?\d*\.?\d+)", d)
            if t and not re.fullmatch(r"[,\s]+", t)]
    out, cmd = [], None
    i, n = 0, len(toks)
    while i < n:
        t = toks[i]
        if re.match(r"^[A-Za-z]$", t):
            cmd = t
            out.append(t)
            i += 1
            continue
        val = float(t)
        if cmd in ("H", "h"):
            out.append(f"{val * scale + tx:.2f} ")
            i += 1
        elif cmd in ("V", "v"):
            out.append(f"{val * scale + ty:.2f} ")
            i += 1
        else:
            y = float(toks[i + 1])
            out.append(f"{val * scale + tx:.2f} {y * scale + ty:.2f} ")
            i += 2
    return re.sub(r"\s+", " ", "".join(out)).strip()


def path_bbox(d):
    """Bounding box of a path, in its own coordinate space.

    Control points over-estimate a curve's extent, but for our corner-heavy
    folder outline every extreme is reached by an explicit anchor, so the box
    is exact.
    """
    toks = [t for t in re.split(r"([A-Za-z])|(-?\d*\.?\d+)", d)
            if t and not re.fullmatch(r"[,\s]+", t)]
    xs, ys, i, cmd = [], [], 0, None
    while i < len(toks):
        t = toks[i]
        if re.match(r"^[A-Za-z]$", t):
            cmd = t
            i += 1
            continue
        v = float(t)
        if cmd in ("H", "h"):
            xs.append(v)
            i += 1
        elif cmd in ("V", "v"):
            ys.append(v)
            i += 1
        else:
            xs.append(v)
            ys.append(float(toks[i + 1]))
            i += 2
    return min(xs), min(ys), max(xs), max(ys)


def glyph_path(canvas, box_ratio, optical_nudge=0.0, margin_ratio=0.0):
    """Folder glyph scaled to `box_ratio` of `canvas` and centred on it.

    Centring uses the glyph's real bounding box rather than the nominal 24
    grid, which is what makes the mark sit optically centred in the icon.
    `optical_nudge` shifts it further in fractions of the glyph size.
    """
    size = canvas * box_ratio
    scale = size / 24.0
    x0, y0, x1, y1 = path_bbox(FOLDER_24)
    # translate so the glyph's bounding box centres on the canvas centre
    tx = canvas / 2.0 - (x0 + x1) / 2.0 * scale + margin_ratio
    ty = canvas / 2.0 - (y0 + y1) / 2.0 * scale + optical_nudge * size
    return transform_path(FOLDER_24, scale, tx, ty)


# ---------------------------------------------------------------------------
# Inkscape SVG sources
# ---------------------------------------------------------------------------

def ns(doc, w, h, vb):
    return (
        'xmlns="http://www.w3.org/2000/svg" '
        'xmlns:sodipodi="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd" '
        'xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" '
        f'sodipodi:docname="{doc}" version="1.1" '
        f'width="{w}" height="{h}" viewBox="0 0 {vb} {vb}" '
        f'id="svg{abs(hash(doc)) % 99999}" inkscape:version="1.3.2 (091e20e, 2023-11-25)"'
    )


def icon_svg(name, parts, vb=24, color="#000000"):
    body, idx = [], 0
    for d, o in parts:
        idx += 1
        pid = f"{name.replace('_', '-')}-p{idx}"
        if o.get("mode", "s") == "s":
            style = (
                f"fill:none;stroke:{color};stroke-width:{o.get('sw', ds.STROKE)};"
                f"stroke-linecap:{o.get('cap', 'round')};"
                f"stroke-linejoin:{o.get('join', 'round')};stroke-miterlimit:4"
            )
        else:
            style = f"fill:{color};fill-opacity:1;stroke:none"
        body.append(f'    <path id="{pid}" d="{d}" style="{style}" />')

    return f'''<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<svg {ns(name + ".svg", f"{vb}px", f"{vb}px", vb)}>
  <sodipodi:namedview id="nv" pagecolor="#ffffff" inkscape:pageopacity="0"
     inkscape:document-units="px" inkscape:zoom="8" inkscape:cx="12"
     inkscape:cy="12" inkscape:current-layer="layer-icon" />
  <g inkscape:label="Guides" inkscape:groupmode="layer" id="layer-guides"
     style="display:none">
    <rect x="0" y="0" width="{vb}" height="{vb}" id="safe-area"
       style="fill:none;stroke:#ff3b30;stroke-width:0.04;stroke-dasharray:0.5 0.5" />
  </g>
  <g inkscape:label="Icon" inkscape:groupmode="layer" id="layer-icon">
{chr(10).join(body)}
  </g>
</svg>
'''


def launcher_svg(size=1024, adaptive=False, with_bg=True, with_fg=True,
                 name="launcher", with_guidelines=True):
    L = ds.LAUNCHER
    c = size / 2.0
    layers = []

    if with_bg:
        d = (f"M0,0 H{size} V{size} H0 Z" if adaptive
             else ds.superellipse_path(c, c, c, c, n=5.0))
        layers.append(f'''  <g inkscape:label="Background" inkscape:groupmode="layer" id="layer-bg" style="display:inline">
    <defs>
      <linearGradient id="bgGrad" x1="0" y1="0" x2="0" y2="{size}"
          gradientUnits="userSpaceOnUse">
        <stop offset="0" stop-color="{L['bg_top']}" stop-opacity="1" />
        <stop offset="1" stop-color="{L['bg_bottom']}" stop-opacity="1" />
      </linearGradient>
    </defs>
    <path d="{d}" id="squircle" style="fill:url(#bgGrad);stroke:none" />
  </g>''')

    if with_fg:
        if adaptive:
            gd = glyph_path(size, 0.62, optical_nudge=-0.008)
        else:
            gd = glyph_path(size, 0.565, optical_nudge=-0.010)
        # shadow first, glyph on top - otherwise the shadow tints the white fill
        layers.append(f'''  <g inkscape:label="Glyph" inkscape:groupmode="layer" id="layer-glyph" style="display:inline">
    <path d="{gd}" id="folder-glyph-shadow" transform="translate(0,{size * 0.016:.1f})"
       style="fill:{L['glyph_shadow']};fill-opacity:0.22;stroke:none" />
    <path d="{gd}" id="folder-glyph"
       style="fill:{L['glyph']};stroke:none" />
  </g>''')

    guides = ""
    if with_guidelines:
        guides = f'''  <g inkscape:label="Guides" inkscape:groupmode="layer" id="layer-guides" style="display:none">
    <circle cx="{c}" cy="{c}" r="{c - 6}" id="safe-circle"
       style="fill:none;stroke:#ffffff;stroke-width:{size / 512:.2f};stroke-dasharray:{size / 60:.1f} {size / 60:.1f};opacity:0.55" />
    <rect x="{size * 0.11:.1f}" y="{size * 0.11:.1f}" width="{size * 0.78:.1f}"
       height="{size * 0.78:.1f}" id="safe-square"
       style="fill:none;stroke:#ffffff;stroke-width:{size / 512:.2f};stroke-dasharray:{size / 60:.1f} {size / 60:.1f};opacity:0.55" />
  </g>
'''

    return f'''<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<svg {ns(name + ".svg", f"{size}px", f"{size}px", size)}>
  <sodipodi:namedview id="nv" pagecolor="#ffffff" inkscape:pageopacity="0"
     inkscape:document-units="px" inkscape:zoom="0.45" inkscape:cx="{c}"
     inkscape:cy="{c}" inkscape:current-layer="layer-bg" />
{layers[0] if layers else ""}
{layers[1] if len(layers) > 1 else ""}
{guides}</svg>
'''


# ---------------------------------------------------------------------------
# Android resources
# ---------------------------------------------------------------------------

def vector_xml(parts, vb=24, fill="#FFFFFFFF", stroke="#FFFFFFFF"):
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    android:width="{vb}dp"',
           f'    android:height="{vb}dp"',
           f'    android:viewportWidth="{vb}"',
           f'    android:viewportHeight="{vb}">']
    for d, o in parts:
        mode = o.get("mode", "s")
        attrs = [f'        android:pathData="{esc(d)}"']
        if mode == "s":
            attrs.append(f'        android:strokeColor="{stroke}"')
            attrs.append(f'        android:strokeWidth="{o.get("sw", ds.STROKE)}"')
            attrs.append(f'        android:strokeLineCap="{o.get("cap", "round")}"')
            attrs.append(f'        android:strokeLineJoin="{o.get("join", "round")}"')
        else:
            attrs.append(f'        android:fillColor="{fill}"')
        out.append("    <path\n" + "\n".join(attrs) + " />")
    out.append("</vector>")
    return "\n".join(out) + "\n"


def adaptive_background_xml():
    L = ds.LAUNCHER
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">
            <gradient android:type="linear"
                android:startX="54" android:startY="0"
                android:endX="54" android:endY="108">
                <item android:offset="0.0" android:color="#FF{L['bg_top'][1:]}" />
                <item android:offset="1.0" android:color="#FF{L['bg_bottom'][1:]}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


def adaptive_foreground_xml():
    gd = glyph_path(108, 0.62, optical_nudge=-0.008)
    L = ds.LAUNCHER
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group android:translateY="1.4">
        <path android:pathData="{esc(gd)}"
            android:fillColor="#FF{L['glyph_shadow'][1:]}"
            android:fillAlpha="0.22" />
    </group>
    <path android:pathData="{esc(gd)}"
        android:fillColor="#FF{L['glyph'][1:]}" />
</vector>
'''


def tile_xml(cat):
    start, end = (argb(c) for c in TILES[cat])
    return f'''<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <corners android:radius="{TILE_RADIUS_DP}dp" />
    <gradient android:angle="270"
        android:startColor="{start}"
        android:endColor="{end}"
        android:type="linear" />
</shape>
'''


def argb(value):
    """designspec colour string -> #AARRGGBB for Android."""
    if value.startswith("rgba"):
        r, g, b, a = (float(x) for x in re.findall(r"[\d.]+", value))
        return "#{:02X}{:02X}{:02X}{:02X}".format(
            round(a * 255), round(r), round(g), round(b))
    if value.startswith("#"):
        value = value[1:]
    if len(value) == 6:  # implicit opaque
        value = "FF" + value
    return "#" + value.upper()


def colors_xml():
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, value in ds.COLORS.items():
        lines.append(f'    <color name="{name}">{argb(value)}</color>')
    for cat, (start, end) in TILES.items():
        lines.append(f'    <color name="tile_{cat}">{argb(start)}</color>')
        lines.append(f'    <color name="tile_{cat}_dark">{argb(end)}</color>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(content)


def inkscape_version():
    try:
        r = subprocess.run([INKSCAPE_BIN, "--version"], capture_output=True,
                           text=True, timeout=120)
        return r.stdout.strip().splitlines()[0] if r.stdout else "unknown"
    except Exception as exc:
        return f"unavailable ({exc})"


def inkscape_png(svg, png, width):
    os.makedirs(os.path.dirname(png), exist_ok=True)
    cmd = [INKSCAPE_BIN, svg, f"--export-filename={png}",
           f"--export-width={width}", f"--export-height={width}",
           "--export-background-opacity=0", "--export-type=png"]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if r.returncode != 0 or not os.path.exists(png):
        print(f"  ! inkscape failed: {svg}\n{r.stderr[:500]}", file=sys.stderr)
        return False
    return True


def main():
    print("File Manager - asset build")
    print("  inkscape:", inkscape_version())

    for name, parts in ds.ICONS.items():
        write(os.path.join(SVG_DIR, f"{name}.svg"), icon_svg(name, parts))
        write(os.path.join(DRAWABLE, f"ic_{name}.xml"), vector_xml(parts))
    print(f"  {len(ds.ICONS)} icons -> design/svg/*.svg + res/drawable/ic_*.xml")

    for cat in TILES:
        write(os.path.join(DRAWABLE, f"tile_{cat}.xml"), tile_xml(cat))
    print(f"  {len(TILES)} tiles -> res/drawable/tile_*.xml")

    legacy = launcher_svg(1024, name="launcher")
    write(os.path.join(SVG_DIR, "launcher.svg"), legacy)
    write(os.path.join(DESIGN, "launcher-legacy.svg"), legacy)
    write(os.path.join(DESIGN, "launcher-foreground.svg"),
          launcher_svg(108, adaptive=True, with_bg=False, name="launcher-foreground",
                       with_guidelines=True))
    write(os.path.join(DESIGN, "launcher-background.svg"),
          launcher_svg(108, adaptive=True, with_fg=False, name="launcher-background",
                       with_guidelines=False))

    write(os.path.join(DRAWABLE, "ic_launcher_background.xml"), adaptive_background_xml())
    write(os.path.join(DRAWABLE, "ic_launcher_foreground.xml"), adaptive_foreground_xml())

    ok = True
    for d, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                  ("xxhdpi", 144), ("xxxhdpi", 192)):
        outdir = os.path.join(RES, f"mipmap-{d}")
        ok &= inkscape_png(os.path.join(DESIGN, "launcher-legacy.svg"),
                           os.path.join(outdir, "ic_launcher.png"), px)
        ok &= inkscape_png(os.path.join(DESIGN, "launcher-legacy.svg"),
                           os.path.join(outdir, "ic_launcher_round.png"), px)
    ok &= inkscape_png(os.path.join(DESIGN, "launcher-legacy.svg"),
                       os.path.join(DESIGN, "playstore-512.png"), 512)
    print("  launcher PNGs rasterised by Inkscape:", "ok" if ok else "FAILED")

    for n in ("ic_launcher", "ic_launcher_round"):
        write(os.path.join(RES, "mipmap-anydpi-v26", f"{n}.xml"),
              '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
''')

    write(os.path.join(VALUES, "colors.xml"), colors_xml())
    print("  res/values/colors.xml written")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
