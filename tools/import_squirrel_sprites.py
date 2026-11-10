#!/usr/bin/env python3
"""
Importerer en ekorn-spritepakke (LES_MEG.md + animations.json + sprites/*.png) til appen.

Bruk:  python3 tools/import_squirrel_sprites.py <mappe-med-animations.json> [--size 192]

- Klipper ut hvert bilde etter rektanglene i animations.json (clips[navn].frames).
- Skalerer alle bilder til samme kvadrat (standard 192 px = 64 dp på telefonen), slik at
  hovedark (222 px-ruter) og overgangsark (148 px-ruter) får samme figurstørrelse.
- Renser alfa: nesten usynlige piksler (dis rundt figuren) fjernes, og nesten helt
  dekkende piksler blir helt dekkende.
- Skriver app/src/main/assets/squirrel/<klipp>/00.png … og kopierer animations.json og LES_MEG.md.
"""
import json, os, shutil, sys
from PIL import Image

src = sys.argv[1]
size = int(sys.argv[sys.argv.index("--size") + 1]) if "--size" in sys.argv else 192
out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "squirrel")

meta = json.load(open(os.path.join(src, "animations.json"), encoding="utf-8"))
if os.path.isdir(out):
    shutil.rmtree(out)
os.makedirs(out)

sheets = {}
for name, sheet in meta["sheets"].items():
    sheets[name] = Image.open(os.path.join(src, sheet["file"])).convert("RGBA")

def clean(im):
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a < 24:
                px[x, y] = (0, 0, 0, 0)
            elif a >= 232:
                px[x, y] = (r, g, b, 255)
    return im

for clip, c in meta["clips"].items():
    d = os.path.join(out, clip)
    os.makedirs(d)
    sheet = sheets[c["sheet"]]
    for i, f in enumerate(c["frames"]):
        cell = sheet.crop((f["x"], f["y"], f["x"] + f["w"], f["y"] + f["h"]))
        # Gjør ruta kvadratisk (noen er 221 px) før skalering, sentrert
        side = max(cell.width, cell.height)
        sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        sq.paste(cell, ((side - cell.width) // 2, (side - cell.height) // 2))
        clean(sq.resize((size, size), Image.LANCZOS)).save(os.path.join(d, f"{i:02d}.png"), optimize=True)
    print(f"{clip}: {len(c['frames'])} bilder, {c['fps']} fps, loop={c['loop']}")

shutil.copy(os.path.join(src, "animations.json"), os.path.join(out, "animations.json"))
if os.path.exists(os.path.join(src, "LES_MEG.md")):
    shutil.copy(os.path.join(src, "LES_MEG.md"), os.path.join(out, "LES_MEG.md"))
