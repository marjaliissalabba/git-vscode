#!/usr/bin/env python3
"""
Importerer en ekorn-spritepakke (LES_MEG.md + animations.json + sprites/*.png) til appen.

Bruk:  python3 tools/import_squirrel_sprites.py <mappe-med-animations.json> [--size 192]

- Klipper ut hvert bilde etter rektanglene i animations.json (clips[navn].frames).
- Skalerer alle bilder til samme kvadrat (standard 192 px = 64 dp på telefonen), slik at
  hovedark (222 px-ruter) og overgangsark (148 px-ruter) får samme figurstørrelse.
- Renser alfa: nesten usynlige piksler (dis rundt figuren) fjernes, og nesten helt
  dekkende piksler blir helt dekkende.
- Ankerpunkt (frames[i].anchor, satt i Ekorn Sprite-studio) havner midt i bildet, så kroppen står
  stille. Uten anker brukes rutens midtpunkt.
- Varighet (frames[i].durationMs) skrives til timing.json, som appen bruker som rytme.
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

timing = {}
for clip, c in meta["clips"].items():
    d = os.path.join(out, clip)
    os.makedirs(d)
    sheet = sheets[c["sheet"]]
    durations = []
    for i, f in enumerate(c["frames"]):
        cell = sheet.crop((f["x"], f["y"], f["x"] + f["w"], f["y"] + f["h"]))
        side = max(cell.width, cell.height)
        scale = size / side
        anchor = f.get("anchor") or {"x": f["w"] / 2, "y": f["h"] / 2}
        scaled = cell.resize((max(1, round(cell.width * scale)), max(1, round(cell.height * scale))), Image.LANCZOS)
        out_im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        # Plasser slik at ankeret havner i midten av bildet
        ox = round(size / 2 - anchor["x"] * scale)
        oy = round(size / 2 - anchor["y"] * scale)
        out_im.alpha_composite(scaled, (ox, oy)) if 0 <= ox and 0 <= oy and ox + scaled.width <= size and oy + scaled.height <= size else out_im.paste(scaled, (ox, oy), scaled)
        clean(out_im).save(os.path.join(d, f"{i:02d}.png"), optimize=True)
        durations.append(f.get("durationMs") or round(1000 / c.get("fps", 10)))
    timing[clip] = durations
    print(f"{clip}: {len(c['frames'])} bilder, {c['fps']} fps, loop={c['loop']}")

json.dump(timing, open(os.path.join(out, "timing.json"), "w"), indent=1)
shutil.copy(os.path.join(src, "animations.json"), os.path.join(out, "animations.json"))
if os.path.exists(os.path.join(src, "LES_MEG.md")):
    shutil.copy(os.path.join(src, "LES_MEG.md"), os.path.join(out, "LES_MEG.md"))
