package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;

import java.util.ArrayList;
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
 * The same building at the four stations of a village's life: a poor hamlet makes do with a cage of posts for a jail
 * and a lean-to for an alehouse, an ordinary village builds the usual timber houses, a wealthy one dresses them in
 * stone, and a city raises stone prisons, grand inns, foundries, warehouses and paved squares. When the village climbs
 * a station, its builder takes the old buildings down one by one and puts up the better ones (see
 * {@link Rebuild#renovate}).
 */
public final class Tiers {
    public static final int POOR = 0, ORDINARY = 1, WEALTHY = 2, CITY = 3;

    private static String[] a(String... s) {
        return s;
    }

    /** Designs per kind of building, for each station from poor to city. */
    private static final Map<String, String[][]> TIERS = Map.ofEntries(
            Map.entry("townhall", new String[][]{a("townhall-mc:townhall1"), a("townhall", "townhall-mc:townhall1"), a("townhall-3", "townhall-mc:townhall3"), a("townhall-4", "townhall-mc:townhall4", "townhall-mc:townhall5")}),
            Map.entry("jail", new String[][]{a("jail-2"), a("jail-ordinary"), a("jail-wealthy"), a("jail-city")}),
            Map.entry("tavern", new String[][]{a("tavern-poor"), a("tavern-ordinary", "tavern-mc:tavern1"), a("tavern-wealthy", "tavern-mc:tavern2"), a("tavern-city", "tavern-mc:tavern3")}),
            Map.entry("storage", new String[][]{a("storage-poor"), a("storage-ordinary"), a("storage-wealthy", "storage-mc:warehouse3"), a("storage-city", "storage-mc:warehouse4", "storage-mc:warehouse5")}),
            Map.entry("chapel", new String[][]{a("chapel-4"), a("chapel-3"), a("chapel"), a("chapel-2")}),
            Map.entry("smithy", new String[][]{a("smithy-3"), a("smithy-2", "smithy-mc:blacksmith2"), a("smithy-wealthy", "smithy-mc:blacksmith3"), a("smithy-city", "smithy-mc:blacksmith5")}),
            Map.entry("market", new String[][]{a("market-3"), a("market"), a("market-2"), a("market-4")}),
            Map.entry("barracks", new String[][]{a("barracks-poor"), a("barracks-ordinary"), a("barracks-wealthy", "barracks-mc:barracks3"), a("barracks-city", "barracks-mc:barracks5")}),
            Map.entry("well", new String[][]{a("well-4"), a("well"), a("well-2"), a("well-3")}),
            Map.entry("tower", new String[][]{a("tower-3"), a("tower-2"), a("tower"), a("tower-4")}),
            Map.entry("granary", new String[][]{a("granary-poor"), a("granary-ordinary"), a("granary-wealthy"), a("granary-city")}),
            Map.entry("mill", new String[][]{a("mill-poor"), a("mill-2"), a("mill"), a("mill-city")}),
            Map.entry("sawmill", new String[][]{a("sawmill-poor"), a("sawmill", "sawmill-mc:sawmill2"), a("sawmill-2", "sawmill-mc:sawmill2"), a("sawmill-city", "sawmill-mc:sawmill4")}),
            Map.entry("mason", new String[][]{a("mason-poor"), a("mason", "mason-mc:stonemason2"), a("mason-2", "mason-mc:stonemason2"), a("mason-city", "mason-mc:stonemason4")}),
            Map.entry("charcoal", new String[][]{a("charcoal"), a("charcoal"), a("charcoal-2"), a("charcoal-city")}),
            Map.entry("armory", new String[][]{a("armory-poor"), a("armory"), a("armory-wealthy"), a("armory-city")}),
            Map.entry("shop", new String[][]{a(), a(), a("shop-general", "shop-bakery", "shop-mc:bakery3"), a("shop-bakery", "shop-weaver", "shop-general", "shop-apothecary", "shop-mc:bakery5")}),
            Map.entry("home", new String[][]{a("home-mc:home1", "home-mc:home1", "home-mc:home1", "cruck", "hut"),
                    a("home-mc:home2", "home-mc:home2", "home-mc:home1", "home-mc:home2", "house", "cottage"),
                    a("home-mc:home3", "home-mc:home3", "home-mc:home4", "home-mc:home3", "wealden", "manor"),
                    a("home-mc:home5", "home-mc:home4", "home-mc:home5", "home-mc:home5", "rowhouse")})
    );

    /** Older designs still standing in older villages, by the station they were built for. They are rebuilt in the new style. */
    private static final Map<String, Integer> LEGACY = Map.ofEntries(
            Map.entry("jail", 1), Map.entry("jail-3", 2), Map.entry("jail-4", 3),
            Map.entry("tavern-3", 0), Map.entry("tavern", 1), Map.entry("tavern-2", 2), Map.entry("tavern-4", 3),
            Map.entry("storage-4", 0), Map.entry("storage", 1), Map.entry("storage-2", 1), Map.entry("storage-3", 2), Map.entry("storage-5", 3),
            Map.entry("smithy", 2), Map.entry("smithy-4", 3),
            Map.entry("barracks-3", 0), Map.entry("barracks", 1), Map.entry("barracks-2", 2), Map.entry("barracks-4", 3),
            Map.entry("granary", 1), Map.entry("granary-2", 2), Map.entry("armory-2", 2),
            Map.entry("shop", 3), Map.entry("shop-2", 3), Map.entry("shop-3", 3), Map.entry("shop-4", 3));

    /** Every kind with stations, and the designs of one station, for the test commands. */
    public static java.util.Set<String> kinds() {
        return new java.util.TreeSet<>(TIERS.keySet());
    }

    public static String[] row(String kind, int tier) {
        String[][] rows = TIERS.get(kind);
        return rows == null ? new String[0] : rows[Math.max(0, Math.min(CITY, tier))];
    }

    /** Kinds the builder rebuilds in a better design when the village climbs a station. The churchyard keeps its dead. */
    private static final java.util.Set<String> RENEWED = java.util.Set.of("sawmill", "mason", "charcoal", "armory", "townhall", "jail", "tavern", "storage", "smithy", "market", "barracks", "well", "tower", "granary", "mill", "shop");

    private static final java.util.Set<String> HOMES = java.util.Set.of("hut", "longhouse", "twin", "house", "porch", "annex", "farmhouse", "mansion", "manor", "tallhouse", "cruck", "wealden", "rowhouse", "cottage");

    private Tiers() {
    }

    /** The design of this kind of building for a village at this station; kinds without stations get a design at random. */
    public static String design(String type, int tier, net.minecraft.util.math.random.Random random, Village village) {
        String[][] rows = TIERS.get(type);
        if (rows == null) return Buildings.variant(type, random);
        for (int t = Math.min(tier, CITY); t >= 0; t--) {
            String[] row = rows[t];
            if (row.length == 0) continue;
            if (village != null && type.equals("shop")) {
                // a street of different shops: one of each before the second of any
                List<String> missing = new ArrayList<>();
                for (String s : row) {
                    boolean built = false;
                    for (Built b : village.built) if (b.type().equals(s)) built = true;
                    if (!built) missing.add(s);
                }
                if (!missing.isEmpty()) return missing.get(random.nextInt(missing.size()));
            }
            // MineColonies buildings (Templates) in the style of the village's land; where none suits, our own
            List<String> fit = new ArrayList<>();
            for (String d : row) {
                if (!Templates.placeholder(d)) fit.add(d);
                else if (village == null) fit.add(d); // resolved when the site is chosen (Builder.startRing)
                else {
                    String r = Templates.resolve(d, village);
                    if (r != null) fit.add(r);
                }
            }
            if (fit.isEmpty()) continue;
            return fit.get(random.nextInt(fit.size()));
        }
        if (type.equals("townhall")) return "townhall"; // no hall in the land's style: the plain one, not a big one at random
        return Buildings.variant(type, random);
    }

    /** The lowest station a design belongs to, -1 for designs that have none. */
    public static int tierOf(String design) {
        if (Templates.isTemplate(design)) { // "home-mc.sandstone.residencetower3" stands in the table as "home-mc:home3"
            String rest = design.substring(design.indexOf("-mc.") + 4);
            design = design.substring(0, design.indexOf("-mc.")) + "-mc:" + Templates.logical(rest.substring(rest.indexOf('.') + 1));
        }
        String base = isHome(design) ? "home" : Buildings.base(design);
        String[][] rows = TIERS.get(base);
        if (rows == null) return -1;
        for (int t = 0; t < rows.length; t++) for (String s : rows[t]) if (s.equals(design)) return t;
        return LEGACY.getOrDefault(design, -1);
    }

    public static boolean isHome(String type) {
        return HOMES.contains(type) || type != null && type.startsWith("home-mc");
    }

    /** Whether a building of this design is behind the village and due to be rebuilt. */
    static boolean outdated(String design, int tier) {
        int t = tierOf(design);
        if (t < 0) return false;
        if (isHome(design)) return t + 2 <= tier; // homes lag behind: the huts go when the village is wealthy
        return RENEWED.contains(Buildings.base(design)) && (t < tier || LEGACY.containsKey(design) && t <= tier);
    }

    // ---------- where things are in the designs ----------

    /** The cell a prisoner is put in, plan coordinates. */
    static int[] jailCell(String type) {
        if (Works.jailPoints(type) != null) return Works.jailPoints(type)[0];
        return switch (type) {
            case "jail-2" -> new int[]{2, 0, 2};
            case "jail-3" -> new int[]{2, 1, 1};
            case "jail-4" -> new int[]{4, 1, 2};
            default -> new int[]{2, 1, 1};
        };
    }

    /** Where a prisoner is let out. */
    static int[] jailExit(String type) {
        if (Works.jailPoints(type) != null) return Works.jailPoints(type)[1];
        return switch (type) {
            case "jail-2" -> new int[]{2, 0, 6};
            case "jail-3" -> new int[]{4, 0, 8};
            case "jail-4" -> new int[]{5, 0, 10};
            default -> new int[]{3, 0, 7};
        };
    }

    /** A tower's lookout {x, y, z} and the foot of its ladder {x, y, z}. */
    static int[][] towerPoints(String type) {
        return switch (type) {
            case "tower-3" -> new int[][]{{2, 8, 2}, {2, 1, 1}};
            case "tower-4" -> new int[][]{{3, 15, 3}, {3, 1, 1}};
            default -> new int[][]{{2, 11, 2}, {2, 1, 1}};
        };
    }

    static int[][] graves(String type) {
        return type.equals("chapel-4") ? new int[][]{{6, 2}, {7, 2}, {6, 4}, {7, 4}} : null;
    }

    static int[] mournSpot(String type) {
        return type.equals("chapel-4") ? new int[]{6, 0, 7} : null;
    }

    // ---------- plans ----------

    static List<Step> plan(String type) {
        return switch (type) {
            case "jail-2" -> stockade();
            case "jail-3" -> lockhouse();
            case "jail-4" -> prison();
            case "tavern-3" -> alehouse();
            case "tavern-4" -> grandInn();
            case "storage-4" -> shed();
            case "storage-5" -> warehouse();
            case "chapel-4" -> shrine();
            case "smithy-3" -> openHearth();
            case "smithy-4" -> foundry();
            case "market-3" -> stalls();
            case "market-4" -> square();
            case "barracks-3" -> guardHut();
            case "barracks-4" -> garrison();
            case "well-4" -> pitWell();
            case "tower-3" -> lookout();
            case "tower-4" -> keep();
            case "shop" -> shop(0);
            case "shop-2" -> shop(1);
            case "shop-3" -> shop(2);
            case "shop-4" -> shop(3);
            default -> Forms.plan(type);
        };
    }

    static int[] footprint(String type) {
        return switch (type) {
            case "jail-2" -> new int[]{8, 6};
            case "jail-3" -> new int[]{13, 7};
            case "jail-4" -> new int[]{16, 9};
            case "tavern-3" -> new int[]{9, 8};
            case "tavern-4" -> new int[]{12, 10};
            case "storage-4" -> new int[]{7, 5};
            case "storage-5" -> new int[]{15, 9};
            case "chapel-4" -> new int[]{9, 7};
            case "smithy-3" -> new int[]{7, 5};
            case "smithy-4" -> new int[]{14, 8};
            case "market-3" -> new int[]{7, 6};
            case "market-4" -> new int[]{15, 15};
            case "barracks-3" -> new int[]{9, 5};
            case "barracks-4" -> new int[]{19, 9};
            case "well-4" -> new int[]{5, 5};
            case "tower-3" -> new int[]{5, 5};
            case "tower-4" -> new int[]{7, 7};
            case "shop", "shop-2", "shop-3", "shop-4" -> new int[]{8, 9};
            default -> Forms.footprint(type);
        };
    }

    /** Posts at the corners, a plank wall at the back, a slab roof: the poor man's workshop or store. */
    private static void leanTo(List<Step> l, int w, int d, Kind back) {
        for (int[] c : new int[][]{{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}}) for (int y = 0; y <= 2; y++) at(l, c[0], y, c[1], Kind.PILLAR);
        for (int x = 1; x < w - 1; x++) for (int y = 0; y <= 1; y++) at(l, x, y, 0, back);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) at(l, x, 3, z, Kind.SLAB);
    }

    // ---------- jails ----------

    /** A poor hamlet's jail: a cage of log posts and fence under a slab roof, and the stocks beside it. */
    static List<Step> stockade() {
        List<Step> l = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean edge = x == 0 || z == 0 || x == 4 || z == 4;
                boolean corner = (x == 0 || x == 4) && (z == 0 || z == 4);
                if (!edge) continue;
                for (int y = 0; y <= 1; y++) {
                    if (corner) at(l, x, y, z, Kind.PILLAR);
                    else if (x == 2 && z == 4 && y == 0) at(l, x, y, z, Kind.GATE, S);
                    else at(l, x, y, z, Kind.FENCE);
                }
            }
        }
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) at(l, x, 2, z, Kind.SLAB);
        at(l, 1, 0, 1, Kind.HAY);
        // the stocks
        at(l, 6, 0, 1, Kind.FENCE);
        at(l, 6, 0, 3, Kind.FENCE);
        for (int z = 1; z <= 3; z++) at(l, 6, 1, z, Kind.SLAB);
        return l;
    }

    /** A wealthy village's lock-up: stone, two barred cells, the guard's table, the stocks outside. */
    static List<Step> lockhouse() {
        int w = 9, d = 7;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 1, false, false, true, true));
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) if (Blueprints.window(x, z, w, d)) at(l, x, 2, z, Kind.BARS);
        for (int y = 1; y <= 2; y++) {
            at(l, 4, y, 1, Kind.STONE);
            at(l, 4, y, 2, Kind.STONE);
            for (int x = 1; x <= 7; x++) {
                if (x == 4) at(l, x, y, 3, Kind.STONE);
                else if (y == 1 && (x == 2 || x == 6)) at(l, x, y, 3, Kind.GATE, S);
                else at(l, x, y, 3, Kind.BARS);
            }
        }
        at(l, 1, 1, 1, Kind.HAY);
        at(l, 7, 1, 1, Kind.HAY);
        at(l, 7, 1, 5, Kind.CHEST);
        at(l, 1, 1, 5, Kind.FENCE);
        at(l, 1, 2, 5, Kind.PLATE);
        at(l, 2, 1, 5, Kind.SEAT_E);
        at(l, 4, 3, 4, Kind.LANTERN);
        // the stocks
        at(l, 10, 0, 2, Kind.FENCE);
        at(l, 12, 0, 2, Kind.FENCE);
        for (int x = 10; x <= 12; x++) at(l, x, 1, 2, Kind.SLAB);
        lamp(l, 11, 5);
        return l;
    }

    /** A city prison: two storeys of stone, a row of cells, the warden's desk, the guards' room above, a walled yard. */
    static List<Step> prison() {
        int w = 11, d = 9;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 2, false, false, true, true));
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) if (Blueprints.window(x, z, w, d)) at(l, x, 2, z, Kind.BARS);
        // cells: two of them along the back wall, stone between, iron bars and gates in front; a passage at x 2
        // past them to the foot of the stairs
        for (int y = 1; y <= 3; y++) {
            for (int z = 1; z <= 3; z++) {
                at(l, 3, y, z, Kind.STONE);
                at(l, 6, y, z, Kind.STONE);
            }
            for (int x = 2; x <= 9; x++) {
                if (x == 3 || x == 6 || (x == 2 && y == 3)) at(l, x, y, 4, Kind.STONE);
                else if (x == 2) continue;
                else if (y == 1 && (x == 4 || x == 8)) at(l, x, y, 4, Kind.GATE, S);
                else if (y <= 2) at(l, x, y, 4, Kind.BARS);
                else at(l, x, y, 4, Kind.STONE);
            }
        }
        at(l, 5, 1, 1, Kind.HAY);
        at(l, 9, 1, 1, Kind.HAY);
        at(l, 4, 1, 1, Kind.CAULDRON);
        // the warden's desk
        at(l, 8, 1, 6, Kind.LECTERN);
        at(l, 9, 1, 7, Kind.CHEST);
        at(l, 9, 1, 6, Kind.SEAT_E);
        at(l, 3, 1, 6, Kind.ARMOR_STAND);
        at(l, 5, 3, 6, Kind.LANTERN);
        // the guards' room above
        at(l, 8, 5, 6, Kind.BED_FOOT);
        at(l, 8, 5, 7, Kind.BED_HEAD);
        at(l, 9, 5, 6, Kind.BED_FOOT);
        at(l, 9, 5, 7, Kind.BED_HEAD);
        at(l, 9, 5, 1, Kind.CHEST);
        at(l, 8, 5, 1, Kind.BARREL);
        at(l, 5, 7, 4, Kind.LANTERN);
        // the yard: stone walls three high, a lamp and a dummy for the guards
        for (int x = 12; x <= 15; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 15 || z == 0 || z == d - 1;
                if (!edge) continue;
                for (int y = 0; y <= 2; y++) at(l, x, y, z, (x == 15 && (z == 0 || z == d - 1)) ? Kind.ACCENT : Kind.STONE);
            }
        }
        for (int x = 12; x <= 14; x++) for (int z = 1; z < d - 1; z++) at(l, x, -1, z, Kind.STONE);
        lamp(l, 13, 2);
        dummy(l, 13, 0, 6);
        return l;
    }

    // ---------- taverns ----------

    /** A poor hamlet's alehouse: a lean-to with barrels and a smoker, benches of logs, a fire in front. */
    static List<Step> alehouse() {
        List<Step> l = new ArrayList<>();
        leanTo(l, 9, 6, Kind.WALL);
        at(l, 1, 0, 1, Kind.BARREL);
        at(l, 2, 0, 1, Kind.BARREL);
        at(l, 1, 1, 1, Kind.BARREL);
        at(l, 6, 0, 1, Kind.CRAFT);
        at(l, 7, 0, 1, Kind.SMOKER);
        at(l, 4, 0, 3, Kind.FENCE);
        at(l, 4, 1, 3, Kind.PLATE);
        at(l, 3, 0, 3, Kind.SEAT_W);
        at(l, 5, 0, 3, Kind.SEAT_E);
        for (int x = 2; x <= 4; x++) at(l, x, 0, 7, Kind.LOG_X);
        at(l, 6, 0, 7, Kind.SMOKE);
        at(l, 8, 0, 7, Kind.LOG_Z);
        return l;
    }

    /** A city's grand inn: three storeys, a bar and tables below, guest rooms with beds above, lanterns everywhere. */
    static List<Step> grandInn() {
        int w = 11, d = 9;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 3, false, false, true, true));
        at(l, 6, 1, 1, Kind.BARREL);
        at(l, 7, 1, 1, Kind.CRAFT);
        at(l, 8, 1, 1, Kind.SMOKER);
        at(l, 9, 1, 1, Kind.BARREL);
        at(l, 9, 2, 1, Kind.BARREL);
        for (int x = 6; x <= 9; x++) at(l, x, 1, 3, Kind.BARREL); // the bar
        for (int[] t : new int[][]{{3, 5}, {8, 6}}) {
            at(l, t[0], 1, t[1], Kind.FENCE);
            at(l, t[0], 2, t[1], Kind.PLATE);
            at(l, t[0] - 1, 1, t[1], Kind.SEAT_W);
            at(l, t[0] + 1, 1, t[1], Kind.SEAT_E);
        }
        at(l, 3, 3, 4, Kind.LANTERN);
        at(l, 8, 3, 4, Kind.LANTERN);
        for (int level = 1; level <= 2; level++) {
            int y = level * Blueprints.LEVEL_HEIGHT + 1;
            for (int x : new int[]{3, 6, 9}) {
                at(l, x, y, 1, Kind.BED_FOOT);
                at(l, x, y, 2, Kind.BED_HEAD);
            }
            at(l, 9, y, 6, Kind.CHEST);
            at(l, 4, y, 6, Kind.BARREL);
            for (int x = 3; x <= 7; x++) at(l, x, y, 4, Kind.CARPET, level);
            at(l, 5, y + 2, 4, Kind.LANTERN);
        }
        return l;
    }

    // ---------- stores ----------

    /** A poor hamlet's store: an open shed with a couple of chests, a barrel and a stack of logs. */
    static List<Step> shed() {
        List<Step> l = new ArrayList<>();
        leanTo(l, 7, 5, Kind.WALL);
        at(l, 2, 0, 1, Kind.CHEST);
        at(l, 3, 0, 1, Kind.CHEST);
        at(l, 5, 0, 1, Kind.BARREL);
        at(l, 1, 0, 3, Kind.HAY);
        at(l, 4, 0, 3, Kind.LOG_X);
        at(l, 5, 0, 3, Kind.LOG_X);
        at(l, 4, 1, 3, Kind.LOG_X);
        return l;
    }

    /** A city warehouse: two storeys of stone, rows of chests on both floors, a crane hoisting a barrel at the side. */
    static List<Step> warehouse() {
        int w = 13, d = 9;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 2, false, false, true, false));
        for (int x = 3; x <= 10; x++) at(l, x, 1, 1, Kind.CHEST);
        for (int x : new int[]{2, 3, 4, 8, 9, 10}) at(l, x, 1, d - 2, Kind.CHEST);
        for (int x : new int[]{4, 8}) {
            at(l, x, 1, 4, Kind.BARREL);
            at(l, x, 2, 4, Kind.BARREL);
        }
        at(l, 6, 3, 4, Kind.LANTERN);
        for (int x = 3; x <= 10; x++) {
            at(l, x, 5, 1, Kind.CHEST);
            at(l, x, 5, d - 2, Kind.CHEST);
        }
        at(l, 6, 5, 4, Kind.HAY);
        at(l, 6, 7, 4, Kind.LANTERN);
        // the crane
        for (int y = 0; y <= 8; y++) at(l, 14, y, 4, Kind.PILLAR);
        for (int x = 12; x <= 13; x++) at(l, x, 8, 4, Kind.LOG_X);
        for (int y = 5; y <= 7; y++) at(l, 13, y, 4, Kind.CHAIN);
        at(l, 13, 4, 4, Kind.BARREL);
        return l;
    }

    // ---------- chapel ----------

    /** A poor hamlet's shrine: a little roof on four posts over a stone altar with a lantern, a cross on top, a few graves. */
    static List<Step> shrine() {
        List<Step> l = new ArrayList<>();
        for (int[] c : new int[][]{{1, 1}, {3, 1}, {1, 3}, {3, 3}}) for (int y = 0; y <= 2; y++) at(l, c[0], y, c[1], Kind.PILLAR);
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) at(l, x, 3, z, Kind.SLAB);
        at(l, 2, 0, 2, Kind.STONE);
        at(l, 2, 1, 2, Kind.LANTERN);
        at(l, 2, 0, 4, Kind.PEW, N);
        for (int y = 4; y <= 6; y++) at(l, 2, y, 2, Kind.FENCE);
        at(l, 1, 5, 2, Kind.FENCE);
        at(l, 3, 5, 2, Kind.FENCE);
        // the graveyard
        for (int x = 5; x <= 8; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean edge = x == 5 || x == 8 || z == 0 || z == 6;
                if (!edge) continue;
                if (x == 6 && z == 6) at(l, x, 0, z, Kind.GATE, S);
                else at(l, x, 0, z, Kind.FENCE);
            }
        }
        return l;
    }

    // ---------- smithies ----------

    /** A poor hamlet's smithy: a hearth under a lean-to, a stone back wall, the anvil and the grindstone. */
    static List<Step> openHearth() {
        List<Step> l = new ArrayList<>();
        leanTo(l, 7, 5, Kind.STONE);
        at(l, 2, 0, 1, Kind.FURNACE);
        at(l, 4, 0, 2, Kind.ANVIL);
        at(l, 5, 0, 1, Kind.GRINDSTONE);
        at(l, 1, 0, 3, Kind.BARREL);
        at(l, 5, 0, 3, Kind.CRAFT);
        return l;
    }

    /** A city foundry: a long stone hall with a row of hearths, two anvils, the smithing table, a quenching cauldron. */
    static List<Step> foundry() {
        int w = 13, d = 8;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 1, false, false, true, true));
        for (int x = 2; x <= 4; x++) at(l, x, 1, 1, Kind.FURNACE);
        for (int x = 2; x <= 4; x++) for (int y = 2; y <= 3; y++) at(l, x, y, 1, Kind.STONE);
        at(l, 6, 1, 3, Kind.ANVIL);
        at(l, 8, 1, 3, Kind.ANVIL);
        at(l, 10, 1, 1, Kind.GRINDSTONE);
        at(l, 11, 1, 1, Kind.SMITHING);
        at(l, 11, 1, 3, Kind.CHEST);
        at(l, 11, 1, 4, Kind.BARREL);
        at(l, 10, 1, 5, Kind.CAULDRON);
        at(l, 2, 1, 5, Kind.ARMOR_STAND);
        at(l, 3, 3, 4, Kind.LANTERN);
        at(l, 9, 3, 4, Kind.LANTERN);
        return l;
    }

    // ---------- markets ----------

    /** A poor hamlet's market: two stalls of posts and slabs, a barrel and a chest each, a log bench. */
    static List<Step> stalls() {
        List<Step> l = new ArrayList<>();
        for (int ox : new int[]{0, 4}) {
            for (int[] c : new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}) {
                at(l, ox + c[0], 0, c[1], Kind.FENCE);
                at(l, ox + c[0], 1, c[1], Kind.FENCE);
            }
            for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) at(l, ox + x, 2, z, Kind.SLAB);
            at(l, ox + 1, 0, 2, Kind.BARREL);
            at(l, ox + 1, 0, 0, Kind.CHEST);
        }
        for (int x = 2; x <= 4; x++) at(l, x, 0, 4, Kind.LOG_X);
        at(l, 6, 0, 4, Kind.PUMPKIN, S);
        return l;
    }

    /** A city square: paved in stone, a fountain in the middle, four stalls with coloured awnings, lamps and benches. */
    static List<Step> square() {
        List<Step> l = new ArrayList<>();
        int n = 15;
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) at(l, x, -1, z, (x + z) % 3 == 0 ? Kind.ACCENT : Kind.STONE);
        shift(Variants.fountain(), 5, 5, l);
        int color = 0;
        for (int[] o : new int[][]{{0, 0}, {12, 0}, {0, 12}, {12, 12}}) {
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
        for (int[] p : new int[][]{{2, 7}, {12, 7}, {7, 2}, {7, 12}}) lamp(l, p[0], p[1]);
        at(l, 4, 0, 6, Kind.PEW, E);
        at(l, 4, 0, 8, Kind.PEW, E);
        at(l, 10, 0, 6, Kind.PEW, W);
        at(l, 10, 0, 8, Kind.PEW, W);
        return l;
    }

    // ---------- barracks ----------

    /** A poor hamlet's guardhouse: a little cabin with two cots, a rack and a straw dummy outside. */
    static List<Step> guardHut() {
        int w = 6, d = 5;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 1, false, false, false, false));
        at(l, 1, 1, 1, Kind.BED_FOOT);
        at(l, 1, 1, 2, Kind.BED_HEAD);
        at(l, 4, 1, 1, Kind.BED_FOOT);
        at(l, 4, 1, 2, Kind.BED_HEAD);
        at(l, 2, 1, 1, Kind.CHEST);
        at(l, 3, 1, 1, Kind.BARREL);
        dummy(l, 7, 0, 2);
        return l;
    }

    /** A city garrison: a long two-storey stone barracks, arms below and bunks above, a drill yard with targets. */
    static List<Step> garrison() {
        int w = 13, d = 9;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 2, false, false, true, true));
        for (int x = 3; x <= 9; x += 2) at(l, x, 1, 1, Kind.ARMOR_STAND);
        for (int x = 4; x <= 10; x += 2) at(l, x, 1, 1, Kind.CHEST);
        at(l, 11, 1, 1, Kind.SMITHING);
        at(l, 11, 1, 3, Kind.GRINDSTONE);
        at(l, 3, 1, 6, Kind.FENCE);
        at(l, 3, 2, 6, Kind.PLATE);
        at(l, 2, 1, 6, Kind.SEAT_W);
        at(l, 4, 1, 6, Kind.SEAT_E);
        at(l, 6, 3, 4, Kind.LANTERN);
        for (int x = 3; x <= 11; x += 2) {
            at(l, x, 5, 1, Kind.BED_FOOT);
            at(l, x, 5, 2, Kind.BED_HEAD);
        }
        for (int x = 3; x <= 11; x += 4) at(l, x, 5, 6, Kind.CHEST);
        at(l, 6, 7, 4, Kind.LANTERN);
        // the drill yard
        for (int x = 14; x <= 18; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 18 || z == 0 || z == d - 1;
                if (edge) at(l, x, 0, z, x == 16 && z == d - 1 ? Kind.GATE : Kind.FENCE, S);
                else at(l, x, -1, z, Kind.PATH);
            }
        }
        at(l, 17, 1, 2, Kind.TARGET);
        at(l, 17, 0, 2, Kind.FENCE);
        at(l, 17, 1, 5, Kind.TARGET);
        at(l, 17, 0, 5, Kind.FENCE);
        dummy(l, 15, 0, 3);
        lamp(l, 15, 6);
        return l;
    }

    // ---------- well and towers ----------

    /** A poor hamlet's well: a hole in a ring of logs, two posts and a crossbar with the bucket chain. */
    static List<Step> pitWell() {
        List<Step> l = new ArrayList<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                if (x == 2 && z == 2) at(l, x, -1, z, Kind.WATER);
                else at(l, x, 0, z, z == 2 ? Kind.LOG_Z : Kind.LOG_X);
            }
        }
        for (int y = 1; y <= 2; y++) {
            at(l, 1, y, 2, Kind.FENCE);
            at(l, 3, y, 2, Kind.FENCE);
        }
        for (int x = 1; x <= 3; x++) at(l, x, 3, 2, Kind.LOG_X);
        at(l, 2, 2, 2, Kind.CHAIN);
        return l;
    }

    /** A poor hamlet's lookout: four posts, a plank wall for the ladder, a railed deck. */
    static List<Step> lookout() {
        List<Step> l = new ArrayList<>();
        int n = 5, h = 7;
        for (int[] c : new int[][]{{0, 0}, {n - 1, 0}, {0, n - 1}, {n - 1, n - 1}}) for (int y = 0; y <= h + 1; y++) at(l, c[0], y, c[1], Kind.PILLAR);
        for (int y = 0; y < h; y++) at(l, 2, y, 0, Kind.WALL);
        for (int y = 0; y <= h; y++) at(l, 2, y, 1, Kind.LADDER);
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) if (!(x == 2 && z == 1)) at(l, x, h, z, Kind.FLOOR);
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                boolean edge = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                boolean corner = (x == 0 || x == n - 1) && (z == 0 || z == n - 1);
                if (edge && !corner) at(l, x, h + 1, z, Kind.FENCE);
            }
        }
        return l;
    }

    /** A city tower: seven blocks square and fifteen high, three floors inside, arrow slits, a crenellated top. */
    static List<Step> keep() {
        List<Step> l = new ArrayList<>();
        int n = 7, h = 14;
        Blueprints.plane(l, n, n, 0, Kind.STONE, -1);
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < n; x++) {
                for (int z = 0; z < n; z++) {
                    boolean edge = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                    if (!edge) continue;
                    boolean corner = (x == 0 || x == n - 1) && (z == 0 || z == n - 1);
                    if (x == n / 2 && z == n - 1 && y <= 2) continue; // the door
                    boolean slit = y % 5 == 3 && !corner && z != 0 && (x == n / 2 || z == n / 2);
                    at(l, x, y, z, corner ? Kind.ACCENT : slit ? Kind.BARS : Kind.STONE);
                }
            }
        }
        at(l, n / 2, 1, n - 1, Kind.DOOR);
        for (int y = 1; y <= h; y++) at(l, 3, y, 1, Kind.LADDER);
        for (int y : new int[]{5, 10, h}) {
            for (int x = 1; x < n - 1; x++) for (int z = 1; z < n - 1; z++) if (!(x == 3 && z == 1)) at(l, x, y, z, Kind.FLOOR);
        }
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                boolean edge = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                if (edge && (x + z) % 2 == 0) at(l, x, h + 1, z, Kind.STONE);
            }
        }
        for (int[] c : new int[][]{{0, 0}, {n - 1, 0}, {0, n - 1}, {n - 1, n - 1}}) at(l, c[0], h + 2, c[1], Kind.LANTERN);
        at(l, 1, 6, 5, Kind.CHEST);
        at(l, 5, 6, 5, Kind.ARMOR_STAND);
        at(l, 3, 4, 3, Kind.LANTERN);
        at(l, 3, 9, 3, Kind.LANTERN);
        return l;
    }

    // ---------- city shops ----------

    /**
     * A shop in a city street: a narrow two-storey house, a stone ground floor with the shop under a coloured awning,
     * the shopkeeper's room above. {@code kind}: 0 a bakery, 1 a weaver's, 2 a general store, 3 an apothecary.
     */
    static List<Step> shop(int kind) {
        int w = 7, d = 8;
        List<Step> l = new ArrayList<>(Blueprints.shell(w, d, 2, false, false, true, true));
        for (int x = 0; x < w; x++) if (Math.abs(x - w / 2) > 1) at(l, x, 3, d, Kind.WOOL, kind);
        // the counter, and the goods behind it
        at(l, 4, 1, 4, Kind.BARREL);
        at(l, 5, 1, 4, Kind.BARREL);
        switch (kind) {
            case 0 -> { // bakery
                at(l, 4, 1, 1, Kind.SMOKER);
                at(l, 5, 1, 1, Kind.SMOKER);
                at(l, 3, 1, 1, Kind.HAY);
                at(l, 5, 1, 3, Kind.BARREL); // (a step free before the ovens)
                at(l, 4, 2, 4, Kind.POT);
            }
            case 1 -> { // weaver's
                at(l, 3, 1, 1, Kind.WOOL, 0);
                at(l, 4, 1, 1, Kind.WOOL, 1);
                at(l, 5, 1, 1, Kind.WOOL, 2);
                at(l, 5, 2, 1, Kind.WOOL, 3);
                at(l, 5, 1, 2, Kind.CRAFT);
                for (int x = 2; x <= 4; x++) at(l, x, 1, 6, Kind.CARPET, x);
            }
            case 2 -> { // general store
                at(l, 3, 1, 1, Kind.CHEST);
                at(l, 4, 1, 1, Kind.CHEST);
                at(l, 5, 1, 1, Kind.BARREL);
                at(l, 5, 2, 1, Kind.BARREL);
                at(l, 5, 1, 3, Kind.BARREL);
                at(l, 4, 2, 4, Kind.PUMPKIN, S);
            }
            default -> { // apothecary
                at(l, 3, 1, 1, Kind.BOOKSHELF);
                at(l, 4, 1, 1, Kind.BOOKSHELF);
                at(l, 5, 1, 1, Kind.CAULDRON);
                at(l, 5, 1, 3, Kind.LECTERN);
                at(l, 4, 2, 4, Kind.POT);
                at(l, 5, 2, 4, Kind.POT);
            }
        }
        at(l, 3, 3, 3, Kind.LANTERN);
        // the shopkeeper's room
        at(l, 5, 5, 1, Kind.BED_FOOT);
        at(l, 5, 5, 2, Kind.BED_HEAD);
        at(l, 5, 5, 5, Kind.CHEST);
        at(l, 3, 5, 5, Kind.CARPET, kind);
        at(l, 3, 7, 3, Kind.LANTERN);
        return l;
    }
}
