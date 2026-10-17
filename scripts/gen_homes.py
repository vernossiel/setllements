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
KEEP_PROPS = ("_stairs", "_slab", "_wall", "_trapdoor")


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
        assert 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.d, (x, y, z, b)
        self.cells[(x, y, z)] = b

    def box(self, x0, y0, z0, x1, y1, z1, b):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.put(x, y, z, b)

    def write(self, name, wood, credit, desert=False):
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

    def table(self, length=1, axis="x", carpet="white"):
        """A table in the middle of the room, chairs along both sides of it."""
        cx, cz = (self.x0 + self.x1) // 2, (self.z0 + self.z1) // 2
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
    """The house's height cut down to what it uses."""
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
            if item == "table":
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


CREDIT = "Settlements' own design, drawn after the MineColonies residences (github.com/ldtteam/minecolonies)"
STYLES = {"medievaloak": "oak", "medievalbirch": "birch", "medievaldarkoak": "dark_oak", "nordic": "spruce", "sandstone": "oak"}
DESIGNS = {"dwelling1": hut, "dwelling2": cottage, "dwelling3": townhouse, "dwelling4": manor, "dwelling5": manor}
for n, spec in HOMES.items():
    DESIGNS[n] = (lambda n=n, spec=spec: build_home(n, spec))
    if n.startswith("dwelling4"):
        DESIGNS["dwelling5" + n[-1]] = (lambda n=n, spec=spec: build_home(n, spec))
for lv in (1, 2, 3):
    DESIGNS[f"hall{lv}"] = (lambda lv=lv: hall(lv))
index = []
made = {}
for name, make in DESIGNS.items():
    h = make()
    made[name] = h
    for style, wood in STYLES.items():
        j = h.write(name, wood, CREDIT, desert=style == "sandstone")
        os.makedirs(os.path.join(OUT, style), exist_ok=True)
        with open(os.path.join(OUT, style, name + ".json"), "w") as f:
            json.dump(j, f, separators=(",", ":"))
        index.append(f"{style}/{name}")
print("\n".join(index))
