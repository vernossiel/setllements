package net.mca.settlements;

import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dresses the rooms of a plan, the way people fill a house over the years: along the walls, out of the way of doors,
 * stairs and the things in use, a barrel or a crate, a bookcase, a shelf board with a pot on it, a banner, something
 * in a frame. The kind of building decides what (a store fills up with barrels and hay, a hall with books and banners,
 * a home with shelves and keepsakes), its rank how much. All of it is optional: a village that cannot afford it
 * leaves the wall bare.
 */
public final class Decor {
    private Decor() {
    }

    private static final Set<Kind> WALL = EnumSet.of(Kind.WALL, Kind.PILLAR, Kind.STONE, Kind.ACCENT, Kind.BEAM_X, Kind.BEAM_Z, Kind.LOG_X, Kind.LOG_Z,
            Kind.DAUB, Kind.CHIMNEY);
    private static final Set<Kind> COVER = EnumSet.of(Kind.FLOOR, Kind.ROOF, Kind.SLAB, Kind.THATCH, Kind.RSTAIR_S, Kind.RSTAIR_N, Kind.STAIRS_N,
            Kind.STAIRS_S, Kind.STAIRS_E, Kind.STAIRS_W, Kind.BEAM_X, Kind.BEAM_Z);
    /** Keep clear around these: the ways in, up and down. */
    private static final Set<Kind> WAY = EnumSet.of(Kind.DOOR, Kind.LADDER, Kind.STAIRS_N, Kind.STAIRS_S, Kind.STAIRS_E, Kind.STAIRS_W, Kind.STEP);
    /** Things used every day: nothing goes right beside them. */
    private static final Set<Kind> USED = EnumSet.of(Kind.CHEST, Kind.HALL_CHEST, Kind.BED_FOOT, Kind.BED_HEAD, Kind.CRAFT, Kind.FURNACE, Kind.SMOKER,
            Kind.ANVIL, Kind.LECTERN, Kind.BARREL, Kind.GRINDSTONE, Kind.SMITHING, Kind.STONECUTTER, Kind.CAULDRON, Kind.COMPOSTER, Kind.SEAT_W,
            Kind.SEAT_E, Kind.PEW, Kind.PLATE, Kind.ARMOR_STAND, Kind.FENCE, Kind.BOOKSHELF, Kind.TORCH, Kind.LANTERN, Kind.SIGN, Kind.CLOCK);

