package net.mca.settlements;

import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;

import java.util.ArrayDeque;
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

    /**
     * A MineColonies building dressed the same way: its blocks read as floor, wall, way in or thing in use from what
     * they are, the rooms dressed on that reading, and only the new things added to the template (its first-level huts
     * come all but empty: a chest and a lectern in a bare hall).
     */
    public static List<Step> dressTemplate(String type, List<Step> raw) {
        Templates.Template t = Templates.of(type);
        if (t == null || raw.isEmpty()) return raw;
        Set<Long> solid = new HashSet<>();
        for (Step s : raw) {
            if (s.kind() != Kind.TPL && s.kind() != Kind.TPL_FIT || s.face() < 0 || s.face() >= t.palette().length) continue;
            net.minecraft.block.BlockState b = t.palette()[s.face()];
            if (b.isOpaque() && b.isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, net.minecraft.util.math.BlockPos.ORIGIN)) solid.add(key(s.dx(), s.dy(), s.dz()));
        }
        List<Step> shadow = new ArrayList<>(raw.size());
        for (Step s : raw) {
            if (s.kind() != Kind.TPL && s.kind() != Kind.TPL_FIT || s.face() < 0 || s.face() >= t.palette().length) {
                shadow.add(s);
                continue;
            }
            net.minecraft.block.BlockState b = t.palette()[s.face()];
            net.minecraft.block.Block bl = b.getBlock();
            Kind k;
            if (b.isAir()) continue;
            if (bl instanceof net.minecraft.block.DoorBlock || bl instanceof net.minecraft.block.FenceGateBlock || bl instanceof net.minecraft.block.TrapdoorBlock) k = Kind.DOOR;
            else if (bl instanceof net.minecraft.block.LadderBlock) k = Kind.LADDER;
            else if (bl instanceof net.minecraft.block.StairsBlock) k = Kind.STAIRS_N;
            else if (bl instanceof net.minecraft.block.BedBlock) k = Kind.BED_FOOT;
            else if (bl instanceof net.minecraft.block.BlockWithEntity || bl instanceof net.minecraft.block.CraftingTableBlock) k = Kind.CHEST;
            else if (bl instanceof net.minecraft.block.TorchBlock || bl instanceof net.minecraft.block.LanternBlock) k = Kind.TORCH;
            else if (bl instanceof net.minecraft.block.FenceBlock || bl instanceof net.minecraft.block.WallBlock || bl instanceof net.minecraft.block.PaneBlock) k = Kind.FENCE;
            else if (bl instanceof net.minecraft.block.SlabBlock) k = Kind.SLAB;
            else if (solid.contains(key(s.dx(), s.dy(), s.dz()))) k = solid.contains(key(s.dx(), s.dy() + 1, s.dz())) ? Kind.WALL : Kind.FLOOR;
            else k = Kind.PLATE; // something small that stands there: kept clear
            shadow.add(new Step(s.dx(), s.dy(), s.dz(), k, s.face()));
        }
        List<Step> dressed = dress(type, shadow);
        if (dressed.size() <= shadow.size()) return raw;
        List<Step> out = new ArrayList<>(raw);
        out.addAll(dressed.subList(shadow.size(), dressed.size()));
        return out;
    }

    /** The plan with its rooms dressed. */
    public static List<Step> dress(String type, List<Step> raw) {
        if (type == null || raw.isEmpty()) return raw;
        String base = Buildings.base(type);
        if (Set.of("mine", "farm", "pen", "wall", "palisade", "well", "tower", "upgrade", "hallup", "graveyard").contains(base)) return raw;
        Map<Long, Kind> at = new HashMap<>();
        Set<Long> dug = new HashSet<>();
        for (Step s : raw) {
            long k = key(s.dx(), s.dy(), s.dz());
            if (s.kind() == Kind.CLEAR || s.kind() == Kind.DIG) { // cleared is empty
                at.remove(k);
                dug.add(k);
            } else {
                at.put(k, s.kind());
                dug.remove(k);
            }
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        for (Step s : raw) {
            minX = Math.min(minX, s.dx());
            maxX = Math.max(maxX, s.dx());
            minZ = Math.min(minZ, s.dz());
            maxZ = Math.max(maxZ, s.dz());
            minY = Math.min(minY, s.dy());
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

        // a house or a hall may get another floor later: its stairs will come up at x 1, z 1..4, from x 2
        boolean growing = Set.of("house", "manor", "mansion", "townhall").contains(type);
        List<Step> out = new ArrayList<>(raw);
        Set<Long> taken = new HashSet<>();
        int props = 0, floorCells = 0;
        List<int[]> spots = new ArrayList<>();
        for (Step s : raw) {
            if (s.kind() != Kind.FLOOR || at.get(key(s.dx(), s.dy(), s.dz())) != Kind.FLOOR) continue; // (not a floor cut away later)
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
                if (crowded(at, x, y, z) || (growing && x <= 2 && z <= 4)) continue;
                spots.add(new int[]{x, y, z, f, WALL.contains(at.get(key(wx, y + 1, wz))) ? 1 : 0});
                break;
            }
        }
        if (spots.isEmpty()) return raw;
        Walk walk = new Walk(at, dug, minX, maxX, minY, maxY, minZ, maxZ);
        Walk.Access before = walk.access();
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
            List<Step> piece = new ArrayList<>();
            if (prop) {
                int code = switch (flavour) {
                    case "store" -> new int[]{BARREL, CRATE, HAY, BARREL, CRATE}[pick % 5];
                    case "tavern" -> new int[]{BARREL, BARREL, CRATE, BARREL, POT}[pick % 5];
                    case "hall" -> new int[]{BOOKS, BOOKS, BARREL, BOOKS, POT}[pick % 5];
                    case "shop" -> new int[]{BARREL, CRATE, BOOKS, CRATE, POT}[pick % 5];
                    default -> new int[]{BARREL, BOOKS, CRATE, POT, BARREL}[pick % 5];
                };
                if (tier == 0 && code == BOOKS) code = CRATE; // no books in a hovel
                piece.add(new Step(x, y, z, Kind.PROP, code));
                // a shelf board above a barrel or a crate, a pot on it where the ceiling allows
                if (hang && (code == BARREL || code == CRATE) && !at.containsKey(key(x, y + 1, z))) {
                    piece.add(new Step(x, y + 1, z, Kind.SHELF, f));
                    if (high && tier >= 1) piece.add(new Step(x, y + 2, z, Kind.POT));
                }
            } else {
                // on the wall: nothing to walk round
                Kind k = switch (flavour) {
                    case "hall" -> pick % 2 == 0 ? Kind.BANNER : Kind.FRAME;
                    case "store" -> Kind.SHELF;
                    default -> pick % 3 == 0 ? Kind.FRAME : pick % 3 == 1 ? Kind.SHELF : Kind.BANNER;
                };
                if (tier == 0 && k != Kind.SHELF) k = Kind.SHELF;
                piece.add(new Step(x, y + 1, z, k, f));
                if (k == Kind.SHELF && high && tier >= 1) piece.add(new Step(x, y + 2, z, Kind.POT));
            }
            // only where it shuts nobody out: every place one could walk to and every thing in use stays in reach
            for (Step p : piece) at.put(key(p.dx(), p.dy(), p.dz()), p.kind());
            Walk.Access after = walk.access();
            if (!after.keeps(before, piece)) {
                for (Step p : piece) at.remove(key(p.dx(), p.dy(), p.dz()));
                continue;
            }
            before = after;
            out.addAll(piece);
            if (prop) props++;
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

    /**
     * Where a person (two blocks tall) can get to from outside: on foot over floors, stairs and slabs, a block up or
     * down at a time (a jump needs a third block of headroom), up and down ladders, through doors.
     */
    static final class Walk {
        private static final Set<Kind> PASS = EnumSet.of(Kind.DOOR, Kind.CARPET, Kind.PLATE, Kind.TORCH, Kind.LANTERN, Kind.SIGN, Kind.FLOWER,
                Kind.BANNER, Kind.FRAME, Kind.CLOCK, Kind.LADDER, Kind.SHUTTER, Kind.GATE, Kind.CROP, Kind.WATER, Kind.CHAIN);
        private static final Set<Kind> FOOTING = EnumSet.of(Kind.FLOOR, Kind.WALL, Kind.PILLAR, Kind.STONE, Kind.ACCENT, Kind.BEAM_X, Kind.BEAM_Z,
                Kind.LOG_X, Kind.LOG_Z, Kind.DAUB, Kind.ROOF, Kind.THATCH, Kind.SLAB, Kind.STEP, Kind.STAIRS_N, Kind.STAIRS_S, Kind.STAIRS_E,
                Kind.STAIRS_W, Kind.RSTAIR_S, Kind.RSTAIR_N, Kind.PATH, Kind.FARMLAND, Kind.CHIMNEY, Kind.MOUND, Kind.WOOL, Kind.HEDGE);
        /** Things that must stay in reach: a free place to stand beside them. */
        private static final Set<Kind> REACH = EnumSet.of(Kind.BED_FOOT, Kind.BED_HEAD, Kind.CHEST, Kind.HALL_CHEST, Kind.CRAFT, Kind.FURNACE,
                Kind.SMOKER, Kind.ANVIL, Kind.LECTERN, Kind.BARREL, Kind.GRINDSTONE, Kind.SMITHING, Kind.STONECUTTER, Kind.CAULDRON,
                Kind.COMPOSTER, Kind.SEAT_W, Kind.SEAT_E, Kind.PEW, Kind.LADDER, Kind.DOOR);
        private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        private final Map<Long, Kind> at;
        private final Set<Long> dug;
        private final int x0, x1, y0, y1, z0, z1;

        Walk(Map<Long, Kind> at, Set<Long> dug, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.at = at;
            this.dug = dug;
            x0 = minX - 2;
            x1 = maxX + 2;
            y0 = Math.min(minY, -1) - 1;
            y1 = maxY + 2;
            z0 = minZ - 2;
            z1 = maxZ + 2;
        }

        private boolean free(int x, int y, int z) {
            Kind k = at.get(key(x, y, z));
            return k == null ? y >= 0 || dug.contains(key(x, y, z)) : PASS.contains(k);
        }

        private boolean footing(int x, int y, int z) {
            Kind k = at.get(key(x, y, z));
            return k == null ? y < 0 && !dug.contains(key(x, y, z)) : FOOTING.contains(k);
        }

        private boolean ladder(int x, int y, int z) {
            return at.get(key(x, y, z)) == Kind.LADDER;
        }

        /** A place to be: room for the body, and a floor underfoot or a ladder in hand. */
        private boolean place(int x, int y, int z) {
            return y >= y0 && y <= y1 && free(x, y, z) && free(x, y + 1, z) && (footing(x, y - 1, z) || ladder(x, y, z));
        }

        /** Every place reachable from all round the outside. */
        Set<Long> reach() {
            Set<Long> seen = new HashSet<>();
            ArrayDeque<int[]> todo = new ArrayDeque<>();
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                    for (int y = -3; y <= 3; y++) {
                        if (place(x, y, z)) {
                            if (seen.add(key(x, y, z))) todo.add(new int[]{x, y, z});
                            break;
                        }
                    }
                }
            }
            while (!todo.isEmpty()) {
                int[] c = todo.poll();
                int x = c[0], y = c[1], z = c[2];
                for (int[] o : SIDES) {
                    int nx = x + o[0], nz = z + o[1];
                    if (nx < x0 || nx > x1 || nz < z0 || nz > z1) continue;
                    int ny = Integer.MIN_VALUE;
                    if (place(nx, y, nz)) ny = y;
                    else if (place(nx, y + 1, nz) && free(x, y + 2, z)) ny = y + 1; // a step up
                    else if (free(nx, y, nz) && free(nx, y + 1, nz)) { // down, falling if need be
                        for (int yy = y - 1; yy >= y0 && free(nx, yy, nz); yy--) {
                            if (place(nx, yy, nz)) {
                                ny = yy;
                                break;
                            }
                        }
                    }
                    if (ny != Integer.MIN_VALUE && seen.add(key(nx, ny, nz))) todo.add(new int[]{nx, ny, nz});
                }
                if (ladder(x, y, z) && place(x, y + 1, z) && seen.add(key(x, y + 1, z))) todo.add(new int[]{x, y + 1, z});
                if ((ladder(x, y, z) || ladder(x, y - 1, z)) && place(x, y - 1, z) && seen.add(key(x, y - 1, z))) todo.add(new int[]{x, y - 1, z});
            }
            return seen;
        }

        /** What can be got to: the places, and the things in use with a place beside them. */
        Access access() {
            Set<Long> places = reach();
            Set<Long> things = new HashSet<>();
            for (Map.Entry<Long, Kind> e : at.entrySet()) {
                if (!REACH.contains(e.getValue())) continue;
                long k = e.getKey();
                int x = unpack(k >>> 42), y = unpack(k >>> 21), z = unpack(k);
                boolean ok = places.contains(k);
                for (int[] o : SIDES) {
                    for (int dy = -1; dy <= 1 && !ok; dy++) ok = places.contains(key(x + o[0], y + dy, z + o[1]));
                }
                if (ok) things.add(k);
            }
            return new Access(places, things);
        }

        private static int unpack(long v) {
            return (int) ((v & 0x1FFFFF) << 43 >> 43);
        }

        record Access(Set<Long> places, Set<Long> things) {
            /** Nothing lost that was there before, but the places the piece itself stands in. */
            boolean keeps(Access before, List<Step> piece) {
                if (!things.containsAll(before.things)) return false;
                Set<Long> covered = new HashSet<>();
                for (Step p : piece) {
                    covered.add(key(p.dx(), p.dy(), p.dz()));
                    covered.add(key(p.dx(), p.dy() - 1, p.dz()));
                }
                for (long k : before.places) if (!places.contains(k) && !covered.contains(k)) return false;
                return true;
            }
        }
    }
}
