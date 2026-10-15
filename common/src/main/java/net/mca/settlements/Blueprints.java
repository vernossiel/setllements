package net.mca.settlements;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural building plans. A plan is an ordered list of steps in coordinates relative to the south-west
 * corner, y = 0 is the ground floor plane. Everything faces south (door on the +z side).
 */
public final class Blueprints {
    public enum Kind { FLOOR, WALL, PILLAR, GLASS, ROOF, DOOR, BED_FOOT, BED_HEAD, CHEST, STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W, DIG, CLEAR, TORCH, ANVIL, FURNACE, GRINDSTONE, SMITHING, ARMOR_STAND, FENCE, FARMLAND, WATER, CROP, LADDER, STONE, SMOKER, BARREL, CRAFT, PLATE, SEAT_W, SEAT_E, LECTERN, HALL_CHEST, BOOKSHELF, CARPET, SIGN,
        BEAM_X, BEAM_Z, SHUTTER, POT, HEDGE, PATH, CHIMNEY, SMOKE, ACCENT, BELL, STEP, LANTERN,
        STONECUTTER, HAY, COMPOSTER, BARS, WOOL, GRAVE, SLAB, PUMPKIN, GATE, CAULDRON, FLOWER, MOUND, PEW, LOG_X, LOG_Z,
        TARGET, CHAIN,
        /** Straw (hay) roofing of a poor house; daub (packed mud) walls; roof stairs rising to the south / north; a clock on the wall. */
        THATCH, DAUB, RSTAIR_S, RSTAIR_N, CLOCK,
        /** Fittings that dress a room (see Decor): a shelf board on the wall, a wall banner, a framed thing, a prop (face picks which). */
        SHELF, BANNER, FRAME, PROP,
        /** A block of an imported template (see Templates): the face is its index in the template's palette; TPL_FIT for the fittings. */
        TPL, TPL_FIT }

    /** {@code face}: a horizontal direction index (0 south, 1 west, 2 north, 3 east) for blocks that face somewhere, -1 otherwise. */
    public record Step(int dx, int dy, int dz, Kind kind, int face) {
        public Step(int dx, int dy, int dz, Kind kind) {
            this(dx, dy, dz, kind, -1);
        }
    }

    public static final int S = 0, W = 1, N = 2, E = 3;

    public static final int LEVEL_HEIGHT = 4;

    /** The chimney stands against the side wall at its back corner, where there is never a window or a shutter. */
    static final int CHIMNEY_Z = 0;

    private Blueprints() {
    }

    // ---------- houses ----------

    /** A new house with the given number of floors. */
    public static List<Step> house(int w, int d, int floors) {
        return house(w, d, floors, false);
    }

    /**
     * A timber-framed house on a stone footing: log posts at the corners and beams under every ceiling, windows with
     * shutters, an overhanging pitched roof in a darker wood, a chimney with smoke, a step and a path at the door,
     * a hedge along the front and a furnished room on every floor. {@code grand} is the town hall's version, with
     * stone corners.
     */
    static List<Step> house(int w, int d, int floors, boolean grand) {
        return shell(w, d, floors, grand, true, false, true);
    }

