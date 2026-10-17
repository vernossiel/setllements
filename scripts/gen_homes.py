# Usage: python3 scripts/gen_homes.py common/src/main/resources/assets/mca/settlements/templates
"""Settlements' own homes, one for each station of a village, written out as templates in the MineColonies format.

Layer 0 is the ground (the foundation goes into it), layer 1 the first above it (the floor), the door on the south
side (z max). Drawn in oak; written for each style with that style's timber, which the game swaps for the village's.
"""
import base64, gzip, json, os, struct, sys

OUT = sys.argv[1]
KEEP = 65535


DESERT = {  # what the desert builds with instead: sandstone in its kinds, adobe; the wood only for doors and furniture
    "minecraft:oak_planks": "minecraft:sandstone", "minecraft:spruce_planks": "minecraft:cut_sandstone",
    "minecraft:dark_oak_planks": "minecraft:cut_red_sandstone", "minecraft:oak_log": "minecraft:cut_sandstone",
    "minecraft:stripped_oak_log": "minecraft:smooth_sandstone", "minecraft:dark_oak_log": "minecraft:cut_red_sandstone",
    "minecraft:cobblestone": "minecraft:sandstone", "minecraft:mossy_cobblestone": "minecraft:sandstone",
    "minecraft:stone_bricks": "minecraft:cut_sandstone", "minecraft:mossy_stone_bricks": "minecraft:cut_sandstone",
    "minecraft:cracked_stone_bricks": "minecraft:cut_sandstone", "minecraft:polished_andesite": "minecraft:smooth_sandstone",
    "minecraft:calcite": "minecraft:smooth_sandstone", "minecraft:packed_mud": "minecraft:mud_bricks",
    "minecraft:bricks": "minecraft:smooth_red_sandstone", "minecraft:coarse_dirt": "minecraft:smooth_sandstone",
    "minecraft:oak_stairs": "minecraft:smooth_sandstone_stairs", "minecraft:spruce_stairs": "minecraft:smooth_sandstone_stairs",
    "minecraft:dark_oak_stairs": "minecraft:smooth_red_sandstone_stairs", "minecraft:deepslate_tile_stairs": "minecraft:smooth_red_sandstone_stairs",
    "minecraft:cobblestone_stairs": "minecraft:sandstone_stairs", "minecraft:stone_brick_stairs": "minecraft:sandstone_stairs",
    "minecraft:oak_slab": "minecraft:smooth_sandstone_slab", "minecraft:spruce_slab": "minecraft:smooth_sandstone_slab",
    "minecraft:dark_oak_slab": "minecraft:smooth_red_sandstone_slab", "minecraft:deepslate_tile_slab": "minecraft:smooth_red_sandstone_slab",
    "minecraft:cobblestone_wall": "minecraft:sandstone_wall", "minecraft:mossy_cobblestone_wall": "minecraft:sandstone_wall",
    "minecraft:stone_brick_wall": "minecraft:sandstone_wall", "minecraft:brick_wall": "minecraft:red_sandstone_wall",
    "minecraft:spruce_trapdoor": "minecraft:oak_trapdoor", "minecraft:dark_oak_trapdoor": "minecraft:oak_trapdoor",
}
KEEP_PROPS = ("_stairs", "_slab", "_wall", "_trapdoor", "_log")

BADLANDS = {  # the badlands: terracotta in its colours and red sandstone; dark oak, the wood of the old mines there
    "minecraft:oak_planks": "minecraft:terracotta", "minecraft:spruce_planks": "minecraft:red_terracotta",
    "minecraft:dark_oak_planks": "minecraft:brown_terracotta", "minecraft:oak_log": "minecraft:cut_red_sandstone",
    "minecraft:stripped_oak_log": "minecraft:smooth_red_sandstone", "minecraft:dark_oak_log": "minecraft:dark_oak_log",
    "minecraft:cobblestone": "minecraft:red_sandstone", "minecraft:mossy_cobblestone": "minecraft:red_sandstone",
    "minecraft:stone_bricks": "minecraft:cut_red_sandstone", "minecraft:mossy_stone_bricks": "minecraft:cut_red_sandstone",
    "minecraft:cracked_stone_bricks": "minecraft:cut_red_sandstone", "minecraft:polished_andesite": "minecraft:smooth_red_sandstone",
    "minecraft:calcite": "minecraft:white_terracotta", "minecraft:packed_mud": "minecraft:orange_terracotta",
    "minecraft:bricks": "minecraft:red_terracotta", "minecraft:coarse_dirt": "minecraft:coarse_dirt",
    "minecraft:oak_stairs": "minecraft:red_sandstone_stairs", "minecraft:spruce_stairs": "minecraft:smooth_red_sandstone_stairs",
    "minecraft:dark_oak_stairs": "minecraft:dark_oak_stairs", "minecraft:deepslate_tile_stairs": "minecraft:brick_stairs",
    "minecraft:cobblestone_stairs": "minecraft:red_sandstone_stairs", "minecraft:stone_brick_stairs": "minecraft:red_sandstone_stairs",
    "minecraft:oak_slab": "minecraft:red_sandstone_slab", "minecraft:spruce_slab": "minecraft:smooth_red_sandstone_slab",
    "minecraft:dark_oak_slab": "minecraft:dark_oak_slab", "minecraft:deepslate_tile_slab": "minecraft:brick_slab",
    "minecraft:cobblestone_wall": "minecraft:red_sandstone_wall", "minecraft:mossy_cobblestone_wall": "minecraft:red_sandstone_wall",
    "minecraft:stone_brick_wall": "minecraft:red_sandstone_wall", "minecraft:brick_wall": "minecraft:brick_wall",
    "minecraft:spruce_trapdoor": "minecraft:dark_oak_trapdoor",
}
NORTH_ROOF = {  # the north: sod and moss on stone over the roofs of the poorer houses
    "minecraft:oak_stairs": "minecraft:mossy_cobblestone_stairs", "minecraft:spruce_stairs": "minecraft:mossy_cobblestone_stairs",
    "minecraft:oak_slab": "minecraft:mossy_cobblestone_slab", "minecraft:spruce_slab": "minecraft:mossy_cobblestone_slab",
}
NORTH_WALL = ("minecraft:oak_planks", "minecraft:oak_log", "minecraft:packed_mud", "minecraft:calcite", "minecraft:mud_bricks",
              "minecraft:stripped_oak_log", "minecraft:bricks")


def translate(table, b, roof):
    bid = b.split("[")[0]
    if bid not in table or bid in WOODEN and not roof:
        return b
    nb = table[bid]
    if "[" in b and nb.endswith(KEEP_PROPS):
        return nb + "[" + b.split("[", 1)[1]
    return nb


WOODEN = ("minecraft:oak_stairs", "minecraft:spruce_stairs", "minecraft:dark_oak_stairs", "minecraft:oak_slab", "minecraft:spruce_slab", "minecraft:dark_oak_slab")


def to_desert(b, roof):
    bid = b.split("[")[0]
    if bid not in DESERT or bid in WOODEN and not roof:
        return b
    nb = DESERT[bid]
    if "[" in b and nb.endswith(KEEP_PROPS):
        return nb + "[" + b.split("[", 1)[1]
    return nb


def cap(material):
    """The top of a chimney: a wall block of its stone, narrower than the stack, as a chimney pot."""
    return {COB: "minecraft:cobblestone_wall", MOSSY: "minecraft:mossy_cobblestone_wall", SBRICK: "minecraft:stone_brick_wall",
            "minecraft:bricks": "minecraft:brick_wall"}.get(material, "minecraft:cobblestone_wall") + "[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"


class House:
    def __init__(self, w, d, h):
        self.w, self.d, self.h = w, d, h
        self.cells = {}
        self.roofs = set()  # the cells of the roof (and the sills): stone ones in the desert, the chairs left wooden

    def roof(self, x, y, z, b):
        self.put(x, y, z, b)
        self.roofs.add((x, y, z))

    def put(self, x, y, z, b):
        assert 0 <= x < self.w and 0 <= y and 0 <= z < self.d, (x, y, z, b)
        if y >= self.h:
            self.h = y + 1  # (a turret, a hoist added on top after the house was measured)
        self.cells[(x, y, z)] = b

    def box(self, x0, y0, z0, x1, y1, z1, b):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.put(x, y, z, b)

    def write(self, name, wood, credit, desert=False, land=None):
        pal = ["minecraft:air"]
        idx = {"minecraft:air": 0}
        data = []
        for y in range(self.h):
            for z in range(self.d):
                for x in range(self.w):
                    b = self.cells.get((x, y, z))
                    if b is None:
                        data.append(KEEP if y == 0 else 0)
                        continue
                    if desert:
                        b = to_desert(b, (x, y, z) in self.roofs)
                    elif land == "badlands":
                        b = translate(BADLANDS, b, (x, y, z) in self.roofs)
                    elif land == "north":
                        if (x, y, z) in self.roofs:
                            b = translate(NORTH_ROOF, b, True)
                        elif y == 2 and b.split("[")[0] in NORTH_WALL:
                            b = "minecraft:cobblestone"  # a second course of stone at the foot of the walls, against the snow
                    if wood != "oak":  # the timber of the style (only oak itself: dark oak and the accents stay as drawn)
                        b = b.replace("minecraft:oak_", f"minecraft:{wood}_").replace("minecraft:stripped_oak_", f"minecraft:stripped_{wood}_")
                        b = b.replace("minecraft:potted_oak_", "minecraft:potted_oak_")
                    if b not in idx:
                        idx[b] = len(pal)
                        pal.append(b)
                    data.append(idx[b])
        raw = struct.pack("<%dH" % len(data), *data)
        j = {"credit": credit, "w": self.w, "d": self.d, "h": self.h, "ground": 1, "palette": pal,
             "data": base64.b64encode(gzip.compress(raw)).decode()}
        return j


def st(f, half="bottom", shape="straight"):
    return f"minecraft:oak_stairs[facing={f},half={half},shape={shape},waterlogged=false]"


def cst(f, half="bottom"):
    return f"minecraft:cobblestone_stairs[facing={f},half={half},shape=straight,waterlogged=false]"


def slab(t="bottom", m="oak"):
    return f"minecraft:{m}_slab[type={t},waterlogged=false]"


def door(f, half, hinge="left"):
    return f"minecraft:oak_door[facing={f},half={half},hinge={hinge},open=false,powered=false]"


def log(axis="y"):
    return f"minecraft:oak_log[axis={axis}]"


def slog(axis="y"):
    return f"minecraft:stripped_oak_log[axis={axis}]"


def pane(axis):  # a window in a wall running along x (axis "x") or z
    ew = "true" if axis == "x" else "false"
    ns = "false" if axis == "x" else "true"
    return f"minecraft:glass_pane[east={ew},north={ns},south={ns},waterlogged=false,west={ew}]"


def fence(n=False, e=False, s=False, w=False):
    t = lambda b: "true" if b else "false"
    return f"minecraft:oak_fence[east={t(e)},north={t(n)},south={t(s)},waterlogged=false,west={t(w)}]"


def trap(f, half="bottom", open_=False):
    return f"minecraft:oak_trapdoor[facing={f},half={half},open={'true' if open_ else 'false'},powered=false,waterlogged=false]"


def bed(color, f, part):
    return f"minecraft:{color}_bed[facing={f},occupied=false,part={part}]"


def wtorch(f):
    return f"minecraft:wall_torch[facing={f}]"


def lantern(hang):
    return f"minecraft:lantern[hanging={'true' if hang else 'false'},waterlogged=false]"


PLANK = "minecraft:oak_planks"
COB = "minecraft:cobblestone"
MOSSY = "minecraft:mossy_cobblestone"
SBRICK = "minecraft:stone_bricks"
CRAFT = "minecraft:crafting_table"
BARREL_UP = "minecraft:barrel[facing=up,open=false]"
PLATE = "minecraft:oak_pressure_plate[powered=false]"


def chest(f):
    return f"minecraft:chest[facing={f},type=single,waterlogged=false]"


def furnace(f):
    return f"minecraft:furnace[facing={f},lit=false]"


def smoker(f):
    return f"minecraft:smoker[facing={f},lit=false]"


def ladder(f):
    return f"minecraft:ladder[facing={f},waterlogged=false]"


def bed2(h, x, y, z, color, facing="north"):
    """A bed with its head toward `facing`: the head at (x, y, z), the foot one block back."""
    back = {"north": (0, 1), "south": (0, -1), "east": (-1, 0), "west": (1, 0)}[facing]
    h.put(x, y, z, bed(color, facing, "head"))
    h.put(x + back[0], y, z + back[1], bed(color, facing, "foot"))


def bed_bed(h, x, y, z, color, facing="north"):
    bed2(h, x, y, z, color, facing)


def frame(h, x0, z0, x1, z1, y0, y1, wall, corner, base=None):
    """Walls round x0..x1, z0..z1 from y0 to y1: corner posts of `corner`, the rest `wall`; `base` the first course."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                c = (x in (x0, x1))
                h.put(x, y, z, corner if c else (base if base and y == y0 else wall))
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                h.put(x, y, z, base if base and y == y0 else wall)


def plate(h, x0, z0, x1, z1, y):
    """The top plates on the walls: beams along each wall, posts' tops at the corners."""
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            h.put(x, y, z, log("y") if x in (x0, x1) else log("x"))
    for z in range(z0 + 1, z1):
        for x in (x0, x1):
            h.put(x, y, z, log("z"))


def roof(h, x0, x1, gx0, gx1, depth, eave, gable=PLANK, ridge_mat="oak"):
    """A pitched roof with its ridge along x over z 0..depth-1 (odd), from y = eave at the eaves; the gable ends at
    x = gx0 and gx1 filled with `gable` under the slopes; the roof itself over x0..x1."""
    i = 0
    while True:
        zn, zs, y = i, depth - 1 - i, eave + i
        if zn > zs:
            break
        for x in range(x0, x1 + 1):
            if zn == zs:
                h.roof(x, y, zn, slab("bottom", ridge_mat))
            else:
                h.roof(x, y, zn, st("south"))
                h.roof(x, y, zs, st("north"))
        if zn < zs:
            for z in range(zn + 1, zs):
                for gx in (gx0, gx1):
                    if (gx, y, z) not in h.cells or h.cells[(gx, y, z)] == "minecraft:air":
                        h.put(gx, y, z, gable)
            # the underside of the eaves: an upside-down step under the first row, as a carpenter finishes it
        i += 1
    return eave + i - 1


def clear(h, x0, x1, z0, z1, y0, y1):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1 + 1):
                if (x, y, z) not in h.cells:
                    h.put(x, y, z, "minecraft:air")


# ---------- the hut of a hamlet: one room, cramped, plain, but a home ----------

