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
    private static final long NO_WORK_PAUSE = 6000;

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

        BlockPos found = switch (chore) {
            case CHOP -> findTree(world, v.getBlockPos());
            case HARVEST -> findCrop(world, v.getBlockPos());
            default -> null;
        };
        if (found != null) {
            v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(found, 0.6f, 2));
        } else {
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
