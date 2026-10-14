package net.mca.settlements;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural building plans. A plan is an ordered list of steps in coordinates relative to the south-west
 * corner, y = 0 is the ground floor plane. Everything faces south (door on the +z side).
 */
public final class Blueprints {
    public enum Kind { FLOOR, WALL, PILLAR, GLASS, ROOF, DOOR, BED_FOOT, BED_HEAD, CHEST, STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W, DIG, CLEAR, TORCH, ANVIL, FURNACE, GRINDSTONE, SMITHING, ARMOR_STAND, FENCE, FARMLAND, WATER, CROP, LADDER, STONE, SMOKER, BARREL, CRAFT, PLATE, SEAT_W, SEAT_E, LECTERN, HALL_CHEST }

    public record Step(int dx, int dy, int dz, Kind kind) {
    }

    public static final int LEVEL_HEIGHT = 4;

    private Blueprints() {
    }

    // ---------- houses ----------

    /** A new house with the given number of floors. */
    public static List<Step> house(int w, int d, int floors) {
        List<Step> l = new ArrayList<>();
        plane(l, w, d, 0, Kind.FLOOR, -1);
        for (int level = 0; level < floors; level++) {
            level(l, w, d, level, level > 0);
        }
        plane(l, w, d, floors * LEVEL_HEIGHT, Kind.ROOF, -1);
        gable(l, w, d, floors * LEVEL_HEIGHT, false);
        return l;
    }

    /** One more floor on top of an existing house of {@code floors} floors. */
    public static List<Step> addFloor(int w, int d, int floors) {
        List<Step> l = new ArrayList<>();
        gable(l, w, d, floors * LEVEL_HEIGHT, true); // take the old pitched roof off first
        level(l, w, d, floors, true);
        plane(l, w, d, (floors + 1) * LEVEL_HEIGHT, Kind.ROOF, -1);
        gable(l, w, d, (floors + 1) * LEVEL_HEIGHT, false);
        return l;
    }

