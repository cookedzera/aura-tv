"""Packs the world map for the live-wind screensaver into launcher/src/main/assets/map/.

Source: Natural Earth (public domain, https://www.naturalearthdata.com), the 1:110m land polygons and
country boundary lines. Run with the two GeoJSON files downloaded into one folder:

    python tools/make_map.py <folder with ne_110m_land.geojson, ne_110m_admin_0_boundary_lines_land.geojson>

land.bin, borders.bin   big-endian: int rings, then per ring int points and float lon, lat pairs
"""
import json
import pathlib
import struct
import sys

src = pathlib.Path(sys.argv[1])
out = pathlib.Path(__file__).resolve().parent.parent / "launcher/src/main/assets/map"
out.mkdir(parents=True, exist_ok=True)


def rings(geojson):
    for f in json.loads((src / geojson).read_text(encoding="utf-8"))["features"]:
        g = f["geometry"]
        if g["type"] == "Polygon":
            yield g["coordinates"][0]
        elif g["type"] == "MultiPolygon":
            for poly in g["coordinates"]:
                yield poly[0]
        elif g["type"] == "LineString":
            yield g["coordinates"]
        elif g["type"] == "MultiLineString":
            yield from g["coordinates"]


for name, geojson in (("land.bin", "ne_110m_land.geojson"), ("borders.bin", "ne_110m_admin_0_boundary_lines_land.geojson")):
    rs = [r for r in rings(geojson) if len(r) >= 2]
    with open(out / name, "wb") as fh:
        fh.write(struct.pack(">i", len(rs)))
        for r in rs:
            fh.write(struct.pack(">i", len(r)))
            for lon, lat in r:
                fh.write(struct.pack(">ff", lon, lat))
    print(f"{name}: {len(rs)} rings, {sum(len(r) for r in rs)} points, {(out / name).stat().st_size // 1024} KB")