    /** Prop codes (the face of a PROP step). */
    public static final int BARREL = 0, CRATE = 1, HAY = 2, BOOKS = 3, POT = 4;

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
    }

    /** The plan with its rooms dressed. */
    public static List<Step> dress(String type, List<Step> raw) {
        if (type == null || raw.isEmpty()) return raw;
        String base = Buildings.base(type);
        if (Set.of("mine", "farm", "pen", "wall", "palisade", "well", "tower", "upgrade", "hallup", "graveyard").contains(base)) return raw;
        Map<Long, Kind> at = new HashMap<>();
        for (Step s : raw) {
            if (s.kind() == Kind.CLEAR || s.kind() == Kind.DIG) at.remove(key(s.dx(), s.dy(), s.dz())); // cleared is empty
            else at.put(key(s.dx(), s.dy(), s.dz()), s.kind());
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Step s : raw) {
            minX = Math.min(minX, s.dx());
            maxX = Math.max(maxX, s.dx());
            minZ = Math.min(minZ, s.dz());
            maxZ = Math.max(maxZ, s.dz());
            maxY = Math.max(maxY, s.dy());
        }
        int tier = Math.max(0, Tiers.tierOf(type));
        if (Tiers.tierOf(type) < 0) tier = 1;
        String flavour = switch (base) {
            case "storage", "granary", "mill", "sawmill", "mason", "charcoal", "barn" -> "store";
            case "tavern", "inn" -> "tavern";
            case "townhall", "chapel", "library", "guildhall", "barracks", "jail", "armory" -> "hall";
            case "shop", "market", "smithy", "bakery" -> "shop";
            default -> "home";
        };

        List<Step> out = new ArrayList<>(raw);
        Set<Long> taken = new HashSet<>();
        int props = 0, floorCells = 0;
        List<int[]> spots = new ArrayList<>();
        for (Step s : raw) {
            if (s.kind() != Kind.FLOOR) continue;
            int x = s.dx(), y = s.dy() + 1, z = s.dz();
            if (at.containsKey(key(x, y, z)) || at.containsKey(key(x, y + 1, z))) continue;
            boolean covered = false;
            for (int yy = y + 2; yy <= maxY && !covered; yy++) if (COVER.contains(at.get(key(x, yy, z))) || WALL.contains(at.get(key(x, yy, z)))) covered = true;
            if (!covered) continue;
            floorCells++;
            // a wall behind the spot, and the room in front of it at least three wide
            int[][] dirs = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}}; // S W N E, as the faces
            for (int f = 0; f < 4; f++) {
                int wx = x + dirs[f][0], wz = z + dirs[f][1];
                if (!WALL.contains(at.get(key(wx, y, wz)))) continue; // (under a window a barrel will do, nothing hung)
                int ix = x - dirs[f][0], iz = z - dirs[f][1];
                if (at.containsKey(key(ix, y, iz)) || at.get(key(ix, y - 1, iz)) != Kind.FLOOR
                        || at.containsKey(key(ix - dirs[f][0], y, iz - dirs[f][1]))) continue;
                if (crowded(at, x, y, z)) continue;
                spots.add(new int[]{x, y, z, f, WALL.contains(at.get(key(wx, y + 1, wz))) ? 1 : 0});
                break;
            }
        }
        if (spots.isEmpty()) return raw;
        // how much: a poor house has a thing or two, a rich one is full of things
        double share = new double[]{0.4, 0.6, 0.8, 0.9}[Math.min(3, tier)];
        int maxProps = Math.max(1, floorCells / (tier >= 2 ? 6 : 9));
        for (int[] sp : spots) {
            int x = sp[0], y = sp[1], z = sp[2], f = sp[3];
            int h = Math.floorMod(x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ type.hashCode(), 1000);
            if (h / 1000.0 > share && !taken.isEmpty()) continue; // (every room gets at least something)
            if (taken.contains(key(x, y, z)) || near(taken, x, y, z)) continue;
            boolean high = !at.containsKey(key(x, y + 2, z)) && y + 2 < maxY;
            int pick = h % 10;
            boolean hang = sp[4] == 1;
            boolean prop = props < maxProps && (pick < 5 || !hang);
            if (!prop && !hang) continue;
            if (prop) {
                int code = switch (flavour) {
                    case "store" -> new int[]{BARREL, CRATE, HAY, BARREL, CRATE}[pick];
                    case "tavern" -> new int[]{BARREL, BARREL, CRATE, BARREL, POT}[pick];
                    case "hall" -> new int[]{BOOKS, BOOKS, BARREL, BOOKS, POT}[pick];
                    case "shop" -> new int[]{BARREL, CRATE, BOOKS, CRATE, POT}[pick];
                    default -> new int[]{BARREL, BOOKS, CRATE, POT, BARREL}[pick];
                };
                if (tier == 0 && code == BOOKS) code = CRATE; // no books in a hovel
                out.add(new Step(x, y, z, Kind.PROP, code));
                props++;
                // a shelf board above a barrel or a crate, a pot on it where the ceiling allows
                if (hang && (code == BARREL || code == CRATE) && !at.containsKey(key(x, y + 1, z))) {
                    out.add(new Step(x, y + 1, z, Kind.SHELF, f));
                    if (high && tier >= 1) out.add(new Step(x, y + 2, z, Kind.POT));
                }
            } else {
                // on the wall: nothing to walk round
                Kind k = switch (flavour) {
                    case "hall" -> pick % 2 == 0 ? Kind.BANNER : Kind.FRAME;
                    case "store" -> Kind.SHELF;
                    default -> pick % 3 == 0 ? Kind.FRAME : pick % 3 == 1 ? Kind.SHELF : Kind.BANNER;
                };
                if (tier == 0 && k != Kind.SHELF) k = Kind.SHELF;
                out.add(new Step(x, y + 1, z, k, f));
                if (k == Kind.SHELF && high && tier >= 1) out.add(new Step(x, y + 2, z, Kind.POT));
            }
            taken.add(key(x, y, z));
        }
        return out;
    }

    /** Beside a door, a ladder, a stair or a thing in use. */
    private static boolean crowded(Map<Long, Kind> at, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Kind k = at.get(key(x + dx, y + dy, z + dz));
                    if (k != null && WAY.contains(k)) return true;
                    if (dy == 0 && k != null && USED.contains(k) && (dx == 0 || dz == 0)) return true;
                }
            }
        }
        return false;
    }

    private static boolean near(Set<Long> taken, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (taken.contains(key(x + dx, y, z + dz))) return true;
        return false;
    }
}
