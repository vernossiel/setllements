package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/** Free dirt paths from every finished building to the plaza, with a lamp post now and then. */
public final class Roads {
    private Roads() {
    }

    private static boolean pavable(BlockState s) {
        return s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.COARSE_DIRT) || s.isOf(Blocks.PODZOL) || s.isOf(Blocks.ROOTED_DIRT);
    }

    private static void pave(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, y - 1, z);
        if (pavable(world.getBlockState(ground)) && world.getBlockState(ground.up()).isAir()) {
            world.setBlockState(ground, Blocks.DIRT_PATH.getDefaultState());
        }
    }

    /** The cells of the road from a building's front to the plaza (two wide), as {x, z}. */
    public static java.util.List<int[]> route(Village village, BlockPos front) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        BlockPos c = new BlockPos(village.getCenter());
        int x0 = front.getX(), z0 = front.getZ(), x1 = c.getX(), z1 = c.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0), sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz, steps = 0;
        while (steps++ < 90 && Math.hypot(x0 - x1, z0 - z1) > 5) {
            out.add(new int[]{x0, z0});
            out.add(new int[]{x0 + (dx > dz ? 0 : 1), z0 + (dx > dz ? 1 : 0)});
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z0 += sz;
            }
        }
        return out;
    }

    /** A road cell that needs mending: grass grew back over it, or somebody dug it up. */
    public static boolean broken(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, y - 1, z);
        if (pavable(world.getBlockState(ground)) && world.getBlockState(ground.up()).isAir()) return true;
        return hole(world, x, y, z);
    }

    /** One block lower than the road on both sides along it: a pit dug into the road. */
    private static boolean hole(ServerWorld world, int x, int y, int z) {
        int higher = 0;
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int ny = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + o[0], z + o[1]);
            BlockPos n = new BlockPos(x + o[0], ny - 1, z + o[1]);
            if (ny > y && world.getBlockState(n).isOf(Blocks.DIRT_PATH)) higher++;
        }
        return higher >= 2;
    }

    /** Mends one road cell: fills a pit with earth and lays the path again. */
    public static void mend(ServerWorld world, Village village, int x, int z) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (hole(world, x, y, z)) {
            BlockPos fill = new BlockPos(x, y, z);
            if (world.getBlockState(fill).isReplaceable()) world.setBlockState(fill, Blocks.DIRT.getDefaultState());
        }
        pave(world, x, z);
    }

    /** Straight road from the front of a building to the plaza ring. */
    public static void connect(ServerWorld world, Village village, BlockPos front) {
        BlockPos c = new BlockPos(village.getCenter());
        int x0 = front.getX(), z0 = front.getZ(), x1 = c.getX(), z1 = c.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0), sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz, steps = 0;
        while (steps++ < 90 && Math.hypot(x0 - x1, z0 - z1) > 5) {
            pave(world, x0, z0);
            pave(world, x0 + (dx > dz ? 0 : 1), z0 + (dx > dz ? 1 : 0)); // two wide
            if (steps % 8 == 4) lamp(world, village, x0 + (dx > dz ? 0 : 2), z0 + (dx > dz ? 2 : 0));
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z0 += sz;
            }
        }
    }

    /** A paved ring around the well. */
    public static void plaza(ServerWorld world, Village village) {
        BlockPos c = new BlockPos(village.getCenter());
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > 3.4 && dist <= 5.6) pave(world, c.getX() + dx, c.getZ() + dz);
            }
        }
    }

    private static void lamp(ServerWorld world, Village village, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        if (!world.getBlockState(p).isAir() || !world.getBlockState(p.down()).isSolidBlock(world, p.down())) return;
        if (!Builder.takeItem(village, s -> s.isOf(Items.TORCH), 1)) return;
        world.setBlockState(p, Blocks.OAK_FENCE.getDefaultState());
        world.setBlockState(p.up(), Blocks.TORCH.getDefaultState());
    }
}
