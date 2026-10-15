package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;

import java.util.ArrayList;
import java.util.List;

import static net.mca.settlements.Blueprints.E;
import static net.mca.settlements.Blueprints.N;
import static net.mca.settlements.Blueprints.S;
import static net.mca.settlements.Blueprints.W;

/**
 * The village's workshops, public buildings and the different kinds of homes. Every plan is decorated in the same
 * spirit as the houses: stone footing, timber frame, the land's own wood, lights, piles of the trade's goods.
 * Coordinates as in {@link Blueprints}: y = 0 is the floor, y = -1 the ground, the front (door) faces +z.
 */
public final class Buildings {
    public static final int SAWMILL_W = 9, SAWMILL_D = 6;
    /** Grave plots in the churchyard, in chapel plan coordinates. */
    public static final int[][] GRAVES = {{9, 2}, {11, 2}, {9, 4}, {11, 4}, {9, 6}, {11, 6}};

    private Buildings() {
    }

    public static List<Step> plan(String type, int w, int d, int f) {
        return switch (type) {
            case "sawmill" -> sawmill();
            case "charcoal" -> charcoal();
            case "mason" -> mason();
            case "granary" -> granary();
            case "mill" -> mill();
            case "barracks" -> barracks();
            case "jail" -> jail();
            case "market" -> market();
            case "chapel" -> chapel();
            case "hut" -> hut();
            case "annex" -> annex();
            case "manor" -> manor();
            case "longhouse" -> longhouse();
            case "porch" -> porch();
            case "tallhouse" -> tallhouse();
            case "twin" -> twin();
            case "farmhouse" -> farmhouse();
            case "cottage" -> cottage();
            case "mansion" -> mansion();
            default -> Variants.plan(type);
        };
    }

    /** The kind of a building whatever its design: "storage-2" is a storage. */
    public static String base(String type) {
        if (type == null) return null;
        int i = type.indexOf('-'); // "storage-city", "shop-bakery", "demolish:jail-3": the kind comes first
        return i > 0 ? type.substring(0, i) : type;
    }

    private static final java.util.Map<String, String[]> DESIGNS = java.util.Map.ofEntries(
            java.util.Map.entry("storage", new String[]{"storage-poor", "storage-ordinary", "storage-wealthy", "storage-city", "storage", "storage-2", "storage-3", "storage-4", "storage-5"}),
            java.util.Map.entry("granary", new String[]{"granary-poor", "granary-ordinary", "granary-wealthy", "granary-city", "granary", "granary-2"}),
            java.util.Map.entry("sawmill", new String[]{"sawmill-poor", "sawmill", "sawmill-2", "sawmill-city"}),
            java.util.Map.entry("mason", new String[]{"mason-poor", "mason", "mason-2", "mason-city"}),
            java.util.Map.entry("mill", new String[]{"mill-poor", "mill-2", "mill", "mill-city"}),
            java.util.Map.entry("smithy", new String[]{"smithy-3", "smithy-2", "smithy-wealthy", "smithy-city", "smithy", "smithy-4"}),
            java.util.Map.entry("armory", new String[]{"armory-poor", "armory", "armory-wealthy", "armory-city", "armory-2"}),
            java.util.Map.entry("tavern", new String[]{"tavern-poor", "tavern-ordinary", "tavern-wealthy", "tavern-city", "tavern", "tavern-2", "tavern-3", "tavern-4"}),
            java.util.Map.entry("barracks", new String[]{"barracks-poor", "barracks-ordinary", "barracks-wealthy", "barracks-city", "barracks", "barracks-2", "barracks-3", "barracks-4"}),
            java.util.Map.entry("market", new String[]{"market", "market-2", "market-3", "market-4"}),
            java.util.Map.entry("chapel", new String[]{"chapel", "chapel-2", "chapel-3", "chapel-4"}),
            java.util.Map.entry("well", new String[]{"well", "well-2", "well-3", "well-4"}),
            java.util.Map.entry("tower", new String[]{"tower", "tower-2", "tower-3", "tower-4"}),
            java.util.Map.entry("charcoal", new String[]{"charcoal", "charcoal-2", "charcoal-city"}),
            java.util.Map.entry("jail", new String[]{"jail-2", "jail-ordinary", "jail-wealthy", "jail-city", "jail", "jail-3", "jail-4"}),
            java.util.Map.entry("townhall", new String[]{"townhall", "townhall-2", "townhall-3", "townhall-4"}),
            java.util.Map.entry("shop", new String[]{"shop-bakery", "shop-weaver", "shop-general", "shop-apothecary", "shop", "shop-2", "shop-3", "shop-4"}));

    /** One of the designs of a kind of building, at random. */
    public static String variant(String type, net.minecraft.util.math.random.Random random) {
        String[] d = DESIGNS.get(type);
        return d == null ? type : d[random.nextInt(d.length)];
    }

    public static java.util.Collection<String[]> designs() {
        return DESIGNS.values();
    }

