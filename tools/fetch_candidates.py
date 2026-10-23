"""Collect landscape-shaped Featured Pictures from Wikimedia Commons and build a contact sheet.

usage: fetch_candidates.py OUT_DIR "Category name:count" ["Category name:count" ...]
"""
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(sys.argv[1])
CATEGORIES = [(a.rsplit(":", 1)[0], int(a.rsplit(":", 1)[1])) for a in sys.argv[2:]]
UA = "AuraTVLauncher/1.0 (personal TV launcher build)"
MIN_WIDTH = 3000


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(8):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 7:
                raise
            time.sleep(5 * (attempt + 1))


def plain(s):
    return html.unescape(re.sub(r"<[^>]+>", "", s or "")).strip()


def main():
    thumbs = OUT / "candidates"
    thumbs.mkdir(parents=True, exist_ok=True)
    picked, seen = [], set()
    for cat, want in CATEGORIES:
        cont, found = {}, 0
        while found < want:
            q = {
                "action": "query", "format": "json", "generator": "categorymembers",
                "gcmtitle": "Category:" + cat, "gcmtype": "file", "gcmlimit": "50",
                "gcmsort": "timestamp", "gcmdir": "desc",
                "prop": "imageinfo", "iiprop": "url|size|mime|extmetadata", "iiurlwidth": "480",
            }
            q.update(cont)
            data = json.loads(get("https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(q)))
            for page in (data.get("query", {}).get("pages", {}) or {}).values():
                ii = (page.get("imageinfo") or [{}])[0]
                w, h = ii.get("width", 0), ii.get("height", 0)
                if page["title"] in seen or ii.get("mime") != "image/jpeg" or not h or w < MIN_WIDTH:
                    continue
                if not (1.4 <= w / h <= 2.2):
                    continue
                meta = ii.get("extmetadata", {})
                seen.add(page["title"])
                picked.append({
                    "id": len(picked), "category": cat, "title": page["title"],
                    "artist": plain(meta.get("Artist", {}).get("value"))[:120],
                    "license": plain(meta.get("LicenseShortName", {}).get("value")),
                    "description": plain(meta.get("ImageDescription", {}).get("value"))[:300],
                    "page": ii.get("descriptionurl"), "thumb": ii.get("thumburl"),
                    "width": w, "height": h,
                })
                found += 1
                if found >= want:
                    break
            if "continue" not in data or found >= want:
                break
            cont = data["continue"]
            time.sleep(0.5)
        print(cat, found, flush=True)
        time.sleep(1)

    for p in picked:
        dest = thumbs / f"{p['id']:03d}.jpg"
        if not dest.exists():
            dest.write_bytes(get(p["thumb"]))
            time.sleep(0.35)
    (OUT / "candidates.json").write_text(json.dumps(picked, indent=1, ensure_ascii=False), encoding="utf-8")

    cols, cw, ch = 10, 240, 135
    rows = (len(picked) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cw, rows * ch), "black")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(size=22)
    for p in picked:
        im = Image.open(thumbs / f"{p['id']:03d}.jpg").convert("RGB")
        im.thumbnail((cw - 4, ch - 4))
        x, y = (p["id"] % cols) * cw, (p["id"] // cols) * ch
        sheet.paste(im, (x + 2, y + 2))
        draw.rectangle([x + 2, y + 2, x + 50, y + 28], fill="black")
        draw.text((x + 6, y + 3), str(p["id"]), fill="yellow", font=font)
    sheet.save(OUT / "contact.jpg", quality=88)
    print("total", len(picked))


if __name__ == "__main__":
    main()
