# Usage: python3 scripts/gen_homes.py common/src/main/resources/assets/mca/settlements/templates
"""Settlements' own homes, one for each station of a village, written out as templates in the MineColonies format.

Layer 0 is the ground (the foundation goes into it), layer 1 the first above it (the floor), the door on the south
side (z max). Drawn in oak; written for each style with that style's timber, which the game swaps for the village's.
"""
import base64, gzip, json, os, struct, sys

OUT = sys.argv[1]
KEEP = 65535


class House:
    def __init__(self, w, d, h):
        self.w, self.d, self.h = w, d, h
        self.cells = {}

    def put(self, x, y, z, b):
        assert 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.d, (x, y, z, b)
        self.cells[(x, y, z)] = b

    def box(self, x0, y0, z0, x1, y1, z1, b):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.put(x, y, z, b)

    def write(self, name, wood, credit):
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
                    b = b.replace("oak", wood) if wood != "oak" else b
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
                h.put(x, y, zn, slab("bottom", ridge_mat))
            else:
                h.put(x, y, zn, st("south"))
                h.put(x, y, zs, st("north"))
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
    h.put(x1 + 1, top + 2, 5, "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")
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
    h.put(x1 + 1, top + 2, 6, "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")
    return h


CREDIT = "Settlements' own design, drawn after the MineColonies residences (github.com/ldtteam/minecolonies)"
STYLES = {"medievaloak": "oak", "medievalbirch": "birch", "medievaldarkoak": "dark_oak", "nordic": "spruce"}
DESIGNS = {"dwelling1": hut, "dwelling2": cottage, "dwelling3": townhouse, "dwelling4": manor, "dwelling5": manor}
index = []
for name, make in DESIGNS.items():
    h = make()
    for style, wood in STYLES.items():
        j = h.write(name, wood, CREDIT)
        os.makedirs(os.path.join(OUT, style), exist_ok=True)
        with open(os.path.join(OUT, style, name + ".json"), "w") as f:
            json.dump(j, f, separators=(",", ":"))
        index.append(f"{style}/{name}")
print("\n".join(index))