    public static int count(Village village, String type) {
        int n = 0;
        for (Built b : village.built) if (base(b.type()).equals(type)) n++;
        return n;
    }

    public static boolean has(Village village, String type) {
        for (Built b : village.built) if (base(b.type()).equals(type)) return true;
        return false;
    }

    public static Built find(Village village, String type) {
        for (Built b : village.built) if (base(b.type()).equals(type)) return b;
        return null;
    }

    // ---------- helpers ----------

    static void at(List<Step> l, int x, int y, int z, Kind k) {
        l.add(new Step(x, y, z, k));
    }

    static void at(List<Step> l, int x, int y, int z, Kind k, int face) {
        l.add(new Step(x, y, z, k, face));
    }

    static void shift(List<Step> from, int ox, int oz, List<Step> to) {
        for (Step s : from) to.add(new Step(s.dx() + ox, s.dy(), s.dz() + oz, s.kind(), s.face()));
    }

    /** Floor of the footprint: stone under the edges, planks (or stone) inside. */
    static void footing(List<Step> l, int x0, int z0, int w, int d, Kind inside) {
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                boolean edge = x == x0 || z == z0 || x == x0 + w - 1 || z == z0 + d - 1;
                at(l, x, 0, z, edge ? Kind.STONE : inside);
            }
        }
    }

    /** A lamp post: a fence post with a lantern on top. */
    static void lamp(List<Step> l, int x, int z) {
        at(l, x, 0, z, Kind.FENCE);
        at(l, x, 1, z, Kind.FENCE);
        at(l, x, 2, z, Kind.LANTERN);
    }

    /** A training dummy or scarecrow: post, straw body, pumpkin head. */
    static void dummy(List<Step> l, int x, int y, int z) {
        at(l, x, y, z, Kind.FENCE);
        at(l, x, y + 1, z, Kind.HAY);
        at(l, x, y + 2, z, Kind.PUMPKIN, S);
    }

    // ---------- workshops ----------

    /** Sawmill: an open timber shed with the saw, the workbench and stacks of logs, more logs piled outside. */
    static List<Step> sawmill() {
        List<Step> l = new ArrayList<>();
        int w = SAWMILL_W, d = SAWMILL_D;
        footing(l, 0, 0, w, d, Kind.FLOOR);
        for (int x : new int[]{0, 4, 8}) for (int z : new int[]{0, d - 1}) for (int y = 1; y <= 3; y++) at(l, x, y, z, Kind.PILLAR);
        for (int x = 1; x < w - 1; x++) {
            if (x == 4) continue;
            for (int y = 1; y <= 2; y++) at(l, x, y, 0, Kind.WALL);
            at(l, x, 3, 0, Kind.BEAM_X);
            at(l, x, 3, d - 1, Kind.BEAM_X); // the front stays open under the beam
        }
        for (int z = 1; z < d - 1; z++) {
            for (int x : new int[]{0, w - 1}) {
                at(l, x, 1, z, Kind.WALL);
                at(l, x, 2, z, z == 2 ? Kind.GLASS : Kind.WALL);
                at(l, x, 3, z, Kind.BEAM_Z);
            }
        }
        at(l, -1, 2, 1, Kind.SHUTTER, W);
        at(l, -1, 2, 3, Kind.SHUTTER, W);
        Blueprints.gable(l, w, d, 3, false);
        at(l, 4, 1, 2, Kind.STONECUTTER);
        at(l, 6, 1, 1, Kind.CRAFT);
        at(l, 7, 1, 1, Kind.CHEST);
        at(l, 7, 1, 3, Kind.BARREL); // (a step in front of the chest stays free)
        for (int x = 1; x <= 3; x++) at(l, x, 1, 1, Kind.LOG_X);
        for (int x = 1; x <= 2; x++) at(l, x, 2, 1, Kind.LOG_X);
        at(l, 1, 1, 3, Kind.LOG_Z);
        at(l, 5, 1, 4, Kind.PILLAR); // the chopping block
        at(l, 3, 2, 1, Kind.TORCH);
        at(l, 0, 2, d, Kind.TORCH);
        at(l, 8, 2, d, Kind.TORCH);
        for (int z = 1; z <= 3; z++) at(l, w, 0, z, Kind.LOG_Z);
        for (int z = 1; z <= 2; z++) at(l, w, 1, z, Kind.LOG_Z);
        for (int z = d; z <= d + 2; z++) at(l, 4, -1, z, Kind.PATH);
        return l;
    }

    /** Charcoal pit: an earth mound smouldering in a stone ring, logs stacked ready, a barrel for the charcoal. */
    static List<Step> charcoal() {
        List<Step> l = new ArrayList<>();
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) if (x == 0 || z == 0 || x == 4 || z == 4) at(l, x, 0, z, Kind.STONE);
        for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) at(l, x, 0, z, Kind.MOUND);
        at(l, 2, 1, 1, Kind.MOUND);
        at(l, 1, 1, 2, Kind.MOUND);
        at(l, 3, 1, 2, Kind.MOUND);
        at(l, 2, 1, 3, Kind.MOUND);
        at(l, 2, 1, 2, Kind.SMOKE);
        for (int x = 0; x <= 2; x++) at(l, x, 0, 5, Kind.LOG_X);
        for (int x = 0; x <= 1; x++) at(l, x, 1, 5, Kind.LOG_X);
        at(l, 4, 0, 5, Kind.BARREL);
        at(l, 5, 0, 5, Kind.CHEST);
        lamp(l, 5, 0);
        return l;
    }

    /** Stonemason's yard: stone walls, an open front, the stonecutter and grindstone, stone blocks stacked about. */
    static List<Step> mason() {
        List<Step> l = new ArrayList<>();
        int w = 9, d = 6;
        footing(l, 0, 0, w, d, Kind.STONE);
        for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) for (int y = 1; y <= 3; y++) at(l, c[0], y, c[1], Kind.ACCENT);
        for (int x = 1; x < w - 1; x++) {
            for (int y = 1; y <= 2; y++) at(l, x, y, 0, Kind.STONE);
            at(l, x, 3, 0, Kind.BEAM_X);
            if (x != 4) at(l, x, 1, d - 1, Kind.STONE); // a low front wall with a gap in the middle
            at(l, x, 3, d - 1, Kind.BEAM_X);
        }
        for (int z = 1; z < d - 1; z++) {
            for (int x : new int[]{0, w - 1}) {
                at(l, x, 1, z, Kind.STONE);
                at(l, x, 2, z, z == 2 ? Kind.GLASS : Kind.STONE);
                at(l, x, 3, z, Kind.BEAM_Z);
            }
        }
        Blueprints.gable(l, w, d, 3, false);
        at(l, 2, 1, 1, Kind.STONECUTTER);
        at(l, 3, 1, 1, Kind.GRINDSTONE);
        at(l, 6, 1, 1, Kind.CHEST);
        at(l, 1, 1, 3, Kind.CAULDRON);
        for (int[] p : new int[][]{{6, 1, 3}, {7, 1, 3}, {7, 2, 3}, {7, 1, 2}}) at(l, p[0], p[1], p[2], Kind.ACCENT);
        at(l, 4, 2, 1, Kind.TORCH);
        at(l, 0, 2, d, Kind.TORCH);
        at(l, 8, 2, d, Kind.TORCH);
        for (int[] p : new int[][]{{w, 0, 1}, {w, 0, 2}, {w, 1, 1}}) at(l, p[0], p[1], p[2], Kind.STONE);
        for (int z = d; z <= d + 2; z++) at(l, 4, -1, z, Kind.PATH);
        return l;
    }

    /** Granary: a stone-footed store full of barrels and hay, with a composter and pumpkins at the side. */
    static List<Step> granary() {
        List<Step> l = Blueprints.shell(8, 7, 1, false, false, true, false);
        for (int x = 1; x <= 3; x++) at(l, x, 1, 1, Kind.BARREL);
        at(l, 1, 2, 1, Kind.BARREL);
        at(l, 1, 1, 3, Kind.CHEST); // in front of the barrels a free step
        for (int[] p : new int[][]{{6, 1, 1}, {6, 1, 2}, {6, 2, 1}, {5, 1, 1}}) at(l, p[0], p[1], p[2], Kind.HAY);
        at(l, 1, 1, 4, Kind.COMPOSTER);
        at(l, 6, 1, 4, Kind.BARREL);
        at(l, 4, 3, 3, Kind.LANTERN);
        for (int[] p : new int[][]{{8, 0, 1}, {8, 0, 2}, {8, 1, 1}}) at(l, p[0], p[1], p[2], Kind.HAY);
        at(l, 8, 0, 4, Kind.PUMPKIN, E);
        at(l, 8, 0, 5, Kind.PUMPKIN, E);
        return l;
    }

    /** Mill and bakery: a bakehouse with ovens and a table, and a stone windmill tower with its sails turned to the front. */
    static List<Step> mill() {
        List<Step> l = new ArrayList<>(Blueprints.shell(7, 8, 1, false, false, false, false));
        // the bakery's chimney on the west side, the tower takes the east
        for (int y = 0; y <= 9; y++) at(l, -1, y, 0, Kind.CHIMNEY);
        at(l, -1, 10, 0, Kind.SMOKE);
        at(l, 1, 1, 1, Kind.SMOKER);
        at(l, 2, 1, 1, Kind.SMOKER);
        at(l, 3, 1, 1, Kind.CRAFT);
        at(l, 4, 1, 1, Kind.BARREL);
        at(l, 5, 1, 1, Kind.BARREL);
        at(l, 2, 1, 4, Kind.FENCE);
        at(l, 2, 2, 4, Kind.PLATE);
        at(l, 1, 1, 4, Kind.SEAT_W);
        at(l, 3, 1, 4, Kind.SEAT_E);
        at(l, 5, 1, 5, Kind.HAY);
        at(l, 5, 2, 5, Kind.HAY);
        // tower: x 7..10, z 2..5
        for (int x = 7; x <= 10; x++) {
            for (int z = 2; z <= 5; z++) {
                at(l, x, 0, z, Kind.STONE);
                boolean edge = x == 7 || x == 10 || z == 2 || z == 5;
                if (!edge) continue;
                boolean corner = (x == 7 || x == 10) && (z == 2 || z == 5);
                for (int y = 1; y <= 9; y++) {
                    if (x == 7 && z == 3 && y <= 2) {
                        at(l, x, y, z, Kind.CLEAR); // the doorway from the bakery
                        continue;
                    }
                    Kind k = y <= 6 ? (corner ? Kind.ACCENT : Kind.STONE) : (corner ? Kind.PILLAR : (y == 8 && !corner && (x == 10 || z == 5) ? Kind.GLASS : Kind.WALL));
                    at(l, x, y, z, k);
                }
            }
        }
        at(l, 6, 1, 3, Kind.CLEAR);
        at(l, 6, 2, 3, Kind.CLEAR);
        List<Step> cap = new ArrayList<>();
        Blueprints.plane(cap, 4, 4, 9, Kind.ROOF, -1);
        Blueprints.gable(cap, 4, 4, 9, false);
        shift(cap, 7, 2, l);
        at(l, 8, 1, 4, Kind.GRINDSTONE); // the millstone, clear of the doorway
        at(l, 9, 1, 4, Kind.BARREL);
        at(l, 8, 3, 4, Kind.TORCH);
        // sails on the front of the tower, around the hub
        int hx = 8, hy = 7, sz = 1;
        at(l, hx, hy, sz, Kind.FENCE);
        for (int i = 1; i <= 3; i++) {
            at(l, hx, hy + i, sz, Kind.FENCE);
            at(l, hx + 1, hy + i, sz, Kind.WOOL);
            at(l, hx, hy - i, sz, Kind.FENCE);
            at(l, hx - 1, hy - i, sz, Kind.WOOL);
            at(l, hx - i, hy, sz, Kind.FENCE);
            at(l, hx - i, hy + 1, sz, Kind.WOOL);
            at(l, hx + i, hy, sz, Kind.FENCE);
            at(l, hx + i, hy - 1, sz, Kind.WOOL);
        }
        return l;
    }

    /** Barracks: a stone-footed hall with a row of bunks and chests, armour stands, and a training yard with dummies. */
    static List<Step> barracks() {
        List<Step> l = Blueprints.shell(11, 7, 1, false, false, true, true);
        for (int x = 1; x <= 9; x += 2) {
            at(l, x, 1, 1, Kind.BED_FOOT);
            at(l, x, 1, 2, Kind.BED_HEAD);
        }
        for (int x = 2; x <= 8; x += 2) at(l, x, 1, 1, Kind.CHEST);
        at(l, 2, 1, 4, Kind.ARMOR_STAND);
        at(l, 8, 1, 4, Kind.ARMOR_STAND);
        at(l, 5, 3, 3, Kind.LANTERN);
        dummy(l, 1, 0, 9);
        dummy(l, 9, 0, 9);
        dummy(l, 2, 0, 9);
        return l;
    }

    /** Jail and stocks: a small stone lock-up with barred windows and a barred cell, the stocks on the square beside it. */
    static List<Step> jail() {
        int w = 6, d = 6;
        List<Step> l = Blueprints.shell(w, d, 1, false, false, true, false);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) if (Blueprints.window(x, z, w, d)) at(l, x, 2, z, Kind.BARS);
        for (int x = 1; x <= 4; x++) {
            at(l, x, 1, 3, x == 2 ? Kind.GATE : Kind.BARS, S);
            at(l, x, 2, 3, Kind.BARS);
        }
        at(l, 1, 1, 1, Kind.HAY);
        at(l, 4, 1, 1, Kind.CAULDRON);
        // the stocks
        at(l, 7, 0, 2, Kind.FENCE);
        at(l, 9, 0, 2, Kind.FENCE);
        for (int x = 7; x <= 9; x++) at(l, x, 1, 2, Kind.SLAB);
        lamp(l, 8, 4);
        return l;
    }

    /** Market square: paved ground, four stalls with coloured awnings and counters, a lamp and flowers in the middle. */
    static List<Step> market() {
        List<Step> l = new ArrayList<>();
        int n = 11;
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) at(l, x, -1, z, Kind.PATH);
        int color = 0;
        for (int[] o : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) {
            int ox = o[0], oz = o[1];
            for (int[] c : new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
                at(l, ox + c[0], 0, oz + c[1], Kind.FENCE);
                at(l, ox + c[0], 1, oz + c[1], Kind.FENCE);
            }
            for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) at(l, ox + x, 2, oz + z, Kind.WOOL, color);
            int front = oz == 0 ? oz + 2 : oz, back = oz == 0 ? oz : oz + 2;
            at(l, ox + 1, 0, front, Kind.BARREL);
            at(l, ox + 1, 1, front, Kind.POT);
            at(l, ox + 1, 0, back, Kind.CHEST);
            color++;
        }
        lamp(l, 5, 5);
        for (int[] p : new int[][]{{4, 5}, {6, 5}, {5, 4}, {5, 6}}) at(l, p[0], 0, p[1], Kind.POT);
        at(l, 3, 0, 5, Kind.PEW, E);
        at(l, 7, 0, 5, Kind.PEW, W);
        return l;
    }

    /** Chapel and churchyard: a stone chapel with pews, an altar and a bell, a fenced graveyard beside it. */
    static List<Step> chapel() {
        int w = 7, d = 10;
        List<Step> l = Blueprints.shell(w, d, 1, true, false, true, false);
        at(l, 3, 1, 1, Kind.ACCENT);
        at(l, 3, 2, 1, Kind.LANTERN);
        at(l, 2, 1, 1, Kind.BOOKSHELF);
        at(l, 4, 1, 1, Kind.BOOKSHELF);
        for (int z = 3; z <= 7; z += 2) {
            for (int x : new int[]{1, 2, 4, 5}) at(l, x, 1, z, Kind.PEW, S);
        }
        for (int z = 2; z <= 8; z++) at(l, 3, 1, z, Kind.CARPET);
        // churchyard: x 8..13, z 0..8
        for (int x = 8; x <= 13; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean edge = x == 8 || x == 13 || z == 0 || z == 8;
                if (!edge) continue;
                if (x == 10 && z == 8) at(l, x, 0, z, Kind.GATE, S);
                else at(l, x, 0, z, Kind.FENCE);
            }
        }
        for (int[] p : new int[][]{{12, 3}, {12, 5}, {10, 1}, {10, 7}}) at(l, p[0], 0, p[1], Kind.FLOWER);
        at(l, 12, 0, 7, Kind.FENCE);
        at(l, 12, 1, 7, Kind.LANTERN);
        return l;
    }

    // ---------- homes ----------

    /** A poor man's hut: a little log cabin with one bed, a barrel and a woodpile by the wall. */
    static List<Step> hut() {
        List<Step> l = new ArrayList<>();
        int w = 5, d = 5;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) at(l, x, 0, z, Kind.FLOOR);
        for (int y = 1; y <= 2; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (!edge) continue;
                    if (x == 2 && z == d - 1) continue; // the door
                    boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    if (corner) at(l, x, y, z, Kind.PILLAR);
                    else if (y == 2 && (x == 0 || x == w - 1) && z == 2) at(l, x, y, z, Kind.GLASS);
                    else at(l, x, y, z, (z == 0 || z == d - 1) ? Kind.LOG_X : Kind.LOG_Z);
                }
            }
        }
        at(l, 2, 1, d - 1, Kind.DOOR);
        Blueprints.gable(l, w, d, 2, false);
        at(l, 3, 1, 1, Kind.BED_FOOT);
        at(l, 3, 1, 2, Kind.BED_HEAD);
        at(l, 1, 1, 1, Kind.BARREL);
        at(l, 1, 2, 1, Kind.POT);
        at(l, 1, 1, 3, Kind.CRAFT);
        at(l, 3, 2, 3, Kind.TORCH);
        at(l, -1, 2, 1, Kind.SHUTTER, W);
        at(l, -1, 2, 3, Kind.SHUTTER, W);
        at(l, w, 2, 1, Kind.SHUTTER, E);
        at(l, w, 2, 3, Kind.SHUTTER, E);
        for (int z = 1; z <= 3; z++) at(l, w, 0, z, Kind.LOG_Z);
        at(l, w, 1, 2, Kind.LOG_Z);
        at(l, 2, 0, d, Kind.STEP, N);
        for (int z = d + 1; z <= d + 2; z++) at(l, 2, -1, z, Kind.PATH);
        at(l, 0, 0, d, Kind.FLOWER);
        at(l, 4, 0, d, Kind.FLOWER);
        return l;
    }

    /** A family house with a lean-to shed at the side and a fenced kitchen garden behind, scarecrow and all. */
    static List<Step> annex() {
        List<Step> l = new ArrayList<>();
        // the garden behind: x 0..10, z 0..4
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean edge = x == 0 || x == 10 || z == 0 || z == 4;
                if (edge) {
                    if (x == 0 && z == 2) at(l, x, 0, z, Kind.GATE, W);
                    else at(l, x, 0, z, Kind.FENCE);
                } else if (x == 5 && z == 2) {
                    at(l, x, -1, z, Kind.WATER);
                } else if (x == 1 && z == 1) {
                    at(l, x, -1, z, Kind.FARMLAND);
                    dummy(l, x, 0, z);
                } else if (x == 9 && z == 3) {
                    at(l, x, 0, z, Kind.COMPOSTER);
                } else {
                    at(l, x, -1, z, Kind.FARMLAND);
                    at(l, x, 0, z, Kind.CROP);
                }
            }
        }
        // the house: x 4..10, z 5..11
        shift(Blueprints.house(7, 7, 1), 4, 5, l);
        // the shed: x 0..3, z 5..9, a lean-to with a slab roof
        for (int x = 0; x <= 3; x++) for (int z = 5; z <= 9; z++) at(l, x, 0, z, Kind.FLOOR);
        for (int y = 1; y <= 2; y++) {
            at(l, 0, y, 5, Kind.PILLAR);
            at(l, 0, y, 9, Kind.PILLAR);
            for (int z = 6; z <= 8; z++) at(l, 0, y, z, Kind.WALL);
            for (int x = 1; x <= 3; x++) at(l, x, y, 5, Kind.WALL);
        }
        for (int x = 0; x <= 3; x++) for (int z = 5; z <= 9; z++) at(l, x, 3, z, Kind.SLAB);
        at(l, 1, 1, 6, Kind.BARREL);
        at(l, 2, 1, 6, Kind.CHEST);
        at(l, 1, 1, 8, Kind.LOG_Z);
        at(l, 1, 2, 8, Kind.LOG_Z);
        at(l, 3, 1, 6, Kind.CRAFT);
        return l;
    }

    /** A well-to-do family's two-storey house: stone ground floor, a library corner, a dining table, lanterns. */
    static List<Step> manor() {
        List<Step> l = Blueprints.shell(9, 8, 2, false, true, true, true);
        at(l, 1, 1, 6, Kind.BOOKSHELF);
        at(l, 2, 1, 6, Kind.BOOKSHELF);
        at(l, 3, 1, 3, Kind.FENCE);
        at(l, 3, 2, 3, Kind.PLATE);
        at(l, 2, 1, 3, Kind.SEAT_W);
        at(l, 4, 1, 3, Kind.SEAT_E);
        at(l, 7, 1, 4, Kind.CHEST);
        at(l, 4, 3, 4, Kind.LANTERN);
        for (int x = 3; x <= 5; x++) at(l, x, 5, 1, Kind.BOOKSHELF);
        for (int x = 3; x <= 5; x++) at(l, x, 5, 5, Kind.CARPET);
        return l;
    }

    /** A long narrow house: the kitchen by the door, the sleeping places at the far end, shutters all along. */
    static List<Step> longhouse() {
        int w = 5, d = 11;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 1, false, false, false, true));
        at(l, 1, 1, 1, Kind.BED_FOOT);
        at(l, 1, 1, 2, Kind.BED_HEAD);
        at(l, 3, 1, 1, Kind.BED_FOOT);
        at(l, 3, 1, 2, Kind.BED_HEAD);
        // the middle (x 2) is the way from the door to the beds, everything else stands along the walls
        at(l, 1, 1, 3, Kind.CHEST);
        at(l, 3, 1, 3, Kind.BARREL);
        at(l, 3, 2, 3, Kind.POT);
        at(l, 1, 1, 6, Kind.FENCE); // the table against the wall, a bench at either end
        at(l, 1, 2, 6, Kind.PLATE);
        at(l, 1, 1, 5, Kind.PEW, S);
        at(l, 1, 1, 7, Kind.PEW, N);
        at(l, 1, 1, 8, Kind.SMOKER);
        at(l, 3, 1, 8, Kind.CRAFT);
        at(l, 2, 3, 3, Kind.LANTERN);
        at(l, 2, 3, 7, Kind.LANTERN);
        for (int z = 2; z <= 5; z++) at(l, 2, 1, z, Kind.CARPET, 3);
        return l;
    }

    /** A house with a covered front porch: posts, a slab roof, benches, a hanging lantern and flowers. */
    static List<Step> porch() {
        int w = 7, d = 6;
        List<Step> l = new ArrayList<>(Blueprints.house(w, d, 1));
        for (int x = 0; x < w; x++) for (int z = d; z <= d + 2; z++) at(l, x, 0, z, Kind.FLOOR);
        for (int y = 1; y <= 3; y++) {
            at(l, 0, y, d + 2, Kind.FENCE);
            at(l, w - 1, y, d + 2, Kind.FENCE);
        }
        for (int x = -1; x <= w; x++) for (int z = d; z <= d + 2; z++) at(l, x, 4, z, Kind.SLAB);
        at(l, 1, 1, d + 1, Kind.PEW, E);
        at(l, w - 2, 1, d + 1, Kind.PEW, W);
        at(l, w / 2, 3, d + 1, Kind.LANTERN);
        at(l, 1, 1, d, Kind.POT);
        at(l, w - 2, 1, d, Kind.POT);
        at(l, w / 2, 0, d + 3, Kind.STEP, N);
        for (int z = d + 4; z <= d + 5; z++) at(l, w / 2, -1, z, Kind.PATH);
        return l;
    }

    /** A narrow three-storey town house with a stone ground floor. */
    static List<Step> tallhouse() {
        return Blueprints.shell(5, 6, 3, false, true, true, true);
    }

    /** Two small homes under one roof line, sharing a wall: two families, two doors, two chimneys. */
    static List<Step> twin() {
        List<Step> l = new ArrayList<>();
        shift(Blueprints.shell(5, 6, 1, false, true, false, false), 0, 0, l);
        for (int y = 0; y <= 8; y++) at(l, -1, y, 0, Kind.CHIMNEY); // the left home's chimney on its own side
        at(l, -1, 9, 0, Kind.SMOKE);
        shift(Blueprints.house(5, 6, 1), 4, 0, l);
        return l;
    }

    /**
     * A two-storey cottage: a course of stone at the foot of the walls, a timber frame with the posts showing, the
     * upper floor on beams; hedges under the front windows, a light either side of the door, a bench by it, and a
     * woodshed built on at the side with the firewood stacked under it. Kitchen and table below, the beds upstairs
     * up a ladder.
     */
    static List<Step> cottage() {
        List<Step> l = new ArrayList<>();
        int w = 7, d = 7;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) at(l, x, 0, z, Kind.FLOOR);
        for (int y = 1; y <= 6; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (!edge) continue;
                    boolean corner = (x == 0 || x == w - 1) && (z == 0 || z == d - 1);
                    boolean frontBack = z == 0 || z == d - 1;
                    if (corner) at(l, x, y, z, Kind.PILLAR);
                    else if (y == 4) at(l, x, y, z, frontBack ? Kind.BEAM_X : Kind.BEAM_Z); // the upper floor rests on beams
                    else if (y == 1) at(l, x, y, z, Kind.STONE); // the stone course
                    else if (y == 2 && (frontBack && (x == 1 || x == w - 2) || !frontBack && (z == 2 || z == d - 3))) at(l, x, y, z, Kind.GLASS);
                    else if (y == 5 && (frontBack && x == w / 2 || !frontBack && z == d / 2)) at(l, x, y, z, Kind.GLASS);
                    else at(l, x, y, z, Kind.WALL);
                }
            }
        }
        // the door, in the middle of the front
        at(l, w / 2, 1, d - 1, Kind.DOOR);
        at(l, w / 2, 2, d - 1, Kind.CLEAR);
        // the upper floor, with the ladder's hole
        for (int x = 1; x < w - 1; x++) for (int z = 1; z < d - 1; z++) if (!(x == w - 2 && z == 1)) at(l, x, 4, z, Kind.FLOOR);
        for (int y = 1; y <= 5; y++) at(l, w - 2, y, 1, Kind.LADDER);
        Blueprints.gable(l, w, d, 6, false);
        // the chimney on the left, from the ground through the roof
        for (int y = 0; y <= 11; y++) at(l, -1, y, d / 2, Kind.CHIMNEY);
        at(l, -1, 12, d / 2, Kind.SMOKE);
        // below: the kitchen at the back, the table in the middle
        at(l, 1, 1, 3, Kind.FURNACE, E);
        at(l, 1, 1, 1, Kind.CRAFT);
        at(l, 2, 1, 1, Kind.BARREL);
        at(l, 3, 1, 1, Kind.CHEST);
        at(l, 3, 1, 3, Kind.FENCE);
        at(l, 3, 2, 3, Kind.PLATE);
        at(l, 2, 1, 3, Kind.SEAT_W);
        at(l, 4, 1, 3, Kind.SEAT_E);
        at(l, 3, 3, 3, Kind.LANTERN);
        // upstairs: two beds, a chest, a rug
        at(l, 1, 5, 1, Kind.BED_FOOT);
        at(l, 1, 5, 2, Kind.BED_HEAD);
        at(l, 3, 5, 1, Kind.BED_FOOT);
        at(l, 3, 5, 2, Kind.BED_HEAD);
        at(l, 1, 5, 5, Kind.CHEST);
        at(l, 3, 5, 4, Kind.CARPET, 3);
        at(l, 4, 6, 3, Kind.LANTERN);
        // the front: a step, two lights, hedges under the windows, a bench, the path
        at(l, w / 2, 0, d, Kind.STEP, N);
        at(l, w / 2 - 1, 2, d, Kind.TORCH, N);
        at(l, w / 2 + 1, 2, d, Kind.TORCH, N);
        at(l, 1, 0, d, Kind.HEDGE);
        at(l, w - 2, 0, d, Kind.HEDGE);
        at(l, 0, 0, d, Kind.FLOWER);
        at(l, w - 1, 0, d, Kind.FLOWER);
        for (int z = d + 1; z <= d + 2; z++) at(l, w / 2, -1, z, Kind.PATH);
        at(l, -1, 2, 2, Kind.SHUTTER, W);
        at(l, -1, 2, d - 3, Kind.SHUTTER, W);
        // the woodshed on the right: two posts, a slab roof, the logs stacked under it
        for (int y = 0; y <= 2; y++) {
            at(l, w + 1, y, 1, Kind.PILLAR);
            at(l, w + 1, y, d - 2, Kind.PILLAR);
        }
        for (int x = w; x <= w + 1; x++) for (int z = 0; z < d; z++) at(l, x, 3, z, Kind.SLAB);
        for (int z = 2; z <= d - 3; z++) {
            at(l, w, 0, z, Kind.LOG_Z);
            if (z != d - 3) at(l, w, 1, z, Kind.LOG_Z);
        }
        at(l, w + 1, 0, 3, Kind.BARREL);
        return l;
    }

    /** A farmhouse with a barn built on at the side: hay, a composter, a cauldron, a small fenced yard. */
    static List<Step> farmhouse() {
        List<Step> l = new ArrayList<>(Blueprints.shell(7, 7, 1, false, true, false, false));
        for (int y = 0; y <= 9; y++) at(l, -1, y, 0, Kind.CHIMNEY);
        at(l, -1, 10, 0, Kind.SMOKE);
        // the barn: x 7..12, open towards the yard in front
        for (int x = 7; x <= 12; x++) for (int z = 0; z <= 6; z++) at(l, x, 0, z, x == 12 || z == 0 ? Kind.STONE : Kind.FLOOR);
        for (int y = 1; y <= 3; y++) {
            for (int z = 0; z <= 6; z++) at(l, 12, y, z, z == 0 || z == 6 ? Kind.PILLAR : y == 3 ? Kind.BEAM_Z : Kind.WALL);
            for (int x = 8; x <= 11; x++) at(l, x, y, 0, y == 3 ? Kind.BEAM_X : Kind.WALL);
            at(l, 7, y, 6, Kind.PILLAR);
            at(l, 12, y, 6, Kind.PILLAR);
        }
        for (int x = 8; x <= 11; x++) at(l, x, 3, 6, Kind.BEAM_X);
        for (int x = 7; x <= 12; x++) for (int z = 0; z <= 6; z++) at(l, x, 4, z, Kind.SLAB);
        for (int[] p : new int[][]{{11, 1, 1}, {11, 1, 2}, {10, 1, 1}, {11, 2, 1}}) at(l, p[0], p[1], p[2], Kind.HAY);
        at(l, 8, 1, 1, Kind.COMPOSTER);
        at(l, 9, 1, 1, Kind.CAULDRON);
        at(l, 11, 1, 4, Kind.BARREL);
        // the yard in front of the barn
        for (int x = 7; x <= 12; x++) at(l, x, 0, 8, Kind.FENCE);
        at(l, 12, 0, 7, Kind.FENCE);
        at(l, 9, 0, 8, Kind.GATE, S);
        at(l, 8, 0, 7, Kind.PUMPKIN, S);
        return l;
    }

    /**
     * A wealthy family's mansion: two storeys on a stone ground floor, a pillared porch carrying a balcony with a door
     * out onto it, a library and dining room, and a walled flower garden with benches and a lamp at the side.
     */
    static List<Step> mansion() {
        int w = 11, d = 9;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 2, false, true, true, true));
        // porch and balcony
        for (int x = 3; x <= 7; x++) for (int z = d; z <= d + 1; z++) {
            at(l, x, 0, z, Kind.FLOOR);
            at(l, x, 4, z, Kind.FLOOR);
        }
        for (int y = 1; y <= 3; y++) {
            at(l, 3, y, d + 1, Kind.PILLAR);
            at(l, 7, y, d + 1, Kind.PILLAR);
        }
        for (int x = 3; x <= 7; x++) at(l, x, 5, d + 1, Kind.FENCE);
        at(l, 3, 5, d, Kind.FENCE);
        at(l, 7, 5, d, Kind.FENCE);
        at(l, 5, 5, d - 1, Kind.DOOR);
        at(l, 5, 6, d - 1, Kind.CLEAR);
        at(l, 5, 3, d + 1, Kind.LANTERN);
        at(l, 5, 0, d + 2, Kind.STEP, N);
        // inside: library, dining, more light
        for (int z = 4; z <= 6; z++) at(l, 1, 1, z, Kind.BOOKSHELF);
        at(l, 4, 1, 3, Kind.FENCE);
        at(l, 4, 2, 3, Kind.PLATE);
        at(l, 3, 1, 3, Kind.SEAT_W);
        at(l, 5, 1, 3, Kind.SEAT_E);
        at(l, 4, 3, 5, Kind.LANTERN);
        for (int x = 3; x <= 7; x++) at(l, x, 5, 1, Kind.BOOKSHELF);
        for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) at(l, x, 5, z, Kind.CARPET, 0);
        at(l, 8, 5, 6, Kind.CHEST);
        at(l, 8, 5, 5, Kind.CHEST);
        // the garden at the side
        for (int x = 12; x <= 15; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean edge = x == 15 || z == 0 || z == 8;
                if (edge) at(l, x, 0, z, Kind.HEDGE);
                else if ((x + z) % 2 == 0) at(l, x, 0, z, Kind.FLOWER);
            }
        }
        lamp(l, 13, 4);
        at(l, 14, 0, 2, Kind.PEW, W);
        at(l, 14, 0, 6, Kind.PEW, W);
        return l;
    }
}
