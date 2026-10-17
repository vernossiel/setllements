package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builders reach as far as a man's arm, no further. For the upper walls and the roof they put up scaffolding beside
 * the building, a column of it at a time, and climb it block by block; to work elsewhere they come down and put up
 * another. When the building stands, the scaffolding comes down.
 */
public final class Scaffold {
    /** How far a builder reaches from where he stands, sideways and up. */
    public static final double REACH = 4.0;
    private static final Map<Integer, Set<BlockPos>> PUT_UP = new HashMap<>();

    private Scaffold() {
    }

    /** True when the block at {@code target} is within his reach from where he stands. */
    public static boolean inReach(VillagerEntityMCA v, BlockPos target) {
        double dx = target.getX() + 0.5 - v.getX(), dz = target.getZ() + 0.5 - v.getZ();
        double dy = target.getY() - v.getY();
        return dx * dx + dz * dz <= REACH * REACH && dy <= 3.5 && dy >= -3;
    }

    private static boolean onScaffold(ServerWorld world, VillagerEntityMCA v) {
        return world.getBlockState(v.getBlockPos().down()).isOf(Blocks.SCAFFOLDING);
    }

    private static boolean open(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        return s.isAir() || s.isReplaceable() && s.getFluidState().isEmpty();
    }

    /**
     * A column beside the target, outside the building, where a man standing at the target's height has it in
     * front of him: open from the ground up to his head.
     */
    private static BlockPos existing(ServerWorld world, VillagerEntityMCA v, BlockPos target, Built site) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        // a column put up already that reaches this block too: back up that one, not a new column for every block
        // (a heap of scaffolding round every building, and the builder forever climbing up and down)
        Village home = v.getResidency().getHomeVillage().orElse(null);
        Set<BlockPos> up = home == null ? null : PUT_UP.get(home.getId());
        if (up != null) {
            java.util.Map<Long, Integer> foot = new java.util.HashMap<>(); // column -> its lowest block
            for (BlockPos q : up) foot.merge(((long) q.getX() << 32) ^ (q.getZ() & 0xFFFFFFFFL), q.getY(), Math::min);
            for (var e : foot.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey().intValue();
                double dx = target.getX() + 0.5 - (cx + 0.5), dz = target.getZ() + 0.5 - (cz + 0.5);
                if (dx * dx + dz * dz > (REACH - 0.5) * (REACH - 0.5)) continue;
                BlockPos c = new BlockPos(cx, target.getY() - 1, cz);
                if (site != null && insideWalls(site, c)) continue;
                if (!open(world, c) && !world.getBlockState(c).isOf(Blocks.SCAFFOLDING) || !open(world, c.up())) continue;
                double dist = dx * dx + dz * dz;
                if (dist < bd) {
                    bd = dist;
                    best = new BlockPos(cx, e.getValue(), cz);
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static BlockPos columnFor(ServerWorld world, VillagerEntityMCA v, BlockPos target, Built site) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        // a column put up already that reaches this block too: back up that one, not a new column for every block
        // (a heap of scaffolding round every building, and the builder forever climbing up and down)
        Village home = v.getResidency().getHomeVillage().orElse(null);
        Set<BlockPos> up = home == null ? null : PUT_UP.get(home.getId());
        if (up != null) {
            java.util.Map<Long, Integer> foot = new java.util.HashMap<>(); // column -> its lowest block
            for (BlockPos q : up) foot.merge(((long) q.getX() << 32) ^ (q.getZ() & 0xFFFFFFFFL), q.getY(), Math::min);
            for (var e : foot.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey().intValue();
                double dx = target.getX() + 0.5 - (cx + 0.5), dz = target.getZ() + 0.5 - (cz + 0.5);
                if (dx * dx + dz * dz > (REACH - 0.5) * (REACH - 0.5)) continue;
                BlockPos c = new BlockPos(cx, target.getY() - 1, cz);
                if (site != null && insideWalls(site, c)) continue;
                if (!open(world, c) && !world.getBlockState(c).isOf(Blocks.SCAFFOLDING) || !open(world, c.up())) continue;
                double dist = dx * dx + dz * dz;
                if (dist < bd) {
                    bd = dist;
                    best = new BlockPos(cx, e.getValue(), cz);
                }
            }
            if (best != null) return best;
        }
        for (int r = 1; r <= 2; r++) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos c = target.offset(d, r);
                if (site != null && site.contains(c) && r == 1 && insideWalls(site, c)) continue;
                int feet = target.getY() - 1;
                if (!open(world, c.withY(feet)) || !open(world, c.withY(feet + 1))) continue;
                int ground = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
                if (ground > feet) continue;
                boolean clear = true;
                for (int y = ground; y < feet && clear; y++) if (!open(world, c.withY(y)) && !world.getBlockState(c.withY(y)).isOf(Blocks.SCAFFOLDING)) clear = false;
                if (!clear) continue;
                double dist = c.getSquaredDistance(v.getBlockPos());
                if (dist < bd) {
                    bd = dist;
                    best = new BlockPos(c.getX(), ground, c.getZ());
                }
            }
        }
        return best;
    }

