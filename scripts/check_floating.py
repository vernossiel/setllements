import json, gzip, base64, struct, sys, os
from collections import deque
base = sys.argv[1]
for f in sorted(os.listdir(base)):
    d = json.load(open(os.path.join(base, f)))
    w, dd, h = d['w'], d['d'], d['h']
    raw = gzip.decompress(base64.b64decode(d['data']))
    v = struct.unpack('<%dH' % (w * dd * h), raw[:2 * w * dd * h])
    pal = d['palette']
    solid = set()
    for y in range(h):
        for z in range(dd):
            for x in range(w):
                i = v[(y * dd + z) * w + x]
                if i != 65535 and i != 0:
                    solid.add((x, y, z))
    seen = set(p for p in solid if p[1] == 0)
    q = deque(seen)
    while q:
        x, y, z = q.popleft()
        for n in ((x+1,y,z),(x-1,y,z),(x,y+1,z),(x,y-1,z),(x,y,z+1),(x,y,z-1)):
            if n in solid and n not in seen:
                seen.add(n); q.append(n)
    loose = solid - seen
    # a cell resting on the ground layer counts as standing even without a layer-0 block (steps, piles)
    standing = set()
    for p in loose:
        if p[1] == 1: standing.add(p)
    q = deque(standing); seen2 = set(standing)
    while q:
        x, y, z = q.popleft()
        for n in ((x+1,y,z),(x-1,y,z),(x,y+1,z),(x,y-1,z),(x,y,z+1),(x,y,z-1)):
            if n in loose and n not in seen2:
                seen2.add(n); q.append(n)
    floating = loose - seen2
    if floating:
        ex = sorted(floating)[:4]
        print(f, len(floating), [(p, pal[v[(p[1]*dd+p[2])*w+p[0]]].split('[')[0].replace('minecraft:','')) for p in ex])
