"""An isometric picture of a template: outside, and cut away floor by floor to show the rooms."""
import base64, gzip, json, struct, sys
from PIL import Image, ImageDraw, ImageFont

S = 18  # half the width of a block on the picture

COL = [
    ("white_wool", (236, 236, 236)), ("chain", (60, 64, 70)), ("carved_pumpkin", (220, 130, 30)), ("smooth_stone", (160, 160, 160)), ("minecraft:stone", (125, 125, 125)), ("gravel", (130, 124, 120)),
    ("white_terracotta", (210, 178, 160)), ("orange_terracotta", (162, 84, 38)), ("red_terracotta", (143, 61, 46)), ("brown_terracotta", (77, 51, 36)), ("terracotta", (152, 94, 68)), ("oak_wood", (110, 86, 54)), ("anvil", (70, 70, 74)), ("blast_furnace", (80, 80, 84)), ("stonecutter", (130, 130, 130)), ("smithing", (60, 50, 50)), ("fletching", (190, 170, 120)), ("target", (230, 220, 200)),
    ("red_sandstone", (184, 98, 40)), ("chiseled_sandstone", (206, 192, 140)), ("cut_sandstone", (210, 196, 146)), ("smooth_sandstone", (224, 212, 168)), ("sandstone", (216, 203, 155)), ("_wall[", (120, 120, 120)),
    ("dark_oak_log", (58, 42, 26)), ("dark_oak", (74, 50, 30)), ("spruce_log", (70, 52, 32)), ("spruce", (112, 84, 52)),
    ("deepslate_tile", (62, 62, 68)), ("cracked_stone", (118, 118, 118)), ("mud_bricks", (138, 106, 82)), ("packed_mud", (142, 106, 78)),
    ("stone_brick", (128, 128, 128)), ("mud_brick", (138, 106, 82)), ("minecraft:bricks", (152, 82, 66)), ("bell", (226, 186, 64)), ("wall_banner", (176, 40, 40)), ("loom", (150, 120, 90)),
    ("grindstone", (130, 130, 130)), ("hay_block", (200, 170, 40)), ("gravel", (130, 124, 120)),
    ("glass_pane", (170, 215, 235)), ("calcite", (226, 226, 220)), ("mossy_stone_bricks", (110, 128, 100)),
    ("stone_brick", (128, 128, 128)), ("mossy_cobble", (100, 122, 92)), ("cobblestone", (116, 116, 116)),
    ("andesite", (140, 140, 138)), ("coarse_dirt", (112, 80, 56)), ("stripped_oak_log", (184, 148, 92)),
    ("oak_log", (96, 76, 48)), ("spruce_planks", (110, 82, 50)), ("oak_door", (130, 98, 56)), ("trapdoor", (140, 108, 62)),
    ("oak_fence", (150, 118, 70)), ("oak_pressure", (175, 142, 90)), ("oak_planks", (168, 134, 82)),
    ("oak_stairs", (150, 118, 72)), ("oak_slab", (158, 126, 76)), ("ladder", (150, 115, 70)),
    ("red_bed", (176, 44, 44)), ("blue_bed", (52, 70, 160)), ("light_blue_bed", (90, 160, 210)),
    ("yellow_bed", (220, 190, 60)), ("brown_bed", (112, 76, 46)), ("red_carpet", (160, 40, 40)),
    ("blue_carpet", (50, 70, 160)), ("green_carpet", (80, 110, 50)), ("white_carpet", (232, 232, 232)),
    ("chest", (170, 118, 44)), ("barrel", (122, 88, 52)), ("crafting", (142, 96, 58)), ("smoker", (82, 76, 70)),
    ("furnace", (96, 96, 96)), ("bookshelf", (120, 84, 50)), ("lantern", (238, 196, 92)), ("torch", (255, 206, 84)),
    ("campfire", (214, 100, 34)), ("cauldron", (58, 58, 66)), ("composter", (112, 86, 52)), ("potted", (120, 160, 70)),
    ("lectern", (150, 112, 62)),
]
SMALL = ("torch", "lantern", "potted", "pressure")
THIN = ("carpet",)
HALF = ("slab", "bed")


DYE = {"white": (230, 230, 230), "orange": (230, 120, 30), "magenta": (190, 70, 180), "light_blue": (90, 160, 210),
       "yellow": (230, 200, 50), "lime": (120, 190, 40), "pink": (230, 140, 170), "gray": (70, 70, 74), "light_gray": (150, 150, 145),
       "cyan": (30, 130, 140), "purple": (120, 50, 160), "blue": (52, 70, 160), "brown": (112, 76, 46), "green": (80, 110, 50),
       "red": (170, 44, 44), "black": (30, 30, 34)}


def colour(name):
    if "_bed" in name or "_carpet" in name:
        n = name.split(":")[1].split("[")[0].replace("_bed", "").replace("_carpet", "")
        if n in DYE:
            return DYE[n]
    for k, c in COL:
        if k in name:
            return c
    return (165, 160, 150)


