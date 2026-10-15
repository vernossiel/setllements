package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.minecraft.block.SaplingBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lumberjack remembers where he planted: he comes back for the trees when they have grown, and while they are
 * still saplings and there is nothing else about, he waits for them rather than walking off into the wild.
 */
public final class Grove {

    private Grove() {
    }

    public static void remember(Village village, BlockPos p) {
        if (village.grove.size() < 256 && village.grove.add(p.toImmutable())) village.markDirty();
    }

    /** The planted spots that are trees now. */
    public static List<BlockPos> grown(ServerWorld world, Village village) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> s = village.grove;
        if (s.removeIf(p -> world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4) && !(world.getBlockState(p).getBlock() instanceof SaplingBlock)
                && !world.getBlockState(p).isIn(BlockTags.LOGS))) village.markDirty();
        for (BlockPos p : s) if (world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4) && world.getBlockState(p).isIn(BlockTags.LOGS)) out.add(p);
        return out;
    }

    /** How many are still saplings. */
    public static int growing(ServerWorld world, Village village) {
        Set<BlockPos> s = village.grove;
        int n = 0;
        for (BlockPos p : s) if (world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4) && world.getBlockState(p).getBlock() instanceof SaplingBlock) n++;
        return n;
    }
}
