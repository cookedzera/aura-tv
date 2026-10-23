"""Crop app banner art out of TV screenshots (lossless PNG) for design mockups."""
import sys
from pathlib import Path

from PIL import Image

SHOTS = Path(sys.argv[1])
OUT = Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)

# (screenshot, name, box) -- dock tiles at rest are 176x98 px in the 720p captures
CROPS = [
    ("05-aura-wallpaper.png", "netflix", (316, 540, 492, 638)),
    ("05-aura-wallpaper.png", "prime", (522, 540, 698, 638)),
    ("05-aura-wallpaper.png", "hotstar", (728, 540, 904, 638)),
    ("06-dock-focus-prime.png", "sonyliv", (376, 540, 552, 638)),
    ("06-dock-focus-prime.png", "youtube", (582, 540, 758, 638)),
    ("06-dock-focus-prime.png", "netmirror", (788, 540, 964, 638)),
    ("05-aura-wallpaper.png", "tizentube", (98, 534, 297, 645)),
]

for shot, name, box in CROPS:
    im = Image.open(SHOTS / shot).convert("RGB")
    l, t, r, b = box
    inset = 3
    im.crop((l + inset, t + inset, r - inset, b - inset)).save(OUT / f"{name}.png")
print("cropped", len(CROPS))
