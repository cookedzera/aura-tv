"""Download chosen Featured Pictures at 3840px, grade and downscale them for the TV, write assets + metadata.

usage: prepare_wallpapers.py SRC_DIR ASSETS_DIR
"""
import colorsys
import json
import math
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter, ImageOps

SRC = Path(sys.argv[1])
ASSETS = Path(sys.argv[2])
UA = "AuraTVLauncher/1.0 (personal TV launcher build)"
# just above the 720p framebuffer times the maximum drift zoom: the GPU barely resamples
OUT_W, OUT_H = 1536, 864

# rotation order -> (candidate id, place, region, author, vertical crop focus, editorial line)
CHOSEN = [
    (102, "Matterhorn", "Zermatt · Switzerland", "Sam Ferrara", 0.45,
     "The mountain wears the last of the light like a veil."),
    (33, "Northern Lights", "Lofoten · Norway", "Johannes Groll", 0.5,
     "A green river runs quietly across the Arctic sky."),
    (37, "Pangong Tso", "Ladakh · India", "KennyOMG", 0.5,
     "Four thousand metres up, a lake the colour of deep sky."),
    (80, "Seljalandsfoss", "Suðurland · Iceland", "Diego Delso", 0.5,
     "Behind the falling water, the evening turns to glass."),
    (65, "Øksfjorden", "Finnmark · Norway", "Ximonic (Simo Räsänen)", 0.5,
     "The low Arctic sun, laid gently across the fjord."),
    (35, "Bear Lake", "Alaska · USA", "U.S. Air Force", 0.5,
     "Aurora dancing over the frozen interior of Alaska."),
    (81, "Reynisfjara", "Vík · Iceland", "Diego Delso", 0.5,
     "Black sand, a lone basalt stack and the North Atlantic."),
    (29, "Aurora & Perseids", "Norderney · Germany", "Stephan Sprinz", 0.5,
     "Shooting stars falling through a crimson aurora."),
    (43, "Zanskar Range", "Padum · Ladakh, India", "Timothy A. Gonsalves", 0.45,
     "The long road to Padum, beneath walls of snow."),
    (27, "High Fläming", "Brandenburg · Germany", "Karl-Sebastian Schulte", 0.5,
     "Sunrise threading through the morning mist."),
]


# light of each photograph, in CHOSEN order; the launcher matches these to the real sky (Sky Sync)
MOODS = [
    "golden", "night", "day", "golden", "golden", "night", "day", "night", "day", "dawn",
]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(8):
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise
            if e.code != 429 or attempt == 7:
                raise
            time.sleep(5 * (attempt + 1))


def fetch(thumb, dest):
    if dest.exists():
        return
    for width in (3840, 2560, 1920):
        url = re.sub(r"/\d+px-", f"/{width}px-", thumb, count=1)
        try:
            data = get(url)
        except urllib.error.HTTPError:
            continue
        dest.write_bytes(data)
        if Image.open(dest).width >= 1900:
            return
    raise RuntimeError("no large rendition for " + thumb)


def grade(im):
    im = im.filter(ImageFilter.UnsharpMask(radius=40, percent=12, threshold=0))   # clarity
    im = ImageEnhance.Color(im).enhance(1.08)                                       # vibrance
    im = ImageEnhance.Contrast(im).enhance(1.03)
    return im


def accent(im):
    small = im.resize((48, 27))
    sx = sy = wsum = 0.0
    for r, g, b in small.get_flattened_data() if hasattr(small, "get_flattened_data") else small.getdata():
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        w = s * v * v
        sx += math.cos(h * 2 * math.pi) * w
        sy += math.sin(h * 2 * math.pi) * w
        wsum += w
    hue = (math.atan2(sy, sx) / (2 * math.pi)) % 1.0
    r, g, b = colorsys.hsv_to_rgb(hue, 0.55, 0.9)
    return "#%02X%02X%02X" % (int(r * 255), int(g * 255), int(b * 255))


def main():
    cands = {c["id"]: c for c in json.loads((SRC / "candidates.json").read_text(encoding="utf-8"))}
    full = SRC / "full"
    full.mkdir(exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    for old in ASSETS.glob("w*.jpg"):
        old.unlink()
    meta = []
    for n, (cid, place, region, author, focus, line) in enumerate(CHOSEN):
        c = cands[cid]
        raw = full / f"{cid:03d}.jpg"
        fetch(c["thumb"], raw)
        time.sleep(0.6)
        im = ImageOps.exif_transpose(Image.open(raw)).convert("RGB")
        src_w = im.width
        im = ImageOps.fit(im, (OUT_W * 2, OUT_H * 2), Image.LANCZOS, centering=(0.5, focus)) if im.width >= OUT_W * 2 \
            else ImageOps.fit(im, (OUT_W, OUT_H), Image.LANCZOS, centering=(0.5, focus))
        im = grade(im)
        if im.width != OUT_W:
            im = im.resize((OUT_W, OUT_H), Image.LANCZOS)
        im = im.filter(ImageFilter.UnsharpMask(radius=0.9, percent=55, threshold=2))
        name = f"w{n:02d}.jpg"
        # quality 82, progressive: a few hundred KB a photograph keeps the APK small to install
        im.save(ASSETS / name, quality=82, optimize=True, progressive=True)
        meta.append({
            "file": name, "place": place, "region": region, "author": author, "line": line, "mood": MOODS[n],
            "license": c["license"], "source": c["page"], "accent": accent(im),
        })
        print(f"{name} {src_w}px src -> {(ASSETS / name).stat().st_size // 1024} KB  {meta[-1]['accent']}", flush=True)
    (ASSETS / "wallpapers.json").write_text(json.dumps(meta, indent=1, ensure_ascii=False), encoding="utf-8")

    sheet = Image.new("RGB", (6 * 320, ((len(meta) + 5) // 6) * 180))
    for i, m in enumerate(meta):
        t = Image.open(ASSETS / m["file"]).resize((320, 180), Image.LANCZOS)
        sheet.paste(t, ((i % 6) * 320, (i // 6) * 180))
    sheet.save(SRC / "chosen.jpg", quality=88)


if __name__ == "__main__":
    main()