def load(path):
    d = json.load(open(path))
    w, dd, h = d["w"], d["d"], d["h"]
    raw = gzip.decompress(base64.b64decode(d["data"]))
    v = struct.unpack("<%dH" % (w * dd * h), raw[:2 * w * dd * h])
    cells = {}
    for y in range(h):
        for z in range(dd):
            for x in range(w):
                i = v[(y * dd + z) * w + x]
                if i == 65535 or i == 0:
                    continue
                cells[(x, y, z)] = d["palette"][i]
    return w, dd, h, cells


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def render(w, dd, h, cells, upto=None, title="", cut=None):
    W = (w + dd) * S + 40
    H = (w + dd) * S // 2 + h * S + 70
    img = Image.new("RGB", (W, H), (244, 241, 234))
    dr = ImageDraw.Draw(img)
    ox, oy = dd * S + 20, h * S + 40

    def pt(x, y, z):  # the picture point of a block corner
        return (ox + (x - z) * S, oy + (x + z) * S // 2 - y * S)

    def box(x0, x1, y0, y1, z0, z1, c):
        T = [pt(x0, y1, z0), pt(x1, y1, z0), pt(x1, y1, z1), pt(x0, y1, z1)]
        L = [pt(x0, y1, z1), pt(x1, y1, z1), pt(x1, y0, z1), pt(x0, y0, z1)]   # south face
        R = [pt(x1, y1, z0), pt(x1, y1, z1), pt(x1, y0, z1), pt(x1, y0, z0)]   # east face
        edge = shade(c, 0.6)
        dr.polygon(L, fill=shade(c, 0.82), outline=edge)
        dr.polygon(R, fill=shade(c, 0.66), outline=edge)
        dr.polygon(T, fill=c, outline=edge)

    order = sorted(cells, key=lambda p: (p[1], p[0] + p[2], p[0]))
    for (x, y, z) in order:
        if upto is not None and y > upto:
            continue
        if cut is not None and y >= cut[0] and (z >= cut[2] or x >= cut[1]):
            continue  # the front and the east side taken away, to look in
        name = cells[(x, y, z)]
        c = colour(name)
        if "stairs" in name:
            top_half = "half=top" in name
            f = name.split("facing=")[1].split(",")[0]
            hx0, hx1, hz0, hz1 = x, x + 1, z, z + 1
            if f == "north": hz1 = z + 0.5
            if f == "south": hz0 = z + 0.5
            if f == "west": hx1 = x + 0.5
            if f == "east": hx0 = x + 0.5
            if top_half:
                box(hx0, hx1, y, y + 0.5, hz0, hz1, c)
                box(x, x + 1, y + 0.5, y + 1, z, z + 1, c)
            else:
                box(x, x + 1, y, y + 0.5, z, z + 1, c)
                box(hx0, hx1, y + 0.5, y + 1, hz0, hz1, c)
            continue
        if "slab" in name:
            if "type=top" in name:
                box(x, x + 1, y + 0.5, y + 1, z, z + 1, c)
            else:
                box(x, x + 1, y, y + 0.5, z, z + 1, c)
            continue
        top, inset = 1.0, 0.0
        if any(k in name for k in THIN):
            top = 0.08
        elif "bed" in name:
            top = 0.56
        if any(k in name for k in SMALL):
            inset, top = 0.32, 0.6
        elif "fence" in name or "pane" in name:
            inset = 0.3
        elif "trapdoor" in name and "open=true" in name:
            f = name.split("facing=")[1].split(",")[0]
            t = 0.18
            bx = {"north": (x, x + 1, z + 1 - t, z + 1), "south": (x, x + 1, z, z + t),
                  "east": (x, x + t, z, z + 1), "west": (x + 1 - t, x + 1, z, z + 1)}[f]
            box(bx[0], bx[1], y, y + 1, bx[2], bx[3], c)
            continue
        box(x + inset, x + 1 - inset, y, y + top, z + inset, z + 1 - inset, c)
    try:
        f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 16)
    except Exception:
        f = ImageFont.load_default()
    dr.text((12, 10), title, fill=(40, 34, 28), font=f)
    return img


def sheet(path, out, floors, title, front):
    w, dd, h, cells = load(path)
    views = [render(w, dd, h, cells, None, "снаружи")]
    for y, label, fy in floors:
        views.append(render(w, dd, h, cells, y, label, (fy, front[0], front[1])))
    gap = 10
    Wt = sum(v.width for v in views) + gap * (len(views) - 1)
    Ht = max(v.height for v in views) + 44
    img = Image.new("RGB", (Wt, Ht), (244, 241, 234))
    dr = ImageDraw.Draw(img)
    f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
    dr.text((14, 10), title, fill=(30, 26, 20), font=f)
    x = 0
    for v in views:
        img.paste(v, (x, 44))
        x += v.width + gap
    img.save(out)


if __name__ == "__main__":
    base = sys.argv[1]
    outdir = sys.argv[2]
    sheet(f"{base}/dwelling1.json", f"{outdir}/1_hut.png", [(4, "в разрезе", 2)],
          "1. Хутор - хижина 7x7: одна комната, земляной пол, кровать, сундук, печь, верстак, бочка, столик с табуретом", (7, 7))
    sheet(f"{base}/dwelling2.json", f"{outdir}/2_cottage.png", [(4, "1-й этаж", 2), (7, "чердак", 6)],
          "2. Деревня - изба 9x7: кухня, стол на двоих, сундук; чердак по лестнице: 2 кровати, перила", (9, 7))
    sheet(f"{base}/dwelling3.json", f"{outdir}/3_townhouse.png", [(4, "1-й этаж", 2), (8, "2-й этаж", 6)],
          "3. Богатая деревня - дом 11x9: кухня, стол на шестерых, кладовая; наверху две спальни, ковры, книги", (11, 9))
    sheet(f"{base}/dwelling4.json", f"{outdir}/4_manor.png", [(5, "1-й этаж", 2), (9, "2-й этаж", 7)],
          "4. Город - особняк 13x11: гостиная, кухня, крыльцо с балконом; наверху три спальни, кабинет", (13, 11))
