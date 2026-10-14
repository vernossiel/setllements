package net.mca.settlements;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural building plans. A plan is an ordered list of steps in coordinates relative to the south-west
 * corner, y = 0 is the ground floor plane. Everything faces south (door on the +z side).
 */
public final class Blueprints {
    public enum Kind { FLOOR, WALL, PILLAR, GLASS, ROOF, DOOR, BED_FOOT, BED_HEAD, CHEST, STAIRS_N, STAIRS_S, LADDER, DIG, CLEAR }

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
        return l;
    }

    /** One more floor on top of an existing house of {@code floors} floors. */
    public static List<Step> addFloor(int w, int d, int floors) {
        List<Step> l = new ArrayList<>();
        level(l, w, d, floors, true);
        plane(l, w, d, (floors + 1) * LEVEL_HEIGHT, Kind.ROOF, -1);
        return l;
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
    }

    // ---------- storage ----------

    /** A big warehouse: log frame, stone walls, rows of chests along both long walls. */
    public static List<Step> storage() {
        int w = 11, d = 7;
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
        for (int x = 2; x <= w - 3; x += 2) {
            l.add(new Step(x, 1, 1, Kind.CHEST));
            l.add(new Step(x, 1, d - 2, Kind.CHEST));
        }
        plane(l, w, d, 5, Kind.ROOF, -1);
        return l;
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
        }
        return l;
    }
}
