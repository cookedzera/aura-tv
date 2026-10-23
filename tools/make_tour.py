"""Build docs/tour.webp: an animated walk through Aura from real TV screenshots, each scene with a
slow Ken Burns zoom and a caption pill, joined by crossfades.

usage: make_tour.py OUT.webp INTER.ttf scene.png "caption" [scene.png "caption" ...]
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 960, 540
HOLD, FADE = 10, 5          # frames per scene and per crossfade
HOLD_MS, FADE_MS = 190, 60  # frame durations


def caption_font(path, size, weight):
    f = ImageFont.truetype(path, size)
    f.set_variation_by_axes([min(32, size), weight])
    return f


def frame(img, t, text, font):
    """The scene zoomed by t (0..1) toward its centre, with a frosted caption pill."""
    z = 1.0 + 0.035 * t
    cw, ch = img.width / z, img.height / z
    box = ((img.width - cw) / 2, (img.height - ch) / 2, (img.width + cw) / 2, (img.height + ch) / 2)
    out = img.resize((W, H), Image.LANCZOS, box=box).convert("RGBA")
    layer = Image.new("RGBA", out.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    tw = d.textlength(text, font=font)
    x0, y0, ph = 28, H - 28 - 38, 38
    d.rounded_rectangle([x0, y0, x0 + tw + 36, y0 + ph], radius=ph // 2, fill=(12, 14, 20, 170),
                        outline=(255, 255, 255, 60), width=1)
    d.text((x0 + 18, y0 + ph / 2), text, font=font, fill=(255, 255, 255, 240), anchor="lm")
    out.alpha_composite(layer)
    return out.convert("RGB")


def main():
    out, font_path, pairs = Path(sys.argv[1]), sys.argv[2], sys.argv[3:]
    font = caption_font(font_path, 15, 600)
    scenes = [(Image.open(pairs[i]).convert("RGB"), pairs[i + 1]) for i in range(0, len(pairs), 2)]
    frames, durations = [], []
    for k, (img, text) in enumerate(scenes):
        held = [frame(img, i / (HOLD - 1), text, font) for i in range(HOLD)]
        frames += held
        durations += [HOLD_MS] * HOLD
        nxt_img, nxt_text = scenes[(k + 1) % len(scenes)]
        nxt = frame(nxt_img, 0, nxt_text, font)
        for i in range(1, FADE + 1):
            frames.append(Image.blend(held[-1], nxt, i / (FADE + 1)))
            durations.append(FADE_MS)
    frames[0].save(out, save_all=True, append_images=frames[1:], duration=durations, loop=0,
                   quality=72, method=6)
    print(out, len(frames), "frames", round(out.stat().st_size / 1024), "KB")


if __name__ == "__main__":
    main()
