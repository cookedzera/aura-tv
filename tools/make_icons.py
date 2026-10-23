"""Write Aura's thin-line icon set (1.5px stroke vector drawables), its brand mark (the sun on its sky
arc, as on the boot screen) as launcher icon and TV banner, and the GitHub social preview.

usage: make_icons.py RES_DIR INTER.ttf [DOCS_DIR]
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = Path(sys.argv[1])
INTER = Path(sys.argv[2])
DOCS = Path(sys.argv[3]) if len(sys.argv) > 3 else None
SS = 4  # supersampling for smooth curves


def inter(size, weight):
    f = ImageFont.truetype(str(INTER), size)
    f.set_variation_by_axes([min(32, max(14, size // SS)), weight])
    return f


def sky(w, h, sun):
    """Night lifting toward first light, warm around the sun."""
    top, bottom = (14, 24, 50), (4, 5, 11)
    img = Image.new("RGB", (w, h))
    px = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        px.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    glow = Image.new("L", (w, h), 0)
    r = int(min(w, h) * 0.42)
    ImageDraw.Draw(glow).ellipse([sun[0] - r, sun[1] - r, sun[0] + r, sun[1] + r], fill=72)
    glow = glow.filter(ImageFilter.GaussianBlur(r * 0.5))
    warm = Image.new("RGB", (w, h), (255, 140, 70))
    return Image.composite(warm, img, glow)


def mark(img, cx, base, radius, progress=0.68, stroke=1.0, sun_size=0.11):
    """The sun climbing its dashed arc above a hairline horizon; the lit trail is warm."""
    # strokes go on their own layer so their alpha blends instead of overwriting the sky's pixels
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    box = [cx - radius, base - radius, cx + radius, base + radius]
    w = max(SS, round(2.4 * SS * stroke))
    # dashed remainder of the arc
    for a in range(int(180 + 180 * progress) + 6, 360, 7):
        d.arc(box, a, a + 3, fill=(255, 255, 255, 110), width=max(SS, round(1.4 * SS * stroke)))
    # lit trail, orange to cream
    end = 180 + 180 * progress
    steps = 60
    for i in range(steps):
        a0 = 180 + (end - 180) * i / steps
        a1 = 180 + (end - 180) * (i + 1) / steps + 0.6
        t = i / steps
        col = tuple(round(a + (b - a) * t) for a, b in zip((255, 166, 92), (255, 240, 200)))
        d.arc(box, a0, a1, fill=col + (255,), width=w)
    # horizon
    hw = radius * 1.7
    for x in range(int(cx - hw), int(cx + hw)):
        t = 1 - abs(x - cx) / hw
        for dy in range(max(1, round(SS * 0.6 * stroke))):
            d.point((x, base + dy), fill=(255, 255, 255, int(70 * t)))
    img.alpha_composite(layer)
    # the sun with its glow
    ang = math.radians(end)
    sx, sy = cx + radius * math.cos(ang), base + radius * math.sin(ang)
    halo = Image.new("RGBA", img.size, (0, 0, 0, 0))
    hr = radius * 0.32
    ImageDraw.Draw(halo).ellipse([sx - hr, sy - hr, sx + hr, sy + hr], fill=(255, 179, 71, 170))
    img.alpha_composite(halo.filter(ImageFilter.GaussianBlur(hr * 0.5)))
    sr = radius * sun_size
    ImageDraw.Draw(img).ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=(255, 244, 214, 255))


def words(img, items):
    """Text blended at its alpha: (xy, text, font, rgba, anchor) per item."""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for xy, text, font, fill, anchor in items:
        d.text(xy, text, font=font, fill=fill, anchor=anchor)
    img.alpha_composite(layer)


def brand(res, docs):
    # launcher icon: the mark alone on a rounded night-sky tile
    s = 144 * SS
    base, radius = s * 0.66, s * 0.30
    sun = (s / 2 + radius * math.cos(math.radians(180 + 180 * 0.68)), base + radius * math.sin(math.radians(180 + 180 * 0.68)))
    tile = sky(s, s, sun).convert("RGBA")
    mark(tile, s / 2, base, radius, stroke=2.2, sun_size=0.16)
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=34 * SS, fill=255)
    icon = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    icon.paste(tile, (0, 0), mask)
    xxh = res / "drawable-xxhdpi"
    xxh.mkdir(exist_ok=True)
    icon.resize((144, 144), Image.LANCZOS).save(xxh / "app_icon.png")

    # TV banner: the mark and the wordmark, as on the boot screen
    w, h = 320 * SS, 180 * SS
    base, radius = h * 0.64, h * 0.26
    sun = (w * 0.27 + radius * math.cos(math.radians(302)), base + radius * math.sin(math.radians(302)))
    banner = sky(w, h, sun).convert("RGBA")
    mark(banner, w * 0.27, base, radius, stroke=1.6, sun_size=0.12)
    words(banner, [((w * 0.48, base), "Aura", inter(56 * SS, 800), (255, 255, 255, 245), "ls"),
                   ((w * 0.485, base + 20 * SS), "  ".join("HOME SCREEN"), inter(9 * SS, 700), (255, 255, 255, 150), "ls")])
    xh = res / "drawable-xhdpi"
    xh.mkdir(exist_ok=True)
    banner.convert("RGB").resize((320, 180), Image.LANCZOS).save(xh / "banner.png")

    if docs:
        # GitHub social preview, 1280 x 640
        w, h = 1280 * 2, 640 * 2
        base, radius = h * 0.5, h * 0.19
        cx = w / 2
        sun = (cx + radius * math.cos(math.radians(302)), base + radius * math.sin(math.radians(302)))
        card = sky(w, h, sun).convert("RGBA")
        mark(card, cx, base, radius, stroke=1.4, sun_size=0.07)
        words(card, [((cx, base + 230), "Aura", inter(170, 800), (255, 255, 255, 245), "ms"),
                     ((cx, base + 320), "  ".join("A FAST, GLASS-CLEAR HOME SCREEN FOR ANDROID TV".split(" ")),
                      inter(30, 700), (255, 255, 255, 150), "ms")])
        docs.mkdir(parents=True, exist_ok=True)
        card.convert("RGB").resize((1280, 640), Image.LANCZOS).save(docs / "social.png")


def gear(teeth=8, ro=9.4, ri=7.1, top=9.5, base=15.0, hole=3.0, c=12.0):
    pts = []
    for k in range(teeth):
        a = k * 360 / teeth
        for ang, r in ((a - base, ri), (a - top, ro), (a + top, ro), (a + base, ri)):
            t = math.radians(ang - 90)
            pts.append((c + r * math.cos(t), c + r * math.sin(t)))
    d = "M%.2f,%.2f " % pts[0]
    for i in range(1, len(pts)):
        x, y = pts[i]
        # root segments follow the inner circle, everything else is straight
        if i % 4 == 0:
            d += "A%.2f,%.2f 0 0,1 %.2f,%.2f " % (ri, ri, x, y)
        else:
            d += "L%.2f,%.2f " % (x, y)
    d += "A%.2f,%.2f 0 0,1 %.2f,%.2f Z " % (ri, ri, pts[0][0], pts[0][1])
    d += "M%.2f,%.2f A%.2f,%.2f 0 1,1 %.2f,%.2f A%.2f,%.2f 0 1,1 %.2f,%.2f Z" % (
        c + hole, c, hole, hole, c - hole, c, hole, hole, c + hole, c)
    return d


# 24-unit grid, drawn as 1.5-unit round-capped strokes
STROKES = {
    "ic_search": "M16.5,10.5 A6,6 0 1,1 4.5,10.5 A6,6 0 1,1 16.5,10.5 Z M15,15 L20,20",
    "ic_mic": "M12,3 A3,3 0 0,1 15,6 L15,11 A3,3 0 0,1 9,11 L9,6 A3,3 0 0,1 12,3 Z M5.5,11 A6.5,6.5 0 0,0 18.5,11 M12,17.5 L12,21 M9,21 L15,21",
    "ic_input": "M9,5 L19,5 A2,2 0 0,1 21,7 L21,17 A2,2 0 0,1 19,19 L9,19 M3,12 L14.5,12 M11,8.5 L14.5,12 L11,15.5",
    "ic_tune": "M4,7 L7,7 M11,7 L20,7 M11,7 A2,2 0 1,1 7,7 A2,2 0 1,1 11,7 Z M4,12 L13,12 M17,12 L20,12 M17,12 A2,2 0 1,1 13,12 A2,2 0 1,1 17,12 Z M4,17 L9,17 M13,17 L20,17 M13,17 A2,2 0 1,1 9,17 A2,2 0 1,1 13,17 Z",
    "ic_wifi": "M2.5,9.2 A13.5,13.5 0 0,1 21.5,9.2 M5.6,12.6 A9,9 0 0,1 18.4,12.6 M8.7,16 A4.7,4.7 0 0,1 15.3,16 M12,19.9 L12,20",
    "ic_bluetooth": "M7,7.5 L17,16.5 L12,21 L12,3 L17,7.5 L7,16.5",
    "ic_picture": "M4.5,4.5 L19.5,4.5 A1.5,1.5 0 0,1 21,6 L21,15.5 A1.5,1.5 0 0,1 19.5,17 L4.5,17 A1.5,1.5 0 0,1 3,15.5 L3,6 A1.5,1.5 0 0,1 4.5,4.5 Z M9,20.5 L15,20.5 M12,17 L12,20.5",
    "ic_volume": "M4,9.5 L7.5,9.5 L12,5.5 L12,18.5 L7.5,14.5 L4,14.5 Z M15.5,9.2 A4,4 0 0,1 15.5,14.8 M18,6.5 A7.5,7.5 0 0,1 18,17.5",
    "ic_volume_off": "M4,9.5 L7.5,9.5 L12,5.5 L12,18.5 L7.5,14.5 L4,14.5 Z M15.5,9.5 L20.5,14.5 M20.5,9.5 L15.5,14.5",
    "ic_moon": "M19.5,14.8 A8,8 0 1,1 9.2,4.5 A8.5,8.5 0 0,0 19.5,14.8 Z",
    "ic_bolt": "M13,2.5 L5.5,13.2 L11.5,13.2 L10.5,21.5 L18.5,10.8 L12.5,10.8 Z",
    "ic_photo": "M5,4.5 L19,4.5 A1.5,1.5 0 0,1 20.5,6 L20.5,18 A1.5,1.5 0 0,1 19,19.5 L5,19.5 A1.5,1.5 0 0,1 3.5,18 L3.5,6 A1.5,1.5 0 0,1 5,4.5 Z M3.5,16 L9,11 L13.5,15 L16,12.5 L20.5,16.5 M17,8.5 A1.5,1.5 0 1,1 14,8.5 A1.5,1.5 0 1,1 17,8.5 Z",
    "ic_settings": gear(),
    "ic_power": "M12,3 L12,11 M7.2,7.2 A7.5,7.5 0 1,0 16.8,7.2",
    "ic_apps": "M4.5,4.5 L10,4.5 L10,10 L4.5,10 Z M14,4.5 L19.5,4.5 L19.5,10 L14,10 Z M4.5,14 L10,14 L10,19.5 L4.5,19.5 Z M14,14 L19.5,14 L19.5,19.5 L14,19.5 Z",
    "ic_place": "M12,21 C12,21 18.5,14.8 18.5,9.5 A6.5,6.5 0 0,0 5.5,9.5 C5.5,14.8 12,21 12,21 Z M14.5,9.5 A2.5,2.5 0 1,1 9.5,9.5 A2.5,2.5 0 1,1 14.5,9.5 Z",
    "ic_add": "M12,5 L12,19 M5,12 L19,12",
    "ic_remove": "M5,12 L19,12",
    "ic_check": "M5,12.5 L10,17.5 L19.5,7",
    "ic_info": "M21,12 A9,9 0 1,1 3,12 A9,9 0 1,1 21,12 Z M12,11 L12,16.5 M12,7.75 L12,7.8",
    "ic_skip": "M6,6 L14,12 L6,18 Z M18,6 L18,18",
    "ic_hdmi": "M3,8 L21,8 L21,12.5 L18.5,16 L5.5,16 L3,12.5 Z M7,11.8 L17,11.8",
    "ic_av": "M17,12 A5,5 0 1,1 7,12 A5,5 0 1,1 17,12 Z M13.5,12 A1.5,1.5 0 1,1 10.5,12 A1.5,1.5 0 1,1 13.5,12 Z",
    "ic_move": "M8,8 L4,12 L8,16 M16,8 L20,12 L16,16 M4,12 L20,12",
    "ic_chevron": "M9.5,6 L15.5,12 L9.5,18",
}


def main():
    d = RES / "drawable"
    d.mkdir(parents=True, exist_ok=True)
    for old in d.glob("ic_*.xml"):
        old.unlink()
    for name, path in STROKES.items():
        width = "2.6" if name == "ic_wifi" else "1.5"
        extra = ""
        if name == "ic_wifi":
            # the dot under the arcs is a filled circle; arcs keep the thin stroke
            path, dot = path.rsplit(" M12,19.9", 1)
            extra = ('  <path android:fillColor="#FFFFFFFF" android:pathData="M13.3,19.9 A1.3,1.3 0 1,1 10.7,19.9 '
                     'A1.3,1.3 0 1,1 13.3,19.9 Z"/>\n')
            width = "1.5"
        (d / f"{name}.xml").write_text(
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
            f'  <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="{width}" '
            f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path}"/>\n'
            f'{extra}</vector>\n', encoding="utf-8")

    brand(RES, DOCS)
    print("icons:", len(STROKES))


if __name__ == "__main__":
    main()
