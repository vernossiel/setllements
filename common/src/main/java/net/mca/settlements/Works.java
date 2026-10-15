package net.mca.settlements;

import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.mca.settlements.Blueprints.E;
import static net.mca.settlements.Blueprints.N;
import static net.mca.settlements.Blueprints.S;
import static net.mca.settlements.Blueprints.W;
import static net.mca.settlements.Buildings.at;
import static net.mca.settlements.Buildings.dummy;
import static net.mca.settlements.Buildings.lamp;
import static net.mca.settlements.Buildings.shift;

/**
 * The village's working buildings, built the way each station of a village builds (see {@link Forms}): every one
 * of them stands on the body of its station and grows with it. A poor hamlet's are low cruck halls under thatch with
 * daub walls and earth floors; an ordinary village builds timber-framed houses on a stone footing; a wealthy one
 * puts a timber storey jutting out over a stone ground floor, with a porch and flowers; a city raises tall stone
 * ground floors, plastered storeys jettied over the street and roofs running along it. Inside, each trade has what
 * it needs, more of it the richer the village.
 */
public final class Works {
    public static final String[] TIER_NAMES = {"poor", "ordinary", "wealthy", "city"};
    private static final Map<String, int[]> FOOTPRINTS = new HashMap<>();

    private Works() {
    }

    static List<Step> plan(String type) {
        return switch (type) {
            case "storage-poor" -> storage(0);
            case "storage-ordinary" -> storage(1);
            case "storage-wealthy" -> storage(2);
            case "storage-city" -> storage(3);
            case "granary-poor" -> granary(0);
            case "granary-ordinary" -> granary(1);
            case "granary-wealthy" -> granary(2);
            case "granary-city" -> granary(3);
            case "sawmill-poor" -> sawmillPoor();
            case "sawmill-city" -> sawmillCity();
            case "mason-poor" -> masonPoor();
            case "mason-city" -> masonCity();
            case "charcoal-city" -> charcoalCity();
            case "mill-poor" -> millPoor();
            case "mill-city" -> millCity();
            case "smithy-wealthy" -> smithy(2);
            case "smithy-city" -> smithy(3);
            case "armory-poor" -> armory(0);
            case "armory-wealthy" -> armory(2);
            case "armory-city" -> armory(3);
            case "tavern-poor" -> tavern(0);
            case "tavern-ordinary" -> tavern(1);
            case "tavern-wealthy" -> tavern(2);
            case "tavern-city" -> tavern(3);
            case "barracks-poor" -> barracks(0);
            case "barracks-ordinary" -> barracks(1);
            case "barracks-wealthy" -> barracks(2);
            case "barracks-city" -> barracks(3);
            case "jail-ordinary" -> jail(1);
            case "jail-wealthy" -> jail(2);
            case "jail-city" -> jail(3);
            case "shop-bakery" -> shop(0);
            case "shop-weaver" -> shop(1);
            case "shop-general" -> shop(2);
            case "shop-apothecary" -> shop(3);
            default -> List.of();
        };
    }

    /** The footprint of a design: as far as it reaches to the east and the south (eaves included). */
    static int[] footprint(String type) {
        int[] f = FOOTPRINTS.get(type);
        if (f != null) return f;
        List<Step> l = plan(type);
        if (l.isEmpty()) return null;
        int mx = 0, mz = 0;
        for (Step s : l) {
            mx = Math.max(mx, s.dx());
            mz = Math.max(mz, s.dz());
        }
        f = new int[]{mx + 1, mz + 1};
        FOOTPRINTS.put(type, f);
        return f;
    }

    /** The cell a prisoner is put in and where he is let out, for these jails; null for others. */
    static int[][] jailPoints(String type) {
        return switch (type) {
            case "jail-ordinary" -> new int[][]{{2, 1, 1}, {4, 0, 9}};
            case "jail-wealthy" -> new int[][]{{1, 1, 1}, {5, 0, 10}};
            case "jail-city" -> new int[][]{{1, 1, 1}, {6, 0, 11}};
            default -> null;
        };
    }

    // ---------- the body of each station ----------

    /** The height of the floor of storey {@code k} (0 the ground floor). */
    static int floorY(int tier, int k) {
        if (k == 0) return 0;
        return tier >= 3 ? 5 + 4 * (k - 1) : 4 * k;
    }

    /** How far storey {@code k} juts out over the front. */
    static int jut(int tier, int k) {
        return tier >= 2 ? k : 0;
    }

