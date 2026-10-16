package net.mca.settlements;

import net.mca.server.world.data.Building;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The village plan. From the plaza in the middle, streets run out straight (east-west and north-south, and parallel
 * streets a block's depth apart as the village grows), and the plots line them on both sides with their fronts to the
 * street, set back a step. Every village keeps its quarters: the town hall, the inn, the market and the chapel round
 * the plaza; the workshops and stores along one street of their own, the way towards the woods and the mine; the
 * homes along the others; farms, pens and watchtowers out beyond the streets. Nothing is put on a street, on another
 * plot or on a building that stands already.
 */
public final class Layout {
    /** Distance between parallel streets, and the half-width of a street (3 wide). */
    static final int GRID = 40, HALF = 1;
    private static final int PLAZA = 9;
    private static final int[] RINGS = {14, 22, 30, 38, 46, 54};
    private static final int MARGIN = 2;
    private static final Set<String> CENTRE = Set.of("townhall", "well", "market", "tavern", "chapel", "shop", "board", "jail");
    private static final Set<String> WORK = Set.of("storage", "granary", "sawmill", "mason", "smithy", "charcoal", "mill", "armory", "barracks");
    private static final Set<String> OUTSKIRTS = Set.of("farm", "pen", "tower", "mine", "palisade", "wall", "graveyard");

    public record Slot(BlockPos origin, int rot, int fw, int fd) {
    }

    private Layout() {
    }

    /** Rotation so that the front (+z side of the plan) faces the plaza. */
    private static int facingCenter(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? 1 : 3;
        return dz > 0 ? 2 : 0;
    }

    /** How far out the streets of this village reach (in streets either side of the main ones). */

    private static int reach(Village village) {
        // the village's grade sets its bounds: a poor one lives along the two main streets, an ordinary one adds a
        // street either side, a wealthy one two, a city three; within that it spreads when it runs out of room
        int tier = village.getId() >= 0 ? Prosperity.tier(village) : 0;
        int base = tier;
        return Math.min(tier + 1, Math.max(base, village.streets));
    }

    /** Streets laid out further when the village has run out of room (for a big building, or simply many). */
    private static void grow(Village village, int to) {
        if (to > village.streets) {
            village.streets = Math.min(4, to);
            village.markDirty();
        }
    }

    /** The street the workshops line: one of the four arms of the main streets, the same for good. */
    private static int workArm(Village village) {
        return Math.floorMod(village.getId() * 7 + 3, 4); // 0 east, 1 south, 2 west, 3 north
    }

    /** True for a cell on a street (or the plaza) of the village's plan. */
    static boolean onStreet(Village village, int x, int z) {
        return onStreet(village, reach(village), x, z);
    }

    static boolean onStreet(Village village, int r, int x, int z) {
        BlockPos c = new BlockPos(village.getCenter());
        int dx = x - c.getX(), dz = z - c.getZ();
        if (dx * dx + dz * dz <= PLAZA * PLAZA) return true;
        int lim = (r + 1) * GRID;
        if (Math.abs(dx) > lim + HALF || Math.abs(dz) > lim + HALF) return false;
        for (int k = -r; k <= r; k++) {
            if (Math.abs(dz - k * GRID) <= HALF) return true; // an east-west street
            if (Math.abs(dx - k * GRID) <= HALF) return true; // a north-south street
        }
        return false;
    }

    private record Lot(int x, int z, int rot, double score) {
    }

    public static Slot find(ServerWorld world, Village village, String type, int w, int d, int minRing, int maxRing) {
        String base = Tiers.isHome(type) ? "home" : Buildings.base(type);
        int[] fp = Blueprints.footprint(type, w, d);
        // a hamlet's fields and pens go on a plot along its streets when there is room, rather than far out on the rings
        if ((base.equals("farm") || base.equals("pen")) && Prosperity.tier(village) <= Tiers.ORDINARY) {
            Slot s = grid(world, village, type, fp, base, reach(village));
            if (s != null) return s;
        }
        if (type.equals("mine") || OUTSKIRTS.contains(base) || minRing >= 2) return ring(world, village, type, fp, minRing, Math.max(maxRing, reach(village) + 3)); // the rings start past the streets already
        int maxExtra = Math.max(0, (village.getId() >= 0 ? Prosperity.tier(village) : 0) + 1 - reach(village)); // no further than its grade allows
        for (int extra = 0; extra <= maxExtra; extra++) {
            Slot s = grid(world, village, type, fp, base, reach(village) + extra);
            if (s != null) {
                if (extra > 0) grow(village, reach(village) + extra); // the village spreads: new streets out there
                return s;
            }
        }
        // no plot left on the streets: out on the rings beyond them
        Slot r = ring(world, village, type, fp, Math.max(minRing, reach(village) + 2), Math.max(maxRing, reach(village) + 4));
        return r != null ? r : open(world, village, type, fp);
    }

    /**
     * Rough country (a village on a ravine's lip, on a hillside): no plot on the plan's streets or rings is level. The
     * village grows where the land lets it - the nearest level ground within a hundred blocks, on about the hall's own
     * level first (the road out to it is laid as for any other building).
     */
    static Slot open(ServerWorld world, Village village, String type, int[] fp) {
        BlockPos c = village.hallPos != null ? village.hallPos : new BlockPos(village.getCenter());
        List<Lot> lots = new ArrayList<>();
        for (int dx = -100; dx <= 100; dx += 6) {
            for (int dz = -100; dz <= 100; dz += 6) {
                double dist = Math.hypot(dx, dz);
                if (dist < 16 || dist > 100) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                int rot = facingCenter(dx, dz); // the door towards the village, as on the rings
                lots.add(new Lot(x, z, rot, dist + Math.abs(y - c.getY()) * 3));
            }
        }
        lots.sort(Comparator.comparingDouble(Lot::score));
        int tried = 0;
        for (int pass = 0; pass < TOLERANCE.length; pass++) {
            for (Lot lot : lots) {
                int fw = (lot.rot() & 1) == 0 ? fp[0] : fp[1], fd = (lot.rot() & 1) == 0 ? fp[1] : fp[0];
                int x = lot.x() - fw / 2, z = lot.z() - fd / 2;
                if (!free(village, x, z, fw, fd) || !freeOfBuildings(village, x, z, fw, fd)) continue;
                BlockPos origin = flat(world, x, z, fw, fd, pass);
                if (origin != null) return new Slot(origin, lot.rot(), fw, fd);
                if (++tried > 400) return null; // enough looking for one time
            }
        }
        return null;
    }

    private static Slot grid(ServerWorld world, Village village, String type, int[] fp, String base, int r) {
        BlockPos c = new BlockPos(village.getCenter());
        int arm = workArm(village);
        boolean centre = CENTRE.contains(base), work = WORK.contains(base);
        List<Lot> lots = new ArrayList<>();
        int lim = (r + 1) * GRID - 4;
        for (int k = -r; k <= r; k++) {
            // along an east-west street at z = k*GRID: plots north of it face south (rot 0), south of it face north (rot 2)
            // along a north-south street at x = k*GRID: plots east of it face west (rot 1), west of it face east (rot 3)
            for (int axis = 0; axis < 2; axis++) {
                for (int side = -1; side <= 1; side += 2) {
                    int rot = axis == 0 ? (side < 0 ? 0 : 2) : (side < 0 ? 3 : 1);
                    int fw = (rot & 1) == 0 ? fp[0] : fp[1], fd = (rot & 1) == 0 ? fp[1] : fp[0];
                    int along = (rot & 1) == 0 ? fw : fd, deep = (rot & 1) == 0 ? fd : fw;
                    // a plot is half a block deep; a big building takes the whole block, from one street to the next
                    boolean whole = deep > GRID / 2 - HALF - 3;
                    if (deep > GRID - 2 * HALF - 6 || whole && side > 0) continue;
                    for (int t = -lim; t <= lim - along; t += 2) {
                        int mid = t + along / 2;
                        if (Math.abs(mid) < PLAZA + 2 && k == 0) continue;
                        int line = k * GRID;
                        int off = side < 0 ? line - HALF - 2 - deep : line + HALF + 2; // set back a step from the street
                        int x = axis == 0 ? c.getX() + t : c.getX() + off;
                        int z = axis == 0 ? c.getZ() + off : c.getZ() + t;
                        double dist = Math.hypot(axis == 0 ? mid : line, axis == 0 ? line : mid);
                        // which arm of the main streets this plot belongs to
                        int plotArm = k != 0 ? -1 : axis == 0 ? (mid > 0 ? 0 : 2) : (mid > 0 ? 1 : 3);
                        double score = dist;
                        if (centre) score = dist + (k == 0 ? 0 : 200);
                        else if (work) score = (plotArm == arm ? 0 : 400) + dist;
                        else score = (plotArm == arm ? 300 : 0) + (dist < 18 ? 60 : 0) + dist; // homes off the plaza and the work street
                        score += world.random.nextDouble() * 6; // not all in a row from the plaza
                        lots.add(new Lot(x, z, rot, score));
                    }
                }
            }
        }
        lots.sort(Comparator.comparingDouble(Lot::score));
        List<Lot> open = new ArrayList<>();
        for (Lot lot : lots) {
            int fw = (lot.rot() & 1) == 0 ? fp[0] : fp[1], fd = (lot.rot() & 1) == 0 ? fp[1] : fp[0];
            if (free(village, r, lot.x(), lot.z(), fw, fd)) open.add(lot);
            if (open.size() >= 300) break;
        }
        for (int pass = 0; pass < TOLERANCE.length; pass++) {
            for (Lot lot : open) {
                int fw = (lot.rot() & 1) == 0 ? fp[0] : fp[1], fd = (lot.rot() & 1) == 0 ? fp[1] : fp[0];
                BlockPos origin = flat(world, lot.x(), lot.z(), fw, fd, pass);
                if (origin != null) return new Slot(origin, lot.rot(), fw, fd);
            }
        }
        return null;
    }

    /** The outskirts: plots on rings round the village, beyond the streets, facing the middle. */
    private static Slot ring(ServerWorld world, Village village, String type, int[] fp, int minRing, int maxRing) {
        BlockPos c = new BlockPos(village.getCenter());
        var rand = world.random;
        // the nearest ring first, rough ground and all, before a level one far off
        for (int ri = Math.min(minRing, RINGS.length - 1); ri <= Math.min(maxRing, RINGS.length - 1); ri++)
        for (int pass = 0; pass < TOLERANCE.length; pass++) {
            int radius = RINGS[ri] + (reach(village) + 1) * GRID - 14; // beyond the ends of the streets
            int n = Math.max(4, (int) (2 * Math.PI * radius / (Math.max(fp[0], fp[1]) + MARGIN + 2)));
            double offset = rand.nextDouble() * Math.PI * 2;
            int start = rand.nextInt(n);
            for (int i = 0; i < n; i++) {
                double angle = offset + ((start + i) % n) * Math.PI * 2 / n;
                double dx = Math.cos(angle) * radius, dz = Math.sin(angle) * radius;
                int rot = type.equals("mine") ? 0 : facingCenter(dx, dz);
                int fw = (rot & 1) == 0 ? fp[0] : fp[1], fd = (rot & 1) == 0 ? fp[1] : fp[0];
                int x = c.getX() + (int) dx - fw / 2, z = c.getZ() + (int) dz - fd / 2;
                if (!free(village, x, z, fw, fd)) continue;
                BlockPos origin = flat(world, x, z, fw, fd, pass);
                if (origin != null) return new Slot(origin, rot, fw, fd);
            }
        }
        return null;
    }

    /** The plaza itself: the plan stands on the village center. */
    public static Slot center(ServerWorld world, Village village, String type) {
        int[] fp = Blueprints.footprint(type, 0, 0);
        BlockPos c = new BlockPos(village.getCenter());
        int x = c.getX() - fp[0] / 2, z = c.getZ() - fp[1] / 2;
        BlockPos origin = flat(world, x, z, fp[0], fp[1]);
        if (origin == null) origin = flat(world, x, z, fp[0], fp[1], 1);
        if (origin == null || !freeOfBuildings(village, origin.getX(), origin.getZ(), fp[0], fp[1])) return null;
        return new Slot(origin, 0, fp[0], fp[1]);
    }

    /** Strict: level ground, no tree on it. Then, failing that anywhere, rougher ground the builders level and trees they fell. */
    private static final int[] TOLERANCE = {2, 5};

    /**
     * The height of the bare land at x, z (the first free block above it), looking down through trees and plants;
     * or MIN_VALUE where the top is something built (a roof, a floor, a wall) or water. A building is never set on the
     * roof of one that stands there already.
     */
    public static int ground(ServerWorld world, int x, int z) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        while (y > world.getBottomY() + 1) {
            BlockState below = world.getBlockState(new BlockPos(x, y - 1, z));
            if (!below.isIn(net.minecraft.registry.tag.BlockTags.LOGS) && !below.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) && !below.isReplaceable()) break;
            y--;
        }
        BlockPos g = new BlockPos(x, y - 1, z);
        BlockState s = world.getBlockState(g);
        if (!s.getFluidState().isEmpty() || !s.isSolidBlock(world, g)) return Integer.MIN_VALUE;
        return land(s) ? y : Integer.MIN_VALUE;
    }

    /** Land as the world made it: earth, sand, stone, gravel, snow, clay, a beaten path. */
    public static boolean land(BlockState s) {
        return s.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || s.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                || s.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD) || s.isIn(net.minecraft.registry.tag.BlockTags.TERRACOTTA)
                || s.isOf(net.minecraft.block.Blocks.GRAVEL) || s.isOf(net.minecraft.block.Blocks.DIRT_PATH) || s.isOf(net.minecraft.block.Blocks.SNOW_BLOCK)
                || s.isOf(net.minecraft.block.Blocks.CLAY) || s.isOf(net.minecraft.block.Blocks.SANDSTONE) || s.isOf(net.minecraft.block.Blocks.RED_SANDSTONE)
                || s.isOf(net.minecraft.block.Blocks.ICE) || s.isOf(net.minecraft.block.Blocks.PACKED_ICE) || s.isOf(net.minecraft.block.Blocks.FARMLAND);
    }

    private static BlockPos flat(ServerWorld world, int x, int z, int w, int d) {
        return flat(world, x, z, w, d, 0);
    }

    private static BlockPos flat(ServerWorld world, int x, int z, int w, int d, int pass) {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        java.util.List<Integer> ys = new java.util.ArrayList<>();
        boolean rough = pass > 0;
        for (int dx = -2; dx <= w + 1; dx++) {
            for (int dz = -2; dz <= d + 1; dz++) {
                if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return null;
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                if (rough) { // the ground under the trees, not the tops of their trunks
                    while (y > world.getBottomY() + 1) {
                        BlockState below = world.getBlockState(new BlockPos(x + dx, y - 1, z + dz));
                        if (!below.isIn(net.minecraft.registry.tag.BlockTags.LOGS) && !below.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) && !below.isReplaceable()) break;
                        y--;
                    }
                }
                BlockPos g = new BlockPos(x + dx, y - 1, z + dz);
                BlockState ground = world.getBlockState(g);
                if (!ground.isSolidBlock(world, g) || !ground.getFluidState().isEmpty()) return null;
                if (!land(ground)) return null; // a roof or a floor: something stands here already
                // not under a tree: no trunk standing on the site or right beside it (the second time round, they are felled)
                if (!rough) {
                    for (int up = 0; up <= 8; up++) {
                        if (world.getBlockState(new BlockPos(x + dx, y + up, z + dz)).isIn(net.minecraft.registry.tag.BlockTags.LOGS)) return null;
                    }
                }
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (dx >= 0 && dx < w && dz >= 0 && dz < d) ys.add(y);
            }
        }
        if (maxY - minY > TOLERANCE[Math.min(pass, TOLERANCE.length - 1)]) return null;
        // not on the lip of a drop: the land just round the plot must not fall away more than four below its floor
        int floorGuess = ys.isEmpty() ? maxY : ys.stream().sorted().toList().get(ys.size() / 2);
        for (int dx = -4; dx <= w + 3; dx += 2) {
            for (int dz = -4; dz <= d + 3; dz += 2) {
                if (dx >= -2 && dx <= w + 1 && dz >= -2 && dz <= d + 1) continue;
                if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) continue;
                if (floorGuess - world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) > 4) return null;
            }
        }
        // the floor at the ground's own middle height, not on its highest bump: the builders cut the bumps and fill
        // the hollows (see Builder.levelSite), and the building never stands up on a mound of earth
        java.util.Collections.sort(ys);
        return new BlockPos(x, ys.isEmpty() ? maxY : ys.get(ys.size() / 2), z);
    }

    /** The plot is clear: off the streets, off other plots and off what is built already (with room between). */
    private static boolean free(Village village, int x, int z, int w, int d) {
        return free(village, reach(village), x, z, w, d);
    }

    private static boolean free(Village village, int r, int x, int z, int w, int d) {
        for (int dx = -1; dx <= w; dx++) {
            if (onStreet(village, r, x + dx, z - 1) || onStreet(village, r, x + dx, z + d)) return false;
        }
        for (int dz = -1; dz <= d; dz++) {
            if (onStreet(village, r, x - 1, z + dz) || onStreet(village, r, x + w, z + dz)) return false;
        }
        for (int dx = 0; dx < w; dx += 2) for (int dz = 0; dz < d; dz += 2) if (onStreet(village, r, x + dx, z + dz)) return false;
        return freeOfBuildings(village, x, z, w, d);
    }

    private static boolean freeOfBuildings(Village village, int x, int z, int w, int d) {
        for (int[] p : village.plots) {
            if (x - MARGIN < p[0] + p[2] && x + w + MARGIN > p[0] && z - MARGIN < p[1] + p[3] && z + d + MARGIN > p[1]) return false;
        }
        for (Built b : village.built) {
            BlockPos o = b.origin();
            if (x - MARGIN < o.getX() + b.fw() && x + w + MARGIN > o.getX() && z - MARGIN < o.getZ() + b.fd() && z + d + MARGIN > o.getZ()) return false;
        }
        for (Building b : village.getBuildings().values()) {
            if (b.getBuildingType().grouped()) continue; // a field or a pen marked by MCA, not a building
            BlockPos p0 = b.getPos0(), p1 = b.getPos1();
            int bx0 = Math.min(p0.getX(), p1.getX()), bx1 = Math.max(p0.getX(), p1.getX());
            int bz0 = Math.min(p0.getZ(), p1.getZ()), bz1 = Math.max(p0.getZ(), p1.getZ());
            if (x - MARGIN <= bx1 && x + w + MARGIN > bx0 && z - MARGIN <= bz1 && z + d + MARGIN > bz0) return false;
        }
        for (House h : village.houses) {
            BlockPos o = h.origin();
            if (x - MARGIN < o.getX() + h.w() && x + w + MARGIN > o.getX() && z - MARGIN < o.getZ() + h.d() && z + d + MARGIN > o.getZ()) return false;
        }
        return true;
    }
}
