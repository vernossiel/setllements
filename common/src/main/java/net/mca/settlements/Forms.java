package net.mca.settlements;

import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;

import java.util.ArrayList;
import java.util.List;

import static net.mca.settlements.Blueprints.E;
import static net.mca.settlements.Blueprints.N;
import static net.mca.settlements.Blueprints.S;
import static net.mca.settlements.Blueprints.W;
import static net.mca.settlements.Buildings.at;
import static net.mca.settlements.Buildings.lamp;

/**
 * Buildings after real ones, from the peasant's hovel to the merchant city. The poor build cruck halls: a thatched
 * roof that comes down nearly to the ground, walls of wattle and daub, an open hearth in the middle of the floor and
 * the smoke going out through the thatch. The well-to-do build a Wealden hall house: a tall open hall in the middle,
 * two-storey wings at both ends with their upper floors jutting out over the street, a tiled roof. A city builds tall
 * and narrow, storey jettied over storey on a crowded street, and its guildhall stands on an open arcade where the
 * market is held, with a clock tower over the square.
 */
public final class Forms {
    private Forms() {
    }

    static List<Step> plan(String type) {
        return switch (type) {
            case "townhall-2" -> meetingHall();
            case "townhall-3" -> wealdenHall(true);
            case "townhall-4" -> guildhall();
            case "cruck" -> cruck();
            case "wealden" -> wealdenHall(false);
            case "rowhouse" -> rowhouse();
            default -> Works.plan(type);
        };
    }

    static int[] footprint(String type) {
        return switch (type) {
            case "townhall-2" -> new int[]{7, 14};
            case "townhall-3", "wealden" -> new int[]{17, 12};
            case "townhall-4" -> new int[]{15, 17};
            case "cruck" -> new int[]{6, 9};
            case "rowhouse" -> new int[]{6, 11};
            default -> Works.footprint(type);
        };
    }

    // ---------- the parts ----------

    /**
     * A thatched roof with its ridge along z, laid in steps of straw from just above {@code yBase}, eaves overhanging
     * one block all round; the gable ends below the slopes are filled with {@code fill}.
     */
    static void thatch(List<Step> l, int x0, int z0, int w, int d, int yBase, Kind fill) {
        for (int i = 0; ; i++) {
            int left = x0 - 1 + i, right = x0 + w - i;
            if (left > right) break;
            int y = yBase + 1 + i;
            for (int z = z0 - 1; z <= z0 + d; z++) {
                at(l, left, y, z, Kind.THATCH);
                if (right != left) at(l, right, y, z, Kind.THATCH);
            }
            for (int x = left + 1; x <= right - 1; x++) {
                at(l, x, y, z0, fill);
                at(l, x, y, z0 + d - 1, fill);
            }
        }
    }

    /** A pitched roof with its ridge along x, in roof stairs sloping to the front and the back; the gable ends filled. */
    static void roofAlongX(List<Step> l, int x0, int z0, int w, int d, int yBase, Kind fill) {
        for (int i = 0; ; i++) {
            int back = z0 - 1 + i, front = z0 + d - i;
            if (back > front) break;
            int y = yBase + 1 + i;
            for (int x = x0 - 1; x <= x0 + w; x++) {
                if (back < front) {
                    at(l, x, y, back, Kind.RSTAIR_S);
                    at(l, x, y, front, Kind.RSTAIR_N);
                } else {
                    at(l, x, y, back, Kind.ROOF);
                }
            }
            for (int z = back + 1; z <= front - 1; z++) {
                at(l, x0, y, z, fill);
                at(l, x0 + w - 1, y, z, fill);
            }
        }
    }