    /**
     * The common frame of houses and workshops. {@code furnished}: beds and furniture inside; {@code stoneGround}:
     * a ground floor of stone under the timber; {@code chimney}: a smoking chimney on the east side.
     */
    static List<Step> shell(int w, int d, int floors, boolean grand, boolean furnished, boolean stoneGround, boolean chimney) {
        List<Step> l = new ArrayList<>();
        // footing: stone under the walls, planks inside
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                l.add(new Step(x, 0, z, edge ? (grand ? Kind.ACCENT : Kind.STONE) : Kind.FLOOR));
            }
        }
        for (int level = 0; level < floors; level++) level(l, w, d, level, level > 0, grand, furnished, stoneGround);
        int top = floors * LEVEL_HEIGHT;
        plane(l, w, d, top, Kind.ROOF, -1);
        gable(l, w, d, top, false);
        outside(l, w, d, top, grand, chimney);
        return l;
    }

    /** One more floor on top of an existing house of {@code floors} floors. */
    public static List<Step> addFloor(int w, int d, int floors) {
        return addFloor(w, d, floors, false);
    }

    static List<Step> addFloor(int w, int d, int floors, boolean grand) {
        List<Step> l = new ArrayList<>();
        gable(l, w, d, floors * LEVEL_HEIGHT, true); // take the old pitched roof off first
        int oldTop = floors * LEVEL_HEIGHT, chimneyTop = oldTop + 2 + w / 2;
        for (int y = oldTop + 1; y <= chimneyTop + 1; y++) {
            l.add(new Step(w, y, d / 2, Kind.CLEAR)); // and the old chimney top (older houses had it mid-wall)
            l.add(new Step(w, y, CHIMNEY_Z, Kind.CLEAR));
        }
        level(l, w, d, floors, true, grand, true, false);
        int top = (floors + 1) * LEVEL_HEIGHT;
        plane(l, w, d, top, Kind.ROOF, -1);
        gable(l, w, d, top, false);
        for (int y = 1; y <= top + 2 + w / 2; y++) l.add(new Step(w, y, CHIMNEY_Z, Kind.CHIMNEY));
        l.add(new Step(w, top + 3 + w / 2, CHIMNEY_Z, Kind.SMOKE));
        return l;
    }

    /** A pitched roof along the depth axis, overhanging one block front and back; {@code clear} removes it. */
    static void gable(List<Step> l, int w, int d, int yBase, boolean clear) {
        for (int i = 0; ; i++) {
            int left = -1 + i, right = w - i;
            if (left > right) break;
            int y = yBase + 1 + i;
            for (int z = -1; z <= d; z++) {
                if (left < right) {
                    l.add(new Step(left, y, z, clear ? Kind.CLEAR : Kind.STAIRS_E));
                    l.add(new Step(right, y, z, clear ? Kind.CLEAR : Kind.STAIRS_W));
                } else {
                    l.add(new Step(left, y, z, clear ? Kind.CLEAR : Kind.ROOF));
                }
            }
            for (int x = left + 1; x <= right - 1; x++) {
                l.add(new Step(x, y, 0, clear ? Kind.CLEAR : Kind.WALL));
                l.add(new Step(x, y, d - 1, clear ? Kind.CLEAR : Kind.WALL));
            }
        }
    }

    static void plane(List<Step> l, int w, int d, int y, Kind kind, int skipX) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) l.add(new Step(x, y, z, kind));
    }

    static boolean window(int x, int z, int w, int d) {
        int door = w / 2;
        if (z == d - 1) return x == 1 || x == w - 2 || (w >= 9 && (x == door - 2 || x == door + 2));
        if (z == 0) return x == w / 2 || (w >= 8 && (x == 2 || x == w - 3));
        if (x == 0 || x == w - 1) return z == d / 2 || (d >= 8 && (z == 2 || z == d - 3));
        return false;
    }

    /** Walls with windows and shutters, posts, a beam under the ceiling, furniture, stairs from the floor below. */
    static void level(List<Step> l, int w, int d, int level, boolean stairsFromBelow, boolean grand, boolean furnished, boolean stoneGround) {
        int base = level * LEVEL_HEIGHT;
        boolean stone = stoneGround && level == 0;
        if (level > 0) {
            // the floor between storeys: a timber band outside, planks inside (the stair hole is cut below)
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    l.add(new Step(x, base, z, corner ? Kind.PILLAR : edge ? ((z == 0 || z == d - 1) ? Kind.BEAM_X : Kind.BEAM_Z) : Kind.FLOOR));
                }
            }
        }
        if (stairsFromBelow) {
            int prev = base - LEVEL_HEIGHT;
            for (int i = 0; i < 3; i++) l.add(new Step(1, base, 1 + i, Kind.CLEAR));
            l.add(new Step(1, base, 4, Kind.CLEAR));
            for (int i = 0; i < 4; i++) l.add(new Step(1, prev + 1 + i, 1 + i, Kind.STAIRS_S));
        }
        int door = w / 2;
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (!edge) continue;
                    boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    if (level == 0 && x == door && z == d - 1 && y <= 2) continue;
                    if (corner) l.add(new Step(x, base + y, z, grand || stone ? Kind.ACCENT : Kind.PILLAR));
                    else if (y == 3) l.add(new Step(x, base + y, z, (z == 0 || z == d - 1) ? Kind.BEAM_X : Kind.BEAM_Z));
                    else if (y == 2 && window(x, z, w, d)) l.add(new Step(x, base + y, z, Kind.GLASS));
                    else l.add(new Step(x, base + y, z, stone ? Kind.STONE : Kind.WALL));
                }
            }
        }
        // shutters beside every window, outside
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!window(x, z, w, d)) continue;
                if (z == d - 1 || z == 0) {
                    int oz = z == 0 ? -1 : d, face = z == 0 ? N : S;
                    for (int sx : new int[]{x - 1, x + 1}) if (sx > 0 && sx < w - 1 && !window(sx, z, w, d) && !(level == 0 && sx == door && z == d - 1)) l.add(new Step(sx, base + 2, oz, Kind.SHUTTER, face));
                } else {
                    int ox = x == 0 ? -1 : w, face = x == 0 ? W : E;
                    for (int sz : new int[]{z - 1, z + 1}) if (sz > 0 && sz < d - 1 && !window(x, sz, w, d)) l.add(new Step(ox, base + 2, sz, Kind.SHUTTER, face));
                }
            }
        }
        if (level == 0) l.add(new Step(door, 1, d - 1, Kind.DOOR));
        if (!furnished) {
            l.add(new Step(1, base + 2, d - 2, Kind.TORCH));
            l.add(new Step(w - 2, base + 2, d - 2, Kind.TORCH));
            return;
        }
        // furniture: always a bed by the back wall; the rest differs from house to house (and floor to floor)
        int bx = w - 2;
        int style = Math.floorMod(w * 31 + d * 17 + level * 7, 3);
        int rug = Math.floorMod(w + d * 3 + level, 6);
        l.add(new Step(bx, base + 1, 1, Kind.BED_FOOT));
        l.add(new Step(bx, base + 1, 2, Kind.BED_HEAD));
        if (!grand) {
            switch (style) {
                case 0 -> { // a plain living room: chest, workbench, barrel with flowers
                    if (d >= 7) l.add(new Step(bx, base + 1, 3, Kind.CHEST));
                    l.add(new Step(bx, base + 1, d - 2, Kind.CRAFT));
                    if (w >= 7 && !stairsFromBelow) { // (x 2 stays free: the foot of the stairs, should a floor be added)
                        l.add(new Step(3, base + 1, 1, Kind.BARREL));
                        l.add(new Step(3, base + 2, 1, Kind.POT));
                    }
                    l.add(new Step(door, base + 1, d / 2, Kind.CARPET, rug));
                }
                case 1 -> { // a kitchen: smoker and barrel, a table with chairs, a bookshelf
                    // x 1..2 stay free beside the table (the stairs, and the way past them), so does the side by the bed
                    l.add(new Step(bx, base + 1, d >= 7 ? d - 2 : 3, Kind.SMOKER));
                    if (d >= 7 && w / 2 + 1 < bx) l.add(new Step(w / 2 + 1, base + 1, 1, Kind.BOOKSHELF));
                    if (!stairsFromBelow && w >= 7) l.add(new Step(3, base + 1, 1, Kind.BARREL));
                    int tz = d >= 6 ? 3 : 2; // a free row behind the table
                    if (w >= 7) {
                        l.add(new Step(w / 2, base + 1, tz, Kind.FENCE));
                        l.add(new Step(w / 2, base + 2, tz, Kind.PLATE));
                        if (w / 2 - 1 > 2) l.add(new Step(w / 2 - 1, base + 1, tz, Kind.SEAT_W));
                        if (w / 2 + 1 < bx) l.add(new Step(w / 2 + 1, base + 1, tz, Kind.SEAT_E));
                        if (d >= 7) l.add(new Step(w / 2, base + 1, tz + 1, Kind.PEW, N)); // a stool at the front of the table
                    }
                }
                default -> { // a store room: chests and barrels, a lantern from the ceiling
                    if (d >= 7) {
                        l.add(new Step(bx, base + 1, 3, Kind.CHEST));
                        l.add(new Step(bx, base + 1, 4, Kind.BARREL));
                        l.add(new Step(bx, base + 2, 4, Kind.POT));
                    }
                    l.add(new Step(bx, base + 1, d - 2, Kind.CRAFT));
                    if (!stairsFromBelow && w >= 7) {
                        l.add(new Step(3, base + 1, 1, Kind.CHEST));
                        l.add(new Step(4, base + 1, 1, Kind.BARREL));
                    }
                    l.add(new Step(door, base + 1, d / 2, Kind.CARPET, rug));
                    l.add(new Step(door, base + 1, d / 2 - 1, Kind.CARPET, rug));
                }
            }
        }
        l.add(new Step(bx, base + 2, d - 2, Kind.TORCH));
        if (level > 0 || style == 2) l.add(new Step(w / 2, base + 3, d / 2, Kind.LANTERN));
    }

    /** The outside: chimney with smoke, door step and path, lights by the door, a hedge and flowers along the front. */
    static void outside(List<Step> l, int w, int d, int top, boolean grand, boolean chimney) {
        int door = w / 2;
        int chimneyTop = top + 2 + w / 2;
        if (chimney) {
            for (int y = 0; y <= chimneyTop; y++) l.add(new Step(w, y, CHIMNEY_Z, Kind.CHIMNEY));
            l.add(new Step(w, chimneyTop + 1, CHIMNEY_Z, Kind.SMOKE));
        }
        l.add(new Step(door, 0, d, Kind.STEP, N));
        // a little roof over the door, and round windows in the gables
        for (int x = door - 1; x <= door + 1; x++) l.add(new Step(x, 3, d, Kind.SLAB));
        if (w >= 6) {
            l.add(new Step(w / 2, top + 2, d - 1, Kind.GLASS));
            l.add(new Step(w / 2, top + 2, 0, Kind.GLASS));
        }
        for (int z = d + 1; z <= d + 3; z++) l.add(new Step(door, -1, z, Kind.PATH));
        l.add(new Step(door - 1, 2, d, Kind.TORCH));
        l.add(new Step(door + 1, 2, d, Kind.TORCH));
        for (int x = 0; x < w; x++) {
            if (Math.abs(x - door) <= 1) continue;
            boolean flower = x == 0 || x == w - 1 || x == door - 2 || x == door + 2;
            l.add(new Step(x, 0, d, flower ? Kind.POT : Kind.HEDGE));
        }
        if (grand) l.add(new Step(door + 2, 0, d + 1, Kind.BELL));
    }

    // ---------- halls: storage, smithy, armory ----------

    /**
     * A workshop hall: stone ground floor under a timber gable, a door at the front, windows with shutters, a chimney.
     * {@code inside}: the trade's furniture.
     */
    private static List<Step> hall(int w, int d, List<Step> inside) {
        List<Step> l = shell(w, d, 1, false, false, true, true);
        l.addAll(inside);
        return l;
    }

    /** A big warehouse with rows of chests along both long walls. */
    public static List<Step> storage() {
        int w = 11, d = 7;
        List<Step> in = new ArrayList<>();
        for (int x = 2; x <= w - 3; x += 2) {
            in.add(new Step(x, 1, 1, Kind.CHEST));
            in.add(new Step(x, 1, d - 2, Kind.CHEST));
        }
        return hall(w, d, in);
    }

    public static final int HALL_W = 9, HALL_D = 7;

    /**
     * The town hall: a grand house with stone corners, the village bell at the door, the stores (two hall chests),
     * the elder's lectern, a meeting table with seats, bookshelves and a rug.
     */
    public static List<Step> townhall() {
        List<Step> l = house(HALL_W, HALL_D, 1, true);
        l.add(new Step(3, 1, 1, Kind.HALL_CHEST)); // (x 2 free: the foot of the stairs when a floor is added)
        l.add(new Step(4, 1, 1, Kind.HALL_CHEST));
        l.add(new Step(5, 1, 1, Kind.BOOKSHELF));
        l.add(new Step(6, 1, 1, Kind.BOOKSHELF));
        l.add(new Step(4, 1, 3, Kind.LECTERN));
        l.add(new Step(2, 1, 4, Kind.FENCE));
        l.add(new Step(2, 2, 4, Kind.PLATE));
        l.add(new Step(1, 1, 4, Kind.SEAT_W));
        l.add(new Step(3, 1, 4, Kind.SEAT_E));
        for (int x = 4; x <= 6; x++) l.add(new Step(x, 1, 4, Kind.CARPET));
        l.add(new Step(1, 3, 3, Kind.LANTERN));
        return l;
    }

    /** One more floor for the hall, furnished: chests, bookshelves and carpets, as grand as the village can afford. */
    public static List<Step> hallUpgrade(int w, int d, int floors) {
        List<Step> l = addFloor(w, d, floors, true);
        int base = floors * LEVEL_HEIGHT;
        for (int x = 3; x <= 5; x++) l.add(new Step(x, base + 1, 1, Kind.HALL_CHEST)); // (x 2: the foot of the next stairs)
        for (int x = 2; x <= w - 3; x++) l.add(new Step(x, base + 1, d - 2, Kind.BOOKSHELF));
        for (int x = 3; x <= w - 4; x++) for (int z = 3; z <= 4; z++) l.add(new Step(x, base + 1, z, Kind.CARPET));
        return l;
    }

    /** A sheep pen: a fence ring with a wide gate in the middle of the front side. */
    public static List<Step> pen() {
        // a paddock: log posts with rails between, a gate in the front, at the back a lean-to shelter with hay under
        // it, a water trough beside it, a path out of the gate
        List<Step> l = new ArrayList<>();
        int n = PEN;
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                boolean border = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                if (!border) continue;
                boolean post = (x == 0 || x == n - 1 || x == n / 2) && (z == 0 || z == n - 1 || z == n / 2) && !(z == n - 1 && x == n / 2);
                boolean gate = z == n - 1 && x == n / 2;
                l.add(new Step(x, 0, z, gate ? Kind.GATE : post ? Kind.PILLAR : Kind.FENCE, gate ? S : -1));
                if (post && !(z == 0 && x <= 4)) l.add(new Step(x, 1, z, Kind.TORCH));
            }
        }
        // the shelter in the back corner
        for (int y = 0; y <= 2; y++) {
            l.add(new Step(0, y, 0, Kind.PILLAR));
            l.add(new Step(4, y, 0, Kind.PILLAR));
            l.add(new Step(4, y, 3, Kind.PILLAR));
            l.add(new Step(0, y, 3, Kind.PILLAR));
        }
        for (int x = 1; x <= 3; x++) for (int y = 0; y <= 1; y++) l.add(new Step(x, y, 0, Kind.WALL));
        for (int x = -1; x <= 5; x++) for (int z = -1; z <= 4; z++) l.add(new Step(x, 3, z, Kind.SLAB));
        l.add(new Step(1, 0, 1, Kind.HAY));
        l.add(new Step(2, 0, 1, Kind.HAY));
        l.add(new Step(1, 1, 1, Kind.HAY));
        l.add(new Step(3, 0, 1, Kind.COMPOSTER));
        // the trough
        l.add(new Step(7, -1, 1, Kind.WATER));
        l.add(new Step(8, -1, 1, Kind.WATER));
        // out of the gate
        for (int z = n; z <= n + 1; z++) l.add(new Step(n / 2, -1, z, Kind.PATH));
        return l;
    }

    /** Tavern: kitchen with a smoker, a barrel and a crafting table at the back, tables with chairs in the hall. */
    public static List<Step> tavern() {
        List<Step> in = new ArrayList<>();
        in.add(new Step(2, 1, 1, Kind.SMOKER));
        in.add(new Step(3, 1, 1, Kind.BARREL));
        in.add(new Step(4, 1, 1, Kind.CRAFT));
        for (int[] table : new int[][]{{3, 5}, {8, 5}, {5, 3}}) {
            in.add(new Step(table[0], 1, table[1], Kind.FENCE));
            in.add(new Step(table[0], 2, table[1], Kind.PLATE));
            in.add(new Step(table[0] - 1, 1, table[1], Kind.SEAT_W));
            in.add(new Step(table[0] + 1, 1, table[1], Kind.SEAT_E));
        }
        return hall(12, 9, in);
    }

    /** Smithy: anvil, furnace, grindstone and smithing table. Needs iron and an established village. */
    public static List<Step> smithy() {
        List<Step> in = new ArrayList<>();
        in.add(new Step(2, 1, 2, Kind.ANVIL));
        in.add(new Step(4, 1, 1, Kind.FURNACE));
        in.add(new Step(6, 1, 1, Kind.GRINDSTONE));
        in.add(new Step(7, 1, 1, Kind.SMITHING));
        return hall(10, 6, in);
    }

    /** Armory: chests for spare gear and armor stands to show it. */
    public static List<Step> armory() {
        List<Step> in = new ArrayList<>();
        for (int x = 2; x <= 7; x += 1) in.add(new Step(x, 1, 1, Kind.CHEST));
        for (int x = 2; x <= 6; x += 2) in.add(new Step(x, 1, 3, Kind.ARMOR_STAND));
        return hall(10, 7, in);
    }

    // ---------- well, farm, tower ----------

    public static final int WELL = 5, FARM = 9, TOWER = 5, PEN = 11;

    /** A village well with a cobblestone rim, four posts and a small roof. */
    public static List<Step> well() {
        List<Step> l = new ArrayList<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                if (x == 2 && z == 2) l.add(new Step(x, -1, z, Kind.WATER));
                else l.add(new Step(x, 0, z, Kind.WALL));
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int[] c : new int[][]{{1, 1}, {3, 1}, {1, 3}, {3, 3}}) l.add(new Step(c[0], y, c[1], Kind.FENCE));
        }
        for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) l.add(new Step(x, 4, z, Kind.ROOF));
        return l;
    }

    /** A fenced wheat field with a water source in the middle. */
    public static List<Step> farm() {
        List<Step> l = new ArrayList<>();
        int n = FARM;
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                boolean border = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                boolean gate = x == n / 2 && z == n - 1;
                if (border) {
                    l.add(new Step(x, 0, z, gate ? Kind.GATE : Kind.FENCE));
                } else if (x == n / 2 && z == n / 2) {
                    l.add(new Step(x, -1, z, Kind.WATER));
                } else {
                    l.add(new Step(x, -1, z, Kind.FARMLAND)); // the farmer sows it, not the builder
                }
            }
        }
        return l;
    }

    /** A cobblestone watchtower with a ladder inside and a parapet on top. */
    public static List<Step> tower() {
        List<Step> l = new ArrayList<>();
        int n = TOWER, h = 10;
        plane(l, n, n, 0, Kind.FLOOR, -1);
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < n; x++) {
                for (int z = 0; z < n; z++) {
                    boolean edge = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                    if (!edge) continue;
                    boolean door = x == n / 2 && z == n - 1 && y <= 2;
                    if (door) continue;
                    boolean slit = y % 4 == 2 && y < h && (x == n / 2 || z == n / 2);
                    l.add(new Step(x, y, z, slit ? Kind.GLASS : Kind.WALL));
                }
            }
        }
        l.add(new Step(n / 2, 1, n - 1, Kind.DOOR));
        for (int y = 1; y <= h; y++) l.add(new Step(2, y, 1, Kind.LADDER));
        // top deck with a hatch over the ladder, and a parapet
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                if (x == 2 && z == 1) continue;
                l.add(new Step(x, h, z, Kind.FLOOR));
            }
        }
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                boolean edge = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                if (edge) l.add(new Step(x, h + 1, z, (x + z) % 2 == 0 ? Kind.WALL : Kind.FENCE));
            }
        }
        l.add(new Step(1, h + 1, n - 2, Kind.TORCH));
        return l;
    }

    // ---------- wall ----------

    /**
     * A defensive wall around a square of side 2*half+1, following the terrain. {@code groundAt} gives the surface
     * height relative to the origin for a coordinate relative to the origin. Gates open in the middle of every side,
     * the corners carry watch posts.
     */
    public static List<Step> wall(int half, java.util.function.IntBinaryOperator groundAt) {
        List<Step> l = new ArrayList<>();
        int n = half * 2;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int side = 0; side < 4; side++) {
            for (int i = 0; i <= n; i++) {
                int x = side == 0 ? i : side == 1 ? n : side == 2 ? n - i : 0;
                int z = side == 0 ? 0 : side == 1 ? i : side == 2 ? n : n - i;
                if (!seen.add(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                boolean corner = (x == 0 || x == n) && (z == 0 || z == n);
                if (corner) continue; // towers come separately
                int g = groundAt.applyAsInt(x, z);
                int low = g;
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) low = Math.min(low, groundAt.applyAsInt(x + o[0], z + o[1]));
                int along = (side == 0 || side == 2) ? x : z;
                boolean gate = Math.abs(along - half) <= 2;
                for (int y = low - 1; y <= g + 3; y++) {
                    if (gate && y >= g && y <= g + 2) continue;
                    l.add(new Step(x, y, z, Kind.STONE));
                }
                if (along % 2 == 0 || gate) l.add(new Step(x, g + 4, z, Kind.STONE));
            }
        }
        for (int[] c : new int[][]{{0, 0}, {n, 0}, {0, n}, {n, n}}) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    int x = c[0] + dx, z = c[1] + dz;
                    int g = groundAt.applyAsInt(x, z);
                    int low = g;
                    for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) low = Math.min(low, groundAt.applyAsInt(x + o[0], z + o[1]));
                    int top = g + 8;
                    if (edge) {
                        for (int y = low - 1; y <= top; y++) l.add(new Step(x, y, z, Kind.STONE));
                        if ((dx + dz) % 2 == 0) l.add(new Step(x, top + 1, z, Kind.STONE));
                    } else {
                        l.add(new Step(x, top, z, Kind.STONE)); // deck
                    }
                }
            }
            int g = groundAt.applyAsInt(c[0], c[1]);
            l.add(new Step(c[0], g + 9, c[1], Kind.TORCH));
        }
        return l;
    }

    public static final int MINE_DEPTH = 16;

    /**
     * Mine level k (k >= 1): the main drift goes on eight blocks (2 wide, 3 high, a timber frame at its start, a
     * torch on the wall), and two side branches run off it left and right, 1 wide and 2 high, a torch at each end.
     */
    public static List<Step> mineExtension(int k) {
        List<Step> l = new ArrayList<>();
        int bottom = -MINE_DEPTH + 1, z0 = MINE_DEPTH + 5 + 8 * (k - 1);
        for (int z = z0; z < z0 + 8; z++) {
            for (int x = 0; x < 2; x++) for (int h = 0; h < 3; h++) l.add(new Step(x, bottom + h, z, Kind.DIG));
        }
        for (int h = 0; h < 3; h++) {
            l.add(new Step(-1, bottom + h, z0, Kind.PILLAR));
            l.add(new Step(2, bottom + h, z0, Kind.PILLAR));
        }
        for (int x = -1; x <= 2; x++) l.add(new Step(x, bottom + 3, z0, Kind.FLOOR));
        l.add(new Step(0, bottom + 1, z0 + 2, Kind.TORCH, W));
        int bz = z0 + 4;
        for (int i = 1; i <= 10; i++) {
            for (int h = 0; h < 2; h++) {
                l.add(new Step(-i, bottom + h, bz, Kind.DIG));
                l.add(new Step(1 + i, bottom + h, bz, Kind.DIG));
            }
        }
        l.add(new Step(-9, bottom + 1, bz, Kind.TORCH, N));
        l.add(new Step(10, bottom + 1, bz, Kind.TORCH, N));
        l.add(new Step(-4, bottom + 1, bz, Kind.TORCH, N));
        l.add(new Step(5, bottom + 1, bz, Kind.TORCH, N));
        return l;
    }

    // ---------- footprints and rotation ----------

    /** Footprint {w, d} of a project type before rotation. */
    public static int[] footprint(String type, int w, int d) {
        int[] variant = Variants.footprint(type);
        if (variant != null) return variant;
        return switch (type) {
            case "storage" -> new int[]{11, 7};
            case "smithy" -> new int[]{10, 6};
            case "armory" -> new int[]{10, 7};
            case "tavern" -> new int[]{12, 9};
            case "townhall" -> new int[]{HALL_W, HALL_D};
            case "pen" -> new int[]{PEN, PEN};
            case "well" -> new int[]{WELL, WELL};
            case "farm" -> new int[]{FARM, FARM};
            case "tower" -> new int[]{TOWER, TOWER};
            case "mine" -> new int[]{4, 4};
            case "board" -> new int[]{3, 2};
            case "sawmill" -> new int[]{Buildings.SAWMILL_W, Buildings.SAWMILL_D};
            case "charcoal" -> new int[]{6, 6};
            case "mason" -> new int[]{9, 6};
            case "granary" -> new int[]{8, 7};
            case "mill" -> new int[]{12, 8};
            case "barracks" -> new int[]{11, 10};
            case "jail" -> new int[]{10, 6};
            case "market" -> new int[]{11, 11};
            case "chapel" -> new int[]{14, 10};
            case "hut" -> new int[]{6, 6};
            case "annex" -> new int[]{12, 12};
            case "manor" -> new int[]{9, 8};
            case "longhouse" -> new int[]{5, 11};
            case "porch" -> new int[]{7, 9};
            case "tallhouse" -> new int[]{5, 6};
            case "twin" -> new int[]{9, 6};
            case "farmhouse" -> new int[]{13, 9};
            case "cottage" -> new int[]{9, 8};
            case "mansion" -> new int[]{16, 13};
            default -> new int[]{w, d};
        };
    }

    /** The notice board: a plank board on two posts, six signs facing the plaza. */
    public static List<Step> board() {
        List<Step> l = new ArrayList<>();
        l.add(new Step(0, 0, 0, Kind.FENCE));
        l.add(new Step(2, 0, 0, Kind.FENCE));
        for (int y = 1; y <= 2; y++) for (int x = 0; x < 3; x++) l.add(new Step(x, y, 0, Kind.FLOOR));
        for (int y = 2; y >= 1; y--) for (int x = 0; x < 3; x++) l.add(new Step(x, y, 1, Kind.SIGN));
        return l;
    }

    /** Rotates a plan coordinate clockwise by {@code rot} quarter turns inside a w x d footprint. */
    public static int[] rotate(int dx, int dz, int w, int d, int rot) {
        return switch (rot & 3) {
            case 1 -> new int[]{d - 1 - dz, dx};
            case 2 -> new int[]{w - 1 - dx, d - 1 - dz};
            case 3 -> new int[]{dz, w - 1 - dx};
            default -> new int[]{dx, dz};
        };
    }

    // ---------- mine ----------

    /** A real mine: a two-wide stair shaft down 28 blocks and two straight galleries at the bottom. */
    /**
     * The mine's opening: a stair shaft two wide going down {@link #MINE_DEPTH} blocks, the steps cut into the ground,
     * a timber frame and a wall torch every five steps, and a landing at the bottom with a chest and lights, from where
     * the main drift will run on (see {@link #mineExtension}).
     */
    public static List<Step> mine() {
        List<Step> l = new ArrayList<>();
        int depth = MINE_DEPTH;
        // the head of the shaft first: a timber shed over the stairs where they go into the ground, a lantern inside
        for (int z : new int[]{0, 3}) for (int x : new int[]{-1, 2}) for (int y = 0; y <= 2; y++) l.add(new Step(x, y, z, Kind.PILLAR));
        for (int z = 1; z <= 2; z++) {
            for (int y = 0; y <= 2; y++) {
                l.add(new Step(-1, y, z, Kind.WALL));
                l.add(new Step(2, y, z, Kind.WALL));
            }
        }
        for (int x = 0; x <= 1; x++) for (int y = 0; y <= 2; y++) l.add(new Step(x, y, 3, Kind.WALL));
        for (int x = -1; x <= 2; x++) for (int z = 0; z <= 3; z++) l.add(new Step(x, 3, z, Kind.ROOF));
        l.add(new Step(0, 2, 1, Kind.LANTERN));
        for (int i = 0; i < depth; i++) {
            for (int x = 0; x < 2; x++) {
                for (int h = 0; h <= 2; h++) l.add(new Step(x, -i + h, i, Kind.DIG));
                l.add(new Step(x, -i - 1, i, Kind.DIG));
                l.add(new Step(x, -i - 1, i, Kind.STAIRS_N));
            }
            if (i % 5 == 4) shaftFrame(l, i);
        }
        int floor = -depth, bottom = floor + 1;
        for (int x = -1; x <= 3; x++) {
            for (int z = depth; z <= depth + 4; z++) {
                for (int h = 0; h < 3; h++) l.add(new Step(x, bottom + h, z, Kind.DIG));
                l.add(new Step(x, floor, z, Kind.STONE));
            }
        }
        for (int[] c : new int[][]{{-1, depth}, {3, depth}, {-1, depth + 4}, {3, depth + 4}}) {
            for (int h = 0; h < 3; h++) l.add(new Step(c[0], bottom + h, c[1], Kind.PILLAR));
        }
        for (int x = -1; x <= 3; x++) l.add(new Step(x, bottom + 3, depth + 2, Kind.BEAM_X));
        l.add(new Step(3, bottom, depth + 1, Kind.CHEST));
        l.add(new Step(3, bottom, depth + 2, Kind.BARREL));
        l.add(new Step(-1, bottom + 1, depth + 2, Kind.TORCH, W));
        l.add(new Step(3, bottom + 1, depth + 3, Kind.TORCH, E));
        return l;
    }

    /** A timber frame across a gallery: posts on both walls and a beam of planks over the roof, against cave-ins. */
    private static void galleryFrame(List<Step> l, int x, int bottom, int z0) {
        for (int h = 0; h < 3; h++) {
            l.add(new Step(x, bottom + h, z0 - 1, Kind.PILLAR));
            l.add(new Step(x, bottom + h, z0 + 2, Kind.PILLAR));
        }
        for (int z = z0 - 1; z <= z0 + 2; z++) l.add(new Step(x, bottom + 3, z, Kind.FLOOR));
    }

    /** The same for the stair shaft: posts beside it and a plank beam above the stairs. */
    private static void shaftFrame(List<Step> l, int i) {
        for (int h = 0; h <= 2; h++) {
            l.add(new Step(-1, -i + h, i, Kind.PILLAR));
            l.add(new Step(2, -i + h, i, Kind.PILLAR));
        }
        for (int x = -1; x <= 2; x++) l.add(new Step(x, -i + 3, i, Kind.FLOOR));
        l.add(new Step(0, -i + 1, i, Kind.TORCH, W)); // on the post, lighting the stairs
    }
}