def hut():
    W, D = 7, 7                       # walls x1..7, z1..7
    h = House(W + 2, D + 2, 11)
    x0, x1, z0, z1 = 1, W, 1, D
    # the footing: cobble under the walls, set into the ground; the floor a step up on it
    h.box(x0, 0, z0, x1, 0, z1, COB)
    frame(h, x0, z0, x1, z1, 1, 1, COB, COB)                    # a course of fieldstone
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, "minecraft:coarse_dirt")  # a beaten earth floor
    frame(h, x0, z0, x1, z1, 2, 4, PLANK, log())                # board walls between posts
    plate(h, x0, z0, x1, z1, 5)
    # the door in the middle of the front, a step of stone before it
    dx = 4
    h.put(dx, 2, z1, door("north", "lower", "left"))
    h.put(dx, 3, z1, door("north", "upper", "left"))
    h.put(dx, 1, z1 + 1, cst("north"))
    # two small windows, shuttered by day
    h.put(x0, 3, 4, pane("z"))
    h.put(x1, 3, 4, pane("z"))
    h.put(x0 - 1, 3, 4, trap("west", "bottom", True)) if False else None
    # inside, all along the walls: a straw bed, the chest at its foot, the hearth (a furnace), the bench
    bed2(h, 2, 2, 2, "brown", "north")                 # head at the north wall
    h.put(2, 2, 4, chest("east"))
    h.put(5, 2, 2, furnace("south"))
    h.put(6, 2, 2, CRAFT)
    h.put(4, 2, 2, BARREL_UP)
    h.put(6, 2, 4, fence())                            # a little table
    h.put(6, 3, 4, PLATE)
    h.put(6, 2, 5, st("south"))                        # its stool, facing it
    h.put(2, 2, 6, "minecraft:composter[level=0]")
    h.put(3, 3, 2 - 0, wtorch("south")) if False else None
    h.put(4, 4, 2, wtorch("south"))
    h.put(6, 4, 6, wtorch("north"))
    # the roof: straw-coloured boards, low eaves, the gables boarded
    clear(h, 0, W + 1, 0, D + 1, 1, 10)
    roof(h, 0, W + 1, x0, x1, D + 2, 5)
    return h


# ---------- the cottage of a village: a room below, a sleeping loft above ----------

def cottage():
    W, D = 9, 7                       # walls x1..9, z1..7
    h = House(W + 2, D + 2, 12)
    x0, x1, z0, z1 = 1, W, 1, D
    h.box(x0, 0, z0, x1, 0, z1, COB)
    frame(h, x0, z0, x1, z1, 1, 1, COB, COB)
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, PLANK)               # a board floor
    frame(h, x0, z0, x1, z1, 2, 5, PLANK, log(), base=COB)           # stone at the foot of the walls
    plate(h, x0, z0, x1, z1, 6)
    # the door, a step, a lamp beside it
    dx = 5
    h.put(dx, 2, z1, door("north", "lower", "left"))
    h.put(dx, 3, z1, door("north", "upper", "left"))
    h.put(dx, 1, z1 + 1, cst("north"))
    h.put(dx + 1, 4, z1 + 1, wtorch("south"))
    # windows: two at the front, one each side, one at the back
    for x in (3, 7):
        h.put(x, 3, z1, pane("x"))
    h.put(5, 3, z0, pane("x"))
    h.put(x0, 3, 3, pane("z"))
    h.put(x1, 3, 3, pane("z"))
    # the room: the hearth wall at the back (furnace, workbench, barrel), the table in the middle, the chest
    h.put(6, 2, 2, CRAFT)
    h.put(7, 2, 2, furnace("south"))
    h.put(8, 2, 2, BARREL_UP)
    h.put(8, 2, 4, chest("west"))
    h.put(8, 3, 4, "minecraft:potted_red_tulip")
    h.put(4, 2, 4, fence())
    h.put(4, 3, 4, "minecraft:white_carpet")
    h.put(4, 2, 3, st("north"))                        # a chair either side of it
    h.put(4, 2, 5, st("south"))
    h.put(8, 2, 6, "minecraft:composter[level=0]")
    h.put(7, 4, 6, wtorch("north"))
    h.put(3, 4, 2, wtorch("south"))
    # the loft over the back half, on the beams, up a ladder by the side wall
    h.box(2, 5, 2, 8, 5, 3, PLANK)
    for y in range(2, 6):
        h.put(2, y, 4, ladder("east"))
    h.box(3, 5, 4, 8, 5, 4, slab("bottom")) if False else None
    bed2(h, 5, 6, 2, "red", "north")
    bed2(h, 7, 6, 2, "red", "north")
    h.put(8, 6, 3, chest("west")) if False else None
    h.put(3, 6, 2, chest("south"))
    h.put(6, 7, 2, wtorch("south"))
    # (the loft's open side is guarded by a rail)
    for x in range(4, 9):
        h.put(x, 6, 4, fence(e=x < 8, w=x > 4))
    clear(h, 0, W + 1, 0, D + 1, 1, 11)
    roof(h, 0, W + 1, x0, x1, D + 2, 6)
    return h


# ---------- the house of a wealthy village: two floors, stone below, timber above, rooms of their own ----------

def townhouse():
    W, D = 11, 9                      # walls x1..11, z1..9
    h = House(W + 3, D + 2, 17)       # (a column more on the east for the chimney)
    x0, x1, z0, z1 = 1, W, 1, D
    h.box(x0, 0, z0, x1, 0, z1, COB)
    frame(h, x0, z0, x1, z1, 1, 1, COB, COB)
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, PLANK)
    frame(h, x0, z0, x1, z1, 2, 4, COB, log(), base=MOSSY)          # the ground floor of stone
    h.box(x0 + 1, 5, z0 + 1, x1 - 1, 5, z1 - 1, PLANK)              # the floor above, on beams
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            h.put(x, 5, z, log("x") if x not in (x0, x1) else log())
    for z in range(z0 + 1, z1):
        for x in (x0, x1):
            h.put(x, 5, z, log("z"))
    frame(h, x0, z0, x1, z1, 6, 8, PLANK, log())                    # the upper floor timber-framed
    for x in (4, 8):                                                 # its studs
        for y in range(6, 9):
            for z in (z0, z1):
                h.put(x, y, z, slog())
    plate(h, x0, z0, x1, z1, 9)
    # the door with a porch roof over it, steps, lamps
    dx = 6
    h.put(dx, 2, z1, door("north", "lower", "left"))
    h.put(dx, 3, z1, door("north", "upper", "left"))
    h.put(dx, 1, z1 + 1, cst("north"))
    h.put(dx - 1, 4, z1 + 1, wtorch("south"))
    h.put(dx + 1, 4, z1 + 1, wtorch("south"))
    # windows, below and above
    for x in (3, 9):
        h.put(x, 3, z1, pane("x"))
        h.put(x, 3, z0, pane("x"))
    for x in (2, 3, 6, 9, 10):
        h.put(x, 7, z1, pane("x"))
    for x in (3, 9):
        h.put(x, 7, z0, pane("x"))
    for z in (4, 6):
        h.put(x0, 3, z, pane("z"))
        h.put(x0, 7, z, pane("z"))
    h.put(x1, 7, 5, pane("z"))
    # the chimney on the east wall, the kitchen's range against it inside
    for y in range(0, 9):
        h.put(x1 + 1, y, 5, COB)
    h.put(x1 - 1, 2, 4, smoker("west"))
    h.put(x1 - 1, 2, 5, furnace("west"))
    h.put(x1 - 1, 2, 6, CRAFT)
    h.put(x1 - 1, 2, 3, "minecraft:water_cauldron[level=3]")
    h.put(x1 - 1, 2, 7, BARREL_UP)
    h.put(x1 - 1, 2, 8, BARREL_UP)
    # the table for six, its benches
    for x in (5, 6, 7):
        h.put(x, 2, 5, fence(e=x < 7, w=x > 5))
        h.put(x, 3, 5, "minecraft:white_carpet")
        h.put(x, 2, 4, st("north"))
        h.put(x, 2, 6, st("south"))
    # the stores and the ladder by the west wall
    h.put(2, 2, 2, chest("south"))
    h.put(3, 2, 2, chest("south"))
    h.put(4, 2, 2, BARREL_UP)
    h.put(2, 2, 6, "minecraft:bookshelf") if False else None
    for y in range(2, 6):
        h.put(2, y, 8, ladder("east"))
    h.put(6, 4, 3, lantern(True))
    h.put(6, 4, 7, lantern(True))
    h.put(3, 4, 5, wtorch("east")) if False else None
    # upstairs: two rooms either side of a board wall with a door in it
    for z in range(z0 + 1, z1):
        for y in range(6, 9):
            h.put(6, y, z, PLANK)
    h.put(6, 6, 6, door("east", "lower", "left"))
    h.put(6, 7, 6, door("east", "upper", "left"))
    # the children's room (west): two beds, a chest, a rug
    bed2(h, 3, 6, 2, "light_blue", "north")
    bed2(h, 5, 6, 2, "light_blue", "north")
    h.put(4, 6, 2, chest("south"))
    h.put(2, 6, 5, BARREL_UP)
    for x in range(3, 6):
        for z in range(5, 7):
            h.put(x, 6, z, "minecraft:blue_carpet")
    h.put(4, 8, 2, wtorch("south"))
    # the parents' room (east): a wide bed, chests at its sides, a shelf of books, a pot of flowers
    bed2(h, 8, 6, 2, "red", "north")
    bed2(h, 9, 6, 2, "red", "north")
    h.put(7, 6, 2, chest("south"))
    h.put(10, 6, 2, chest("south"))
    h.put(10, 6, 8, "minecraft:bookshelf")
    h.put(10, 7, 8, "minecraft:bookshelf")
    h.put(10, 6, 7, "minecraft:bookshelf")
    h.put(10, 7, 7, "minecraft:potted_blue_orchid")
    for x in range(7, 10):
        for z in range(5, 8):
            h.put(x, 6, z, "minecraft:red_carpet")
    h.put(8, 8, 8, wtorch("north"))
    clear(h, 0, W + 2, 0, D + 1, 1, 16)
    top = roof(h, 0, W + 1, x0, x1, D + 2, 9)
    for y in range(9, top + 2):                      # the chimney up through the eaves, above the ridge
        h.put(x1 + 1, y, 5, COB)
    h.put(x1 + 1, top + 2, 5, cap(COB))
    return h


# ---------- the house of a town: stone below, a hall and a parlour, three bedrooms, a balcony ----------

