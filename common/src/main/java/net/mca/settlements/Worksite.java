package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * A chore that produces nothing is not work. When a lumberjack or farmer has been standing for half a minute without
 * collecting anything, they look for the nearest tree or ripe crop, walk there, and if there is nothing to be found
 * they stop pretending and rest for a while.
 */
public final class Worksite {
    private static final int IDLE_LIMIT = 30;
    private static final int RADIUS = 44;
    private static final long NO_WORK_PAUSE = 1200; // a minute, then he looks again

    private Worksite() {
    }

    private static int count(VillagerEntityMCA v) {
        int n = 0;
        for (int i = 0; i < v.getInventory().size(); i++) n += v.getInventory().getStack(i).getCount();
        return n;
    }

    /** Called once per second while an autonomous chore is running. */
    public static void check(VillagerEntityMCA v, Chore chore) {
        if (!(v.getWorld() instanceof ServerWorld world)) return;
        int inv = count(v);
        if (inv != v.settlementsLastInv) {
            v.settlementsLastInv = inv;
            v.settlementsIdleSec = 0;
            return;
        }
        if (++v.settlementsIdleSec < IDLE_LIMIT) return;
        v.settlementsIdleSec = 0;
        // a fisherman by good water is at work, a catch or not (a bite every quarter minute, a third of them lost: half a
        // minute with nothing in the bag had him put down his rod and stand idle a minute, again and again, the village starving)
        if (chore == Chore.FISH && byWater(world, v.getBlockPos())) return;

        BlockPos found = switch (chore) {
            case CHOP -> findTree(world, v.getBlockPos());
            case HARVEST -> findCrop(world, v.getBlockPos());
            case FISH -> findShore(world, v.getBlockPos()); // a pond or a river further off: he walks to its bank
            default -> null;
        };
        if (found != null) {
            v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(found, 0.6f, 2));
            Speech.say(v, "settlements.say.search");
        } else {
            Speech.say(v, "settlements.say.nowork");
            v.getVillagerBrain().abandonJob();
            v.settlementsNoWorkUntil = world.getTime() + NO_WORK_PAUSE;
        }
    }

    private static BlockPos findTree(ServerWorld world, BlockPos center) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                for (int y = top - 1; y >= top - 10 && y > world.getBottomY(); y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (!s.isIn(BlockTags.LOGS)) continue;
                    BlockPos below = p.down();
                    if (world.getBlockState(below).isIn(BlockTags.LOGS)) continue;
                    if (!world.getBlockState(below).isSolidBlock(world, below)) continue;
                    double d = dx * dx + dz * dz;
                    if (d < bestD && hasLeavesAbove(world, p)) {
                        bestD = d;
                        best = p;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static boolean byWater(ServerWorld world, BlockPos at) {
        for (BlockPos q : BlockPos.iterate(at.add(-5, -3, -5), at.add(5, 1, 5))) {
            if (world.getFluidState(q).isStill() && world.getBlockState(q).isOf(net.minecraft.block.Blocks.WATER) && world.getBlockState(q.up()).isAir()
                    && net.mca.entity.ai.brain.tasks.chore.FishingTask.fishable(world, q.toImmutable())) return true;
        }
        return false;
    }

    /** The nearest dry bank of good fishing water (see FishingTask.fishable) within reach of the village. */
    private static BlockPos findShore(ServerWorld world, BlockPos center) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx += 2) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += 2) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                double d = dx * dx + dz * dz;
                if (d >= bestD) continue;
                BlockPos w = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1, z);
                if (!world.getBlockState(w).isOf(net.minecraft.block.Blocks.WATER) || !world.getFluidState(w).isStill()
                        || !net.mca.entity.ai.brain.tasks.chore.FishingTask.fishable(world, w)) continue;
                for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                    for (int step = 1; step <= 4; step++) { // the bank: dry ground a few steps off the water's edge
                        BlockPos g = w.offset(dir, step);
                        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, g.getX(), g.getZ());
                        BlockPos ground = new BlockPos(g.getX(), top - 1, g.getZ());
                        if (Math.abs(top - 1 - w.getY()) > 2) break;
                        if (!world.getFluidState(ground).isEmpty()) continue;
                        if (!world.getBlockState(ground).isSolidBlock(world, ground)) break;
                        bestD = d;
                        best = ground.up();
                        break;
                    }
                    if (best != null && bestD == d) break;
                }
            }
        }
        return best;
    }

    private static boolean hasLeavesAbove(ServerWorld world, BlockPos base) {
        BlockPos p = base;
        for (int i = 0; i < 24; i++) {
            p = p.up();
            BlockState s = world.getBlockState(p);
            if (s.isIn(BlockTags.LEAVES)) return true;
            if (!s.isIn(BlockTags.LOGS)) return false;
        }
        return false;
    }

    private static BlockPos findCrop(ServerWorld world, BlockPos center) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos p = new BlockPos(x, top - 1, z);
                BlockState s = world.getBlockState(p);
                if (s.getBlock() instanceof CropBlock crop && crop.isMature(s)) {
                    double d = dx * dx + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }
}
