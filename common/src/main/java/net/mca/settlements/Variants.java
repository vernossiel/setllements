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
import static net.mca.settlements.Buildings.dummy;
import static net.mca.settlements.Buildings.footing;
import static net.mca.settlements.Buildings.lamp;
import static net.mca.settlements.Buildings.shift;

/**
 * Second and third designs of the village's buildings: the same trade or purpose, a different house. The builder picks
 * one at random each time, so no two villages look alike.
 */
public final class Variants {
    private Variants() {
    }

    static List<Step> plan(String type) {
        return switch (type) {
            case "storage-2" -> storageYard();
            case "storage-3" -> storeHouse();
            case "granary-2" -> granarySilo();
            case "sawmill-2" -> sawyard();
            case "mason-2" -> quarryYard();
            case "mill-2" -> bakery();
            case "smithy-2" -> openForge();
            case "armory-2" -> arsenal();
            case "tavern-2" -> inn();
            case "barracks-2" -> fortHouse();
            case "market-2" -> marketHall();
            case "chapel-2" -> church();
            case "chapel-3" -> wayside();
            case "well-2" -> coveredWell();
            case "well-3" -> fountain();
            case "tower-2" -> woodenTower();
            case "charcoal-2" -> kiln();
            default -> List.of();
        };
    }

    static int[] footprint(String type) {
        return switch (type) {
            case "storage-2" -> new int[]{13, 7};
            case "storage-3" -> new int[]{7, 7};
            case "granary-2" -> new int[]{10, 6};
            case "sawmill-2" -> new int[]{13, 7};
            case "mason-2" -> new int[]{9, 9};
            case "mill-2" -> new int[]{11, 7};
            case "smithy-2" -> new int[]{10, 7};
            case "armory-2" -> new int[]{11, 7};
            case "tavern-2" -> new int[]{10, 9};
            case "barracks-2" -> new int[]{14, 7};
            case "market-2" -> new int[]{11, 9};
            case "chapel-2" -> new int[]{16, 11};
            case "chapel-3" -> new int[]{11, 7};
            case "well-2", "well-3", "tower-2" -> new int[]{5, 5};
            case "charcoal-2" -> new int[]{6, 6};
            default -> null;
        };
    }

    /** Grave plots per churchyard design, and the spot by the gate where mourners stand. */
    static int[][] graves(String type) {
        return switch (type) {
            case "chapel-2" -> new int[][]{{1, 2}, {3, 2}, {1, 4}, {3, 4}, {1, 6}, {3, 6}, {1, 8}, {3, 8}};
            case "chapel-3" -> new int[][]{{7, 2}, {9, 2}, {7, 4}, {9, 4}};
            default -> Buildings.GRAVES;
        };
    }

    static int[] mournSpot(String type) {
        return switch (type) {
            case "chapel-2" -> new int[]{2, 0, 9};
            case "chapel-3" -> new int[]{8, 0, 5};
            default -> new int[]{10, 0, 7};
        };
    }

    // ---------- storehouses ----------

