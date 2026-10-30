"""Packs the night-sky data for the screensaver into launcher/src/main/assets/sky/.

Source: d3-celestial by Olaf Frohn (BSD-3-Clause, https://github.com/ofrohn/d3-celestial), whose
stars come from the XHIP Hipparcos compilation (Anderson & Francis 2012) and whose constellation
figures follow the IAU's. Run with the four JSON files downloaded into one folder:

    python tools/make_sky.py <folder with stars.6.json, constellations.lines.json,
                              constellations.json, starnames.json>

stars.bin   big-endian: int count, then per star float ra, dec (J2000, degrees), mag, B-V
lines.bin   big-endian: int count, then per segment float ra1, dec1, ra2, dec2
names.json  constellations (name, label position, rank) and the brightest stars' proper names
"""
import json
import pathlib
import struct
import sys

MAG = 5.8     # about 4,000 stars: a dark country sky, as rich as a TV picture can carry
NAMED = 1.6   # proper names only for the brightest

src = pathlib.Path(sys.argv[1])
out = pathlib.Path(__file__).resolve().parent.parent / "launcher/src/main/assets/sky"
out.mkdir(parents=True, exist_ok=True)


def ra(lon):
    return lon % 360.0


stars = json.loads((src / "stars.6.json").read_text(encoding="utf-8"))["features"]
names = json.loads((src / "starnames.json").read_text(encoding="utf-8"))
kept, named = [], []
for f in stars:
    mag = f["properties"]["mag"]
    if mag > MAG:
        continue
    lon, lat = f["geometry"]["coordinates"]
    try:
        bv = float(f["properties"]["bv"])
    except (TypeError, ValueError):
        bv = 0.6
    kept.append((ra(lon), lat, mag, bv))
    n = names.get(str(f["id"]), {}).get("name", "")
    if mag <= NAMED and n:
        named.append({"n": n, "ra": round(ra(lon), 4), "dec": round(lat, 4), "mag": mag})
kept.sort(key=lambda s: s[2])
with open(out / "stars.bin", "wb") as fh:
    fh.write(struct.pack(">i", len(kept)))
    for s in kept:
        fh.write(struct.pack(">ffff", *s))

segments = []
for f in json.loads((src / "constellations.lines.json").read_text(encoding="utf-8"))["features"]:
    for line in f["geometry"]["coordinates"]:
        for (a, b), (c, d) in zip(line, line[1:]):
            segments.append((ra(a), b, ra(c), d))
with open(out / "lines.bin", "wb") as fh:
    fh.write(struct.pack(">i", len(segments)))
    for s in segments:
        fh.write(struct.pack(">ffff", *s))

cons = []
for f in json.loads((src / "constellations.json").read_text(encoding="utf-8"))["features"]:
    p = f["properties"]
    lon, lat = f["geometry"]["coordinates"]
    cons.append({"n": p["name"], "ra": round(ra(lon), 3), "dec": round(lat, 3), "rank": int(p.get("rank", 3))})
(out / "names.json").write_text(json.dumps({"constellations": cons, "stars": named}, separators=(",", ":")), encoding="utf-8")
print(f"{len(kept)} stars, {len(segments)} line segments, {len(cons)} constellations, {len(named)} named stars")
for f in out.iterdir():
    print(f"  {f.name}: {f.stat().st_size // 1024} KB")