    /** His own spot as the foot of a column, when the target is within reach sideways from it and the way up is open. */
    private static BlockPos here(ServerWorld world, VillagerEntityMCA v, BlockPos target, Built site) {
        BlockPos feet = v.getBlockPos();
        if (onScaffold(world, v)) { // on a column already: its foot
            BlockPos f = feet.down();
            while (world.getBlockState(f.down()).isOf(Blocks.SCAFFOLDING)) f = f.down();
            feet = f;
        }
        double dx = target.getX() + 0.5 - (feet.getX() + 0.5), dz = target.getZ() + 0.5 - (feet.getZ() + 0.5);
        if (dx * dx + dz * dz > (REACH - 0.5) * (REACH - 0.5) || dx * dx + dz * dz < 0.5) return null;
        if (site != null && insideWalls(site, feet)) return null;
        for (int y = feet.getY(); y <= target.getY(); y++) {
            BlockPos q = new BlockPos(feet.getX(), y, feet.getZ());
            if (!open(world, q) && !world.getBlockState(q).isOf(Blocks.SCAFFOLDING) && !q.equals(v.getBlockPos())) return null;
        }
        if (!world.getBlockState(feet.down()).isSolidBlock(world, feet.down()) && !world.getBlockState(feet.down()).isOf(Blocks.SCAFFOLDING)) return null;
        return feet.toImmutable();
    }

    private static boolean insideWalls(Built site, BlockPos p) {
        BlockPos o = site.origin();
        return p.getX() > o.getX() && p.getX() < o.getX() + site.fw() - 1 && p.getZ() > o.getZ() && p.getZ() < o.getZ() + site.fd() - 1;
    }

