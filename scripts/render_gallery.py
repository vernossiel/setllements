import sys, os
exec(open(os.path.join(os.path.dirname(__file__), 'render_homes.py')).read().split('if __name__')[0])

def walls_of(cells):
    doors = [z for (x, y, z), b in cells.items() if 'door' in b and 'lower' in b and 'trap' not in b and y == 2]
    z1 = max(doors)
    cnt = {}
    for (x, y, z), b in cells.items():
        if y == 3:
            cnt[x] = cnt.get(x, 0) + 1
    x1 = max(x for x, c in cnt.items() if c >= 4)
    return x1, z1

def up_floor(cells, z1):
    ys = sorted({y for (x, y, z), b in cells.items() if 'bed' in b or 'lectern' in b or 'chest' in b})
    return [y for y in ys if y > 3]

def sheet2(path, out, title):
    w, dd, h, cells = load(path)
    x1, z1 = walls_of(cells)
    views = [render(w, dd, h, cells, None, "снаружи")]
    views.append(render(w, dd, h, cells, 4, "1-й этаж", (2, x1, z1)))
    ups = up_floor(cells, z1)
    if ups:
        y = ups[0]
        views.append(render(w, dd, h, cells, y + 1, "наверху", (y, x1, z1)))
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

base, outdir = sys.argv[1], sys.argv[2]
for name in sys.argv[3:]:
    sheet2(f"{base}/{name}.json", f"{outdir}/{name}.png", name)