def manor():
    W, D = 13, 11                     # walls x1..13, z1..11
    h = House(W + 3, D + 3, 20)       # a column more east (chimney), a row more south (the porch)
    x0, x1, z0, z1 = 1, W, 1, D
    h.box(x0, 0, z0, x1, 0, z1, SBRICK)
    frame(h, x0, z0, x1, z1, 1, 1, SBRICK, SBRICK)
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, "minecraft:spruce_planks")   # a dark oak floor, laid in another wood
    frame(h, x0, z0, x1, z1, 2, 5, SBRICK, "minecraft:polished_andesite", base="minecraft:mossy_stone_bricks")
    h.box(x0 + 1, 6, z0 + 1, x1 - 1, 6, z1 - 1, PLANK)
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            h.put(x, 6, z, log("x") if x not in (x0, x1) else log())
    for z in range(z0 + 1, z1):
        for x in (x0, x1):
            h.put(x, 6, z, log("z"))
    frame(h, x0, z0, x1, z1, 7, 9, "minecraft:calcite", log())       # white plaster between dark timbers
    for x in (4, 7, 10):
        for y in range(7, 10):
            for z in (z0, z1):
                h.put(x, y, z, slog())
    for z in (4, 8):
        for y in range(7, 10):
            for x in (x0, x1):
                h.put(x, y, z, slog())
    plate(h, x0, z0, x1, z1, 10)
    # the double door under a porch with a balcony over it
    for dx, hinge in ((6, "left"), (7, "right")):
        h.put(dx, 2, z1, door("north", "lower", hinge))
        h.put(dx, 3, z1, door("north", "upper", hinge))
        h.put(dx, 1, z1 + 1, SBRICK)
        h.put(dx, 1, z1 + 2, "minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
    for x in (5, 8):                                                 # the porch posts
        for y in range(2, 6):
            h.put(x, y, z1 + 1, log())
        h.put(x, 1, z1 + 1, SBRICK)
    for x in range(5, 9):                                            # the balcony floor and its rail
        h.put(x, 6, z1 + 1, PLANK)
        h.put(x, 7, z1 + 1, fence(e=x < 8, w=x > 5))
    h.put(6, 7, z1, door("south", "lower", "left"))                  # out onto the balcony from upstairs
    h.put(6, 8, z1, door("south", "upper", "left"))
    h.put(6, 5, z1 + 1, lantern(True))
    h.put(7, 5, z1 + 1, lantern(True))
    # windows, tall below, paired above
    for x in (3, 10):
        for y in (3, 4):
            h.put(x, y, z1, pane("x"))
            h.put(x, y, z0, pane("x"))
    for x in (2, 3, 8, 9, 11, 12):
        h.put(x, 8, z1, pane("x"))
        h.put(x, 8, z0, pane("x"))
    for z in (3, 6, 9):
        for y in (3, 4):
            h.put(x0, y, z, pane("z"))
        h.put(x0, 8, z, pane("z"))
        h.put(x1, 8, z, pane("z"))
    # the chimney, the kitchen against it
    for y in range(0, 10):
        h.put(x1 + 1, y, 6, SBRICK)
    h.put(x1 - 1, 2, 5, smoker("west"))
    h.put(x1 - 1, 2, 6, furnace("west"))
    h.put(x1 - 1, 2, 7, "minecraft:water_cauldron[level=3]")
    h.put(x1 - 1, 2, 4, CRAFT)
    h.put(x1 - 1, 2, 8, BARREL_UP)
    h.put(x1 - 1, 2, 9, BARREL_UP)
    h.put(x1 - 1, 2, 10, chest("west"))
    h.put(x1 - 1, 4, 6, lantern(False)) if False else None
    # the board wall between the kitchen and the parlour, an opening in it
    for z in range(z0 + 1, z1):
        for y in range(2, 6):
            if z not in (6, 7) or y > 3:
                h.put(9, y, z, PLANK)
    # the parlour: a long table, benches, shelves of books, a rug, the lantern over it
    for x in (4, 5, 6):
        h.put(x, 2, 6, fence(e=x < 6, w=x > 4))
        h.put(x, 3, 6, "minecraft:white_carpet")
        h.put(x, 2, 5, st("north"))
        h.put(x, 2, 7, st("south"))
    for z in (3, 4, 5):
        h.put(2, 2, z, "minecraft:bookshelf")
        h.put(2, 3, z, "minecraft:bookshelf")
    h.put(2, 4, 4, "minecraft:potted_dandelion") if False else None
    for x in range(3, 8):
        for z in range(8, 10):
            h.put(x, 2, z, "minecraft:green_carpet")
    h.put(5, 5, 6, lantern(True))
    h.put(2, 2, 2, chest("south"))
    h.put(3, 2, 2, chest("south"))
    h.put(7, 2, 2, "minecraft:potted_azalea_bush")
    # the ladder up in the corner by the parlour
    for y in range(2, 7):
        h.put(2, y, 10, ladder("east"))
    # upstairs: a passage along the back, three bedrooms off it
    for x in range(x0 + 1, x1):
        for y in range(7, 10):
            if x in (5, 9):
                for z in range(z0 + 1, z1):
                    h.put(x, y, z, PLANK)
    for x in (5, 9):
        h.put(x, 7, 9, door("east", "lower", "left"))
        h.put(x, 8, 9, door("east", "upper", "left"))
    # west room: two children
    bed2(h, 2, 7, 2, "yellow", "north")
    bed2(h, 4, 7, 2, "yellow", "north")
    h.put(3, 7, 2, chest("south"))
    h.put(3, 9, 2, wtorch("south"))
    # the middle room: the parents, the wide bed, the doors to the balcony
    bed2(h, 6, 7, 2, "red", "north")
    bed2(h, 7, 7, 2, "red", "north")
    h.put(8, 7, 2, chest("south"))
    for x in range(6, 9):
        for z in range(5, 8):
            h.put(x, 7, z, "minecraft:red_carpet")
    h.put(8, 7, 7, "minecraft:potted_red_tulip") if False else None
    h.put(7, 9, 2, wtorch("south"))
    # east room: the eldest, a desk, books
    bed2(h, 11, 7, 2, "blue", "north")
    h.put(12, 7, 2, chest("south"))
    h.put(12, 7, 6, "minecraft:bookshelf")
    h.put(12, 8, 6, "minecraft:bookshelf")
    h.put(12, 7, 5, "minecraft:lectern[facing=west,has_book=false,powered=false]")
    h.put(11, 7, 5, st("west"))                      # the chair at the desk, facing it
    h.put(11, 9, 2, wtorch("south"))
    clear(h, 0, W + 2, 0, D + 2, 1, 19)
    top = roof(h, 0, W + 1, x0, x1, D + 2, 10)
    for y in range(10, top + 2):
        h.put(x1 + 1, y, 6, SBRICK)
    h.put(x1 + 1, top + 2, 6, cap(SBRICK))
    return h


# =====================================================================================================================
# Many houses from one carpenter: a house described (its size, floors, the way its ridge runs, what its walls, footing
# and roof are made of, a chimney, a porch, shutters) and its rooms furnished by a planner, along the walls, facing
# into the room, the table in the middle, the way from the door kept clear.
# =====================================================================================================================
import random

DIR = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
OPP = {"north": "south", "south": "north", "east": "west", "west": "east"}


def wall_block(mat, along, x, y, z, rng):
    """A block of a wall of `mat`; `along` the axis the wall runs along ('x' or 'z')."""
    if mat == "planks":
        return PLANK
    if mat == "logs":
        return log(along)
    if mat == "daub":
        return "minecraft:calcite"
    if mat == "mud":
        return "minecraft:packed_mud"
    if mat == "mudbrick":
        return "minecraft:mud_bricks"
    if mat == "cobble":
        return MOSSY if rng.random() < 0.18 else COB
    if mat == "stonebrick":
        r = rng.random()
        return "minecraft:mossy_stone_bricks" if r < 0.12 else "minecraft:cracked_stone_bricks" if r < 0.2 else SBRICK
    if mat == "bricks":
        return "minecraft:bricks"
    if mat == "spruce":
        return "minecraft:spruce_planks"
    return PLANK


def rstairs(mat, f, half="bottom"):
    if mat in ("oak", "spruce", "dark_oak", "birch"):
        return f"minecraft:{mat}_stairs[facing={f},half={half},shape=straight,waterlogged=false]"
    return f"minecraft:{mat}_stairs[facing={f},half={half},shape=straight,waterlogged=false]"


def rslab(mat):
    return f"minecraft:{mat}_slab[type=bottom,waterlogged=false]"


def roof_x(h, x0, x1, gx0, gx1, depth, eave, mat, gable):
    """Ridge along x over z 0..depth-1 (odd): slopes to north and south, gables at gx0, gx1."""
    i = 0
    while True:
        zn, zs, y = i, depth - 1 - i, eave + i
        if zn > zs:
            break
        for x in range(x0, x1 + 1):
            if zn == zs:
                h.roof(x, y, zn, rslab(mat))
            else:
                h.roof(x, y, zn, rstairs(mat, "south"))
                h.roof(x, y, zs, rstairs(mat, "north"))
        for z in range(zn + 1, zs):
            for gx in (gx0, gx1):
                if h.cells.get((gx, y, z), "minecraft:air") == "minecraft:air":
                    h.put(gx, y, z, gable)
        i += 1
    return eave + i - 1


def roof_z(h, z0, z1, gz0, gz1, width, eave, mat, gable):
    """Ridge along z over x 0..width-1 (odd): slopes to east and west, gables at gz0 (back), gz1 (front)."""
    i = 0
    while True:
        xw, xe, y = i, width - 1 - i, eave + i
        if xw > xe:
            break
        for z in range(z0, z1 + 1):
            if xw == xe:
                h.roof(xw, y, z, rslab(mat))
            else:
                h.roof(xw, y, z, rstairs(mat, "east"))
                h.roof(xe, y, z, rstairs(mat, "west"))
        for x in range(xw + 1, xe):
            for gz in (gz0, gz1):
                if h.cells.get((x, y, gz), "minecraft:air") == "minecraft:air":
                    h.put(x, y, gz, gable)
        i += 1
    return eave + i - 1


class Room:
    """A room to furnish: its floor cells x0..x1, z0..z1 at standing height y."""

    def __init__(self, h, x0, z0, x1, z1, y, rng):
        self.h, self.x0, self.z0, self.x1, self.z1, self.y, self.rng = h, x0, z0, x1, z1, y, rng
        self.used = set()
        self.tops = []  # cells with a chest or barrel on which a pot may stand

    def keep(self, x, z):
        self.used.add((x, z))

    def free(self, x, z):
        return self.x0 <= x <= self.x1 and self.z0 <= z <= self.z1 and (x, z) not in self.used and \
            self.h.cells.get((x, self.y, z), "minecraft:air") == "minecraft:air"

    def ring(self):
        """The cells along the walls, round the room in order, starting somewhere of the room's own choosing."""
        cells = []
        for x in range(self.x0, self.x1 + 1):
            cells.append((x, self.z0, "north"))
        for z in range(self.z0 + 1, self.z1 + 1):
            cells.append((self.x1, z, "east"))
        for x in range(self.x1 - 1, self.x0 - 1, -1):
            cells.append((x, self.z1, "south"))
        for z in range(self.z1 - 1, self.z0, -1):
            cells.append((self.x0, z, "west"))
        k = self.rng.randrange(len(cells))
        return cells[k:] + cells[:k]

    def wall_is_wall(self, x, z, wall):
        dx, dz = DIR[wall]
        b = self.h.cells.get((x + dx, self.y, z + dz), "minecraft:air")
        return b != "minecraft:air" and "door" not in b and "pane" not in b

    def place(self, item):
        y, h = self.y, self.h
        for (x, z, wall) in self.ring():
            if not self.free(x, z) or not self.wall_is_wall(x, z, wall):
                continue
            inward = OPP[wall]
            ix, iz = x + DIR[inward][0], z + DIR[inward][1]
            if item.startswith("bed:"):
                color = item.split(":")[1]
                if not self.free(ix, iz):
                    continue
                h.put(x, y, z, bed(color, wall, "head"))
                h.put(ix, y, iz, bed(color, wall, "foot"))
                self.keep(x, z)
                self.keep(ix, iz)
                return True
            block = {
                "chest": chest(inward), "barrel": BARREL_UP, "craft": CRAFT, "furnace": furnace(inward),
                "smoker": smoker(inward), "cauldron": "minecraft:water_cauldron[level=3]",
                "composter": "minecraft:composter[level=0]", "books": "minecraft:bookshelf",
                "lectern": f"minecraft:lectern[facing={inward},has_book=false,powered=false]",
                "loom": f"minecraft:loom[facing={inward}]", "hay": "minecraft:hay_block[axis=y]",
                "grind": f"minecraft:grindstone[face=floor,facing={inward}]",
                "anvil": f"minecraft:anvil[facing={'east' if inward in ('north', 'south') else 'north'}]",
                "smithing": "minecraft:smithing_table", "fletching": "minecraft:fletching_table",
                "stonecutter": f"minecraft:stonecutter[facing={inward}]", "blast": f"minecraft:blast_furnace[facing={inward},lit=false]",
                "logs": "minecraft:oak_wood[axis=x]", "cobble": "minecraft:cobblestone", "target": "minecraft:target[power=0]",
                "crate": "minecraft:barrel[facing=east,open=false]", "brew": "minecraft:brewing_stand[has_bottle_0=false,has_bottle_1=false,has_bottle_2=false]",
                "bench": st(inward),
            }[item]
            h.put(x, y, z, block)
            self.keep(x, z)
            if item in ("chest", "barrel", "books"):
                self.tops.append((x, z))
            return True
        return False

    def pot(self, flower):
        if not self.tops:
            return
        x, z = self.tops.pop(self.rng.randrange(len(self.tops)))
        if self.h.cells.get((x, self.y + 1, z), "minecraft:air") == "minecraft:air":
            self.h.put(x, self.y + 1, z, f"minecraft:potted_{flower}")

    def table(self, length=1, axis="x", carpet="white", off=0):
        """A table in the middle of the room (or `off` along x from it), chairs along both sides of it."""
        cx, cz = (self.x0 + self.x1) // 2 + off, (self.z0 + self.z1) // 2
        cells = [(cx + i, cz) if axis == "x" else (cx, cz + i) for i in range(-(length // 2), length - length // 2)]
        side = [("north", "south")] if axis == "x" else [("west", "east")]
        need = cells + [(x + DIR[d][0], z + DIR[d][1]) for (x, z) in cells for d in side[0]]
        if not all(self.free(x, z) for (x, z) in need):
            return False
        for k, (x, z) in enumerate(cells):
            if axis == "x":
                self.h.put(x, self.y, z, fence(e=k < len(cells) - 1, w=k > 0))
            else:
                self.h.put(x, self.y, z, fence(s=k < len(cells) - 1, n=k > 0))
            self.h.put(x, self.y + 1, z, f"minecraft:{carpet}_carpet")
            self.keep(x, z)
            for d in side[0]:
                sx, sz = x + DIR[d][0], z + DIR[d][1]
                self.h.put(sx, self.y, sz, st(d))  # the chair's back away from the table: he sits facing it
                self.keep(sx, sz)
        return True

    def rug(self, color, x0, z0, x1, z1):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if self.free(x, z):
                    self.h.put(x, self.y, z, f"minecraft:{color}_carpet")

    def torches(self, n=2, lanterns=False):
        cells = self.ring()
        step = max(1, len(cells) // n)
        put = 0
        for i in range(0, len(cells), step):
            x, z, wall = cells[i]
            if put >= n:
                break
            if not self.wall_is_wall(x, z, wall) or self.h.cells.get((x, self.y + 2, z), "minecraft:air") != "minecraft:air":
                continue
            self.h.put(x, self.y + 2, z, wtorch(OPP[wall]))
            put += 1
        if lanterns:
            cx, cz = (self.x0 + self.x1) // 2, (self.z0 + self.z1) // 2
            self.h.put(cx, self.y + 2, cz, lantern(True)) if self.h.cells.get((cx, self.y + 2, cz), "minecraft:air") == "minecraft:air" else None


def home(spec, seed):
    """A house from its description. The door on the south side, in the middle or a little off it."""
    rng = random.Random(seed)
    W, D = spec["W"], spec["D"]
    floors, loft = spec.get("floors", 1), spec.get("loft", False)
    ridge = spec.get("ridge", "x")
    chim = spec.get("chimney")  # "east", "west" or None
    porch = spec.get("porch", False)
    extra_e = 1 if chim == "east" else 0
    h = House(W + 2 + extra_e, D + 2 + (1 if porch else 0), 22)
    x0, x1, z0, z1 = 1, W, 1, D
    foot = spec.get("footing", "cobble")
    fb = {"cobble": COB, "mossy": MOSSY, "stonebrick": SBRICK, "none": COB, "bricks": "minecraft:bricks"}[foot]
    h.box(x0, 0, z0, x1, 0, z1, fb)
    frame(h, x0, z0, x1, z1, 1, 1, fb, fb)
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, spec.get("floor", PLANK))
    # the storeys: (floor y, wall height, wall material)
    mats = spec.get("walls", ["planks"])
    storeys = []
    y = 2
    for f in range(floors):
        hgt = 4 if (loft and floors == 1) else 3
        storeys.append((y, hgt, mats[min(f, len(mats) - 1)]))
        y += hgt + 1
    post = spec.get("post", "log")
    for (sy, hgt, mat) in storeys:
        for yy in range(sy, sy + hgt):
            for x in range(x0, x1 + 1):
                for z in (z0, z1):
                    h.put(x, yy, z, wall_block(mat, "x", x, yy, z, rng))
            for z in range(z0 + 1, z1):
                for x in (x0, x1):
                    h.put(x, yy, z, wall_block(mat, "z", x, yy, z, rng))
            if post:  # the frame: corner posts, studs every few blocks
                pb = log() if post == "log" else slog() if post == "stripped" else post
                for (cx, cz) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
                    h.put(cx, yy, cz, pb)
                stud = spec.get("studs", 0)
                if stud:
                    for x in range(x0 + stud, x1, stud):
                        h.put(x, yy, z0, pb)
                        h.put(x, yy, z1, pb)
                    for z in range(z0 + stud, z1, stud):
                        h.put(x0, yy, z, pb)
                        h.put(x1, yy, z, pb)
        top = sy + hgt
        if (sy, hgt, mat) != storeys[-1]:  # a floor above: the beams round it and the boards
            h.box(x0 + 1, top, z0 + 1, x1 - 1, top, z1 - 1, PLANK)
        plate(h, x0, z0, x1, z1, top)
    eave = storeys[-1][0] + storeys[-1][1]
    # the door, off the middle now and then; a step before it
    dx = (x0 + x1) // 2 + spec.get("door_off", 0)
    sy0 = storeys[0][0]
    h.put(dx, sy0, z1, door("north", "lower", "left"))
    h.put(dx, sy0 + 1, z1, door("north", "upper", "left"))
    if spec.get("double"):
        h.put(dx + 1, sy0, z1, door("north", "lower", "right"))
        h.put(dx + 1, sy0 + 1, z1, door("north", "upper", "right"))
    h.put(dx, 1, z1 + 1, cst("north"))
    if spec.get("double"):
        h.put(dx + 1, 1, z1 + 1, cst("north"))
    # windows in every storey, shutters and sills as the house can afford
    shutters, sills = spec.get("shutters", False), spec.get("sills", False)
    tr = spec.get("trim", "spruce")
    for (sy, hgt, mat) in storeys:
        wy = sy + 1
        sp = spec.get("win_every", 3)
        for x in range(x0 + 2, x1 - 1, sp):
            for z, out, f in ((z1, z1 + 1, "south"), (z0, z0 - 1, "north")):
                if z == z1 and sy == sy0 and abs(x - dx) <= (2 if spec.get("double") else 1):
                    continue
                if "log" in h.cells.get((x, wy, z), "") and post and spec.get("studs") and (x - x0) % spec["studs"] == 0:
                    continue
                h.put(x, wy, z, pane("x"))
                if shutters and 0 <= out < h.d:
                    for sx in (x - 1, x + 1):
                        if h.cells.get((sx, wy, out), "minecraft:air") == "minecraft:air":
                            h.put(sx, wy, out, f"minecraft:{tr}_trapdoor[facing={f},half=bottom,open=true,powered=false,waterlogged=false]")
                if sills and 0 <= out < h.d and h.cells.get((x, wy - 1, out), "minecraft:air") == "minecraft:air":
                    h.roof(x, wy - 1, out, f"minecraft:{tr}_stairs[facing={OPP[f]},half=top,shape=straight,waterlogged=false]")
        for z in range(z0 + 2, z1 - 1, sp):
            for x, out, f in ((x0, x0 - 1, "west"), (x1, x1 + 1, "east")):
                if chim and ((chim == "east" and x == x1) or (chim == "west" and x == x0)) and abs(z - (z0 + z1) // 2) <= 1:
                    continue
                h.put(x, wy, z, pane("z"))
                if shutters and 0 <= out < h.w:
                    for sz in (z - 1, z + 1):
                        if h.cells.get((out, wy, sz), "minecraft:air") == "minecraft:air":
                            h.put(out, wy, sz, f"minecraft:{tr}_trapdoor[facing={f},half=bottom,open=true,powered=false,waterlogged=false]")
    # a porch: posts either side of the door under the eaves, a rail, a bench
    if porch:
        for px in (dx - 2, dx + 2 + (1 if spec.get("double") else 0)):
            for yy in range(sy0, sy0 + 3):
                h.put(px, yy, z1 + 1, log())
            h.put(px, 1, z1 + 1, fb)
        for px in range(dx - 1, dx + 2 + (1 if spec.get("double") else 0)):
            h.put(px, 1, z1 + 1, PLANK) if px != dx and not (spec.get("double") and px == dx + 1) else None
            h.put(px, sy0 + 2, z1 + 1, slab("top")) if False else None
        h.put(dx, 1, z1 + 2, cst("north"))
        if spec.get("double"):
            h.put(dx + 1, 1, z1 + 2, cst("north"))
        h.put(dx - 1, sy0, z1 + 1, st("east")) if False else None
        h.put(dx - 1, sy0 + 2, z1 + 1, lantern(True))
    # the clearing round it, then the roof
    clear(h, 0, h.w - 1, 0, h.d - 1, 1, 21)
    rmat, gable = spec.get("roof", "oak"), spec.get("gable", PLANK)
    if ridge == "x":
        apex = roof_x(h, 0, W + 1, x0, x1, D + 2, eave, rmat, gable)
    else:
        apex = roof_z(h, 0, D + 1, z0, z1, W + 2, eave, rmat, gable)
    if porch and ridge == "z":  # (the eaves run along the sides: the porch gets a little roof of its own)
        for px in range(dx - 2, dx + 3 + (1 if spec.get("double") else 0)):
            h.roof(px, sy0 + 3, z1 + 1, rslab(rmat))
    # a chimney up the side wall, above the roof, smoking
    if chim:
        cx = x1 + 1 if chim == "east" else x0 - 1
        cz = (z0 + z1) // 2
        cm = spec.get("chimney_mat", COB)
        for yy in range(0, apex + 2):
            h.put(cx, yy, cz, cm)
        h.put(cx, apex + 2, cz, cap(cm))  # the chimney pot on top (a fire up there showed: a pipe with a campfire on it)
    # the rooms
    for k, (sy, hgt, mat) in enumerate(storeys):
        plan = spec["rooms"][k] if k < len(spec["rooms"]) else []
        furnish(h, spec, k, sy, hgt, plan, rng, dx, storeys)
    trim_top(h)
    return h


def trim_top(h):
    """The house's height cut down to what it uses; and no lantern hung from thin air (a room open to the roof)."""
    for (x, y, z), b in list(h.cells.items()):
        if "lantern[hanging=true" in b and h.cells.get((x, y + 1, z), "minecraft:air") == "minecraft:air":
            h.cells[(x, y, z)] = "minecraft:air"
    ys = [y for (x, y, z), b in h.cells.items() if b != "minecraft:air"]
    top = max(ys) + 1
    h.cells = {k: v for k, v in h.cells.items() if k[1] <= top}
    h.h = top + 1


def furnish(h, spec, k, sy, hgt, plan, rng, dx, storeys):
    """One storey's rooms. `plan` is a list of rooms: (x0, x1 in the house, items...). A partition between them with
    a door; on the ground floor the way in from the door kept clear; a ladder to the floor above where there is one."""
    W, D = spec["W"], spec["D"]
    x0, x1, z0, z1 = 2, W - 1, 2, D - 1
    upper = k + 1 < len(storeys)
    ladder_at = spec.get("ladder", (x0, z1 - 1 if k == 0 else z1 - 1))
    rooms = plan if plan else [{"x": (x0, x1), "items": []}]
    # the partitions
    for r in rooms[1:]:
        px = r["x"][0] - 1
        for yy in range(sy, sy + hgt):
            for z in range(z0, z1 + 1):
                h.put(px, yy, z, PLANK)
        pz = r.get("door_z", (z0 + z1) // 2 + 1)
        h.put(px, sy, pz, door("east", "lower", "left"))
        h.put(px, sy + 1, pz, door("east", "upper", "left"))
    for i, r in enumerate(rooms):
        rx0, rx1 = r["x"]
        room = Room(h, rx0, z0, rx1, z1, sy, rng)
        if k == 0 and rx0 <= dx <= rx1:  # the way in
            room.keep(dx, z1)
            room.keep(dx, z1 - 1)
            if spec.get("double"):
                room.keep(dx + 1, z1)
        for rr in rooms[1:]:  # the doors in the partitions
            px = rr["x"][0] - 1
            pz = rr.get("door_z", (z0 + z1) // 2 + 1)
            room.keep(px - 1, pz)
            room.keep(px + 1, pz)
        lx, lz = ladder_at
        if rx0 <= lx <= rx1 and (upper or k > 0):
            room.keep(lx, lz)
            room.keep(lx + 1, lz)
        for item in r["items"]:
            if item.startswith("table@"):  # a table off the middle: table@dx[:len]
                parts = item[6:].split(":")
                room.table(int(parts[1]) if len(parts) > 1 else 1, "x", off=int(parts[0]))
            elif item == "table":
                room.table(1, "x")
            elif item.startswith("table"):
                n = int(item[5:])
                room.table(n, "x") or room.table(n, "z")
            elif item.startswith("rug:"):
                c = item.split(":")[1]
                room.rug(c, rx0 + 1, z0 + 2, rx1 - 1, z1 - 1)
            elif item.startswith("pot:"):
                room.pot(item.split(":")[1])
            elif item == "torches":
                room.torches(2)
            elif item == "lanterns":
                room.torches(2, True)
            else:
                room.place(item)
    # the ladder up through the floor above
    if upper:
        lx, lz = ladder_at
        top = sy + hgt
        for yy in range(sy, top + 1):
            h.put(lx, yy, lz, ladder("east"))


# ---------- the designs: four homes for each station, three halls ----------

HOMES = {
    # a hamlet: one room, cramped
    "dwelling1b": dict(W=7, D=7, ridge="z", walls=["logs"], post="log", footing="cobble", floor="minecraft:coarse_dirt",
                       roof="spruce", gable="minecraft:spruce_planks", chimney="west",
                       rooms=[[{"x": (2, 6), "items": ["bed:brown", "chest", "furnace", "craft", "barrel", "table", "torches"]}]]),
    "dwelling1c": dict(W=9, D=5, ridge="x", walls=["mud"], post="log", studs=4, footing="none", floor="minecraft:coarse_dirt",
                       roof="oak", gable=PLANK, door_off=-2,
                       rooms=[[{"x": (2, 8), "items": ["bed:green", "chest", "furnace", "barrel", "craft", "composter", "table", "torches"]}]]),
    "dwelling1d": dict(W=7, D=7, ridge="x", walls=["cobble"], post=None, footing="mossy", floor=PLANK, roof="spruce",
                       gable="minecraft:spruce_planks", chimney="east", door_off=1,
                       rooms=[[{"x": (2, 6), "items": ["bed:gray", "chest", "smoker", "barrel", "craft", "table", "torches"]}]]),
    # a village: a room and a loft, or two rooms
    "dwelling2b": dict(W=9, D=7, ridge="z", loft=True, walls=["planks"], post="stripped", studs=4, footing="cobble",
                       roof="spruce", gable="minecraft:spruce_planks", chimney="east", shutters=True,
                       rooms=[[{"x": (2, 8), "items": ["furnace", "craft", "barrel", "chest", "table", "pot:poppy", "torches"]}]],
                       loftbeds=["red", "blue"]),
    "dwelling2c": dict(W=11, D=7, ridge="x", walls=["logs"], post="log", footing="cobble", roof="dark_oak",
                       gable=PLANK, chimney="west", shutters=True, door_off=-2,
                       rooms=[[{"x": (2, 6), "items": ["furnace", "craft", "barrel", "chest", "table", "torches"]},
                               {"x": (8, 10), "items": ["bed:red", "bed:yellow", "chest", "pot:dandelion", "torches"]}]]),
    "dwelling2d": dict(W=9, D=9, ridge="x", loft=True, walls=["planks"], post="log", studs=4, footing="stonebrick",
                       roof="oak", gable=PLANK, porch=True, sills=True,
                       rooms=[[{"x": (2, 8), "items": ["smoker", "craft", "barrel", "barrel", "chest", "table2", "pot:azure_bluet", "torches"]}]],
                       loftbeds=["light_blue", "light_blue"]),
    # a wealthy village: two floors, rooms of their own
    "dwelling3b": dict(W=11, D=9, ridge="z", floors=2, walls=["cobble", "daub"], post="log", studs=3, footing="stonebrick",
                       roof="spruce", gable="minecraft:spruce_planks", chimney="west", shutters=True, sills=True,
                       rooms=[[{"x": (2, 10), "items": ["smoker", "furnace", "cauldron", "craft", "barrel", "chest", "chest", "table3", "lanterns"]}],
                              [{"x": (2, 5), "items": ["bed:lime", "bed:lime", "chest", "rug:green", "torches"]},
                               {"x": (7, 10), "items": ["bed:red", "chest", "books", "books", "pot:blue_orchid", "rug:red", "torches"]}]]),
    "dwelling3c": dict(W=11, D=11, ridge="x", floors=2, walls=["planks", "planks"], post="log", studs=5, footing="cobble",
                       roof="dark_oak", gable=PLANK, chimney="east", shutters=True, porch=True,
                       rooms=[[{"x": (2, 6), "items": ["chest", "chest", "barrel", "barrel", "books", "lanterns"]},
                               {"x": (8, 10), "items": ["smoker", "furnace", "craft", "cauldron", "table", "torches"]}],
                              [{"x": (2, 10), "items": ["bed:blue", "bed:blue", "bed:white", "chest", "chest", "books", "pot:red_tulip", "rug:light_gray", "lanterns"]}]]),
    "dwelling3d": dict(W=13, D=9, ridge="x", floors=2, walls=["mudbrick", "planks"], post="stripped", studs=4, footing="stonebrick",
                       roof="spruce", gable="minecraft:spruce_planks", chimney="east", sills=True, door_off=-3,
                       rooms=[[{"x": (2, 7), "items": ["smoker", "furnace", "craft", "barrel", "cauldron", "table3", "torches"]},
                               {"x": (9, 12), "items": ["chest", "chest", "barrel", "loom", "pot:allium", "torches"]}],
                              [{"x": (2, 7), "items": ["bed:orange", "bed:orange", "chest", "rug:orange", "torches"]},
                               {"x": (9, 12), "items": ["bed:purple", "chest", "books", "rug:purple", "torches"]}]]),
    # a town: stone and plaster, many rooms
    "dwelling4b": dict(W=13, D=11, ridge="x", floors=2, walls=["stonebrick", "stonebrick"], post="minecraft:dark_oak_log[axis=y]",
                       studs=4, footing="stonebrick", roof="deepslate_tile", gable="minecraft:dark_oak_planks", chimney="east",
                       chimney_mat=SBRICK, shutters=True, sills=True, porch=True, double=True, trim="dark_oak",
                       floor="minecraft:spruce_planks",
                       rooms=[[{"x": (2, 7), "items": ["books", "books", "books", "chest", "table3", "rug:green", "pot:fern", "lanterns"]},
                               {"x": (9, 12), "items": ["smoker", "furnace", "cauldron", "craft", "barrel", "barrel", "table", "torches"]}],
                              [{"x": (2, 5), "items": ["bed:cyan", "bed:cyan", "chest", "torches"]},
                               {"x": (7, 9), "items": ["bed:red", "chest", "pot:red_tulip", "rug:red", "torches"]},
                               {"x": (11, 12), "items": ["bed:blue", "lectern", "books", "torches"]}]]),
    "dwelling4c": dict(W=13, D=11, ridge="z", floors=2, walls=["daub", "daub"], post="minecraft:dark_oak_log[axis=y]", studs=3,
                       footing="stonebrick", roof="dark_oak", gable="minecraft:calcite", chimney="west", chimney_mat="minecraft:bricks",
                       shutters=True, sills=True, double=True, trim="dark_oak", floor="minecraft:dark_oak_planks",
                       rooms=[[{"x": (2, 12), "items": ["smoker", "furnace", "cauldron", "craft", "barrel", "chest", "chest", "books", "books", "table5", "lanterns"]}],
                              [{"x": (2, 6), "items": ["bed:yellow", "bed:yellow", "chest", "rug:yellow", "torches"]},
                               {"x": (8, 12), "items": ["bed:red", "bed:red", "chest", "chest", "books", "pot:blue_orchid", "rug:red", "lanterns"]}]]),
    "dwelling4d": dict(W=15, D=9, ridge="x", floors=2, walls=["bricks", "planks"], post="log", studs=4, footing="stonebrick",
                       roof="spruce", gable="minecraft:spruce_planks", chimney="east", chimney_mat="minecraft:bricks",
                       shutters=True, sills=True, porch=True, door_off=-4,
                       rooms=[[{"x": (2, 8), "items": ["books", "books", "chest", "chest", "table3", "rug:brown", "lanterns"]},
                               {"x": (10, 14), "items": ["smoker", "furnace", "cauldron", "craft", "barrel", "barrel", "torches"]}],
                              [{"x": (2, 6), "items": ["bed:lime", "bed:lime", "chest", "torches"]},
                               {"x": (8, 10), "items": ["bed:white", "chest", "books", "torches"]},
                               {"x": (12, 14), "items": ["bed:magenta", "bed:magenta", "chest", "pot:allium", "torches"]}]]),
}


def loft_for(h, spec, rng):
    """The sleeping loft over the back half of a one-floor house with a loft."""
    if not spec.get("loft") or spec.get("floors", 1) > 1:
        return
    W, D = spec["W"], spec["D"]
    y = 6  # the loft floor at 5 (in walls 2..5), standing at 6
    zb = 2 + (D - 3) // 2  # its front edge
    h.box(2, 5, 2, W - 1, 5, zb - 1, PLANK)
    for yy in range(2, 6):
        h.put(2, yy, zb, ladder("east"))
    for x in range(3, W):
        h.put(x, 6, zb, fence(e=x < W - 1, w=x > 3))
    room = Room(h, 2, 2, W - 1, zb - 1, 6, rng)
    room.keep(2, zb - 1)
    for c in spec.get("loftbeds", []):
        room.place("bed:" + c)
    room.place("chest")


def build_home(name, spec):
    seed = sum(map(ord, name))
    h = home(spec, seed)
    loft_for(h, spec, random.Random(seed + 1))
    return h


# ---------- the halls ----------

def bench_rows(h, x0, x1, z_from, z_to, y, aisle):
    """Rows of benches facing north (the elder's lectern at the back), an aisle down the middle."""
    for z in range(z_from, z_to + 1, 2):
        for x in range(x0, x1 + 1):
            if abs(x - aisle) <= 0:
                continue
            h.put(x, y, z, st("south"))


def hall(level):
    rng = random.Random(100 + level)
    if level == 1:
        spec = dict(W=11, D=9, ridge="x", walls=["planks"], post="log", studs=5, footing="cobble", roof="spruce",
                    gable="minecraft:spruce_planks", chimney="west", shutters=True, rooms=[[]])
    elif level == 2:
        spec = dict(W=13, D=11, ridge="z", floors=2, walls=["cobble", "planks"], post="log", studs=4, footing="stonebrick",
                    roof="dark_oak", gable=PLANK, chimney="east", shutters=True, sills=True, double=True, porch=True, rooms=[[], []])
    else:
        spec = dict(W=15, D=13, ridge="z", floors=2, walls=["stonebrick", "daub"], post="minecraft:dark_oak_log[axis=y]", studs=3,
                    footing="stonebrick", roof="deepslate_tile", gable="minecraft:calcite", chimney="west", chimney_mat=SBRICK,
                    shutters=True, sills=True, double=True, trim="dark_oak", floor="minecraft:spruce_planks", rooms=[[], []],
                    ladder=(2, 2))
    h = home(spec, 100 + level)
    W, D = spec["W"], spec["D"]
    x0, x1, z0, z1 = 2, W - 1, 2, D - 1
    mid = (W + 1) // 2
    y = 2
    # the elder's lectern on a dais at the back, a banner of the village either side of it
    for x in range(mid - 2, mid + 3):
        h.put(x, 1, z0, slab("top"))  # (the floor raised a half block there: the dais)
    h.put(mid, y, z0, "minecraft:lectern[facing=south,has_book=false,powered=false]")
    h.put(mid - 1, y, z0, st("south")) if level >= 2 else None
    if level >= 2:
        h.put(mid - 2, y + 1, z0 - 0, "minecraft:red_wall_banner[facing=south]")
        h.put(mid + 2, y + 1, z0 - 0, "minecraft:red_wall_banner[facing=south]")
    # the benches of the gathering
    bench_rows(h, x0 + 1, x1 - 1, z0 + 2, z1 - 2, y, mid)
    # the stores along the walls: chests and barrels, the village's goods
    room = Room(h, x0, z0, x1, z1, y, rng)
    for x in range(mid - 2, mid + 3):
        room.keep(x, z0)
    for x in range(x0 + 1, x1):
        for z in range(z0 + 2, z1 - 1, 2):
            room.keep(x, z)
    room.keep(mid, z1)
    room.keep(mid, z1 - 1)
    room.keep(mid + 1, z1)
    for it in ["chest", "chest", "barrel", "chest", "barrel"] + (["chest", "chest", "books", "books"] if level >= 2 else []):
        room.place(it)
    room.torches(4, True)
    # the bell of the village by the door: on a frame of two posts and a beam
    if level == 1:
        bx = mid + 3
        for yy in range(1, 4):
            h.put(bx - 1, yy, D + 1, log())
            h.put(bx + 1, yy, D + 1, log())
        h.put(bx - 1, 4, D + 1, log("x"))
        h.put(bx, 4, D + 1, log("x"))
        h.put(bx + 1, 4, D + 1, log("x"))
        h.put(bx, 3, D + 1, "minecraft:bell[attachment=ceiling,facing=north,powered=false]")
    else:
        # a bell in the gable over the door: an opening in the front gable, the bell hung in it
        apex_y = max(yy for (xx, yy, zz), b in h.cells.items() if zz == D and xx == mid and b != "minecraft:air")
        by = apex_y - 2
        h.put(mid, by, D, "minecraft:bell[attachment=ceiling,facing=south,powered=false]")
        h.put(mid, by - 1, D, "minecraft:air")
    # upstairs (a hall of two floors): the elder's chamber and the archive
    if spec.get("floors", 1) > 1:
        y2 = 6
        up = Room(h, x0, z0, x1, z1, y2, rng)
        lx, lz = spec.get("ladder", (x0, z1 - 1))
        up.keep(lx, lz)
        up.keep(lx + 1, lz)
        for it in ["bed:red", "chest", "books", "books", "books", "books", "lectern", "chest"]:
            up.place(it)
        up.table(3 if level == 2 else 5, "x", "red")
        up.torches(4, True)
    trim_top(h)
    return h


# ---------- houses of more than one block: an L, the wing off the front of the main house ----------

def lhome(name, main, wing, side="east"):
    """The main house with a wing coming forward from one end of its front: the wing's own door to the street, a door
    between them inside, the wing's roof running into the main one (a cross gable)."""
    seed = sum(map(ord, name))
    Wm, Dm, Ww = main["W"], main["D"], wing["W"]
    ox = Wm - Ww if side == "east" else 0  # where the wing's walls start, in the main house's terms
    lo, hi = ox + 1, ox + Ww               # the wing's walls along x
    # the main door on the front the wing leaves free
    free = list(range(2, lo - 1)) if side == "east" else list(range(hi + 2, Wm))
    main = dict(main)
    main["door_off"] = free[len(free) // 2] - (1 + Wm) // 2 if free else 0
    hm = home(main, seed)
    loft_for(hm, main, random.Random(seed + 1))
    hw = home(wing, seed + 7)
    loft_for(hw, wing, random.Random(seed + 8))
    oz = Dm - 1
    h = House(max(hm.w, ox + hw.w), max(hm.d, oz + hw.d), max(hm.h, hw.h))
    h.cells = dict(hm.cells)
    h.roofs = set(hm.roofs)
    for (x, y, z), b in hw.cells.items():
        mx, mz = x + ox, z + oz
        if 1 <= mx <= Wm and 1 <= mz <= Dm - 1:
            continue  # inside the main house: the main house's
        if b == "minecraft:air" and h.cells.get((mx, y, mz), "minecraft:air") != "minecraft:air":
            continue  # (the wing's empty air does not take the main roof away)
        h.cells[(mx, y, mz)] = b
        if (x, y, z) in hw.roofs:
            h.roofs.add((mx, y, mz))
    # the wall between them: no window looking into the other room, a door through it in the middle
    for x in range(lo, hi + 1):
        for y in range(2, 6):
            if "pane" in h.cells.get((x, y, Dm), ""):
                h.cells[(x, y, Dm)] = PLANK
    cx = (lo + hi) // 2
    h.cells[(cx, 2, Dm)] = door("north", "lower", "left")
    h.cells[(cx, 3, Dm)] = door("north", "upper", "left")
    for z in (Dm - 1, Dm + 1):
        for y in (2, 3):
            b = h.cells.get((cx, y, z), "minecraft:air")
            if b != "minecraft:air" and "ladder" not in b:
                h.cells[(cx, y, z)] = "minecraft:air"
    trim_top(h)
    return h


LHOMES = {
    "dwelling1e": (dict(W=7, D=5, ridge="x", walls=["planks"], post="log", footing="cobble", floor="minecraft:coarse_dirt",
                        roof="oak", rooms=[[{"x": (2, 6), "items": ["bed:brown", "chest", "craft", "barrel", "torches"]}]]),
                   dict(W=5, D=5, ridge="z", walls=["planks"], post="log", footing="cobble", floor="minecraft:coarse_dirt",
                        roof="oak", rooms=[[{"x": (2, 4), "items": ["furnace", "composter", "torches"]}]]), "east"),
    "dwelling2e": (dict(W=9, D=7, ridge="x", walls=["logs"], post="log", footing="cobble", roof="spruce", gable="minecraft:spruce_planks",
                        shutters=True, chimney="west",
                        rooms=[[{"x": (2, 8), "items": ["furnace", "craft", "barrel", "chest", "table", "pot:poppy", "torches"]}]]),
                   dict(W=5, D=7, ridge="z", walls=["logs"], post="log", footing="cobble", roof="spruce", gable="minecraft:spruce_planks",
                        shutters=True, rooms=[[{"x": (2, 4), "items": ["bed:red", "bed:red", "chest", "torches"]}]]), "east"),
    "dwelling3e": (dict(W=11, D=9, ridge="x", floors=2, walls=["cobble", "planks"], post="log", studs=5, footing="stonebrick",
                        roof="dark_oak", chimney="west", shutters=True, sills=True,
                        rooms=[[{"x": (2, 10), "items": ["chest", "chest", "barrel", "books", "table3", "rug:green", "lanterns"]}],
                               [{"x": (2, 5), "items": ["bed:blue", "bed:blue", "chest", "torches"]},
                                {"x": (7, 10), "items": ["bed:red", "chest", "books", "pot:allium", "rug:red", "torches"]}]]),
                   dict(W=7, D=7, ridge="z", walls=["cobble"], post="log", footing="stonebrick", roof="dark_oak", shutters=True,
                        rooms=[[{"x": (2, 6), "items": ["smoker", "furnace", "cauldron", "craft", "barrel", "table", "torches"]}]]), "west"),
    "dwelling4e": (dict(W=13, D=9, ridge="x", floors=2, walls=["stonebrick", "daub"], post="minecraft:dark_oak_log[axis=y]", studs=3,
                        footing="stonebrick", roof="deepslate_tile", gable="minecraft:calcite", chimney="west", chimney_mat=SBRICK,
                        shutters=True, sills=True, trim="dark_oak", floor="minecraft:spruce_planks",
                        rooms=[[{"x": (2, 12), "items": ["books", "books", "books", "chest", "chest", "table5", "rug:green", "pot:fern", "lanterns"]}],
                               [{"x": (2, 6), "items": ["bed:cyan", "bed:cyan", "chest", "torches"]},
                                {"x": (8, 12), "items": ["bed:red", "bed:red", "chest", "books", "rug:red", "lanterns"]}]]),
                   dict(W=9, D=9, ridge="z", floors=2, walls=["stonebrick", "daub"], post="minecraft:dark_oak_log[axis=y]", studs=3,
                        footing="stonebrick", roof="deepslate_tile", gable="minecraft:calcite", shutters=True, sills=True, trim="dark_oak",
                        floor="minecraft:spruce_planks",
                        rooms=[[{"x": (2, 8), "items": ["smoker", "furnace", "cauldron", "craft", "barrel", "barrel", "table2", "torches"]}],
                               [{"x": (2, 8), "items": ["bed:yellow", "bed:yellow", "chest", "loom", "pot:red_tulip", "torches"]}]]), "east"),
}

# ---------- the workplaces: the stores, the granary, the forge, the armoury, the sawmill, the masons', the mill,
# the inn, the barracks - three of each, for the hamlet, the village and the wealthy village ----------

def W(**k):
    return k


WORKS = {
    "storage": [
        W(W=7, D=7, ridge="x", walls=["planks"], post="log", footing="cobble", roof="oak", double=True, door_off=-1,
          rooms=[[{"x": (2, 6), "items": ["barrel", "barrel", "barrel", "chest", "chest", "crate", "hay", "chest", "torches"]}]]),
        W(W=11, D=7, ridge="x", walls=["logs"], post="log", footing="cobble", roof="spruce", gable="minecraft:spruce_planks", double=True,
          porch=True, rooms=[[{"x": (2, 10), "items": ["chest", "chest", "chest", "chest", "barrel", "barrel", "barrel", "barrel", "crate", "crate", "hay", "hay", "lanterns"]}]]),
        W(W=13, D=9, ridge="x", floors=2, walls=["stonebrick", "planks"], post="log", studs=4, footing="stonebrick", roof="dark_oak",
          double=True, shutters=True, rooms=[[{"x": (2, 12), "items": ["chest"] * 8 + ["barrel"] * 6 + ["lanterns"]}],
                                             [{"x": (2, 12), "items": ["barrel"] * 6 + ["hay"] * 4 + ["crate"] * 4 + ["torches"]}]]),
    ],
    "granary": [
        W(W=7, D=5, ridge="z", walls=["planks"], post="log", footing="cobble", roof="oak", double=True,
          rooms=[[{"x": (2, 6), "items": ["hay", "hay", "hay", "barrel", "barrel", "composter", "torches"]}]]),
        W(W=9, D=7, ridge="x", loft=True, walls=["planks"], post="stripped", studs=4, footing="cobble", roof="spruce", double=True,
          rooms=[[{"x": (2, 8), "items": ["hay"] * 6 + ["barrel"] * 3 + ["composter", "torches"]}]]),
        W(W=11, D=9, ridge="z", floors=2, walls=["stonebrick", "planks"], post="log", studs=5, footing="stonebrick", roof="dark_oak",
          double=True, rooms=[[{"x": (2, 10), "items": ["barrel"] * 6 + ["hay"] * 4 + ["composter", "lanterns"]}],
                              [{"x": (2, 10), "items": ["hay"] * 10 + ["torches"]}]]),
    ],
    "smithy": [
        W(W=7, D=7, ridge="x", walls=["cobble"], post=None, footing="cobble", roof="spruce", gable="minecraft:spruce_planks",
          chimney="east", porch=True, rooms=[[{"x": (2, 6), "items": ["furnace", "anvil", "grind", "chest", "barrel", "torches"]}]]),
        W(W=9, D=7, ridge="x", walls=["cobble"], post="log", footing="stonebrick", roof="dark_oak", chimney="east", porch=True, double=True,
          rooms=[[{"x": (2, 8), "items": ["blast", "furnace", "anvil", "smithing", "grind", "chest", "chest", "barrel", "lanterns"]}]]),
        W(W=11, D=9, ridge="x", floors=2, walls=["stonebrick", "planks"], post="log", studs=5, footing="stonebrick", roof="deepslate_tile",
          gable="minecraft:dark_oak_planks", chimney="east", chimney_mat=SBRICK, shutters=True, double=True,
          rooms=[[{"x": (2, 10), "items": ["blast", "furnace", "furnace", "anvil", "anvil", "smithing", "grind", "chest", "chest", "barrel", "lanterns"]}],
                 [{"x": (2, 10), "items": ["bed:red", "chest", "chest", "barrel", "table", "torches"]}]]),
    ],
    "armory": [
        W(W=7, D=7, ridge="z", walls=["planks"], post="log", footing="cobble", roof="spruce", gable="minecraft:spruce_planks",
          rooms=[[{"x": (2, 6), "items": ["grind", "smithing", "chest", "chest", "barrel", "target", "torches"]}]]),
        W(W=9, D=7, ridge="x", walls=["cobble"], post="log", footing="stonebrick", roof="dark_oak", double=True, shutters=True,
          rooms=[[{"x": (2, 8), "items": ["smithing", "grind", "fletching", "chest", "chest", "chest", "barrel", "target", "lanterns"]}]]),
        W(W=11, D=9, ridge="z", floors=2, walls=["stonebrick", "stonebrick"], post="minecraft:dark_oak_log[axis=y]", studs=5,
          footing="stonebrick", roof="deepslate_tile", gable="minecraft:dark_oak_planks", double=True, shutters=True, trim="dark_oak",
          rooms=[[{"x": (2, 10), "items": ["smithing", "grind", "fletching", "anvil", "chest", "chest", "chest", "chest", "barrel", "lanterns"]}],
                 [{"x": (2, 10), "items": ["target", "target", "chest", "chest", "barrel", "barrel", "torches"]}]]),
    ],
    "sawmill": [
        W(W=7, D=7, ridge="x", walls=["planks"], post="log", footing="cobble", roof="oak", porch=True, double=True, door_off=-1,
          rooms=[[{"x": (2, 6), "items": ["craft", "logs", "logs", "logs", "chest", "barrel", "torches"]}]]),
        W(W=11, D=7, ridge="x", walls=["logs"], post="log", footing="cobble", roof="spruce", gable="minecraft:spruce_planks", porch=True,
          double=True, rooms=[[{"x": (2, 10), "items": ["craft", "craft", "stonecutter", "logs", "logs", "logs", "logs", "logs", "chest", "chest", "barrel", "lanterns"]}]]),
        W(W=13, D=9, ridge="z", floors=2, walls=["planks", "planks"], post="log", studs=4, footing="stonebrick", roof="dark_oak", double=True,
          rooms=[[{"x": (2, 12), "items": ["craft", "craft", "stonecutter", "logs"] + ["logs"] * 6 + ["chest", "chest", "lanterns"]}],
                 [{"x": (2, 12), "items": ["logs"] * 6 + ["barrel", "barrel", "chest", "torches"]}]]),
    ],
    "mason": [
        W(W=7, D=7, ridge="x", walls=["cobble"], post="log", footing="cobble", roof="spruce", gable="minecraft:spruce_planks", porch=True,
          rooms=[[{"x": (2, 6), "items": ["stonecutter", "cobble", "cobble", "chest", "barrel", "torches"]}]]),
        W(W=9, D=7, ridge="z", walls=["stonebrick"], post="log", footing="stonebrick", roof="dark_oak", double=True,
          rooms=[[{"x": (2, 8), "items": ["stonecutter", "stonecutter", "cobble", "cobble", "cobble", "chest", "chest", "barrel", "lanterns"]}]]),
        W(W=11, D=9, ridge="x", floors=2, walls=["stonebrick", "stonebrick"], post=None, footing="stonebrick", roof="deepslate_tile",
          gable="minecraft:stone_bricks", double=True, shutters=True,
          rooms=[[{"x": (2, 10), "items": ["stonecutter", "stonecutter", "grind"] + ["cobble"] * 5 + ["chest", "chest", "lanterns"]}],
                 [{"x": (2, 10), "items": ["bed:gray", "chest", "barrel", "table", "books", "torches"]}]]),
    ],
    "mill": [
        W(W=7, D=7, ridge="z", walls=["planks"], post="log", footing="cobble", roof="oak",
          rooms=[[{"x": (2, 6), "items": ["grind", "hay", "hay", "barrel", "smoker", "chest", "torches"]}]]),
        W(W=9, D=7, ridge="x", loft=True, walls=["planks"], post="log", studs=4, footing="cobble", roof="spruce", double=True, chimney="west",
          rooms=[[{"x": (2, 8), "items": ["grind", "grind", "smoker", "furnace", "hay", "hay", "barrel", "barrel", "chest", "torches"]}]]),
        W(W=11, D=9, ridge="z", floors=2, walls=["stonebrick", "planks"], post="log", studs=5, footing="stonebrick", roof="dark_oak",
          chimney="west", chimney_mat=SBRICK, double=True, shutters=True,
          rooms=[[{"x": (2, 10), "items": ["grind", "grind", "smoker", "smoker", "furnace", "barrel", "barrel", "chest", "craft", "lanterns"]}],
                 [{"x": (2, 10), "items": ["hay"] * 8 + ["barrel", "barrel", "torches"]}]]),
    ],
    "tavern": [
        W(W=9, D=7, ridge="x", walls=["planks"], post="log", footing="cobble", roof="oak", chimney="west", porch=True,
          rooms=[[{"x": (2, 8), "items": ["smoker", "barrel", "craft", "barrel", "table@-2", "table@2", "lanterns"]}]]),
        W(W=11, D=9, ridge="x", floors=2, walls=["cobble", "planks"], post="log", studs=5, footing="stonebrick", roof="spruce",
          gable="minecraft:spruce_planks", chimney="west", porch=True, double=True, shutters=True,
          rooms=[[{"x": (2, 10), "items": ["smoker", "furnace", "barrel", "barrel", "barrel", "craft", "table@-3", "table@3", "table", "lanterns"]}],
                 [{"x": (2, 5), "items": ["bed:red", "bed:red", "chest", "torches"]}, {"x": (7, 10), "items": ["bed:blue", "bed:blue", "chest", "torches"]}]]),
        W(W=13, D=11, ridge="z", floors=2, walls=["stonebrick", "daub"], post="minecraft:dark_oak_log[axis=y]", studs=3, footing="stonebrick",
          roof="deepslate_tile", gable="minecraft:calcite", chimney="west", chimney_mat=SBRICK, double=True, shutters=True, sills=True,
          trim="dark_oak", floor="minecraft:spruce_planks",
          rooms=[[{"x": (2, 12), "items": ["smoker", "smoker", "furnace", "barrel", "barrel", "barrel", "barrel", "craft", "cauldron",
                                           "table@-3:2", "table@2:2", "rug:red", "lanterns"]}],
                 [{"x": (2, 4), "items": ["bed:red", "chest", "torches"]}, {"x": (6, 8), "items": ["bed:green", "chest", "torches"]},
                  {"x": (10, 12), "items": ["bed:blue", "chest", "torches"]}]]),
    ],
    "barracks": [
        W(W=9, D=7, ridge="x", walls=["logs"], post="log", footing="cobble", roof="spruce", gable="minecraft:spruce_planks", double=True,
          rooms=[[{"x": (2, 8), "items": ["bed:red", "bed:red", "bed:red", "bed:red", "chest", "chest", "grind", "torches"]}]]),
        W(W=11, D=9, ridge="x", walls=["cobble"], post="log", footing="stonebrick", roof="dark_oak", double=True, shutters=True,
          rooms=[[{"x": (2, 10), "items": ["bed:red"] * 6 + ["chest", "chest", "chest", "grind", "target", "table", "lanterns"]}]]),
        W(W=13, D=11, ridge="z", floors=2, walls=["stonebrick", "stonebrick"], post="minecraft:dark_oak_log[axis=y]", studs=4,
          footing="stonebrick", roof="deepslate_tile", gable="minecraft:stone_bricks", double=True, shutters=True, trim="dark_oak",
          rooms=[[{"x": (2, 12), "items": ["grind", "smithing", "target", "target", "chest", "chest", "chest", "barrel", "table3", "lanterns"]}],
                 [{"x": (2, 12), "items": ["bed:red"] * 8 + ["chest"] * 4 + ["torches"]}]]),
    ],
}
WORK_FILE = {"storage": "storehouse", "granary": "granary", "smithy": "forge", "armory": "armoury", "sawmill": "lumberyard",
             "mason": "masonry", "mill": "mill", "tavern": "inn", "barracks": "garrison"}

# ---------- workplaces that look like what they are: the windmill, the forge shed, the barn, the granary on its
# staddle stones, the saw shed, the masons' yard, the barracks and its yard, the armoury keep ----------

AIR = "minecraft:air"


def wcol(h, x, z, y0, y1, b):
    for y in range(y0, y1 + 1):
        h.put(x, y, z, b)


def windmill(level):
    R, B = 4 + level, (5 if level < 3 else 7)
    H = 10 + 2 * level                    # the top of the tower walls: a tower, not a house
    w = max(B + 2, 2 * R + 3)
    cx = w // 2
    x0, x1, z0, z1 = cx - B // 2, cx - B // 2 + B - 1, 1, B
    h = House(w, B + 4, H + 8)
    lower = COB if level < 3 else SBRICK
    h.box(x0, 0, z0, x1, 0, z1, lower)
    frame(h, x0, z0, x1, z1, 1, 1, lower, lower)
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, PLANK)
    for y in range(2, H + 1):
        stone = y <= 4 + level
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                h.put(x, y, z, lower if stone else (log() if x in (x0, x1) else PLANK))
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                h.put(x, y, z, lower if stone else PLANK)
    plate(h, x0, z0, x1, z1, H + 1)
    h.put(cx, 2, z1, door("north", "lower", "left"))
    h.put(cx, 3, z1, door("north", "upper", "left"))
    h.put(cx, 1, z1 + 1, cst("north"))
    for (x, y, z, a) in ((x0, 3, (z0 + z1) // 2, "z"), (x1, 7, (z0 + z1) // 2, "z"), (cx, 9, z0, "x"), (x0, 10, (z0 + z1) // 2, "z")):
        h.put(x, y, z, pane(a))
    # the stones and the stores below; up the ladder the grain floor
    up = level >= 2
    if up:
        h.box(x0 + 1, 6, z0 + 1, x1 - 1, 6, z1 - 1, PLANK)
        for y in range(2, 7):
            h.put(x0 + 1, y, z1 - 1, ladder("east"))
    room = Room(h, x0 + 1, z0 + 1, x1 - 1, z1 - 1, 2, random.Random(level))
    room.keep(cx, z1 - 1)
    room.keep(x0 + 1, z1 - 1)
    room.keep(x0 + 2, z1 - 1)
    for it in ["grind", "grind", "hay", "barrel", "chest"] + (["smoker", "barrel"] if level >= 2 else []):
        room.place(it)
    room.torches(2)
    if up:
        top = Room(h, x0 + 1, z0 + 1, x1 - 1, z1 - 1, 7, random.Random(level + 9))
        top.keep(x0 + 1, z1 - 1)
        top.keep(x0 + 2, z1 - 1)
        for it in ["hay", "hay", "hay", "barrel", "barrel"]:
            top.place(it)
    clear(h, 0, w - 1, 0, B + 3, 1, H + 7)
    # the cap: a steep little roof over the tower
    for i in range(0, B // 2 + 2):
        zn, zs, y = z0 - 1 + i, z1 + 1 - i, H + 1 + i
        if zn > zs:
            break
        for x in range(x0 - 1, x1 + 2):
            if zn == zs:
                h.roof(x, y, zn, slab("bottom", "spruce"))
            else:
                h.roof(x, y, zn, rstairs("spruce", "south"))
                h.roof(x, y, zs, rstairs("spruce", "north"))
        for z in range(zn + 1, zs):
            for gx in (x0, x1):
                if h.cells.get((gx, y, z), AIR) == AIR:
                    h.put(gx, y, z, "minecraft:spruce_planks")
    # the sails: a hub on the front wall, four arms of timber with their cloth, turned to the wind
    hy = H - 1
    h.put(cx, hy, z1 + 1, log("z"))
    zs = z1 + 2
    h.put(cx, hy, zs, log("z"))
    for k in range(1, R + 1):
        h.put(cx, hy + k, zs, fence())                       # up
        h.put(cx, hy - k, zs, fence())                       # down
        h.put(cx + k, hy, zs, fence(e=k < R, w=True))        # right
        h.put(cx - k, hy, zs, fence(e=True, w=k < R))        # left
        if k >= 2:
            for (xx, yy) in ((cx + 1, hy + k), (cx - 1, hy - k), (cx + k, hy - 1), (cx - k, hy + 1)):
                h.put(xx, yy, zs, "minecraft:white_wool")
    trim_top(h)
    return h


def forge_shed(level):
    W, D = (7, 5, 9, 7)[0:2] if level == 1 else (9, 5) if level == 2 else (11, 7)
    h = House(W + 2, D + 2, 16)
    x0, x1, z0, z1 = 1, W, 1, D
    stone = COB if level < 3 else SBRICK
    h.box(x0, 0, z0, x1, 0, z1, stone)
    h.box(x0, 1, z0, x1, 1, z1, "minecraft:cobblestone" if level < 3 else "minecraft:stone_bricks")  # a stone floor: sparks
    for y in range(2, 5):  # the back wall of stone behind the hearth, the rest open on its posts: a shed, not a house
        for x in range(x0, x1 + 1):
            h.put(x, y, z0, stone)
        for z in range(z0, z0 + 2):
            h.put(x0, y, z, stone)
            h.put(x1, y, z, stone)
        for x in [x0, x1] + list(range(x0 + 4, x1 - 1, 4)):
            h.put(x, y, z1, log())
        for z in range(z0 + 3, z1, 2):
            h.put(x0, y, z, log())
            h.put(x1, y, z, log())
    for z in range(z0 + 2, z1):  # a rail along the open sides, waist high
        for x in (x0, x1):
            if h.cells.get((x, 2, z), AIR) == AIR:
                h.put(x, 2, z, fence(n=True, s=True))
    plate(h, x0, z0, x1, z1, 5)
    cx = (x0 + x1) // 2
    # the hearth at the back: the fire, a stone hood over it, the stack up through the roof
    h.put(cx, 2, z0 + 1, (f"minecraft:blast_furnace[facing=south,lit=false]" if level >= 2 else furnace("south")))
    for x in (cx - 1, cx, cx + 1):
        h.put(x, 4, z0 + 1, f"minecraft:{'cobblestone' if level < 3 else 'stone_brick'}_stairs[facing=north,half=top,shape=straight,waterlogged=false]")
    h.put(cx - 1, 2, z0 + 1, stone)
    h.put(cx + 1, 2, z0 + 1, stone)
    # the anvil in the middle of the floor, the trough, the grindstone, the bench, the stores
    h.put(cx, 2, z0 + 3, "minecraft:anvil[facing=east]")
    h.put(cx + 2, 2, z0 + 1, "minecraft:water_cauldron[level=3]")
    h.put(x0 + 1, 2, z0 + 1, "minecraft:grindstone[face=floor,facing=south]")
    h.put(x1 - 1, 2, z0 + 1, chest("south"))
    h.put(x1 - 1, 2, z0 + 2, BARREL_UP)
    if level >= 2:
        h.put(x0 + 1, 2, z0 + 2, "minecraft:smithing_table")
        h.put(x1 - 1, 2, z0 + 3, "minecraft:barrel[facing=west,open=false]")
    if level >= 3:  # a store room behind a board wall at the east end
        for z in range(z0 + 1, z1):
            for y in range(2, 5):
                h.put(x1 - 3, y, z, PLANK)
        h.put(x1 - 3, 2, z1 - 1, door("east", "lower", "left"))
        h.put(x1 - 3, 3, z1 - 1, door("east", "upper", "left"))
        for z in range(z0 + 1, z1 - 1):
            h.put(x1 - 1, 2, z, chest("west"))
        h.put(x1 - 2, 2, z0 + 1, BARREL_UP)
        h.put(cx - 2, 2, z0 + 3, "minecraft:anvil[facing=east]")
    h.put(cx, 4, z0 + 3, lantern(True))
    clear(h, 0, W + 1, 0, D + 1, 1, 15)
    top = roof_x(h, 0, W + 1, x0, x1, D + 2, 5, "spruce" if level < 3 else "deepslate_tile", "minecraft:spruce_planks")
    for y in range(3, top + 2):
        h.put(cx, y, z0, stone)
    h.put(cx, top + 2, z0, cap(stone))
    trim_top(h)
    return h


def barn(level):
    W, D = ((9, 7), (11, 9), (13, 9))[level - 1]
    h = House(W + 2, D + 3, 22)
    x0, x1, z0, z1 = 1, W, 1, D
    post = "minecraft:dark_oak_log[axis=y]"
    h.box(x0, 0, z0, x1, 0, z1, COB)
    frame(h, x0, z0, x1, z1, 1, 1, COB, COB)
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, PLANK)
    top = 6
    for y in range(2, top + 1):  # tall board walls on a dark frame
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                h.put(x, y, z, post if x in (x0, x1) or (x - x0) % 4 == 0 else "minecraft:spruce_planks")
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                h.put(x, y, z, post if (z - z0) % 4 == 0 else "minecraft:spruce_planks")
    plate(h, x0, z0, x1, z1, top + 1)
    cx = (x0 + x1) // 2
    # the great doors, two wide, and the hay door over them in the gable
    for (dx, hinge) in ((cx, "left"), (cx + 1, "right")):
        h.put(dx, 2, z1, door("north", "lower", hinge))
        h.put(dx, 3, z1, door("north", "upper", hinge))
        h.put(dx, 4, z1, "minecraft:spruce_planks")
        h.put(dx, 1, z1 + 1, cst("north"))
    # the loft over the back half, up a ladder
    zl = z0 + (D - 2) // 2
    h.box(x0 + 1, 5, z0 + 1, x1 - 1, 5, zl, PLANK)
    for y in range(2, 6):
        h.put(x0 + 1, y, zl + 1, ladder("east"))
    room = Room(h, x0 + 1, z0 + 1, x1 - 1, z1 - 1, 2, random.Random(level))
    for x in (cx, cx + 1):
        for z in range(zl + 1, z1):
            room.keep(x, z)
    room.keep(x0 + 1, zl + 1)
    room.keep(x0 + 2, zl + 1)
    for it in ["chest"] * (3 + level) + ["barrel"] * (3 + level) + ["crate"] * level:
        room.place(it)
    room.torches(3)
    loft = Room(h, x0 + 1, z0 + 1, x1 - 1, zl, 6, random.Random(level + 5))
    loft.keep(x0 + 1, zl)
    for it in ["hay"] * (4 + 2 * level) + ["barrel"] * 2:
        loft.place(it)
    clear(h, 0, W + 1, 0, D + 2, 1, 21)
    apex = roof_z(h, 0, D + 1, z0, z1, W + 2, top + 1, "dark_oak", "minecraft:spruce_planks")  # the gable to the street, as a barn stands
    # the hoist: a beam out over the hay door, a rope and a bale on it
    hb = top + 3
    h.put(cx, hb - 1, z1, AIR)
    h.put(cx + 1, hb - 1, z1, AIR)
    h.put(cx, hb - 1, z1, "minecraft:spruce_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]")
    h.put(cx, hb + 1, z1 + 1, log("z"))
    h.put(cx, hb + 1, z1 + 2, log("z"))
    h.put(cx, hb, z1 + 2, "minecraft:chain[axis=y,waterlogged=false]")
    h.put(cx, hb - 1, z1 + 2, "minecraft:hay_block[axis=y]")
    trim_top(h)
    return h


def staddle_granary(level):
    W, D = ((5, 5), (7, 5), (7, 7))[level - 1]
    h = House(W + 2, D + 5, 16)
    x0, x1, z0, z1 = 1, W, 1, D
    h.box(x0, 0, z0, x1, 0, z1, "minecraft:gravel")
    # the staddle stones: a post of stone, a flat cap, the floor up on them out of the mice's reach
    for x in range(x0, x1 + 1, 2):
        for z in range(z0, z1 + 1, 2):
            h.put(x, 1, z, "minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
            h.put(x, 2, z, "minecraft:smooth_stone_slab[type=top,waterlogged=false]")
    h.box(x0, 3, z0, x1, 3, z1, PLANK)
    for y in range(4, 7):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                h.put(x, y, z, log() if x in (x0, x1) else PLANK)
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                h.put(x, y, z, PLANK)
    plate(h, x0, z0, x1, z1, 7)
    cx = (x0 + x1) // 2
    h.put(cx, 4, z1, door("north", "lower", "left"))
    h.put(cx, 5, z1, door("north", "upper", "left"))
    for k, y in ((1, 3), (2, 2), (3, 1)):  # steps up to the door, each on its timber
        h.put(cx, y, z1 + k, st("north"))
        for yy in range(1, y):
            h.put(cx, yy, z1 + k, PLANK)
    room = Room(h, x0 + 1, z0 + 1, x1 - 1, z1 - 1, 4, random.Random(level))
    room.keep(cx, z1 - 1)
    for it in ["hay"] * (2 + level) + ["barrel"] * (1 + level) + ["composter"]:
        room.place(it)
    room.torches(1)
    clear(h, 0, W + 1, 0, D + 4, 1, 15)
    roof_x(h, 0, W + 1, x0, x1, D + 2, 7, "oak" if level < 3 else "spruce", PLANK)
    trim_top(h)
    return h


def saw_shed(level):
    W, D = ((9, 5), (11, 7), (13, 7))[level - 1]
    h = House(W + 2, D + 2, 16)
    x0, x1, z0, z1 = 1, W, 1, D
    h.box(x0, 0, z0, x1, 0, z1, COB)
    h.box(x0, 1, z0, x1, 1, z1, PLANK)
    for x in [x0, x1] + list(range(x0 + 4, x1 - 1, 4)):
        for z in (z0, z1):
            wcol(h, x, z, 2, 4, log())
    for z in range(z0 + 2, z1 - 1, 3):
        wcol(h, x0, z, 2, 4, log())
        wcol(h, x1, z, 2, 4, log())
    plate(h, x0, z0, x1, z1, 5)
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    # the saw bench down the middle: the log fed in at one end, the boards out at the other
    h.put(cx, 2, cz, "minecraft:stonecutter[facing=south]")
    for x in range(cx - 3, cx):
        h.put(x, 2, cz, "minecraft:oak_wood[axis=x]")
    for x in range(cx + 1, cx + 3):
        h.put(x, 2, cz, slab("bottom"))
    # the log piles along the back, the stacked boards at the front corners
    for x in range(x0 + 1, x1):
        if x % 4 == 0:
            continue
        h.put(x, 2, z0, "minecraft:oak_wood[axis=x]") if False else None
        h.put(x, 2, z0 + 1, "minecraft:oak_wood[axis=x]")
        if x % 2 == 0:
            h.put(x, 3, z0 + 1, "minecraft:oak_wood[axis=x]")
    for (x, z) in ((x0 + 1, z1 - 1), (x1 - 1, z1 - 1)):
        h.put(x, 2, z, PLANK)
        h.put(x, 3, z, slab("bottom"))
    h.put(x1 - 1, 2, cz, CRAFT)
    h.put(x1 - 1, 2, cz + 1, chest("west"))
    if level >= 2:  # the sawyer's office at the west end
        for z in range(z0 + 1, z1):
            wcol(h, x0 + 3, z, 2, 4, PLANK)
        for x in range(x0 + 1, x0 + 3):
            wcol(h, x, z1, 2, 4, PLANK)
        h.put(x0 + 3, 2, cz, door("east", "lower", "left"))
        h.put(x0 + 3, 3, cz, door("east", "upper", "left"))
        h.put(x0 + 1, 2, z0 + 2, chest("east"))
        h.put(x0 + 1, 2, z0 + 3, CRAFT)
        h.put(x0 + 2, 4, cz, lantern(True))
    h.put(cx, 4, cz, lantern(True))
    clear(h, 0, W + 1, 0, D + 1, 1, 15)
    roof_x(h, 0, W + 1, x0, x1, D + 2, 5, "spruce", "minecraft:spruce_planks")
    # the gables left open under the roof (a saw shed: air through it)
    trim_top(h)
    return h


def mason_yard(level):
    W, D = ((9, 9), (11, 9), (13, 11))[level - 1]
    h = House(W + 2, D + 2, 16)
    x0, x1, z0, z1 = 1, W, 1, D
    h.box(x0, 0, z0, x1, 0, z1, COB)
    h.box(x0, 1, z0, x1, 1, z1, "minecraft:gravel")
    wall = "minecraft:cobblestone_wall[east=low,north=none,south=none,up=true,waterlogged=false,west=low]"
    wallz = "minecraft:cobblestone_wall[east=none,north=low,south=low,up=true,waterlogged=false,west=none]"
    cx = (x0 + x1) // 2
    for x in range(x0, x1 + 1):  # the low wall round the yard, a way in at the front
        h.put(x, 2, z1, wall if abs(x - cx) > 1 else AIR)
    for z in range(z0 + 5, z1):
        h.put(x0, 2, z, wallz)
        h.put(x1, 2, z, wallz)
    # the shed at the back: stone walls, open to the yard
    for y in range(2, 5):
        for x in range(x0, x1 + 1):
            h.put(x, y, z0, COB)
        for z in range(z0, z0 + 5):
            h.put(x0, y, z, COB)
            h.put(x1, y, z, COB)
        for x in range(x0 + 3, x1, 3):
            if y < 4:
                h.put(x, y, z0 + 4, log())
    for x in range(x0, x1 + 1):
        for z in range(z0, z0 + 5):
            pass
    room = Room(h, x0 + 1, z0 + 1, x1 - 1, z0 + 3, 2, random.Random(level))
    for it in ["stonecutter"] * (1 + level // 2) + ["chest", "chest", "barrel", "grind"] + (["stonecutter"] if level >= 3 else []):
        room.place(it)
    room.torches(2)
    # the blocks dressed and waiting in the yard
    rng = random.Random(level + 3)
    for (x, z) in ((x0 + 1, z1 - 2), (x0 + 2, z1 - 2), (x0 + 1, z1 - 3), (x1 - 2, z0 + 6), (x1 - 1, z0 + 6)):
        h.put(x, 2, z, rng.choice(["minecraft:stone_bricks", COB, "minecraft:smooth_stone", "minecraft:stone"]))
    h.put(x0 + 1, 3, z1 - 2, "minecraft:stone_bricks")
    # the crane: a post, a jib, a chain and a block on it
    px, pz = x1 - 1, z1 - 2
    wcol(h, px, pz, 2, 7, log())
    for x in range(px - 3, px):
        h.put(x, 7, pz, log("x"))
    h.put(px - 3, 6, pz, "minecraft:chain[axis=y,waterlogged=false]")
    h.put(px - 3, 5, pz, "minecraft:chain[axis=y,waterlogged=false]")
    h.put(px - 3, 4, pz, "minecraft:stone_bricks")
    clear(h, 0, W + 1, 0, D + 1, 1, 15)
    # the shed's roof only (a lean-to of stone over the back of the yard)
    mat = "spruce" if level < 3 else "deepslate_tile"
    for i, z in enumerate(range(z0 - 1, z0 + 6)):  # on the walls at the back, a half block down on the posts at the front
        for x in range(x0 - 1, x1 + 2):
            if i < 4:
                h.roof(x, 5, z, rslab(mat))
            else:
                h.roof(x, 4, z, rslab(mat).replace("bottom", "top"))
    trim_top(h)
    return h


def garrison(level):
    W, D = ((11, 5), (13, 7), (15, 7))[level - 1]
    YARD = 7
    h = House(W + 2 + YARD, D + 2, 16)
    x0, x1, z0, z1 = 1, W, 1, D
    stone = (COB, COB, SBRICK)[level - 1]
    walls = ("logs", "cobble", "stonebrick")[level - 1]
    rng = random.Random(level)
    h.box(x0, 0, z0, x1, 0, z1, stone)
    frame(h, x0, z0, x1, z1, 1, 1, stone, stone)
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, PLANK)
    for y in range(2, 5):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                h.put(x, y, z, log() if x in (x0, x1) else wall_block(walls, "x", x, y, z, rng))
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                h.put(x, y, z, wall_block(walls, "z", x, y, z, rng))
    plate(h, x0, z0, x1, z1, 5)
    cx = (x0 + x1) // 2
    h.put(cx, 2, z1, door("north", "lower", "left"))
    h.put(cx, 3, z1, door("north", "upper", "left"))
    h.put(cx, 1, z1 + 1, cst("north"))
    for x in range(x0 + 2, x1 - 1, 3):
        if abs(x - cx) > 1:
            h.put(x, 3, z1, pane("x"))
        h.put(x, 3, z0, pane("x"))
    h.put(x1, 3, (z0 + z1) // 2, door("west", "lower", "left")) if False else None
    room = Room(h, x0 + 1, z0 + 1, x1 - 1, z1 - 1, 2, rng)
    room.keep(cx, z1 - 1)
    for it in ["bed:red"] * (3 + 2 * level) + ["chest"] * (2 + level) + ["grind"]:
        room.place(it)
    room.torches(3)
    # the yard for drill beside it: a fence round it, straw men to strike, targets to shoot at
    yx0, yx1 = x1 + 1, x1 + YARD
    h.box(yx0, 0, z0, yx1, 0, z1, "minecraft:gravel") if False else None
    h.box(yx0, 1, z0, yx1, 1, z1, "minecraft:gravel")
    for x in range(yx0, yx1 + 1):
        for z in (z0, z1):
            h.put(x, 2, z, fence(e=x < yx1, w=x > yx0))
    for z in range(z0 + 1, z1):
        h.put(yx1, 2, z, fence(n=True, s=True))
    gz = (z0 + z1) // 2
    h.put(yx0 + 3, 2, z1, f"minecraft:oak_fence_gate[facing=south,in_wall=false,open=false,powered=false]")
    for (x, z) in ((yx0 + 2, z0 + 2), (yx0 + 4, z0 + 2)):
        h.put(x, 2, z, fence())
        h.put(x, 3, z, "minecraft:hay_block[axis=y]")
        h.put(x, 4, z, "minecraft:carved_pumpkin[facing=south]")
    h.put(yx1 - 1, 2, z1 - 2, "minecraft:target[power=0]")
    h.put(yx1 - 1, 3, z1 - 2, "minecraft:target[power=0]") if level >= 2 else None
    h.put(yx0 + 1, 2, z1 - 1, BARREL_UP)
    clear(h, 0, h.w - 1, 0, D + 1, 1, 15)
    roof_x(h, 0, W + 1, x0, x1, D + 2, 5, ("spruce", "dark_oak", "deepslate_tile")[level - 1], "minecraft:spruce_planks")
    trim_top(h)
    return h


def keep(level):
    W = (7, 7, 9)[level - 1]
    floors = 1 if level == 1 else 2
    top = 5 if floors == 1 else 9
    h = House(W + 2, W + 2, 16)
    x0, x1, z0, z1 = 1, W, 1, W
    stone = COB if level < 3 else SBRICK
    rng = random.Random(level)
    h.box(x0, 0, z0, x1, 0, z1, stone)
    h.box(x0, 1, z0, x1, 1, z1, stone)
    for y in range(2, top + 1):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                h.put(x, y, z, wall_block("cobble" if level < 3 else "stonebrick", "x", x, y, z, rng))
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                h.put(x, y, z, wall_block("cobble" if level < 3 else "stonebrick", "z", x, y, z, rng))
    if floors == 2:
        h.box(x0 + 1, 5, z0 + 1, x1 - 1, 5, z1 - 1, PLANK)
    h.box(x0 + 1, top + 1, z0 + 1, x1 - 1, top + 1, z1 - 1, slab("top"))   # the flat roof to stand on
    for x in range(x0, x1 + 1):  # the battlements
        for z in (z0, z1):
            h.put(x, top + 1, z, stone)
            if (x - x0) % 2 == 0:
                h.put(x, top + 2, z, stone)
    for z in range(z0 + 1, z1):
        for x in (x0, x1):
            h.put(x, top + 1, z, stone)
            if (z - z0) % 2 == 0:
                h.put(x, top + 2, z, stone)
    cx = (x0 + x1) // 2
    h.put(cx, 2, z1, door("north", "lower", "left"))
    h.put(cx, 3, z1, door("north", "upper", "left"))
    h.put(cx, 1, z1 + 1, cst("north"))
    for y in (3, 7) if floors == 2 else (3,):  # arrow slits
        h.put(x0, y, cx, pane("z"))
        h.put(x1, y, cx, pane("z"))
        h.put(cx, y, z0, pane("x"))
    for y in range(2, top + 2):  # the ladder up to the roof, through a hatch
        h.put(x0 + 1, y, z0 + 1, ladder("east") if y <= top else ladder("east"))
    room = Room(h, x0 + 1, z0 + 1, x1 - 1, z1 - 1, 2, rng)
    room.keep(x0 + 1, z0 + 1)
    room.keep(x0 + 2, z0 + 1)
    room.keep(cx, z1 - 1)
    for it in ["smithing", "grind", "chest", "chest", "barrel", "barrel"] + (["anvil", "fletching"] if level >= 2 else []):
        room.place(it)
    room.torches(2)
    if floors == 2:
        up = Room(h, x0 + 1, z0 + 1, x1 - 1, z1 - 1, 6, random.Random(level + 4))
        up.keep(x0 + 1, z0 + 1)
        up.keep(x0 + 2, z0 + 1)
        for it in ["chest", "chest", "chest", "target", "barrel", "barrel"]:
            up.place(it)
        up.torches(2)
    clear(h, 0, W + 1, 0, W + 1, 1, 15)
    trim_top(h)
    return h


# ---------- what sets the stations apart: the hamlet's makeshift, the wealthy village's establishment ----------

def sails(h, cx, hy, zs, R, cloth=True):
    """Four arms of timber on a hub, their cloth beside them, turned to the wind."""
    h.put(cx, hy, zs, log("z"))
    for k in range(1, R + 1):
        h.put(cx, hy + k, zs, fence())
        h.put(cx, hy - k, zs, fence())
        h.put(cx + k, hy, zs, fence(e=k < R, w=True))
        h.put(cx - k, hy, zs, fence(e=True, w=k < R))
        if cloth and k >= 2:
            for (xx, yy) in ((cx + 1, hy + k), (cx - 1, hy - k), (cx + k, hy - 1), (cx - k, hy + 1)):
                h.put(xx, yy, zs, "minecraft:white_wool")


def post_mill():
    """The hamlet's mill: a box of boards up on a post and its trestle, turned whole to the wind; a ladder of steps."""
    R = 5
    w = 2 * R + 3
    cx = w // 2
    x0, x1, z0, z1 = cx - 2, cx + 2, 1, 5
    h = House(w, 12, 20)
    h.box(x0 - 1, 0, z0 - 1, x1 + 1, 0, z1 + 1, "minecraft:gravel")
    # the trestle: the post up the middle, its four legs out on stones
    wcol(h, cx, 3, 1, 4, log())
    for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        h.put(x, 1, z, COB)          # each leg on its stone, up to the floor of the body (it hung in the air on its post)
        wcol(h, x, z, 2, 4, log())
    for (x, z) in ((cx - 1, 3), (cx + 1, 3), (cx, 2), (cx, 4)):
        h.put(x, 3, z, fence(e=x < cx, w=x > cx, n=z > 3, s=z < 3))
    # the body on top of it
    h.box(x0, 5, z0, x1, 5, z1, PLANK)
    for y in range(6, 10):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                h.put(x, y, z, log() if x in (x0, x1) else PLANK)
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                h.put(x, y, z, PLANK)
    plate(h, x0, z0, x1, z1, 10)
    h.put(cx, 6, z1, door("north", "lower", "left"))
    h.put(cx, 7, z1, door("north", "upper", "left"))
    for k in range(1, 6):  # the steps up to it
        h.put(cx, 6 - k, z1 + k, st("north"))
        if k > 1:
            h.put(cx, 5 - k, z1 + k, PLANK) if 5 - k >= 1 else None
    h.put(x0 + 1, 6, z0 + 1, "minecraft:grindstone[face=floor,facing=south]")
    h.put(x1 - 1, 6, z0 + 1, "minecraft:hay_block[axis=y]")
    h.put(x1 - 1, 6, z0 + 2, BARREL_UP)
    h.put(x0 + 1, 8, z0 + 2, wtorch("east"))
    clear(h, 0, w - 1, 0, 11, 1, 19)
    roof_x(h, x0 - 1, x1 + 1, x0, x1, z1 + 2, 10, "oak", PLANK) if False else None
    for i in range(0, 4):
        zn, zs_, y = z0 - 1 + i, z1 + 1 - i, 10 + i
        if zn > zs_:
            break
        for x in range(x0 - 1, x1 + 2):
            if zn == zs_:
                h.roof(x, y, zn, slab("bottom"))
            else:
                h.roof(x, y, zn, st("south"))
                h.roof(x, y, zs_, st("north"))
        for z in range(zn + 1, zs_):
            for gx in (x0, x1):
                if h.cells.get((gx, y, z), AIR) == AIR:
                    h.put(gx, y, z, PLANK)
    sails(h, cx, 9, z0 - 1, R, cloth=False) if False else None
    # (the sails on the back of the body, away from the door and its steps)
    h.put(cx, 8, z0 - 1, log("z")) if False else None
    sails(h, cx, 8, 0, 4, cloth=True)
    trim_top(h)
    return h


def mill_gallery(h):
    """The great mill's stage: a gallery round the tower half way up, railed, for the miller to reach the sails."""
    xs = [x for (x, y, z), b in h.cells.items() if y == 6 and b != AIR and "log" not in b]
    zs = [z for (x, y, z), b in h.cells.items() if y == 6 and b != AIR and "log" not in b]
    x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            if x0 <= x <= x1 and z0 <= z <= z1:
                continue
            if h.cells.get((x, 8, z), AIR) == AIR:
                h.put(x, 8, z, slab("top"))
            if h.cells.get((x, 9, z), AIR) == AIR and (x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1)):
                h.put(x, 9, z, fence(e=x < x1 + 1 and z in (z0 - 1, z1 + 1), w=x > x0 - 1 and z in (z0 - 1, z1 + 1),
                                     n=z > z0 - 1 and x in (x0 - 1, x1 + 1), s=z < z1 + 1 and x in (x0 - 1, x1 + 1)))
    for (x, z) in ((x0 - 1, z0 - 1), (x1 + 1, z0 - 1), (x0 - 1, z1 + 1), (x1 + 1, z1 + 1)):
        for y in range(1, 8):  # its struts down to the ground
            if h.cells.get((x, y, z), AIR) == AIR:
                h.put(x, y, z, fence())


def lean_forge():
    """The hamlet's smith: a hearth of stone under a lean-to roof on two posts, the anvil out in the open."""
    h = House(7, 7, 10)
    h.box(1, 0, 1, 5, 0, 5, COB)
    h.box(1, 1, 1, 5, 1, 4, "minecraft:cobblestone")
    for x in range(1, 6):
        for y in range(2, 5):  # the back wall up to the roof
            h.put(x, y, 1, COB)
    h.put(3, 2, 2, furnace("south"))
    h.put(2, 2, 2, COB)
    h.put(4, 2, 2, COB)
    for x in (2, 3, 4):  # the hood over the fire, under the roof
        h.put(x, 3, 2, cst("north", "top"))
    wcol(h, 1, 3, 2, 3, log())
    wcol(h, 5, 3, 2, 3, log())
    h.put(3, 2, 4, "minecraft:anvil[facing=east]")
    h.put(5, 2, 4, "minecraft:water_cauldron[level=3]")
    h.put(1, 2, 2, "minecraft:grindstone[face=floor,facing=south]")
    h.put(5, 2, 2, chest("south"))
    clear(h, 0, 6, 0, 6, 1, 9)
    for x in range(0, 7):  # the lean-to: high at the hearth, a half block down over the anvil, low over the posts, whole
        h.roof(x, 5, 0, slab("bottom", "spruce"))
        h.roof(x, 5, 1, slab("bottom", "spruce"))
        h.roof(x, 4, 2, slab("top", "spruce"))
        h.roof(x, 4, 3, slab("bottom", "spruce"))
        h.roof(x, 4, 4, slab("bottom", "spruce"))
        h.roof(x, 4, 5, slab("bottom", "spruce"))
    for y in range(2, 8):
        h.put(3, y, 0, COB)
    h.put(3, 8, 0, cap(COB))
    trim_top(h)
    return h


def crib():
    """The hamlet's store: a little log crib, notched corners, a door, the sacks and barrels inside."""
    h = House(7, 7, 12)
    x0, x1, z0, z1 = 1, 5, 1, 5
    h.box(x0, 0, z0, x1, 0, z1, COB)
    frame(h, x0, z0, x1, z1, 1, 1, COB, COB)
    h.box(x0 + 1, 1, z0 + 1, x1 - 1, 1, z1 - 1, PLANK)
    for y in range(2, 5):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                h.put(x, y, z, log("x") if y % 2 == 0 or x in (x0, x1) else log("x"))
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                h.put(x, y, z, log("z"))
        for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
            h.put(x, y, z, log("x") if y % 2 == 0 else log("z"))  # the notched corners, crossing course by course
    h.put(3, 2, z1, door("north", "lower", "left"))
    h.put(3, 3, z1, door("north", "upper", "left"))
    h.put(3, 1, z1 + 1, cst("north"))
    for (x, z, b) in ((2, 2, BARREL_UP), (3, 2, chest("south")), (4, 2, BARREL_UP), (4, 3, "minecraft:hay_block[axis=y]"),
                      (2, 3, "minecraft:barrel[facing=east,open=false]")):
        h.put(x, 2, z, b)
    h.put(3, 4, 2, wtorch("south"))
    clear(h, 0, 6, 0, 6, 1, 11)
    roof_x(h, 0, 6, x0, x1, 7, 5, "spruce", log("x"))
    trim_top(h)
    return h


def saw_pit():
    """The hamlet's sawyers: a pit with a log across it under a roof on four posts, the logs lying out in the open."""
    h = House(11, 9, 10)
    h.box(0, 0, 0, 10, 0, 8, "minecraft:coarse_dirt")
    cx, cz = 5, 4
    for x in range(cx - 2, cx + 3):
        h.put(x, 0, cz, AIR)  # the pit
    h.put(cx - 2, 0, cz, "minecraft:coarse_dirt")
    for x in range(cx - 3, cx + 4):
        h.put(x, 1, cz, "minecraft:oak_wood[axis=x]") if x in (cx - 3, cx + 3) else None
    for x in range(cx - 2, cx + 3):
        h.put(x, 2, cz, "minecraft:oak_log[axis=x]") if False else None
    h.put(cx, 1, cz, "minecraft:stonecutter[facing=south]")
    for (x, z) in ((cx - 3, cz - 2), (cx + 3, cz - 2), (cx - 3, cz + 2), (cx + 3, cz + 2)):
        wcol(h, x, z, 1, 3, log())
    clear(h, 0, 10, 0, 8, 1, 9)
    for x in range(cx - 4, cx + 5):
        for z in range(cz - 3, cz + 4):
            h.roof(x, 4, z, slab("bottom", "spruce"))
    for x in range(cx - 4, cx + 5):
        h.roof(x, 4, cz, rstairs("spruce", "south")) if False else None
    # the logs waiting, the boards stacked, in the open
    for x in range(0, 4):
        h.put(x, 1, 7, "minecraft:oak_wood[axis=x]")
        h.put(x, 1, 8, "minecraft:oak_wood[axis=x]")
        if x < 3:
            h.put(x, 2, 7, "minecraft:oak_wood[axis=x]")
    for x in range(7, 10):
        h.put(x, 1, 8, PLANK)
        h.put(x, 2, 8, slab("bottom"))
    h.put(9, 1, 0, CRAFT)
    h.put(9, 1, 1, chest("west"))
    trim_top(h)
    return h


def drying_racks(h, x0, x1, z, y=2):
    """Racks of boards drying in the air: posts and rails with planks laid across."""
    for x in range(x0, x1 + 1):
        h.put(x, y, z, fence(e=x < x1, w=x > x0))
        h.put(x, y + 1, z, slab("bottom") if x % 2 else fence(e=x < x1, w=x > x0))


def turrets(h, size, top, stone):
    """Corner turrets on a keep: each corner raised two courses more, its own battlement."""
    x0, x1, z0, z1 = 1, size, 1, size
    for (cx, cz) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        for dx in (0, 1 if cx == x0 else -1):
            for dz in (0, 1 if cz == z0 else -1):
                for y in range(top + 1, top + 4):
                    h.put(cx + dx, y, cz + dz, stone)
        h.put(cx, top + 4, cz, stone)


def walled_yard(h, x0, x1, z0, z1, stone):
    """A stone wall round the drill yard, a gate in it."""
    wx = "minecraft:cobblestone_wall[east=low,north=none,south=none,up=true,waterlogged=false,west=low]"
    wz = "minecraft:cobblestone_wall[east=none,north=low,south=low,up=true,waterlogged=false,west=none]"
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            h.put(x, 2, z, stone)
            h.put(x, 3, z, wx)
    for z in range(z0 + 1, z1):
        h.put(x1, 2, z, stone)
        h.put(x1, 3, z, wz)

def mill_at(level):
    if level == 1:
        return post_mill()
    h = windmill(level)
    if level == 3:
        mill_gallery(h)
    return h


def forge_at(level):
    return lean_forge() if level == 1 else forge_shed(level)


def store_at(level):
    if level == 1:
        return crib()
    h = barn(level)
    if level == 3:  # a lean-to along its side, and the loading yard before it
        W = 13
        for z in range(2, 9):
            for y in range(2, 4):
                h.put(W + 1, y, z, BARREL_UP if y == 2 and z % 2 else AIR)
    return h


def granary_at(level):
    return staddle_granary(level)


def lumber_at(level):
    if level == 1:
        return saw_pit()
    h = saw_shed(level)
    if level == 3:
        drying_racks(h, 2, 8, 0)
    return h


def mason_at(level):
    return mason_yard(level)


def garrison_at(level):
    h = garrison(level)
    if level == 3:
        W, D, YARD = 15, 7, 7
        walled_yard(h, W + 1, W + YARD, 1, D, SBRICK)
        h.put(W + 4, 2, D, AIR)
        h.put(W + 4, 3, D, AIR)
    return h


def armoury_at(level):
    h = keep(level)
    if level == 3:
        turrets(h, 9, 9, SBRICK)
    return h


SHAPED = {"storehouse": store_at, "granary": granary_at, "forge": forge_at, "armoury": armoury_at, "lumberyard": lumber_at,
          "masonry": mason_at, "mill": mill_at, "garrison": garrison_at}

CREDIT = "Settlements' own design, drawn after the MineColonies residences (github.com/ldtteam/minecolonies)"
STYLES = {"medievaloak": "oak", "medievalbirch": "birch", "medievaldarkoak": "dark_oak", "nordic": "spruce", "sandstone": "oak", "badlands": "oak"}
DESIGNS = {"dwelling1": hut, "dwelling2": cottage, "dwelling3": townhouse, "dwelling4": manor, "dwelling5": manor}
for n, spec in HOMES.items():
    DESIGNS[n] = (lambda n=n, spec=spec: build_home(n, spec))
    if n.startswith("dwelling4"):
        DESIGNS["dwelling5" + n[-1]] = (lambda n=n, spec=spec: build_home(n, spec))
for n, (m, wg, side) in LHOMES.items():
    DESIGNS[n] = (lambda n=n, m=m, wg=wg, side=side: lhome(n, m, wg, side))
    if n.startswith("dwelling4"):
        DESIGNS["dwelling5e"] = (lambda n=n, m=m, wg=wg, side=side: lhome(n, m, wg, side))
for lv in (1, 2, 3):
    DESIGNS[f"hall{lv}"] = (lambda lv=lv: hall(lv))
for kind, specs in WORKS.items():
    for lv, spec in enumerate(specs, 1):
        n = f"{WORK_FILE[kind]}{lv}"
        if WORK_FILE[kind] in SHAPED:  # a workplace built as what it is, not as a house
            DESIGNS[n] = (lambda lv=lv, f=SHAPED[WORK_FILE[kind]]: f(lv))
        else:
            DESIGNS[n] = (lambda n=n, spec=spec: build_home(n, spec))
index = []
made = {}
for name, make in DESIGNS.items():
    h = make()
    made[name] = h
    for style, wood in STYLES.items():
        j = h.write(name, wood, CREDIT, desert=style == "sandstone",
                    land="badlands" if style == "badlands" else "north" if style == "nordic" and not name.startswith(("dwelling4", "dwelling5", "hall3")) else None)
        os.makedirs(os.path.join(OUT, style), exist_ok=True)
        with open(os.path.join(OUT, style, name + ".json"), "w") as f:
            json.dump(j, f, separators=(",", ":"))
        index.append(f"{style}/{name}")
print("\n".join(index))