    /**
     * One second of getting to a block too high to reach from the ground: down off the scaffolding he is on if it is
     * the wrong one, over to the foot of the right column, then up it a block at a time, putting it up as he goes.
     * True while he is on his way; false when there is no way up (he works from where he can).
     */
    public static boolean approach(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos target, Built site, Set<Long> planned) {
        // as a man builds: up on what stands already - the top of a wall, the floor laid upstairs - when he can walk
        // there or step over onto it from where he is, the scaffolding only where there is nothing to stand on
        BlockPos perch = perch(v, world, target, site, planned);
        if (perch != null) {
            v.fallDistance = 0;
            double hx = perch.getX() + 0.5 - v.getX(), hz = perch.getZ() + 0.5 - v.getZ(), hy = perch.getY() - v.getY();
            if (hx * hx + hz * hz <= 1.7 * 1.7 && hy <= 1.2 && hy >= -1.5) {
                Doing.count(hx * hx + hz * hz < 0.3 && Math.abs(hy) < 0.3 ? "c.perch.there" : "c.perch.step");
                v.getNavigation().stop();
                v.refreshPositionAndAngles(perch.getX() + 0.5, perch.getY(), perch.getZ() + 0.5, v.getYaw(), v.getPitch()); // a step over
            } else {
                Doing.count("c.perch.walk");
                // a quarter of a minute walking and not there: no way up to it after all (the pathfinder says yes to
                // ways a man cannot take); the scaffold then (a day went in walking about the house so)
                long[] w = WALKING.computeIfAbsent(v.getUuid(), k -> new long[]{Long.MIN_VALUE, 0});
                long now = world.getTime();
                if (w[0] != perch.asLong()) {
                    w[0] = perch.asLong();
                    w[1] = now;
                } else if (now - w[1] > 300) {
                    BAD.add(perch.asLong());
                    PERCH.remove(v.getUuid());
                    WALKING.remove(v.getUuid());
                    return true;
                }
                Move.to(v, perch.getX() + 0.5, perch.getY(), perch.getZ() + 0.5, 0.6);
            }
            Gaze.at(v, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            return true;
        }
        // as a player does: up where he stands, if from there the block is within his reach (no walking off to a column
        // chosen beforehand that he may never get to); else the nearest column that will do
        // a column put up already that reaches it first (a new one for every block: the house stood in a forest of them)
        BlockPos col = existing(world, v, target, site);
        if (col == null) col = here(world, v, target, site);
        if (col == null) col = columnFor(world, v, target, site);
        if (col == null) {
            Doing.count("c.nocolumn");
            return false;
        }
        v.fallDistance = 0; // on the scaffolding a step down is a step, not a fall
        boolean onIt = Math.abs(v.getX() - (col.getX() + 0.5)) < 0.8 && Math.abs(v.getZ() - (col.getZ() + 0.5)) < 0.8;
        if (!onIt && onScaffold(world, v)) {
            Doing.count("c.col.down");
            // down the scaffolding he is on, a block at a time
            v.getNavigation().stop();
            v.refreshPositionAndAngles(v.getX(), Math.floor(v.getY()) - 1, v.getZ(), v.getYaw(), v.getPitch());
            return true;
        }
        if (!onIt) {
            Doing.count("c.col.walk");
            // a quarter of a minute and not at the foot of it: set down there (he walked at one the whole day)
            long[] w = WALKING.computeIfAbsent(v.getUuid(), k -> new long[]{Long.MIN_VALUE, 0});
            if (w[0] != col.asLong()) {
                w[0] = col.asLong();
                w[1] = world.getTime();
            } else if (world.getTime() - w[1] > 300) {
                WALKING.remove(v.getUuid());
                Doing.count("c.col.put");
                if (Move.putSafely(v, col)) return true;
            }
            Move.to(v, col.getX() + 0.5, col.getY(), col.getZ() + 0.5, 0.6);
            return true;
        }
        int feet = target.getY() - 1, at = (int) Math.floor(v.getY() + 0.01);
        v.getNavigation().stop();
        Gaze.at(v, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (at < feet) {
            Doing.count("c.col.up");
            // up a block: the scaffolding under his feet, and he steps up onto it
            BlockPos under = new BlockPos(col.getX(), at, col.getZ());
            if (open(world, under)) {
                world.setBlockState(under, Blocks.SCAFFOLDING.getDefaultState().with(ScaffoldingBlock.DISTANCE, 0));
                world.playSound(null, under, SoundEvents.BLOCK_SCAFFOLDING_PLACE, SoundCategory.BLOCKS, 0.7f, 1f);
                PUT_UP.computeIfAbsent(village.getId(), k -> new HashSet<>()).add(under.toImmutable());
                v.swingHand(v.getDominantHand());
            }
            v.refreshPositionAndAngles(col.getX() + 0.5, at + 1, col.getZ() + 0.5, v.getYaw(), v.getPitch());
            return true;
        }
        if (at > feet + 1) {
            Doing.count("c.col.lower");
            v.refreshPositionAndAngles(col.getX() + 0.5, at - 1, col.getZ() + 0.5, v.getYaw(), v.getPitch());
            return true;
        }
        Doing.count("c.col.top");
        return false; // up there: in reach
    }

    private static final Map<java.util.UUID, Object[]> PERCH = new HashMap<>(); // builder -> {target, perch or null, until}
    private static final Map<java.util.UUID, long[]> WALKING = new HashMap<>(); // builder -> {perch, since}
    private static final Set<Long> BAD = new HashSet<>(); // perches he could not get to

    /**
     * Where on the building itself he can stand with the block in reach: solid under foot, room for him, nothing more
     * to go there; and a way to it - a step over from where he stands, or a walk (stairs up, along the wall tops).
     */
    private static BlockPos perch(VillagerEntityMCA v, ServerWorld world, BlockPos target, Built site, Set<Long> planned) {
        Object[] c = PERCH.get(v.getUuid());
        long now = world.getTime();
        if (BAD.size() > 2000) BAD.clear();
        if (c != null && c[0].equals(target) && now < (long) c[2]) {
            BlockPos p = (BlockPos) c[1];
            if (p == null || stand(world, p, planned) && !BAD.contains(p.asLong())) return p;
        }
        java.util.List<BlockPos> cands = new java.util.ArrayList<>();
        int r = (int) REACH - 1;
        for (BlockPos p : BlockPos.iterate(target.add(-r, -2, -r), target.add(r, 0, r))) {
            double dx = target.getX() - p.getX(), dz = target.getZ() - p.getZ();
            if (dx * dx + dz * dz > (REACH - 0.5) * (REACH - 0.5) || dx == 0 && dz == 0) continue;
            if (site != null && !site.contains(p)) continue; // on the building or at its edge
            if (!stand(world, p, planned) || BAD.contains(p.asLong())) continue;
            cands.add(p.toImmutable());
        }
        cands.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(v.getPos())));
        BlockPos found = null;
        int paths = 0;
        for (BlockPos p : cands) {
            double hx = p.getX() + 0.5 - v.getX(), hz = p.getZ() + 0.5 - v.getZ(), hy = p.getY() - v.getY();
            if (hx * hx + hz * hz <= 1.7 * 1.7 && hy <= 1.2 && hy >= -1.5) {
                found = p;
                break;
            }
            if (paths++ >= 3) break;
            var path = v.getNavigation().findPathTo(p, 0);
            if (path != null && path.reachesTarget()) {
                found = p;
                break;
            }
        }
        PERCH.put(v.getUuid(), new Object[]{target.toImmutable(), found, now + (found == null ? 200 : 100)});
        return found;
    }