    /** A warehouse with barrels stacked along the walls and a fenced yard of logs and hay at the side. */
    static List<Step> storageYard() {
        List<Step> l = new ArrayList<>(Blueprints.shell(9, 7, 1, false, false, true, false));
        for (int x = 1; x <= 7; x++) {
            at(l, x, 1, 1, Kind.BARREL);
            if (x % 2 == 1) at(l, x, 2, 1, Kind.BARREL);
        }
        for (int z = 3; z <= 4; z++) {
            at(l, 1, 1, z, Kind.CHEST);
            at(l, 7, 1, z, Kind.CHEST);
        }
        at(l, 3, 1, 3, Kind.BARREL);
        at(l, 5, 1, 3, Kind.BARREL);
        at(l, 3, 2, 3, Kind.BARREL);
        at(l, 4, 3, 3, Kind.LANTERN);
        for (int x = 9; x <= 12; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean edge = x == 12 || z == 0 || z == 6;
                if (!edge) continue;
                if (x == 11 && z == 6) at(l, x, 0, z, Kind.GATE, S);
                else at(l, x, 0, z, Kind.FENCE);
            }
        }
        for (int x = 10; x <= 11; x++) at(l, x, 0, 1, Kind.LOG_X);
        at(l, 10, 1, 1, Kind.LOG_X);
        at(l, 10, 0, 3, Kind.HAY);
        at(l, 11, 0, 3, Kind.HAY);
        at(l, 10, 1, 3, Kind.HAY);
        at(l, 11, 0, 5, Kind.BARREL);
        return l;
    }

    /** A two-storey stone store: chests and barrels on both floors, stairs inside, a lantern under the roof. */
    static List<Step> storeHouse() {
        List<Step> l = new ArrayList<>(Blueprints.shell(7, 7, 2, false, false, true, true));
        for (int z = 1; z <= 3; z++) at(l, 5, 1, z, Kind.CHEST);
        at(l, 3, 1, 1, Kind.BARREL);
        at(l, 3, 2, 1, Kind.BARREL);
        at(l, 4, 1, 1, Kind.BARREL);
        for (int z = 1; z <= 4; z++) at(l, 5, 5, z, Kind.CHEST);
        at(l, 3, 5, 1, Kind.BARREL);
        at(l, 3, 5, 4, Kind.HAY);
        at(l, 3, 7, 3, Kind.LANTERN);
        at(l, 3, 3, 3, Kind.LANTERN);
        return l;
    }

    /** A barn with a stone silo full of grain at its side. */
    static List<Step> granarySilo() {
        List<Step> l = new ArrayList<>(Blueprints.shell(7, 6, 1, false, false, false, false));
        for (int[] p : new int[][]{{1, 1, 1}, {2, 1, 1}, {1, 2, 1}, {5, 1, 1}, {5, 1, 2}}) at(l, p[0], p[1], p[2], Kind.HAY);
        at(l, 3, 1, 1, Kind.BARREL);
        at(l, 4, 1, 1, Kind.CHEST);
        at(l, 5, 1, 3, Kind.COMPOSTER);
        for (int y = 0; y <= 8; y++) {
            for (int x = 7; x <= 9; x++) {
                for (int z = 1; z <= 3; z++) {
                    boolean center = x == 8 && z == 2;
                    boolean corner = (x == 7 || x == 9) && (z == 1 || z == 3);
                    if (center) {
                        if (y >= 1 && y <= 6) at(l, x, y, z, Kind.HAY);
                        else if (y == 0) at(l, x, y, z, Kind.STONE);
                    } else {
                        at(l, x, y, z, corner ? Kind.ACCENT : Kind.STONE);
                    }
                }
            }
        }
        for (int x = 7; x <= 9; x++) for (int z = 1; z <= 3; z++) at(l, x, 9, z, Kind.SLAB);
        at(l, 8, 4, 0, Kind.TORCH);
        return l;
    }

    // ---------- workshops ----------

    /** An open sawyard: a roof on posts, two saws, logs everywhere, a crane hoisting a log at the side. */
    static List<Step> sawyard() {
        List<Step> l = new ArrayList<>();
        int w = 9, d = 7;
        footing(l, 0, 0, w, d, Kind.FLOOR);
        for (int x : new int[]{0, 4, 8}) for (int z : new int[]{0, d - 1}) for (int y = 1; y <= 3; y++) at(l, x, y, z, Kind.PILLAR);
        for (int x = 1; x < w - 1; x++) {
            if (x == 4) continue;
            at(l, x, 3, 0, Kind.BEAM_X);
            at(l, x, 3, d - 1, Kind.BEAM_X);
        }
        for (int z = 1; z < d - 1; z++) {
            at(l, 0, 3, z, Kind.BEAM_Z);
            at(l, w - 1, 3, z, Kind.BEAM_Z);
        }
        Blueprints.gable(l, w, d, 3, false);
        at(l, 3, 1, 3, Kind.STONECUTTER);
        at(l, 5, 1, 3, Kind.STONECUTTER);
        for (int x = 1; x <= 3; x++) at(l, x, 1, 1, Kind.LOG_X);
        for (int x = 1; x <= 2; x++) at(l, x, 2, 1, Kind.LOG_X);
        for (int x = 5; x <= 7; x++) at(l, x, 1, 1, Kind.LOG_X);
        for (int x = 6; x <= 7; x++) at(l, x, 2, 1, Kind.LOG_X);
        at(l, 1, 1, 4, Kind.LOG_Z);
        at(l, 1, 1, 5, Kind.LOG_Z);
        at(l, 1, 2, 4, Kind.LOG_Z);
        at(l, 7, 1, 5, Kind.CHEST);
        at(l, 7, 1, 4, Kind.CRAFT);
        // the crane
        for (int y = 0; y <= 5; y++) at(l, 10, y, 3, Kind.PILLAR);
        at(l, 11, 5, 3, Kind.LOG_X);
        at(l, 12, 5, 3, Kind.LOG_X);
        at(l, 12, 4, 3, Kind.CHAIN);
        at(l, 12, 3, 3, Kind.CHAIN);
        at(l, 12, 2, 3, Kind.LOG_X);
        for (int x : new int[]{0, 4, 8}) at(l, x, 2, d, Kind.TORCH);
        for (int z = d; z <= d + 1; z++) at(l, 4, -1, z, Kind.PATH);
        return l;
    }

    /** A quarry yard: a fenced yard of cut stone with a little shed for the stonecutter. */
    static List<Step> quarryYard() {
        List<Step> l = new ArrayList<>();
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean edge = x == 0 || z == 0 || x == 8 || z == 8;
                if (!edge) continue;
                if (x == 4 && z == 8) at(l, x, 0, z, Kind.GATE, S);
                else at(l, x, 0, z, Kind.FENCE);
            }
        }
        for (int x = 1; x <= 4; x++) for (int z = 1; z <= 3; z++) at(l, x, 0, z, Kind.STONE);
        for (int[] c : new int[][]{{1, 1}, {4, 1}, {1, 3}, {4, 3}}) for (int y = 1; y <= 2; y++) at(l, c[0], y, c[1], Kind.PILLAR);
        for (int x = 0; x <= 5; x++) for (int z = 0; z <= 4; z++) at(l, x, 3, z, Kind.SLAB);
        at(l, 2, 1, 2, Kind.STONECUTTER);
        at(l, 3, 1, 2, Kind.GRINDSTONE);
        at(l, 2, 1, 1, Kind.CHEST);
        for (int[] p : new int[][]{{6, 0, 2}, {7, 0, 2}, {7, 1, 2}, {6, 0, 3}, {7, 0, 3}}) at(l, p[0], p[1], p[2], Kind.ACCENT);
        for (int[] p : new int[][]{{6, 0, 6}, {7, 0, 6}, {7, 1, 6}, {6, 0, 7}}) at(l, p[0], p[1], p[2], Kind.STONE);
        at(l, 2, 0, 6, Kind.CAULDRON);
        lamp(l, 4, 5);
        for (int z = 9; z <= 10; z++) at(l, 4, -1, z, Kind.PATH);
        return l;
    }

    /** A bakehouse with a stone dome oven outside and a millstone under the open sky. */
    static List<Step> bakery() {
        List<Step> l = new ArrayList<>(Blueprints.shell(7, 7, 1, false, false, false, false));
        for (int y = 0; y <= 9; y++) at(l, -1, y, 3, Kind.CHIMNEY);
        at(l, -1, 10, 3, Kind.SMOKE);
        at(l, 1, 1, 1, Kind.SMOKER);
        at(l, 2, 1, 1, Kind.SMOKER);
        at(l, 3, 1, 1, Kind.CRAFT);
        at(l, 4, 1, 1, Kind.BARREL);
        at(l, 5, 1, 1, Kind.BARREL);
        at(l, 2, 1, 4, Kind.FENCE);
        at(l, 2, 2, 4, Kind.PLATE);
        at(l, 1, 1, 4, Kind.SEAT_W);
        at(l, 3, 1, 4, Kind.SEAT_E);
        at(l, 5, 1, 4, Kind.HAY);
        // the dome oven
        for (int x = 8; x <= 10; x++) for (int z = 2; z <= 4; z++) at(l, x, 0, z, Kind.ACCENT);
        for (int x = 8; x <= 10; x++) {
            for (int z = 2; z <= 4; z++) {
                if (x == 9 && z == 3) at(l, x, 1, z, Kind.SMOKER);
                else if (!(x == 9 && z == 4)) at(l, x, 1, z, Kind.STONE);
                at(l, x, 2, z, Kind.STONE);
            }
        }
        at(l, 9, 3, 3, Kind.SMOKE);
        at(l, 8, 0, 6, Kind.GRINDSTONE);
        at(l, 10, 0, 6, Kind.HAY);
        return l;
    }

    /** An open forge: stone walls on three sides, the hearths with a chimney behind, anvil, quenching cauldron. */
    static List<Step> openForge() {
        List<Step> l = new ArrayList<>();
        int w = 9, d = 7;
        footing(l, 0, 0, w, d, Kind.STONE);
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < w; x++) at(l, x, y, 0, x == 0 || x == w - 1 ? Kind.ACCENT : Kind.STONE);
            for (int z = 1; z <= 3; z++) {
                at(l, 0, y, z, Kind.STONE);
                at(l, w - 1, y, z, Kind.STONE);
            }
            for (int x : new int[]{0, 4, 8}) at(l, x, y, d - 1, Kind.PILLAR);
        }
        for (int x = 1; x < w - 1; x++) if (x != 4) at(l, x, 3, d - 1, Kind.BEAM_X);
        for (int z = 4; z <= 5; z++) {
            at(l, 0, 3, z, Kind.BEAM_Z);
            at(l, w - 1, 3, z, Kind.BEAM_Z);
        }
        Blueprints.gable(l, w, d, 3, false);
        at(l, 2, 1, 1, Kind.FURNACE);
        at(l, 3, 1, 1, Kind.FURNACE);
        for (int y = 0; y <= 9; y++) at(l, 2, y, -1, Kind.CHIMNEY);
        at(l, 2, 10, -1, Kind.SMOKE);
        at(l, 4, 1, 3, Kind.ANVIL);
        at(l, 6, 1, 1, Kind.GRINDSTONE);
        at(l, 7, 1, 1, Kind.SMITHING);
        at(l, 6, 1, 3, Kind.CAULDRON);
        at(l, 1, 1, 3, Kind.CHEST);
        at(l, 2, 1, 5, Kind.ARMOR_STAND);
        for (int z = 1; z <= 3; z++) at(l, w, 0, z, Kind.LOG_Z);
        for (int z = 1; z <= 2; z++) at(l, w, 1, z, Kind.LOG_Z);
        at(l, 4, 2, d, Kind.TORCH);
        return l;
    }

    /** A stone arsenal: racks of armour, chests of arms, and practice targets outside. */
    static List<Step> arsenal() {
        List<Step> l = new ArrayList<>(Blueprints.shell(9, 7, 1, false, false, true, false));
        for (int x = 1; x <= 7; x += 2) at(l, x, 1, 1, Kind.CHEST);
        for (int x = 2; x <= 6; x += 2) at(l, x, 1, 1, Kind.BARREL);
        at(l, 2, 1, 3, Kind.ARMOR_STAND);
        at(l, 6, 1, 3, Kind.ARMOR_STAND);
        at(l, 4, 3, 3, Kind.LANTERN);
        for (int z : new int[]{1, 3, 5}) {
            at(l, 10, 0, z, Kind.FENCE);
            at(l, 10, 1, z, Kind.TARGET);
        }
        return l;
    }

    // ---------- public houses ----------

    /** An inn: the common room with a kitchen and two tables below, guest rooms with beds upstairs. */
    static List<Step> inn() {
        List<Step> l = new ArrayList<>(Blueprints.shell(9, 9, 2, false, false, true, true));
        at(l, 6, 1, 1, Kind.SMOKER);
        at(l, 5, 1, 1, Kind.BARREL);
        at(l, 4, 1, 1, Kind.CRAFT);
        at(l, 7, 1, 1, Kind.BARREL);
        at(l, 7, 2, 1, Kind.BARREL);
        for (int tx : new int[]{2, 6}) {
            at(l, tx, 1, 6, Kind.FENCE);
            at(l, tx, 2, 6, Kind.PLATE);
            at(l, tx - 1, 1, 6, Kind.SEAT_W);
            at(l, tx + 1, 1, 6, Kind.SEAT_E);
        }
        at(l, 4, 3, 4, Kind.LANTERN);
        for (int x = 3; x <= 7; x += 2) {
            at(l, x, 5, 1, Kind.BED_FOOT);
            at(l, x, 5, 2, Kind.BED_HEAD);
        }
        at(l, 7, 5, 4, Kind.CHEST);
        for (int z = 4; z <= 6; z++) at(l, 4, 5, z, Kind.CARPET, 0);
        at(l, 4, 7, 4, Kind.LANTERN);
        return l;
    }

    /** A two-storey stone fort house: arms below, bunks above, an archery range and a dummy at the side. */
    static List<Step> fortHouse() {
        List<Step> l = new ArrayList<>(Blueprints.shell(9, 7, 2, false, false, true, false));
        at(l, 2, 1, 4, Kind.ARMOR_STAND);
        at(l, 6, 1, 4, Kind.ARMOR_STAND);
        for (int x = 3; x <= 7; x += 2) at(l, x, 1, 1, Kind.CHEST);
        at(l, 4, 3, 3, Kind.LANTERN);
        for (int x = 3; x <= 7; x += 2) {
            at(l, x, 5, 1, Kind.BED_FOOT);
            at(l, x, 5, 2, Kind.BED_HEAD);
        }
        at(l, 6, 5, 4, Kind.CHEST);
        at(l, 4, 7, 4, Kind.LANTERN);
        for (int z : new int[]{1, 3, 5}) {
            at(l, 12, 0, z, Kind.FENCE);
            at(l, 12, 1, z, Kind.TARGET);
        }
        dummy(l, 10, 0, 3);
        return l;
    }

    /** A covered market hall: a roof on nine posts, stalls of barrels and chests under it, lanterns from the beams. */
    static List<Step> marketHall() {
        List<Step> l = new ArrayList<>();
        int w = 11, d = 9;
        footing(l, 0, 0, w, d, Kind.STONE);
        for (int x : new int[]{0, 5, 10}) for (int z : new int[]{0, 4, 8}) for (int y = 1; y <= 3; y++) at(l, x, y, z, Kind.PILLAR);
        for (int z : new int[]{0, 4, 8}) for (int x = 1; x < w - 1; x++) if (x != 5) at(l, x, 3, z, Kind.BEAM_X);
        for (int x : new int[]{0, 5, 10}) for (int z = 1; z < d - 1; z++) if (z != 4) at(l, x, 3, z, Kind.BEAM_Z);
        Blueprints.gable(l, w, d, 3, false);
        for (int[] s : new int[][]{{2, 2}, {7, 2}, {2, 6}, {7, 6}}) {
            at(l, s[0], 1, s[1], Kind.BARREL);
            at(l, s[0] + 1, 1, s[1], Kind.BARREL);
            at(l, s[0], 2, s[1], Kind.POT);
            at(l, s[0] + 1, 1, s[1] + (s[1] == 2 ? -1 : 1), Kind.CHEST);
        }
        at(l, 1, 1, 7, Kind.HAY);
        at(l, 9, 1, 7, Kind.PUMPKIN, S);
        at(l, 2, 2, 4, Kind.LANTERN);
        at(l, 8, 2, 4, Kind.LANTERN);
        return l;
    }

    // ---------- chapels ----------

    /** A stone church with a bell tower at the front corner and a fenced graveyard beside it. */
    static List<Step> church() {
        List<Step> l = new ArrayList<>();
        // graveyard: x 0..5, z 0..10
        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 10; z++) {
                boolean edge = x == 0 || x == 5 || z == 0 || z == 10;
                if (!edge) continue;
                if (x == 2 && z == 10) at(l, x, 0, z, Kind.GATE, S);
                else at(l, x, 0, z, Kind.FENCE);
            }
        }
        for (int[] p : new int[][]{{4, 1}, {4, 5}, {4, 9}}) at(l, p[0], 0, p[1], Kind.FLOWER);
        // the nave: x 6..12
        List<Step> nave = new ArrayList<>(Blueprints.shell(7, 11, 1, true, false, true, false));
        at(nave, 3, 1, 1, Kind.ACCENT);
        at(nave, 3, 2, 1, Kind.LANTERN);
        at(nave, 2, 1, 1, Kind.BOOKSHELF);
        at(nave, 4, 1, 1, Kind.BOOKSHELF);
        for (int z = 3; z <= 7; z += 2) for (int x : new int[]{1, 2, 4, 5}) at(nave, x, 1, z, Kind.PEW, S);
        for (int z = 2; z <= 9; z++) at(nave, 3, 1, z, Kind.CARPET, 0);
        at(nave, 3, 3, 5, Kind.LANTERN);
        shift(nave, 6, 0, l);
        // the bell tower: x 13..15, z 7..9
        for (int y = 0; y <= 10; y++) {
            for (int x = 13; x <= 15; x++) {
                for (int z = 7; z <= 9; z++) {
                    boolean corner = (x == 13 || x == 15) && (z == 7 || z == 9);
                    boolean center = x == 14 && z == 8;
                    if (center) {
                        if (y == 0 || y == 7) at(l, x, y, z, Kind.STONE);
                        continue;
                    }
                    boolean belfry = (y == 8 || y == 9) && !corner;
                    if (belfry) continue;
                    if (y == 1 && x == 14 && z == 9) continue; // a way in at the foot
                    at(l, x, y, z, corner ? Kind.ACCENT : Kind.STONE);
                }
            }
        }
        at(l, 14, 8, 8, Kind.BELL);
        for (int x = 13; x <= 15; x++) for (int z = 7; z <= 9; z++) at(l, x, 11, z, Kind.SLAB);
        return l;
    }

    /** A little wayside chapel of stone with a small churchyard at its side. */
    static List<Step> wayside() {
        List<Step> l = new ArrayList<>(Blueprints.shell(5, 7, 1, false, false, true, false));
        at(l, 2, 1, 1, Kind.ACCENT);
        at(l, 2, 2, 1, Kind.LANTERN);
        at(l, 1, 1, 1, Kind.POT);
        at(l, 3, 1, 1, Kind.POT);
        at(l, 1, 1, 3, Kind.PEW, S);
        at(l, 3, 1, 3, Kind.PEW, S);
        for (int z = 2; z <= 5; z++) at(l, 2, 1, z, Kind.CARPET, 4);
        for (int x = 6; x <= 10; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean edge = x == 6 || x == 10 || z == 0 || z == 6;
                if (!edge) continue;
                if (x == 8 && z == 6) at(l, x, 0, z, Kind.GATE, S);
                else at(l, x, 0, z, Kind.FENCE);
            }
        }
        at(l, 8, 0, 1, Kind.FLOWER);
        at(l, 8, 0, 3, Kind.FLOWER);
        return l;
    }

    // ---------- wells, towers, kilns ----------

    /** A covered well: a stone rim, four posts, a little pitched roof and the bucket chain. */
    static List<Step> coveredWell() {
        List<Step> l = new ArrayList<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                if (x == 2 && z == 2) at(l, x, -1, z, Kind.WATER);
                else at(l, x, 0, z, Kind.ACCENT);
            }
        }
        for (int[] c : new int[][]{{1, 1}, {3, 1}, {1, 3}, {3, 3}}) for (int y = 1; y <= 2; y++) at(l, c[0], y, c[1], Kind.PILLAR);
        List<Step> roof = new ArrayList<>();
        Blueprints.gable(roof, 3, 3, 2, false);
        shift(roof, 1, 1, l);
        at(l, 2, 3, 2, Kind.CHAIN);
        at(l, 2, 2, 2, Kind.CHAIN);
        at(l, 0, 0, 2, Kind.POT);
        at(l, 4, 0, 2, Kind.BARREL);
        return l;
    }

    /** A fountain: a stone basin full of water round a column with a lantern on top. */
    static List<Step> fountain() {
        List<Step> l = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean edge = x == 0 || z == 0 || x == 4 || z == 4;
                boolean corner = (x == 0 || x == 4) && (z == 0 || z == 4);
                if (edge) at(l, x, 0, z, corner ? Kind.ACCENT : Kind.STONE);
                else if (x == 2 && z == 2) {
                    at(l, x, 0, z, Kind.ACCENT);
                    at(l, x, 1, z, Kind.ACCENT);
                    at(l, x, 2, z, Kind.LANTERN);
                } else {
                    at(l, x, -1, z, Kind.STONE);
                    at(l, x, 0, z, Kind.WATER);
                }
            }
        }
        return l;
    }

    /** A timber watchtower: four log posts with braces, a ladder up the middle, a railed deck under a roof. */
    static List<Step> woodenTower() {
        List<Step> l = new ArrayList<>();
        int n = 5, h = 10;
        for (int[] c : new int[][]{{0, 0}, {n - 1, 0}, {0, n - 1}, {n - 1, n - 1}}) for (int y = 0; y <= h + 3; y++) at(l, c[0], y, c[1], Kind.PILLAR);
        for (int y = 0; y < h; y++) at(l, 2, y, 0, Kind.PILLAR); // the ladder's post
        for (int y : new int[]{4, 8}) {
            for (int x = 1; x < n - 1; x++) {
                at(l, x, y, 0, Kind.BEAM_X);
                at(l, x, y, n - 1, Kind.BEAM_X);
            }
            for (int z = 1; z < n - 1; z++) {
                at(l, 0, y, z, Kind.BEAM_Z);
                at(l, n - 1, y, z, Kind.BEAM_Z);
            }
        }
        for (int y = 1; y <= h; y++) at(l, 2, y, 1, Kind.LADDER);
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) if (!(x == 2 && z == 1)) at(l, x, h, z, Kind.FLOOR);
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                boolean edge = x == 0 || z == 0 || x == n - 1 || z == n - 1;
                boolean corner = (x == 0 || x == n - 1) && (z == 0 || z == n - 1);
                if (edge && !corner) at(l, x, h + 1, z, Kind.FENCE);
            }
        }
        for (int x = -1; x <= n; x++) for (int z = -1; z <= n; z++) at(l, x, h + 4, z, Kind.SLAB);
        at(l, 2, h + 3, 2, Kind.LANTERN);
        return l;
    }

    /** A stone charcoal kiln, smoking through its top, with a log stack and the charcoal barrel. */
    static List<Step> kiln() {
        List<Step> l = new ArrayList<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                at(l, x, 0, z, Kind.STONE);
                boolean center = x == 2 && z == 2;
                if (center) at(l, x, 1, z, Kind.SMOKE);
                else if (!(x == 2 && z == 3)) at(l, x, 1, z, Kind.STONE);
                if (!center) at(l, x, 2, z, (x == 2 || z == 2) ? Kind.STONE : Kind.ACCENT);
            }
        }
        for (int x = 0; x <= 2; x++) at(l, x, 0, 5, Kind.LOG_X);
        for (int x = 0; x <= 1; x++) at(l, x, 1, 5, Kind.LOG_X);
        at(l, 4, 0, 5, Kind.BARREL);
        at(l, 4, 0, 4, Kind.CHEST);
        lamp(l, 5, 0);
        return l;
    }
}
