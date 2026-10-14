package net.mca.settlements;

import net.mca.server.world.data.Building;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * Village plan: plots sit on rings around the central plaza and face it, so the roads run straight to the middle.
 * Houses and workshops take the inner rings, farms and watchtowers the outer ones.
 */
public final class Layout {
    private static final int[] RINGS = {14, 22, 30, 38, 46, 54};
    private static final int MARGIN = 3;

    public record Slot(BlockPos origin, int rot, int fw, int fd) {
    }

    private Layout() {
    }

    /** Rotation so that the front (+z side of the plan) faces the plaza. */
    private static int facingCenter(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? 1 : 3;
        return dz > 0 ? 2 : 0;
    }

    public static Slot find(ServerWorld world, Village village, String type, int w, int d, int minRing, int maxRing) {
        int[] fp = Blueprints.footprint(type, w, d);
        BlockPos c = new BlockPos(village.getCenter());
        var rand = world.random;
        for (int ri = minRing; ri <= Math.min(maxRing, RINGS.length - 1); ri++) {
            int radius = RINGS[ri];
            int n = Math.max(4, (int) (2 * Math.PI * radius / (Math.max(fp[0], fp[1]) + MARGIN + 2)));
            double offset = rand.nextDouble() * Math.PI * 2;
            int start = rand.nextInt(n);
            for (int i = 0; i < n; i++) {
                double angle = offset + ((start + i) % n) * Math.PI * 2 / n;
                double dx = Math.cos(angle) * radius, dz = Math.sin(angle) * radius;
                int rot = type.equals("mine") ? 0 : facingCenter(dx, dz);
                int fw = (rot & 1) == 0 ? fp[0] : fp[1], fd = (rot & 1) == 0 ? fp[1] : fp[0];
                int x = c.getX() + (int) dx - fw / 2, z = c.getZ() + (int) dz - fd / 2;
                BlockPos origin = flat(world, x, z, fw, fd);
                if (origin != null && free(village, origin, fw, fd)) return new Slot(origin, rot, fw, fd);
            }
        }
        return null;
    }

    /** The plaza itself: the plan stands on the village center. */
    public static Slot center(ServerWorld world, Village village, String type) {
        int[] fp = Blueprints.footprint(type, 0, 0);
        BlockPos c = new BlockPos(village.getCenter());
        int x = c.getX() - fp[0] / 2, z = c.getZ() - fp[1] / 2;
        BlockPos origin = flat(world, x, z, fp[0], fp[1]);
        if (origin == null || !free(village, origin, fp[0], fp[1])) return null;
        return new Slot(origin, 0, fp[0], fp[1]);
    }

    private static BlockPos flat(ServerWorld world, int x, int z, int w, int d) {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int dx = -2; dx <= w + 1; dx++) {
            for (int dz = -2; dz <= d + 1; dz++) {
                if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return null;
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                BlockPos g = new BlockPos(x + dx, y - 1, z + dz);
                BlockState ground = world.getBlockState(g);
                if (!ground.isSolidBlock(world, g) || !ground.getFluidState().isEmpty()) return null;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        return maxY - minY <= 1 ? new BlockPos(x, maxY, z) : null;
    }

    private static boolean free(Village village, BlockPos o, int w, int d) {
        for (int[] p : village.plots) {
            if (o.getX() - MARGIN < p[0] + p[2] && o.getX() + w + MARGIN > p[0] && o.getZ() - MARGIN < p[1] + p[3] && o.getZ() + d + MARGIN > p[1]) return false;
        }
        for (Building b : village.getBuildings().values()) {
            for (int dx = -1; dx <= w; dx += w + 1) {
                for (int dz = -1; dz <= d; dz += d + 1) {
                    if (b.containsPos(o.add(dx, 1, dz))) return false;
                }
            }
        }
        return true;
    }
}
