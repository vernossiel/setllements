package net.mca.entity.ai;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public interface TaskUtils {
    /**
     * Finds a y position given an x,y,z coordinate that is assumed to be the world's "ground".
     *
     * @param world The world in which blocks will be tested
     * @param x     X coordinate
     * @param y     Y coordinate, used as the starting height for finding ground.
     * @param z     Z coordinate
     * @return Integer representing the air block above the first non-air block given the provided ordered triples.
     */
    static int getSpawnSafeTopLevel(World world, int x, int y, int z) {
        BlockPos.Mutable pos = new BlockPos.Mutable(x, Math.min(y, world.getTopY()), z);
        while (world.isAir(pos.move(Direction.DOWN)) && pos.getY() > world.getBottomY()) {}

        return pos.getY() + 1;
    }

    static List<BlockPos> getNearbyBlocks(BlockPos origin, World world, @Nullable Predicate<BlockState> filter, int xzDist, int yDist) {
        return BlockPos.streamOutwards(origin, xzDist, yDist, xzDist)
                .filter(pos -> !origin.equals(pos) && (filter == null || filter.test(world.getBlockState(pos))))
                .map(BlockPos::toImmutable)
                .toList();
    }

    @Nullable
    static BlockPos getNearestPoint(BlockPos origin, List<BlockPos> blocks) {
        return blocks.stream().min(Comparator.comparing(origin::getSquaredDistance)).orElse(null);
    }
}
