package net.mca.util;

import net.minecraft.util.math.BlockBox;

public class BlockBoxExtended extends BlockBox {
    public BlockBoxExtended(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        super(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public BlockBox expand(int margin) {
        return expand(margin, margin, margin);
    }

    public BlockBox expand(int x, int y, int z) {
        return new BlockBox(
                getMinX() - x,
                getMinY() - y,
                getMinZ() - z,
                getMaxX() + x,
                getMaxY() + y,
                getMaxZ() + z
        );
    }

    public int getMaxBlockCount() {
        return Math.max(Math.max(getBlockCountX(), getBlockCountY()), getBlockCountZ());
    }
}