    /**
     * Framed walls round a box: posts at the corners and every third block, a beam along the top, {@code fill}
     * between, windows between the posts on the middle rows, the door (if {@code doorX >= 0}) in the front wall.
     */
    static void walls(List<Step> l, int x0, int z0, int w, int d, int y0, int h, Kind fill, int doorX, boolean windows) {
        int x1 = x0 + w - 1, z1 = z0 + d - 1;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean xEdge = x == x0 || x == x1, zEdge = z == z0 || z == z1;
                    if (!xEdge && !zEdge) continue;
                    if (z == z1 && x == doorX && y <= y0 + 1) continue;
                    boolean corner = xEdge && zEdge;
                    boolean post = corner || (zEdge && (x - x0) % 3 == 0) || (xEdge && (z - z0) % 3 == 0);
                    if (post) at(l, x, y, z, Kind.PILLAR);
                    else if (y == y0 + h - 1) at(l, x, y, z, zEdge ? Kind.BEAM_X : Kind.BEAM_Z);
                    else if (windows && y > y0 && y < y0 + h - 1 && h >= 3 && !(z == z1 && Math.abs(x - doorX) <= 1)) at(l, x, y, z, Kind.GLASS);
                    else at(l, x, y, z, fill);
                }
            }
        }
        if (doorX >= 0) {
            at(l, doorX, y0, z1, Kind.DOOR);
            at(l, doorX, y0 + 1, z1, Kind.CLEAR);
        }
    }

    static void floor(List<Step> l, int x0, int z0, int w, int d, int y, Kind kind) {
        for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) at(l, x, y, z, kind);
    }

    // ---------- the poor: cruck halls ----------

    /**
     * A poor hamlet's meeting hall: a long cruck hall under a thatch that comes down to the ground, daub at the ends,
     * benches along the sides, the fire in the middle of the floor and its smoke out through the ridge, the elder's
     * lectern and the village's two chests at the far end.
     */
    static List<Step> meetingHall() {
        int w = 7, d = 11;
        List<Step> l = new ArrayList<>();
        floor(l, 0, 0, w, d, 0, Kind.FLOOR);
        for (int z = 0; z < d; z++) {
            for (int y = 1; y <= 2; y++) {
                at(l, 0, y, z, z % 3 == 0 ? Kind.PILLAR : Kind.DAUB);
                at(l, w - 1, y, z, z % 3 == 0 ? Kind.PILLAR : Kind.DAUB);
            }
        }
        for (int x = 1; x < w - 1; x++) {
            for (int y = 1; y <= 2; y++) {
                at(l, x, y, 0, Kind.DAUB);
                if (x != 3) at(l, x, y, d - 1, Kind.DAUB);
            }
        }
        thatch(l, 0, 0, w, d, 1, Kind.DAUB);
        at(l, 3, 1, d - 1, Kind.DOOR);
        at(l, 3, 2, d - 1, Kind.CLEAR);
        // the hearth in the middle of the floor, and the smoke hole above it
        at(l, 3, 0, 6, Kind.SMOKE);
        for (int[] c : new int[][]{{2, 6}, {4, 6}, {3, 5}, {3, 7}}) at(l, c[0], 0, c[1], Kind.STONE);
        at(l, 3, 6, 6, Kind.CLEAR);
        // the elder's place at the far end: the lectern, a village chest on either side, the way to them clear
        at(l, 3, 1, 1, Kind.LECTERN);
        at(l, 2, 1, 1, Kind.HALL_CHEST);
        at(l, 4, 1, 1, Kind.HALL_CHEST);
        // benches along the walls, facing the fire
        for (int z : new int[]{4, 5, 7, 8}) {
            at(l, 1, 1, z, Kind.PEW, E);
            at(l, 5, 1, z, Kind.PEW, W);
        }
        // light: torches on the posts
        for (int z : new int[]{3, 9}) {
            at(l, 1, 2, z, Kind.TORCH, W);
            at(l, 5, 2, z, Kind.TORCH, E);
        }
        // outside: the step, the path, a torch on a pole
        at(l, 3, 0, d, Kind.STEP, N);
        for (int z = d + 1; z <= d + 2; z++) at(l, 3, -1, z, Kind.PATH);
        at(l, 5, 0, d + 1, Kind.FENCE);
        at(l, 5, 1, d + 1, Kind.FENCE);
        at(l, 5, 2, d + 1, Kind.TORCH);
        return l;
    }

    /** A poor family's cruck house: thatch down to the ground, a daub front with the door, a bed, a barrel, a bench. */
    static List<Step> cruck() {
        int w = 5, d = 7;
        List<Step> l = new ArrayList<>();
        floor(l, 0, 0, w, d, 0, Kind.FLOOR);
        for (int z = 0; z < d; z++) {
            at(l, 0, 1, z, Kind.DAUB);
            at(l, w - 1, 1, z, Kind.DAUB);
        }
        for (int x = 1; x < w - 1; x++) {
            at(l, x, 1, 0, Kind.DAUB);
            if (x != 2) at(l, x, 1, d - 1, Kind.DAUB);
        }
        thatch(l, 0, 0, w, d, 0, Kind.DAUB);
        at(l, 2, 1, d - 1, Kind.DOOR);
        at(l, 2, 2, d - 1, Kind.CLEAR);
        at(l, 1, 1, 1, Kind.BED_FOOT);
        at(l, 1, 1, 2, Kind.BED_HEAD);
        at(l, 3, 1, 1, Kind.BARREL);
        at(l, 3, 1, 3, Kind.CRAFT);
        at(l, 1, 2, 3, Kind.TORCH, W);
        at(l, 2, 0, d, Kind.STEP, N);
        for (int z = d + 1; z <= d + 1; z++) at(l, 2, -1, z, Kind.PATH);
        // a woodpile by the wall
        at(l, w, 0, 2, Kind.LOG_Z);
        at(l, w, 0, 3, Kind.LOG_Z);
        return l;
    }

    // ---------- the well-to-do: the Wealden hall house ----------

    /**
     * A Wealden hall house: a tall open hall in the middle with tall windows, two-storey wings of stone and timber at
     * both ends whose upper floors jut out a block over the front, each wing under its own gable, the hall under a
     * roof running across between them. As the town hall ({@code hall}): the elder's lectern and the village chests,
     * a long table and a bell turret on the ridge; as a home: a great room, kitchen and bedrooms.
     */
    static List<Step> wealdenHall(boolean hall) {
        List<Step> l = new ArrayList<>();
        int d = 9;
        // footings
        floor(l, 0, 0, 17, d, 0, Kind.FLOOR);
        for (int x = 0; x < 17; x++) {
            at(l, x, 0, 0, Kind.STONE);
            at(l, x, 0, d - 1, Kind.STONE);
        }
        for (int z = 0; z < d; z++) {
            at(l, 0, 0, z, Kind.STONE);
            at(l, 16, 0, z, Kind.STONE);
        }
        for (int wx : new int[]{0, 12}) {
            // the wing: a stone ground floor, a jettied timber storey above, its own gable
            walls(l, wx, 0, 5, d, 1, 3, Kind.STONE, -1, true);
            floor(l, wx, 0, 5, d + 1, 4, Kind.FLOOR);
            for (int x = wx; x < wx + 5; x++) at(l, x, 4, d, Kind.BEAM_Z); // the joists jutting out under the jetty
            walls(l, wx, 0, 5, d + 1, 5, 3, Kind.WALL, -1, true);
            floor(l, wx, 0, 5, d + 1, 8, Kind.ROOF);
            Blueprints.gable(sub(l, wx, 0), 5, d + 1, 8, false);
            at(l, wx + 1, 4, 1, Kind.CLEAR); // the hatch of the ladder
            for (int y = 1; y <= 7; y++) if (y != 4) at(l, wx + 1, y, 0, y <= 3 ? Kind.STONE : Kind.WALL); // no window behind the ladder
            for (int y = 1; y <= 4; y++) at(l, wx + 1, y, 1, Kind.LADDER);
        }
        // the hall between the wings: one tall storey with tall windows
        for (int y = 1; y <= 7; y++) {
            for (int x = 5; x <= 11; x++) {
                for (int z : new int[]{0, d - 1}) {
                    if (z == d - 1 && x == 8 && y <= 2) continue;
                    boolean post = (x - 5) % 3 == 0;
                    boolean window = !post && y >= 2 && y <= 5;
                    at(l, x, y, z, post ? Kind.PILLAR : y == 7 ? Kind.BEAM_X : window ? Kind.GLASS : y == 1 ? Kind.STONE : Kind.WALL);
                }
            }
        }
        at(l, 8, 1, d - 1, Kind.DOOR);
        at(l, 8, 2, d - 1, Kind.CLEAR);
        for (int x = 5; x <= 11; x++) at(l, x, 7, 4, Kind.BEAM_X); // the tie beam across the hall
        roofAlongX(l, 5, 0, 7, d, 7, Kind.WALL);
        // the porch over the door
        for (int x = 7; x <= 9; x++) at(l, x, 3, d, Kind.SLAB);
        at(l, 7, 2, d, Kind.TORCH);
        at(l, 9, 2, d, Kind.TORCH);
        at(l, 8, 0, d, Kind.STEP, N);
        for (int z = d + 1; z <= d + 2; z++) at(l, 8, -1, z, Kind.PATH);
        // inside the hall
        at(l, 6, 6, 4, Kind.LANTERN);
        at(l, 10, 6, 4, Kind.LANTERN);
        for (int x = 6; x <= 10; x++) {
            at(l, x, 1, 4, Kind.FENCE);
            at(l, x, 2, 4, Kind.PLATE);
            at(l, x, 1, 3, Kind.PEW, S);
            at(l, x, 1, 5, Kind.PEW, N);
        }
        for (int x = 6; x <= 10; x++) at(l, x, 1, 7, Kind.CARPET, hall ? 0 : 3);
        if (hall) {
            at(l, 8, 1, 1, Kind.LECTERN);
            at(l, 6, 1, 1, Kind.HALL_CHEST);
            at(l, 10, 1, 1, Kind.HALL_CHEST);
            at(l, 7, 1, 1, Kind.BOOKSHELF);
            at(l, 9, 1, 1, Kind.BOOKSHELF);
            // the bell turret on the ridge
            int ry = 7 + 1 + 5; // the ridge of the hall roof (d = 9: the sixth step)
            for (int[] c : new int[][]{{7, 3}, {9, 3}, {7, 5}, {9, 5}}) for (int y = ry; y <= ry + 2; y++) at(l, c[0], y, c[1], Kind.FENCE);
            for (int x = 7; x <= 9; x++) for (int z = 3; z <= 5; z++) at(l, x, ry + 3, z, Kind.SLAB);
            at(l, 8, ry + 1, 4, Kind.BELL);
        } else {
            at(l, 7, 1, 1, Kind.BOOKSHELF);
            at(l, 9, 1, 1, Kind.BOOKSHELF);
            at(l, 8, 1, 1, Kind.CHEST);
        }
        // the west wing: the kitchen below, a bedroom above
        at(l, 3, 1, 1, Kind.SMOKER);
        at(l, 3, 1, 2, Kind.BARREL);
        at(l, 3, 1, 4, Kind.CRAFT);
        at(l, 2, 3, 4, Kind.LANTERN);
        at(l, 3, 5, 1, Kind.BED_FOOT);
        at(l, 3, 5, 2, Kind.BED_HEAD);
        at(l, 3, 5, 6, Kind.CHEST);
        at(l, 2, 7, 4, Kind.LANTERN);
        // the east wing: the store below, bedrooms (or the elder's study) above
        at(l, 15, 1, 1, Kind.CHEST);
        at(l, 15, 1, 2, Kind.BARREL);
        at(l, 15, 1, 4, Kind.BARREL);
        at(l, 14, 3, 4, Kind.LANTERN);
        at(l, 15, 5, 1, Kind.BED_FOOT);
        at(l, 15, 5, 2, Kind.BED_HEAD);
        if (hall) for (int z = 4; z <= 6; z++) at(l, 15, 5, z, Kind.BOOKSHELF);
        else {
            at(l, 14, 5, 1, Kind.BED_FOOT);
            at(l, 14, 5, 2, Kind.BED_HEAD);
        }
        at(l, 14, 7, 4, Kind.LANTERN);
        // the garden in front
        for (int x = 0; x <= 16; x++) if (x < 6 || x > 10) at(l, x, 0, d + 1, Kind.HEDGE);
        for (int x : new int[]{1, 3, 13, 15}) at(l, x, 0, d + 2, Kind.FLOWER);
        return l;
    }

    /** A view of the plan list shifted by (ox, oz): what is added to it lands shifted in {@code l}. */
    private static List<Step> sub(List<Step> l, int ox, int oz) {
        return new java.util.AbstractList<>() {
            @Override
            public Step get(int i) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public boolean add(Step s) {
                return l.add(new Step(s.dx() + ox, s.dy(), s.dz() + oz, s.kind(), s.face()));
            }
        };
    }

    // ---------- the city ----------

    /**
     * A city guildhall: the market under an open stone arcade on the ground floor, the hall above it jutting out
     * over the front, timber-framed and plastered under a tiled roof, and a clock tower on the square with the
     * town bell and a spire.
     */
    static List<Step> guildhall() {
        List<Step> l = new ArrayList<>();
        int w = 15, d = 10;
        // the arcade: paving, columns, the stone lintel under the hall's floor (which juts a block out over the front)
        floor(l, 0, 0, w, d, 0, Kind.STONE);
        floor(l, 0, 0, w, d + 1, 4, Kind.FLOOR);
        for (int x = 0; x < w; x++) at(l, x, 4, d, Kind.BEAM_Z);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean xEdge = x == 0 || x == w - 1, zEdge = z == 0 || z == d - 1;
                if (!xEdge && !zEdge) continue;
                boolean column = (xEdge && zEdge) || (zEdge && x % 3 == 1) || (xEdge && z % 3 == 0);
                if (z == 0 && !column) { // the back wall is closed
                    for (int y = 1; y <= 3; y++) at(l, x, y, z, Kind.STONE);
                }
                if (column) for (int y = 1; y <= 3; y++) at(l, x, y, z, Kind.ACCENT);
                at(l, x, 4, z, Kind.STONE);
            }
        }
        // the market under the arcade
        for (int x : new int[]{1, 4, 7, 10}) {
            at(l, x, 1, 2, Kind.BARREL);
            at(l, x + 1, 1, 2, Kind.CHEST);
        }
        at(l, 4, 3, 5, Kind.LANTERN);
        at(l, 10, 3, 5, Kind.LANTERN);
        // the stairs up, in the back corner
        for (int i = 0; i < 3; i++) at(l, 13, 4, 1 + i, Kind.CLEAR);
        for (int i = 0; i < 4; i++) at(l, 13, 1 + i, 1 + i, Kind.STAIRS_S);
        // the hall above
        walls(l, 0, 0, w, d + 1, 5, 4, Kind.WALL, -1, true);
        floor(l, 0, 0, w, d + 1, 9, Kind.ROOF);
        roofAlongX(l, 0, 0, w, d + 1, 9, Kind.WALL);
        at(l, 7, 5, 2, Kind.LECTERN);
        for (int x : new int[]{2, 3, 11, 12}) at(l, x, 5, 1, Kind.HALL_CHEST);
        for (int x = 5; x <= 9; x++) at(l, x, 5, 1, Kind.BOOKSHELF);
        for (int x = 3; x <= 11; x++) {
            at(l, x, 5, 6, Kind.FENCE);
            at(l, x, 6, 6, Kind.PLATE);
            at(l, x, 5, 5, Kind.PEW, S);
            at(l, x, 5, 7, Kind.PEW, N);
        }
        for (int x = 3; x <= 11; x++) at(l, x, 5, 9, Kind.CARPET, 0);
        for (int x : new int[]{3, 7, 11}) at(l, x, 8, 6, Kind.LANTERN);
        // the clock tower before the front, on the square
        int tx = 5, tz = d + 1, n = 5, top = 17;
        for (int y = 0; y <= top; y++) {
            for (int x = tx; x < tx + n; x++) {
                for (int z = tz; z < tz + n; z++) {
                    boolean xEdge = x == tx || x == tx + n - 1, zEdge = z == tz || z == tz + n - 1;
                    boolean corner = xEdge && zEdge;
                    if (y == 0) {
                        at(l, x, y, z, Kind.STONE);
                        continue;
                    }
                    if (!xEdge && !zEdge) continue;
                    boolean arch = !corner && (y == 1 || y == 2) && (z == tz + n - 1 || z == tz) && x == tx + 2; // a way through
                    boolean belfry = !corner && y >= top - 2 && y < top && (x == tx + 2 || z == tz + 2);
                    if (arch || belfry) continue;
                    at(l, x, y, z, corner ? Kind.ACCENT : Kind.STONE);
                }
            }
        }
        for (int x = tx + 1; x < tx + n - 1; x++) for (int z = tz + 1; z < tz + n - 1; z++) at(l, x, top - 3, z, Kind.STONE);
        at(l, tx + 2, top - 2, tz + 2, Kind.BELL);
        at(l, tx + 2, 11, tz + n, Kind.CLOCK); // the clock over the square
        // the spire
        for (int x = tx; x < tx + n; x++) for (int z = tz; z < tz + n; z++) at(l, x, top + 1, z, Kind.ROOF);
        for (int x = tx + 1; x < tx + n - 1; x++) for (int z = tz + 1; z < tz + n - 1; z++) at(l, x, top + 2, z, Kind.ROOF);
        at(l, tx + 2, top + 3, tz + 2, Kind.ROOF);
        at(l, tx + 2, top + 4, tz + 2, Kind.FENCE);
        at(l, tx + 2, top + 5, tz + 2, Kind.LANTERN);
        lamp(l, 1, d + 2);
        lamp(l, w - 2, d + 2);
        return l;
    }

    /**
     * A city row house: tall and narrow, a stone ground floor with the workshop, two upper storeys of timber and
     * plaster each jutting a block further out over the street, a steep front gable, a ladder up through the floors.
     */
    static List<Step> rowhouse() {
        List<Step> l = new ArrayList<>();
        int w = 6, d = 8;
        floor(l, 0, 0, w, d, 0, Kind.FLOOR);
        walls(l, 0, 0, w, d, 1, 3, Kind.STONE, 3, true);
        for (int y = 1; y <= 11; y++) if (y % 4 != 0) at(l, 1, y, 0, y <= 3 ? Kind.STONE : Kind.WALL); // no window behind the ladder
        for (int k = 1; k <= 2; k++) {
            int y = k * 4, depth = d + k;
            floor(l, 0, 0, w, depth, y, Kind.FLOOR);
            for (int x = 0; x < w; x++) at(l, x, y, depth - 1, Kind.BEAM_Z);
            walls(l, 0, 0, w, depth, y + 1, 3, Kind.WALL, -1, true);
            at(l, 1, y, 1, Kind.CLEAR); // the hatch
        }
        for (int y = 1; y <= 8; y++) at(l, 1, y, 1, Kind.LADDER);
        floor(l, 0, 0, w, d + 2, 12, Kind.ROOF);
        Blueprints.gable(l, w, d + 2, 12, false);
        // the workshop below, the kitchen above, the bedroom at the top
        at(l, 4, 1, 1, Kind.CRAFT);
        at(l, 4, 1, 2, Kind.BARREL);
        at(l, 4, 1, 4, Kind.CHEST);
        at(l, 2, 3, 4, Kind.LANTERN);
        at(l, 4, 5, 1, Kind.SMOKER);
        at(l, 4, 5, 2, Kind.BARREL);
        at(l, 3, 5, 6, Kind.FENCE);
        at(l, 3, 6, 6, Kind.PLATE);
        at(l, 2, 5, 6, Kind.SEAT_W);
        at(l, 4, 5, 6, Kind.SEAT_E);
        at(l, 3, 7, 4, Kind.LANTERN);
        at(l, 4, 9, 1, Kind.BED_FOOT);
        at(l, 4, 9, 2, Kind.BED_HEAD);
        at(l, 3, 9, 1, Kind.BED_FOOT);
        at(l, 3, 9, 2, Kind.BED_HEAD);
        at(l, 4, 9, 7, Kind.CHEST);
        at(l, 3, 11, 5, Kind.LANTERN);
        // the shop sign and the step
        at(l, 1, 3, d, Kind.WOOL, 1);
        at(l, 2, 3, d, Kind.WOOL, 1);
        at(l, 3, 0, d, Kind.STEP, N);
        return l;
    }
}
