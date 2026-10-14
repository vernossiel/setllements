package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;

/** Break spots: villagers walk to the nearest campfire near their workplace to rest, and light one if there is none. */
public final class Camp {
    private static final int SEARCH_RADIUS_SQ = 40 * 40;

    private Camp() {
    }

    public static BlockPos nearest(Village village, ServerWorld world, VillagerEntityMCA v) {
        BlockPos best = null;
        double bestD = SEARCH_RADIUS_SQ;
        for (Iterator<BlockPos> it = village.campfires.iterator(); it.hasNext(); ) {
            BlockPos p = it.next();
            if (!world.getBlockState(p).isOf(Blocks.CAMPFIRE)) {
                it.remove();
                village.markDirty();
                continue;
            }
            double d = v.squaredDistanceTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static BlockPos buildNear(Village village, ServerWorld world, VillagerEntityMCA v) {
        if (village.storageBuffer.stream().filter(s -> s.isIn(ItemTags.LOGS)).mapToInt(ItemStack::getCount).sum() < 3) return null;
        BlockPos base = v.getBlockPos();
        for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}}) {
            BlockPos p = base.add(o[0], 0, o[1]);
            BlockState below = world.getBlockState(p.down());
            if (world.getBlockState(p).isAir() && below.isSolidBlock(world, p.down()) && below.getFluidState().isEmpty()) {
                int need = 3;
                for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext() && need > 0; ) {
                    ItemStack s = it.next();
                    if (!s.isIn(ItemTags.LOGS)) continue;
                    int d = Math.min(need, s.getCount());
                    s.decrement(d);
                    need -= d;
                    if (s.isEmpty()) it.remove();
                }
                world.setBlockState(p, Blocks.CAMPFIRE.getDefaultState());
                village.campfires.add(p);
                village.markDirty();
                return p;
            }
        }
        return null;
    }

    /**
     * Walks to the campfire (lighting one if needed). Returns true while the villager sits by the fire.
     */
    public static boolean goAndSit(VillagerEntityMCA v, Village village) {
        if (village == null || !(v.getWorld() instanceof ServerWorld world)) return false;
        BlockPos fire = nearest(village, world, v);
        if (fire == null) fire = buildNear(village, world, v);
        if (fire == null) return false;
        if (v.squaredDistanceTo(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5) > 9) {
            Move.to(v, fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5, 0.6);
            return false;
        }
        v.getNavigation().stop();
        v.getLookControl().lookAt(fire.getX() + 0.5, fire.getY() + 0.5, fire.getZ() + 0.5);
        return true;
    }
}
