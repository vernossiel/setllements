package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.VillageManager;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * MCA only knows a village once its buildings have been scanned. A village that came with the world, or one nobody has
 * walked through yet, would otherwise be invisible to everything in Settlements. Villagers (and the /settlements scan
 * command) hand the beds they can find to MCA's building scanner.
 */
public final class Registration {
    private Registration() {
    }

    public static int report(ServerWorld world, VillagerEntityMCA v) {
        return reportAround(world, v.getBlockPos(), 14);
    }

    public static int reportAround(ServerWorld world, BlockPos center, int radius) {
        VillageManager vm = VillageManager.get(world);
        int n = 0;
        for (BlockPos p : BlockPos.iterate(center.add(-radius, -4, -radius), center.add(radius, 4, radius))) {
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (world.getBlockState(p).isIn(BlockTags.BEDS)) {
                vm.reportBuilding(p.toImmutable());
                n++;
            }
        }
        return n;
    }
}