    /** A pitched roof along the depth axis with closed gable ends; {@code clear} produces the steps that remove it. */
    private static void gable(List<Step> l, int w, int d, int yBase, boolean clear) {
        for (int i = 0; ; i++) {
            int left = -1 + i, right = w - i;
            if (left > right) break;
            int y = yBase + 1 + i;
            for (int z = 0; z < d; z++) {
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

    private static void plane(List<Step> l, int w, int d, int y, Kind kind, int skipX) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) l.add(new Step(x, y, z, kind));
    }

    /** Walls, windows, pillars, bed and (for upper levels) the stairs coming up from below. */
    private static void level(List<Step> l, int w, int d, int level, boolean stairsFromBelow) {
        int base = level * LEVEL_HEIGHT;
        if (stairsFromBelow) {
            int prev = base - LEVEL_HEIGHT;
            // staircase from the floor below: four steps along the west wall, hole in the ceiling above them
            for (int i = 0; i < 3; i++) l.add(new Step(1, base, 1 + i, Kind.CLEAR));
            l.add(new Step(1, base, 4, Kind.CLEAR));
            for (int i = 0; i < 4; i++) l.add(new Step(1, prev + 1 + i, 1 + i, Kind.STAIRS_S));
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (!edge) continue;
                    boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    boolean door = level == 0 && x == w / 2 && z == d - 1 && y <= 2;
                    if (door) continue;
                    if (corner) l.add(new Step(x, base + y, z, Kind.PILLAR));
                    else if (y == 2 && ((x + z) % 2 == 0)) l.add(new Step(x, base + y, z, Kind.GLASS));
                    else l.add(new Step(x, base + y, z, Kind.WALL));
                }
            }
        }
        if (level == 0) l.add(new Step(w / 2, 1, d - 1, Kind.DOOR));
        int bx = w - 2;
        l.add(new Step(bx, base + 1, 1, Kind.BED_FOOT));
        l.add(new Step(bx, base + 1, 2, Kind.BED_HEAD));
        l.add(new Step(bx, base + 1, d - 2, Kind.TORCH));
    }

    // ---------- halls: storage, smithy, armory ----------

    /** A log-framed stone hall, 4 high, double door on the south side, windows on the north side. */
    private static List<Step> hall(int w, int d, List<Step> inside) {
        List<Step> l = new ArrayList<>();
        plane(l, w, d, 0, Kind.FLOOR, -1);
        for (int y = 1; y <= 4; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (!edge) continue;
                    boolean door = (x == w / 2 || x == w / 2 + 1) && z == d - 1 && y <= 3;
                    if (door) continue;
                    boolean post = (x % 5 == 0) && (z == 0 || z == d - 1 || x == 0 || x == w - 1);
                    if (post) l.add(new Step(x, y, z, Kind.PILLAR));
                    else if (y == 3 && x % 2 == 1 && z == 0) l.add(new Step(x, y, z, Kind.GLASS));
                    else l.add(new Step(x, y, z, Kind.WALL));
                }
            }
        }
        l.addAll(inside);
        l.add(new Step(1, 1, 1, Kind.TORCH));
        l.add(new Step(w - 2, 1, 1, Kind.TORCH));
        plane(l, w, d, 5, Kind.ROOF, -1);
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

    /** Town hall: the village stores in four chests along the back wall and a lectern for the plans. */
    public static List<Step> townhall() {
        List<Step> in = new ArrayList<>();
        for (int x = 2; x <= 8; x += 2) in.add(new Step(x, 1, 1, Kind.HALL_CHEST));
        in.add(new Step(5, 1, 4, Kind.LECTERN));
        return hall(11, 8, in);
    }

    /** A sheep pen: a fence ring with a wide gate in the middle of the front side. */
    public static List<Step> pen() {
        List<Step> l = new ArrayList<>();
        int n = PEN;
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                boolean border = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                boolean gate = z == n - 1 && Math.abs(x - n / 2) <= 1;
                if (border && !gate) l.add(new Step(x, 0, z, Kind.FENCE));
            }
        }
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
                    if (!gate) l.add(new Step(x, 0, z, Kind.FENCE));
                } else if (x == n / 2 && z == n / 2) {
                    l.add(new Step(x, -1, z, Kind.WATER));
                } else {
                    l.add(new Step(x, -1, z, Kind.FARMLAND));
                    l.add(new Step(x, 0, z, Kind.CROP));
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
                    boolean slit = y % 4 == 2 && (x == n / 2 || z == n / 2);
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

    /** Mine level k: a connector shaft and two new galleries, five blocks further along than the previous level. */
    public static List<Step> mineExtension(int k) {
        List<Step> l = new ArrayList<>();
        int depth = 28, bottom = -(depth - 1), z0 = depth - 1 + 5 * k;
        for (int z = depth - 1 + 5 * (k - 1) + 2; z <= z0 + 1; z++) {
            for (int x = 0; x < 2; x++) for (int h = 0; h < 3; h++) l.add(new Step(x, bottom + h, z, Kind.DIG));
        }
        for (int g = 0; g < 24; g++) {
            for (int zz = 0; zz < 2; zz++) {
                for (int h = 0; h < 3; h++) {
                    l.add(new Step(2 + g, bottom + h, z0 + zz, Kind.DIG));
                    l.add(new Step(-1 - g, bottom + h, z0 + zz, Kind.DIG));
                }
            }
            if (g % 5 == 4) {
                l.add(new Step(2 + g, bottom, z0, Kind.TORCH));
                l.add(new Step(-1 - g, bottom, z0, Kind.TORCH));
            }
        }
        return l;
    }

    // ---------- footprints and rotation ----------

    /** Footprint {w, d} of a project type before rotation. */
    public static int[] footprint(String type, int w, int d) {
        return switch (type) {
            case "storage" -> new int[]{11, 7};
            case "smithy" -> new int[]{10, 6};
            case "armory" -> new int[]{10, 7};
            case "tavern" -> new int[]{12, 9};
            case "townhall" -> new int[]{11, 8};
            case "pen" -> new int[]{PEN, PEN};
            case "well" -> new int[]{WELL, WELL};
            case "farm" -> new int[]{FARM, FARM};
            case "tower" -> new int[]{TOWER, TOWER};
            case "mine" -> new int[]{4, 4};
            default -> new int[]{w, d};
        };
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
    public static List<Step> mine() {
        List<Step> l = new ArrayList<>();
        int depth = 28;
        for (int i = 0; i < depth; i++) {
            for (int x = 0; x < 2; x++) {
                for (int h = 1; h <= 3; h++) l.add(new Step(x, -i + h, i, Kind.DIG));
                l.add(new Step(x, -i, i, Kind.DIG));
                l.add(new Step(x, -i, i, Kind.STAIRS_N));
            }
        }
        int bottom = -(depth - 1);
        // two galleries, 24 blocks each, 2 wide and 3 high
        for (int g = 0; g < 24; g++) {
            for (int zz = 0; zz < 2; zz++) {
                for (int h = 0; h < 3; h++) {
                    l.add(new Step(2 + g, bottom + h, depth - 1 + zz, Kind.DIG));
                    l.add(new Step(-1 - g, bottom + h, depth - 1 + zz, Kind.DIG));
                }
            }
            if (g % 5 == 4) {
                l.add(new Step(2 + g, bottom, depth - 1, Kind.TORCH));
                l.add(new Step(-1 - g, bottom, depth - 1, Kind.TORCH));
            }
        }
        return l;
    }
}