    /**
     * The shell of a building at a station. The ground floor's inside is x 1..w-2, z 1..d-2 from y 1; with more than
     * one storey a ladder stands against the back wall at x = w-2, z = 1, through a hatch in every floor (keep that
     * corner free). The door is in the middle of the front.
     */
    static List<Step> body(int tier, int w, int d, int floors, boolean chimney) {
        List<Step> l = new ArrayList<>();
        int door = w / 2;
        if (tier <= 0) {
            // the cruck hall: thatch down to the ground, daub between the posts, earth underfoot
            Forms.floor(l, 0, 0, w, d, 0, Kind.FLOOR);
            for (int z = 0; z < d; z++) {
                for (int y = 1; y <= 2; y++) {
                    at(l, 0, y, z, z % 3 == 0 ? Kind.PILLAR : Kind.DAUB);
                    at(l, w - 1, y, z, z % 3 == 0 ? Kind.PILLAR : Kind.DAUB);
                }
            }
            for (int x = 1; x < w - 1; x++) {
                for (int y = 1; y <= 2; y++) {
                    at(l, x, y, 0, Kind.DAUB);
                    if (x != door) at(l, x, y, d - 1, Kind.DAUB);
                }
            }
            Forms.thatch(l, 0, 0, w, d, 1, Kind.DAUB);
            at(l, door, 1, d - 1, Kind.DOOR);
            at(l, door, 2, d - 1, Kind.CLEAR);
            at(l, 0, 2, d / 2 + (d / 2 % 3 == 0 ? 1 : 0), Kind.GLASS);
            at(l, w - 1, 2, d / 2 + (d / 2 % 3 == 0 ? 1 : 0), Kind.GLASS);
            at(l, door, 0, d, Kind.STEP, N);
            at(l, door, -1, d + 1, Kind.PATH);
            // a lantern from the ridge, so the place is not a cave
            int ridge = 2 + (w + 1) / 2;
            at(l, w / 2, ridge - 1, 2, Kind.LANTERN);
            return l;
        }
        boolean city = tier >= 3;
        int h0 = city ? 4 : 3;
        Kind ground = tier == 1 ? Kind.WALL : Kind.STONE;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                at(l, x, 0, z, edge ? Kind.STONE : Kind.FLOOR);
            }
        }
        Forms.walls(l, 0, 0, w, d, 1, h0, ground, door, true);
        if (tier >= 2) {
            for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) for (int y = 1; y <= h0; y++) at(l, c[0], y, c[1], Kind.ACCENT);
        }
        int depth = d;
        for (int k = 1; k < floors; k++) {
            int y = floorY(tier, k);
            int nd = d + jut(tier, k);
            Forms.floor(l, 0, 0, w, nd, y, Kind.FLOOR);
            if (nd > depth) for (int x = 0; x < w; x++) at(l, x, y, nd - 1, Kind.BEAM_Z); // the joists under the jetty
            Forms.walls(l, 0, 0, w, nd, y + 1, 3, Kind.WALL, -1, true);
            depth = nd;
        }
        int top = floors > 1 ? floorY(tier, floors - 1) + 4 : h0 + 1;
        Forms.floor(l, 0, 0, w, depth, top, Kind.ROOF);
        if (city) Forms.roofAlongX(l, 0, 0, w, depth, top, Kind.WALL);
        else Blueprints.gable(l, w, depth, top, false);
        if (floors > 1) {
            for (int y = 1; y < top; y++) {
                at(l, w - 2, y, 0, y <= h0 ? ground : Kind.WALL); // no window behind the ladder
                at(l, w - 2, y, 1, Kind.LADDER);
            }
            for (int k = 1; k < floors; k++) {
                at(l, w - 2, floorY(tier, k), 1, Kind.CLEAR);
                at(l, w - 2, floorY(tier, k), 1, Kind.LADDER);
            }
        }
        if (chimney) {
            int roofTop = top + (city ? (depth + 1) / 2 + 1 : w / 2 + 1);
            for (int y = 0; y <= roofTop + 1; y++) at(l, w, y, Blueprints.CHIMNEY_Z, Kind.CHIMNEY);
            at(l, w, roofTop + 2, Blueprints.CHIMNEY_Z, Kind.SMOKE);
        }
        // the front
        at(l, door, 0, d, Kind.STEP, N);
        for (int z = d + 1; z <= d + 2; z++) at(l, door, -1, z, Kind.PATH);
        if (tier == 1) {
            at(l, door - 1, 2, d, Kind.TORCH);
            at(l, door + 1, 2, d, Kind.TORCH);
            for (int x = 0; x < w; x++) if (Math.abs(x - door) > 1 && x % 2 == 0) at(l, x, 0, d, Kind.POT);
        } else if (tier == 2) {
            for (int x = door - 1; x <= door + 1; x++) at(l, x, h0, d, Kind.SLAB); // a little porch roof
            at(l, door - 1, 2, d, Kind.TORCH);
            at(l, door + 1, 2, d, Kind.TORCH);
            for (int x = 0; x < w; x++) if (Math.abs(x - door) > 1) at(l, x, 0, d, x % 2 == 0 ? Kind.FLOWER : Kind.HEDGE);
        } else {
            if (floors > 1) {
                at(l, door - 1, h0, d, Kind.LANTERN); // hanging under the jetty
                at(l, door + 1, h0, d, Kind.LANTERN);
            } else {
                at(l, door - 1, 2, d, Kind.TORCH);
                at(l, door + 1, 2, d, Kind.TORCH);
            }
        }
        return l;
    }

    /** A lean-to of the poorest: posts at the corners, a back wall, a slab roof. */
    private static void leanTo(List<Step> l, int w, int d, Kind back) {
        for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) for (int y = 0; y <= 2; y++) at(l, c[0], y, c[1], Kind.PILLAR);
        for (int x = 1; x < w - 1; x++) for (int y = 0; y <= 1; y++) at(l, x, y, 0, back);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) at(l, x, 3, z, Kind.SLAB);
    }

    private static void row(List<Step> l, int x0, int x1, int y, int z, Kind k) {
        for (int x = x0; x <= x1; x++) at(l, x, y, z, k);
    }

    /** A stone silo: a round-cornered stone drum full of grain, slab-capped. */
    private static void silo(List<Step> l, int x0, int z0, int h) {
        for (int y = 0; y <= h; y++) {
            for (int x = x0; x < x0 + 3; x++) {
                for (int z = z0; z < z0 + 3; z++) {
                    boolean center = x == x0 + 1 && z == z0 + 1;
                    boolean corner = (x == x0 || x == x0 + 2) && (z == z0 || z == z0 + 2);
                    if (center) at(l, x, y, z, y == 0 ? Kind.STONE : Kind.HAY);
                    else at(l, x, y, z, corner ? Kind.ACCENT : Kind.STONE);
                }
            }
        }
        for (int x = x0; x < x0 + 3; x++) for (int z = z0; z < z0 + 3; z++) at(l, x, h + 1, z, Kind.SLAB);
    }

    // ---------- stores ----------

    static List<Step> storage(int tier) {
        return switch (tier) {
            case 0 -> {
                List<Step> l = body(0, 5, 7, 1, false);
                at(l, 1, 1, 1, Kind.CHEST);
                at(l, 3, 1, 1, Kind.CHEST);
                at(l, 1, 1, 3, Kind.HAY);
                at(l, 3, 1, 3, Kind.BARREL);
                at(l, 1, 1, 4, Kind.BARREL);
                yield l;
            }
            case 1 -> {
                List<Step> l = body(1, 9, 7, 1, false);
                for (int x : new int[]{1, 2, 4, 5, 6, 7}) at(l, x, 1, 1, Kind.CHEST);
                at(l, 1, 1, 4, Kind.BARREL);
                at(l, 1, 2, 4, Kind.BARREL);
                at(l, 7, 1, 4, Kind.BARREL);
                at(l, 4, 3, 3, Kind.LANTERN);
                yield l;
            }
            case 2 -> {
                List<Step> l = body(2, 11, 8, 2, false);
                row(l, 1, 8, 1, 1, Kind.CHEST);
                row(l, 1, 3, 1, 6, Kind.CHEST);
                row(l, 7, 9, 1, 6, Kind.CHEST);
                at(l, 3, 1, 3, Kind.BARREL);
                at(l, 7, 1, 3, Kind.BARREL);
                at(l, 5, 3, 4, Kind.LANTERN);
                row(l, 1, 8, 5, 1, Kind.CHEST);
                row(l, 1, 9, 5, 7, Kind.CHEST);
                at(l, 5, 5, 4, Kind.HAY);
                at(l, 5, 7, 4, Kind.LANTERN);
                yield l;
            }
            default -> {
                List<Step> l = body(3, 13, 9, 2, false);
                row(l, 1, 10, 1, 1, Kind.CHEST);
                row(l, 1, 4, 1, 7, Kind.CHEST);
                row(l, 8, 11, 1, 7, Kind.CHEST);
                for (int x : new int[]{4, 8}) {
                    at(l, x, 1, 4, Kind.BARREL);
                    at(l, x, 2, 4, Kind.BARREL);
                }
                at(l, 6, 4, 4, Kind.LANTERN);
                row(l, 1, 10, 6, 1, Kind.CHEST);
                row(l, 1, 11, 6, 8, Kind.CHEST);
                at(l, 6, 6, 4, Kind.HAY);
                at(l, 6, 8, 4, Kind.LANTERN);
                // the hoist over the street
                for (int z = 10; z <= 11; z++) at(l, 6, 8, z, Kind.LOG_Z);
                for (int y = 6; y <= 7; y++) at(l, 6, y, 11, Kind.CHAIN);
                at(l, 6, 5, 11, Kind.BARREL);
                yield l;
            }
        };
    }

    static List<Step> granary(int tier) {
        return switch (tier) {
            case 0 -> {
                List<Step> l = body(0, 5, 7, 1, false);
                at(l, 1, 1, 1, Kind.HAY);
                at(l, 1, 2, 1, Kind.HAY);
                at(l, 3, 1, 1, Kind.HAY);
                at(l, 1, 1, 2, Kind.HAY);
                at(l, 3, 1, 3, Kind.BARREL);
                at(l, 5, 0, 2, Kind.COMPOSTER);
                yield l;
            }
            case 1 -> {
                List<Step> l = body(1, 7, 7, 1, false);
                row(l, 1, 5, 1, 1, Kind.HAY);
                at(l, 1, 2, 1, Kind.HAY);
                at(l, 2, 2, 1, Kind.HAY);
                at(l, 5, 1, 3, Kind.BARREL);
                at(l, 5, 1, 4, Kind.BARREL);
                at(l, 1, 1, 4, Kind.CHEST);
                at(l, 7, 0, 2, Kind.COMPOSTER);
                at(l, 7, 0, 4, Kind.PUMPKIN, E);
                yield l;
            }
            case 2 -> {
                List<Step> l = body(2, 9, 7, 2, false);
                row(l, 1, 6, 1, 1, Kind.HAY);
                at(l, 1, 1, 4, Kind.BARREL);
                at(l, 1, 2, 4, Kind.BARREL);
                at(l, 7, 1, 4, Kind.CHEST);
                row(l, 1, 6, 5, 1, Kind.HAY);
                row(l, 1, 7, 5, 6, Kind.HAY);
                at(l, 4, 7, 3, Kind.LANTERN);
                silo(l, 10, 2, 8);
                yield l;
            }
            default -> {
                List<Step> l = body(3, 11, 8, 2, false);
                row(l, 1, 8, 1, 1, Kind.HAY);
                row(l, 1, 3, 1, 6, Kind.BARREL);
                row(l, 7, 9, 1, 6, Kind.CHEST);
                at(l, 5, 4, 3, Kind.LANTERN);
                row(l, 1, 8, 6, 1, Kind.HAY);
                row(l, 1, 9, 6, 7, Kind.HAY);
                at(l, 5, 8, 4, Kind.LANTERN);
                silo(l, 12, 1, 11);
                silo(l, 12, 5, 9);
                yield l;
            }
        };
    }

    // ---------- yards and workshops ----------

    static List<Step> sawmillPoor() {
        List<Step> l = new ArrayList<>();
        leanTo(l, 7, 5, Kind.WALL);
        at(l, 2, 0, 1, Kind.CRAFT);
        row(l, 4, 5, 0, 1, Kind.LOG_X);
        at(l, 4, 1, 1, Kind.LOG_X);
        row(l, 1, 3, 0, 3, Kind.LOG_X);
        at(l, 8, 0, 1, Kind.LOG_Z);
        at(l, 8, 0, 2, Kind.LOG_Z);
        at(l, 8, 1, 1, Kind.LOG_Z);
        return l;
    }

    static List<Step> sawmillCity() {
        List<Step> l = body(3, 11, 8, 1, false);
        at(l, 2, 1, 1, Kind.CRAFT);
        at(l, 3, 1, 1, Kind.CRAFT);
        at(l, 5, 1, 1, Kind.STONECUTTER); // the saw
        at(l, 6, 1, 1, Kind.STONECUTTER);
        row(l, 8, 9, 1, 1, Kind.BARREL);
        row(l, 1, 3, 1, 5, Kind.LOG_X);
        at(l, 1, 2, 5, Kind.LOG_X);
        at(l, 5, 4, 3, Kind.LANTERN);
        // the timber yard at the side, with a crane
        for (int x = 12; x <= 17; x++) {
            for (int z = 0; z <= 7; z++) {
                boolean edge = x == 17 || z == 0 || z == 7;
                if (edge) at(l, x, 0, z, x == 14 && z == 7 ? Kind.GATE : Kind.FENCE, S);
            }
        }
        for (int z = 1; z <= 3; z++) {
            row(l, 12, 15, 0, z, Kind.LOG_X);
            if (z < 3) row(l, 12, 14, 1, z, Kind.LOG_X);
        }
        for (int y = 0; y <= 6; y++) at(l, 16, y, 5, Kind.PILLAR);
        for (int x = 13; x <= 15; x++) at(l, x, 6, 5, Kind.LOG_X);
        at(l, 14, 5, 5, Kind.CHAIN);
        at(l, 14, 4, 5, Kind.LOG_X);
        return l;
    }

    static List<Step> masonPoor() {
        List<Step> l = new ArrayList<>();
        leanTo(l, 7, 5, Kind.STONE);
        at(l, 2, 0, 1, Kind.STONECUTTER);
        at(l, 4, 0, 1, Kind.GRINDSTONE);
        at(l, 1, 0, 3, Kind.STONE);
        at(l, 2, 0, 3, Kind.STONE);
        at(l, 1, 1, 3, Kind.STONE);
        at(l, 5, 0, 3, Kind.BARREL);
        return l;
    }

    static List<Step> masonCity() {
        List<Step> l = body(3, 11, 8, 1, false);
        at(l, 2, 1, 1, Kind.STONECUTTER);
        at(l, 4, 1, 1, Kind.STONECUTTER);
        at(l, 6, 1, 1, Kind.GRINDSTONE);
        row(l, 8, 9, 1, 1, Kind.CHEST);
        at(l, 1, 1, 5, Kind.CRAFT);
        at(l, 5, 4, 3, Kind.LANTERN);
        // the stone yard: blocks cut and stacked
        for (int x = 12; x <= 16; x++) {
            for (int z = 0; z <= 7; z++) {
                boolean edge = x == 16 || z == 0 || z == 7;
                if (edge) at(l, x, 0, z, x == 14 && z == 7 ? Kind.GATE : Kind.FENCE, S);
                else at(l, x, -1, z, Kind.STONE);
            }
        }
        for (int[] p : new int[][]{{12, 1}, {13, 1}, {12, 2}, {14, 1}}) at(l, p[0], 0, p[1], Kind.ACCENT);
        at(l, 12, 1, 1, Kind.ACCENT);
        at(l, 13, 1, 1, Kind.STONE);
        at(l, 14, 0, 4, Kind.STONECUTTER);
        lamp(l, 15, 5);
        return l;
    }

    static List<Step> charcoalCity() {
        List<Step> l = new ArrayList<>();
        shift(Variants.kiln(), 0, 0, l);
        shift(Variants.kiln(), 6, 0, l);
        shift(Variants.kiln(), 12, 0, l);
        return l;
    }

    static List<Step> millPoor() {
        List<Step> l = body(0, 5, 7, 1, false);
        at(l, 2, 1, 2, Kind.GRINDSTONE); // the hand quern
        at(l, 1, 1, 1, Kind.HAY);
        at(l, 3, 1, 1, Kind.BARREL);
        at(l, 1, 1, 4, Kind.COMPOSTER);
        at(l, 3, 1, 4, Kind.SMOKER);
        return l;
    }

    /** A city mill: a bakehouse of two storeys and a stone windmill beside it, its sails turned to the street. */
    static List<Step> millCity() {
        List<Step> l = body(3, 9, 8, 2, true);
        at(l, 1, 1, 1, Kind.SMOKER);
        at(l, 2, 1, 1, Kind.SMOKER);
        at(l, 3, 1, 1, Kind.SMOKER);
        at(l, 5, 1, 1, Kind.BARREL);
        at(l, 1, 1, 5, Kind.CRAFT);
        at(l, 2, 1, 5, Kind.FENCE);
        at(l, 2, 2, 5, Kind.PLATE);
        at(l, 4, 4, 3, Kind.LANTERN);
        row(l, 1, 5, 6, 1, Kind.HAY);
        at(l, 1, 6, 6, Kind.CHEST);
        at(l, 4, 8, 4, Kind.LANTERN);
        // the windmill
        int tx = 11, tz = 2, h = 12;
        for (int y = 0; y <= h; y++) {
            for (int x = tx; x < tx + 3; x++) {
                for (int z = tz; z < tz + 3; z++) {
                    boolean center = x == tx + 1 && z == tz + 1;
                    if (center && y > 0) continue;
                    at(l, x, y, z, (x == tx + 1 || z == tz + 1) ? Kind.STONE : Kind.ACCENT);
                }
            }
        }
        at(l, tx + 1, 1, tz + 2, Kind.DOOR);
        at(l, tx + 1, 2, tz + 2, Kind.CLEAR);
        at(l, 6, 1, 1, Kind.GRINDSTONE); // the millstone, in the bakehouse
        for (int x = tx - 1; x <= tx + 3; x++) for (int z = tz - 1; z <= tz + 3; z++) at(l, x, h + 1, z, Kind.ROOF);
        for (int x = tx; x < tx + 3; x++) for (int z = tz; z < tz + 3; z++) at(l, x, h + 2, z, Kind.ROOF);
        // the sails on the front, a cross of cloth on a hub
        int hy = h - 2, sz = tz + 3;
        at(l, tx + 1, hy, sz, Kind.LOG_Z);
        for (int k = 1; k <= 4; k++) {
            at(l, tx + 1, hy + k, sz, Kind.WOOL);
            at(l, tx + 1, hy - k, sz, Kind.WOOL);
            at(l, tx + 1 + k, hy, sz, Kind.WOOL);
            at(l, tx + 1 - k, hy, sz, Kind.WOOL);
        }
        return l;
    }

    // ---------- smithy and armory ----------

    static List<Step> smithy(int tier) {
        if (tier == 2) {
            List<Step> l = body(2, 9, 7, 1, true);
            at(l, 1, 1, 1, Kind.FURNACE);
            at(l, 2, 1, 1, Kind.FURNACE);
            row(l, 1, 2, 2, 1, Kind.STONE); // the hood
            at(l, 4, 1, 3, Kind.ANVIL);
            at(l, 6, 1, 1, Kind.GRINDSTONE);
            at(l, 7, 1, 1, Kind.SMITHING);
            at(l, 7, 1, 4, Kind.CAULDRON);
            at(l, 1, 1, 4, Kind.BARREL);
            at(l, 4, 3, 3, Kind.LANTERN);
            // the shoeing yard
            for (int z = 0; z <= 6; z++) if (z != 3) at(l, 12, 0, z, Kind.FENCE);
            at(l, 10, 0, 0, Kind.FENCE);
            at(l, 11, 0, 0, Kind.FENCE);
            at(l, 10, 0, 6, Kind.FENCE);
            at(l, 11, 0, 6, Kind.FENCE);
            at(l, 11, 0, 2, Kind.ARMOR_STAND);
            at(l, 11, 0, 4, Kind.HAY);
            return l;
        }
        // the city foundry: a row of hearths under a hood, two anvils, the journeymen's room above
        List<Step> l = body(3, 13, 8, 2, true);
        for (int x = 1; x <= 3; x++) {
            at(l, x, 1, 1, Kind.FURNACE);
            for (int y = 2; y <= 3; y++) at(l, x, y, 1, Kind.STONE);
        }
        at(l, 5, 1, 3, Kind.ANVIL);
        at(l, 8, 1, 3, Kind.ANVIL);
        at(l, 9, 1, 1, Kind.GRINDSTONE);
        at(l, 10, 1, 1, Kind.SMITHING);
        at(l, 10, 1, 5, Kind.CAULDRON);
        at(l, 1, 1, 5, Kind.CHEST);
        at(l, 2, 1, 5, Kind.BARREL);
        at(l, 4, 4, 4, Kind.LANTERN);
        at(l, 8, 4, 4, Kind.LANTERN);
        for (int x : new int[]{2, 4}) {
            at(l, x, 6, 1, Kind.BED_FOOT);
            at(l, x, 6, 2, Kind.BED_HEAD);
        }
        at(l, 7, 6, 1, Kind.CHEST);
        at(l, 8, 6, 1, Kind.CHEST);
        at(l, 6, 8, 4, Kind.LANTERN);
        return l;
    }

    static List<Step> armory(int tier) {
        return switch (tier) {
            case 0 -> {
                List<Step> l = body(0, 5, 7, 1, false);
                at(l, 1, 1, 1, Kind.ARMOR_STAND);
                at(l, 3, 1, 1, Kind.ARMOR_STAND);
                at(l, 1, 1, 3, Kind.CHEST);
                at(l, 3, 1, 3, Kind.BARREL);
                yield l;
            }
            case 2 -> {
                List<Step> l = body(2, 9, 7, 1, false);
                for (int x = 1; x <= 7; x += 2) at(l, x, 1, 1, Kind.ARMOR_STAND);
                for (int x = 2; x <= 6; x += 2) at(l, x, 1, 1, Kind.CHEST);
                at(l, 1, 1, 4, Kind.CHEST);
                at(l, 7, 1, 4, Kind.GRINDSTONE);
                at(l, 4, 3, 3, Kind.LANTERN);
                for (int x = 10; x <= 12; x++) {
                    at(l, x, 0, 3, Kind.FENCE);
                    at(l, x, 1, 3, Kind.TARGET);
                }
                yield l;
            }
            default -> {
                List<Step> l = body(3, 11, 8, 2, false);
                for (int x = 1; x <= 8; x++) at(l, x, 1, 1, x % 2 == 1 ? Kind.ARMOR_STAND : Kind.CHEST);
                for (int x : new int[]{1, 3, 7, 9}) at(l, x, 1, 6, Kind.ARMOR_STAND);
                at(l, 5, 4, 3, Kind.LANTERN);
                row(l, 1, 8, 6, 1, Kind.CHEST);
                row(l, 1, 9, 6, 7, Kind.BARREL);
                at(l, 5, 8, 4, Kind.LANTERN);
                yield l;
            }
        };
    }

    // ---------- taverns ----------

    static List<Step> tavern(int tier) {
        return switch (tier) {
            case 0 -> {
                // a smoky alehouse: the fire in the middle, the barrels at the back, benches round
                List<Step> l = body(0, 7, 9, 1, false);
                at(l, 3, 0, 4, Kind.SMOKE);
                for (int[] c : new int[][]{{2, 4}, {4, 4}, {3, 3}, {3, 5}}) at(l, c[0], 0, c[1], Kind.STONE);
                at(l, 3, 6, 4, Kind.CLEAR); // the smoke hole
                at(l, 1, 1, 1, Kind.SMOKER);
                at(l, 2, 1, 1, Kind.BARREL);
                at(l, 4, 1, 1, Kind.CRAFT);
                at(l, 5, 1, 1, Kind.BARREL);
                table(l, 4, 1, 6);
                for (int z = 3; z <= 5; z++) {
                    at(l, 1, 1, z, Kind.PEW, E);
                    at(l, 5, 1, z, Kind.PEW, W);
                }
                yield l;
            }
            case 1 -> {
                List<Step> l = body(1, 11, 9, 1, true);
                at(l, 6, 1, 1, Kind.BARREL);
                at(l, 7, 1, 1, Kind.CRAFT);
                at(l, 8, 1, 1, Kind.SMOKER);
                at(l, 9, 1, 1, Kind.BARREL);
                row(l, 6, 9, 1, 3, Kind.BARREL); // the bar
                table(l, 3, 1, 3);
                table(l, 3, 1, 6);
                table(l, 7, 1, 6);
                at(l, 3, 3, 4, Kind.LANTERN);
                at(l, 7, 3, 4, Kind.LANTERN);
                yield l;
            }
            case 2 -> {
                List<Step> l = body(2, 13, 9, 2, true);
                at(l, 6, 1, 1, Kind.BARREL);
                at(l, 7, 1, 1, Kind.CRAFT);
                at(l, 8, 1, 1, Kind.SMOKER);
                at(l, 9, 1, 1, Kind.BARREL);
                row(l, 6, 9, 1, 3, Kind.BARREL);
                table(l, 3, 1, 3);
                table(l, 3, 1, 6);
                table(l, 8, 1, 6);
                at(l, 4, 3, 4, Kind.LANTERN);
                at(l, 8, 3, 4, Kind.LANTERN);
                for (int x = 1; x <= 9; x += 2) {
                    at(l, x, 5, 1, Kind.BED_FOOT);
                    at(l, x, 5, 2, Kind.BED_HEAD);
                }
                for (int x = 2; x <= 10; x += 4) at(l, x, 5, 7, Kind.CHEST);
                for (int x = 3; x <= 9; x++) at(l, x, 5, 5, Kind.CARPET, 0);
                at(l, 6, 7, 4, Kind.LANTERN);
                yield l;
            }
            default -> {
                List<Step> l = body(3, 15, 10, 3, true);
                at(l, 8, 1, 1, Kind.BARREL);
                at(l, 9, 1, 1, Kind.CRAFT);
                at(l, 10, 1, 1, Kind.SMOKER);
                at(l, 11, 1, 1, Kind.SMOKER);
                row(l, 8, 11, 1, 3, Kind.BARREL);
                table(l, 3, 1, 3);
                table(l, 3, 1, 7);
                table(l, 7, 1, 7);
                table(l, 11, 1, 7);
                for (int x : new int[]{3, 7, 11}) at(l, x, 4, 5, Kind.LANTERN);
                for (int k = 1; k <= 2; k++) {
                    int y = floorY(3, k) + 1;
                    for (int x = 1; x <= 11; x += 2) {
                        at(l, x, y, 1, Kind.BED_FOOT);
                        at(l, x, y, 2, Kind.BED_HEAD);
                    }
                    for (int x = 2; x <= 12; x += 5) at(l, x, y, 8, Kind.CHEST);
                    for (int x = 3; x <= 11; x++) at(l, x, y, 5, Kind.CARPET, k);
                    at(l, 7, y + 2, 5, Kind.LANTERN);
                }
                yield l;
            }
        };
    }

    /** A table with a chair on each side. */
    private static void table(List<Step> l, int x, int y, int z) {
        at(l, x, y, z, Kind.FENCE);
        at(l, x, y + 1, z, Kind.PLATE);
        at(l, x - 1, y, z, Kind.SEAT_W);
        at(l, x + 1, y, z, Kind.SEAT_E);
    }

    // ---------- barracks ----------

    static List<Step> barracks(int tier) {
        return switch (tier) {
            case 0 -> {
                List<Step> l = body(0, 7, 9, 1, false);
                for (int x : new int[]{1, 5}) {
                    at(l, x, 1, 1, Kind.BED_FOOT);
                    at(l, x, 1, 2, Kind.BED_HEAD);
                    at(l, x, 1, 5, Kind.BED_FOOT);
                    at(l, x, 1, 6, Kind.BED_HEAD);
                }
                at(l, 3, 1, 1, Kind.ARMOR_STAND);
                at(l, 2, 1, 1, Kind.CHEST);
                at(l, 4, 1, 1, Kind.BARREL);
                dummy(l, 8, 0, 3);
                yield l;
            }
            case 1 -> {
                List<Step> l = body(1, 11, 8, 1, true);
                for (int x = 1; x <= 9; x += 2) {
                    at(l, x, 1, 1, Kind.BED_FOOT);
                    at(l, x, 1, 2, Kind.BED_HEAD);
                }
                at(l, 1, 1, 5, Kind.CHEST);
                at(l, 9, 1, 5, Kind.CHEST);
                at(l, 2, 1, 5, Kind.ARMOR_STAND);
                at(l, 8, 1, 5, Kind.ARMOR_STAND);
                at(l, 5, 3, 4, Kind.LANTERN);
                dummy(l, 13, 0, 2);
                dummy(l, 13, 0, 5);
                yield l;
            }
            case 2 -> {
                List<Step> l = body(2, 13, 9, 2, true);
                for (int x = 1; x <= 10; x++) at(l, x, 1, 1, x % 2 == 1 ? Kind.ARMOR_STAND : Kind.CHEST);
                table(l, 3, 1, 5);
                at(l, 9, 1, 5, Kind.GRINDSTONE);
                at(l, 6, 3, 4, Kind.LANTERN);
                for (int x = 1; x <= 9; x += 2) {
                    at(l, x, 5, 1, Kind.BED_FOOT);
                    at(l, x, 5, 2, Kind.BED_HEAD);
                }
                for (int x = 2; x <= 10; x += 4) at(l, x, 5, 7, Kind.CHEST);
                at(l, 6, 7, 4, Kind.LANTERN);
                yard(l, 14, 9);
                yield l;
            }
            default -> {
                List<Step> l = body(3, 15, 10, 3, true);
                for (int x = 1; x <= 12; x++) at(l, x, 1, 1, x % 2 == 1 ? Kind.ARMOR_STAND : Kind.CHEST);
                table(l, 3, 1, 6);
                table(l, 7, 1, 6);
                at(l, 11, 1, 6, Kind.SMITHING);
                at(l, 12, 1, 6, Kind.GRINDSTONE);
                for (int x : new int[]{3, 7, 11}) at(l, x, 4, 4, Kind.LANTERN);
                for (int k = 1; k <= 2; k++) {
                    int y = floorY(3, k) + 1;
                    for (int x = 1; x <= 11; x += 2) {
                        at(l, x, y, 1, Kind.BED_FOOT);
                        at(l, x, y, 2, Kind.BED_HEAD);
                    }
                    for (int x = 2; x <= 12; x += 5) at(l, x, y, 8, Kind.CHEST);
                    at(l, 7, y + 2, 5, Kind.LANTERN);
                }
                yard(l, 16, 10);
                yield l;
            }
        };
    }

    /** A drill yard: a fence round it, straw targets, a dummy and a lamp. */
    private static void yard(List<Step> l, int x0, int d) {
        int x1 = x0 + 5;
        for (int x = x0; x <= x1; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == x1 || z == 0 || z == d - 1;
                if (edge) at(l, x, 0, z, x == x0 + 2 && z == d - 1 ? Kind.GATE : Kind.FENCE, S);
                else at(l, x, -1, z, Kind.PATH);
            }
        }
        at(l, x1 - 1, 0, 2, Kind.FENCE);
        at(l, x1 - 1, 1, 2, Kind.TARGET);
        at(l, x1 - 1, 0, d - 3, Kind.FENCE);
        at(l, x1 - 1, 1, d - 3, Kind.TARGET);
        dummy(l, x0 + 1, 0, 3);
        lamp(l, x0 + 1, d - 3);
    }

    // ---------- jails ----------

    /**
     * Barred cells along the back wall, divided by stone, each with a barred gate onto the guards' room, and barred
     * windows; the stocks stand outside for the lesser offenders.
     */
    static List<Step> jail(int tier) {
        int w = tier == 1 ? 9 : tier == 2 ? 11 : 13, d = tier == 1 ? 7 : tier == 2 ? 8 : 9;
        List<Step> l = body(tier, w, d, tier == 1 ? 1 : 2, false);
        int h0 = tier >= 3 ? 4 : 3;
        // bars in every ground floor window
        for (int x = 1; x < w - 1; x++) for (int y = 2; y < h0; y++) {
            at(l, x, y, 0, x == w - 2 && tier > 1 ? Kind.STONE : x % 3 == 0 ? Kind.PILLAR : Kind.BARS);
        }
        for (int z = 1; z < d - 1; z++) for (int y = 2; y < h0; y++) {
            if (z % 3 == 0) continue;
            at(l, 0, y, z, Kind.BARS);
            at(l, w - 1, y, z, Kind.BARS);
        }
        // the cells: three blocks wide (two inside, a wall between), as many as fit before the ladder
        int last = tier == 1 ? w - 2 : w - 4; // with an upper floor, the ladder corner stays outside the cells
        for (int y = 1; y < h0; y++) {
            for (int x = 1; x <= last; x++) {
                boolean wall = x % 3 == 0;
                if (wall) {
                    at(l, x, y, 1, Kind.STONE);
                    at(l, x, y, 2, Kind.STONE);
                }
                Kind front = wall ? Kind.STONE : (y == 1 && x % 3 == 1) ? Kind.GATE : Kind.BARS;
                at(l, x, y, 3, front, S);
            }
        }
        if (tier > 1) for (int y = 1; y < h0; y++) for (int z = 1; z <= 3; z++) at(l, w - 3, y, z, Kind.STONE);
        for (int x = 1; x <= last; x += 3) at(l, x + (x + 1 <= last ? 1 : 0), 1, 2, Kind.HAY); // straw to sleep on, off the spot a prisoner is put
        // the guards' desk
        at(l, 1, 1, d - 2, Kind.LECTERN);
        at(l, 2, 1, d - 2, Kind.CHEST);
        at(l, w / 2, h0 - 1, 4, Kind.LANTERN);
        if (tier > 1) {
            int y = floorY(tier, 1) + 1;
            at(l, 1, y, 1, Kind.BED_FOOT);
            at(l, 1, y, 2, Kind.BED_HEAD);
            at(l, 3, y, 1, Kind.BED_FOOT);
            at(l, 3, y, 2, Kind.BED_HEAD);
            at(l, 5, y, 1, Kind.CHEST);
            at(l, w / 2, y + 2, 4, Kind.LANTERN);
        }
        // the stocks
        int sx = w + 2;
        at(l, sx, 0, 2, Kind.FENCE);
        at(l, sx + 2, 0, 2, Kind.FENCE);
        row(l, sx, sx + 2, 1, 2, Kind.SLAB);
        lamp(l, sx + 1, 5);
        return l;
    }

    // ---------- shops ----------

    /**
     * A shop in a town street: the stone shop floor with the counter under a striped awning, the family above in
     * the jutting storey. {@code kind}: 0 a bakery, 1 a weaver's, 2 a general store, 3 an apothecary.
     */
    static List<Step> shop(int kind) {
        int w = 7, d = 9;
        List<Step> l = body(3, w, d, 2, true);
        for (int x = 0; x < w; x++) at(l, x, 4, d + 1, Kind.WOOL, kind); // the awning out over the street
        at(l, 3, 1, 4, Kind.BARREL);
        at(l, 4, 1, 4, Kind.BARREL);
        switch (kind) {
            case 0 -> {
                at(l, 1, 1, 1, Kind.SMOKER);
                at(l, 2, 1, 1, Kind.SMOKER);
                at(l, 3, 1, 1, Kind.HAY);
                at(l, 4, 1, 1, Kind.BARREL);
                at(l, 3, 2, 4, Kind.POT);
            }
            case 1 -> {
                for (int x = 1; x <= 4; x++) at(l, x, 1, 1, Kind.WOOL, x - 1);
                at(l, 1, 2, 1, Kind.WOOL, 3);
                at(l, 1, 1, 2, Kind.CRAFT);
                for (int x = 1; x <= 4; x++) at(l, x, 1, 6, Kind.CARPET, x);
            }
            case 2 -> {
                at(l, 1, 1, 1, Kind.CHEST);
                at(l, 2, 1, 1, Kind.CHEST);
                at(l, 3, 1, 1, Kind.BARREL);
                at(l, 4, 1, 1, Kind.BARREL);
                at(l, 3, 2, 1, Kind.BARREL);
                at(l, 3, 2, 4, Kind.PUMPKIN, S);
            }
            default -> {
                at(l, 1, 1, 1, Kind.BOOKSHELF);
                at(l, 2, 1, 1, Kind.BOOKSHELF);
                at(l, 3, 1, 1, Kind.CAULDRON);
                at(l, 4, 1, 1, Kind.LECTERN);
                at(l, 3, 2, 4, Kind.POT);
                at(l, 4, 2, 4, Kind.POT);
            }
        }
        at(l, 3, 4, 3, Kind.LANTERN);
        int y = floorY(3, 1) + 1;
        at(l, 1, y, 1, Kind.BED_FOOT);
        at(l, 1, y, 2, Kind.BED_HEAD);
        at(l, 3, y, 1, Kind.CHEST);
        at(l, 3, y, 6, Kind.FENCE);
        at(l, 3, y + 1, 6, Kind.PLATE);
        at(l, 2, y, 6, Kind.SEAT_W);
        at(l, 3, y + 2, 4, Kind.LANTERN);
        return l;
    }
}