    /** Somewhere to stand: solid under foot (not scaffolding), open for his feet and head, and nothing to go there. */
    private static boolean stand(ServerWorld world, BlockPos p, Set<Long> planned) {
        BlockState under = world.getBlockState(p.down());
        if (!under.isSolidBlock(world, p.down()) || under.isOf(Blocks.SCAFFOLDING)) return false;
        if (!open(world, p) || !open(world, p.up())) return false;
        return planned == null || !planned.contains(p.asLong()) && !planned.contains(p.up().asLong());
    }

    /** The building stands: the scaffolding comes down. */
    public static void takeDown(Village village, ServerWorld world) {
        Set<BlockPos> s = PUT_UP.remove(village.getId());
        if (s == null) return;
        for (BlockPos p : s) if (world.getBlockState(p).isOf(Blocks.SCAFFOLDING)) world.setBlockState(p, Blocks.AIR.getDefaultState());
        // anyone still up there comes down safely
        for (VillagerEntityMCA r : village.getResidents(world)) {
            if (s.contains(r.getBlockPos().down()) || world.getBlockState(r.getBlockPos().down()).isAir() && s.stream().anyMatch(p -> p.getX() == r.getBlockX() && p.getZ() == r.getBlockZ())) {
                int g = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, r.getBlockX(), r.getBlockZ());
                r.refreshPositionAndAngles(r.getX(), g, r.getZ(), r.getYaw(), r.getPitch());
                r.fallDistance = 0;
            }
        }
    }
}
