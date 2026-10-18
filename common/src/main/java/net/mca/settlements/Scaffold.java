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
    /**
     * A column already standing that reaches this block: looked for in the world itself, not only in the list of what
     * he put up (that list is gone after a restart, and he put up a new column beside the old one for every block).
     */
    private static BlockPos existing(ServerWorld world, VillagerEntityMCA v, BlockPos target, Built site) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        int r = (int) REACH;
        for (BlockPos p : BlockPos.iterate(target.add(-r, -1, -r), target.add(r, -1, r))) {
            if (!world.getBlockState(p).isOf(Blocks.SCAFFOLDING) && !world.getBlockState(p.down()).isOf(Blocks.SCAFFOLDING)) continue;
            double dx = target.getX() - p.getX(), dz = target.getZ() - p.getZ();
            if (dx * dx + dz * dz > (REACH - 0.5) * (REACH - 0.5) || dx == 0 && dz == 0) continue;
            if (site != null && insideWalls(site, p)) continue;
            if (!open(world, p.up()) && !world.getBlockState(p.up()).isOf(Blocks.SCAFFOLDING)) continue;
            BlockPos foot = world.getBlockState(p).isOf(Blocks.SCAFFOLDING) ? p : p.down();
            while (world.getBlockState(foot.down()).isOf(Blocks.SCAFFOLDING)) foot = foot.down();
            double dist = dx * dx + dz * dz;
            if (dist < bd) {
                bd = dist;
                best = foot.toImmutable();
            }
        }
        return best;
    }

    /** The feet of the columns of scaffolding standing about this site. */
    private static java.util.List<BlockPos> feet(ServerWorld world, Built site) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        if (site == null) return out;
        java.util.Set<Long> cols = new java.util.HashSet<>();
        BlockPos o = site.origin();
        for (BlockPos p : BlockPos.iterate(o.add(-3, -1, -3), o.add(site.fw() + 3, 1, site.fd() + 3))) {
            if (!world.getBlockState(p).isOf(Blocks.SCAFFOLDING)) continue;
            if (!cols.add(((long) p.getX() << 32) ^ (p.getZ() & 0xFFFFFFFFL))) continue;
            BlockPos f = p.toImmutable();
            while (world.getBlockState(f.down()).isOf(Blocks.SCAFFOLDING)) f = f.down();
            out.add(f);
        }
        return out;
    }

    /** A column with nothing left to build within reach of it: it has done its work. */
    private static boolean spent(ServerWorld world, BlockPos foot, Set<Long> planned) {
        if (planned == null) return false;
        for (long l : planned) {
            BlockPos q = BlockPos.fromLong(l);
            if (q.getY() >= foot.getY() + 2 && Math.abs(q.getX() - foot.getX()) <= REACH && Math.abs(q.getZ() - foot.getZ()) <= REACH
                    && world.getBlockState(q).isReplaceable()) return false; // (laid already counts as done: the list is kept a while)
        }
        return true;
    }

    /** How many columns of scaffolding about this site are still of use (the spent ones come down, see tidy). */
    private static int columns(ServerWorld world, Built site, Set<Long> planned) {
        int n = 0;
        for (BlockPos f : feet(world, site)) if (!spent(world, f, planned)) n++;
        return n;
    }

    /**
     * A spent column by him, as he passes on the ground: taken down, as a player takes down the scaffolding he has
     * done with and puts it up again where the work is now (eight columns stood round a house, none of them where the
     * roof still wanted laying, and he stood a minute at every block of it). True when he took one down.
     */
    static boolean tidy(VillagerEntityMCA v, ServerWorld world, Built site, Set<Long> planned) {
        if (onScaffold(world, v) || !v.isOnGround()) return false;
        BlockPos far = null;
        double fd = 16 * 16;
        for (BlockPos f : feet(world, site)) {
            if (!spent(world, f, planned)) continue;
            double d = v.squaredDistanceTo(f.getX() + 0.5, f.getY(), f.getZ() + 0.5);
            if (d > 3.5 * 3.5) {
                if (d < fd) {
                    fd = d;
                    far = f;
                }
                continue;
            }
            Gear.face(v, f);
            v.swingHand(v.getDominantHand());
            world.breakBlock(f, true, v); // the column above falls with its foot, and he picks it up
            Doing.count("c.tidy");
            return true;
        }
        // one done with across the site: he goes over and takes it down, not leaving the house stood in its scaffold
        if (far != null && !BAD.contains(far.asLong())) {
            Doing.count("c.tidy.walk");
            if (!Move.to(v, far.getX() + 0.5, far.getY(), far.getZ() + 0.5, 0.6)) BAD.add(far.asLong());
            return true;
        }
        return false;
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
                Moves.step(v, perch); // a step over (walked, a hop up)
            } else {
                Doing.count("c.perch.walk");
                // a quarter of a minute walking and not there: no way up to it after all (the pathfinder says yes to
                // ways a man cannot take); the scaffold then (a day went in walking about the house so)
                long[] w = WALKING.computeIfAbsent(v.getUuid(), k -> new long[]{Long.MIN_VALUE, 0});
                long now = world.getTime();
                if (w[0] != perch.asLong()) {
                    w[0] = perch.asLong();
                    w[1] = now;
                } else if (now - w[1] > 600) { // (half a minute: up a ladder by hand takes its time)
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
        // and no more than eight columns of use round one building: the spent ones he takes down as he passes
        if (col == null && tidy(v, world, site, planned)) return true;
        if (col == null && columns(world, site, planned) < 8) col = here(world, v, target, site);
        if (col == null && columns(world, site, planned) < 8) col = columnFor(world, v, target, site);
        if (col != null && BAD.contains(col.asLong())) col = null; // one he could not get to
        if (col == null) {
            Doing.count("c.nocolumn");
            return false;
        }
        v.fallDistance = 0; // on the scaffolding a step down is a step, not a fall
        boolean onIt = Math.abs(v.getX() - (col.getX() + 0.5)) < 0.8 && Math.abs(v.getZ() - (col.getZ() + 0.5)) < 0.8;
        if (!onIt && onScaffold(world, v)) {
            Doing.count("c.col.down");
            // down the scaffolding he is on, a block at a time
            Moves.crouchDown(v); // a crouch, and down through it as a player lets himself down
            return true;
        }
        if (!onIt) {
            // right by it: onto it, into the scaffolding at its foot, as a man steps onto a ladder (he was walked to the foot
            // of a column standing there, the foot itself being scaffolding he cannot stand in, and set down beside it
            // again and again: a day of it)
            double cx = col.getX() + 0.5 - v.getX(), cz = col.getZ() + 0.5 - v.getZ();
            if (cx * cx + cz * cz <= 2.0 * 2.0 && Math.abs(v.getY() - col.getY()) <= 2.5 && Move.passable(world, col.up())) {
                Doing.count("c.col.on");
                Moves.step(v, col); // onto it, a step
                return true;
            }
            Doing.count("c.col.walk");
            // a quarter of a minute and not at the foot of it: set down there (he walked at one the whole day)
            long[] w = WALKING.computeIfAbsent(v.getUuid(), k -> new long[]{Long.MIN_VALUE, 0});
            if (w[0] != col.asLong()) {
                w[0] = col.asLong();
                w[1] = world.getTime();
            } else if (world.getTime() - w[1] > 600) {
                // half a minute and not at its foot: no way to it, another column (he was set down at it before, a
                // jump across the house a player saw)
                WALKING.remove(v.getUuid());
                Doing.count("c.col.bad");
                BAD.add(col.asLong());
                return false;
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
            if (open(world, under)) { // a jump, and the scaffolding put in under his feet at the top of it
                Moves.jumpPlace(v, Blocks.SCAFFOLDING.getDefaultState().with(ScaffoldingBlock.DISTANCE, 0),
                        () -> PUT_UP.computeIfAbsent(village.getId(), k -> new HashSet<>()).add(under.toImmutable()));
            } else if (world.getBlockState(under).isOf(Blocks.SCAFFOLDING)) {
                v.getJumpControl().setActive(); // up the scaffolding there already, climbing it
                v.setVelocity(v.getVelocity().x, Math.max(v.getVelocity().y, 0.2), v.getVelocity().z);
            }
            return true;
        }
        if (at > feet + 1) {
            Doing.count("c.col.lower");
            Moves.crouchDown(v);
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
            // the path finder takes no ladders: the floor upstairs, the loft under the roof (the best place to lay a
            // roof from) were never his to stand on; our own route goes up the ladder (and Move walks it so)
            var route = Route.find(world, v.getResidency().getHomeVillage().orElse(null), v.getBlockPos(), p, false);
            if (route != null && !route.isEmpty() && route.get(route.size() - 1).pos().getManhattanDistance(p) <= 1) {
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
        // (the steps of the roof and its slabs too: up on the roof itself, as a man lays its ridge; with nothing but
        // full blocks to stand on he stood three days below the last row of it)
        boolean step = under.getBlock() instanceof net.minecraft.block.StairsBlock || under.getBlock() instanceof net.minecraft.block.SlabBlock;
        if (!under.isSolidBlock(world, p.down()) && !step || under.isOf(Blocks.SCAFFOLDING)) return false;
        if (!open(world, p) || !open(world, p.up())) return false;
        return planned == null || !planned.contains(p.asLong()) && !planned.contains(p.up().asLong());
    }

    /** All scaffolding in and around a site (three blocks out, up to thirty above), taken down; whoever stands on it set on the ground. */
    public static void clearAround(Village village, ServerWorld world, BlockPos o, int w, int d) {
        for (BlockPos p : BlockPos.iterate(o.add(-3, -2, -3), o.add(w + 3, 30, d + 3))) {
            if (world.getBlockState(p).isOf(Blocks.SCAFFOLDING)) world.setBlockState(p, Blocks.AIR.getDefaultState(), net.minecraft.block.Block.NOTIFY_LISTENERS);
        }
        for (VillagerEntityMCA r : village.getResidents(world)) {
            if (r.squaredDistanceTo(o.getX(), o.getY(), o.getZ()) < 40 * 40 && world.getBlockState(r.getBlockPos().down()).isAir()) {
                Moves.softFall(r); // he drops with it, and lands unharmed (he was set down on the ground before)
            }
        }
    }

    /** The building stands: the scaffolding comes down. */
    public static void takeDown(Village village, ServerWorld world) {
        Set<BlockPos> s = PUT_UP.remove(village.getId());
        if (s == null) return;
        for (BlockPos p : s) if (world.getBlockState(p).isOf(Blocks.SCAFFOLDING)) world.setBlockState(p, Blocks.AIR.getDefaultState());
        // anyone still up there comes down safely
        for (VillagerEntityMCA r : village.getResidents(world)) {
            if (s.contains(r.getBlockPos().down()) || world.getBlockState(r.getBlockPos().down()).isAir() && s.stream().anyMatch(p -> p.getX() == r.getBlockX() && p.getZ() == r.getBlockZ())) {
                Moves.softFall(r); // he drops with it, and lands unharmed (he was set down on the ground before)
            }
        }
    }
}
