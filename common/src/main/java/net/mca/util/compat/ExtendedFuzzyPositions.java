package net.mca.util.compat;

import net.minecraft.util.math.BlockPos;

import java.util.function.Predicate;

public class ExtendedFuzzyPositions {
    public static BlockPos downWhile(BlockPos pos, int minY, Predicate<BlockPos> condition) {
        if (condition.test(pos)) {
            BlockPos blockPos = pos.down();
            while (blockPos.getY() > minY && condition.test(blockPos)) {
                blockPos = blockPos.down();
            }
            return blockPos;
        }
        return pos;
    }
}