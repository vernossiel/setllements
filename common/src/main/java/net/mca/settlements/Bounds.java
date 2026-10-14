package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.minecraft.util.math.BlockPos;

/** How far the village reaches: the farthest of its buildings, plots and houses from the center, plus a margin. */
public final class Bounds {
    private Bounds() {
    }

    public static int radius(Village village) {
        BlockPos c = new BlockPos(village.getCenter());
        double r = 12;
        for (Built b : village.built) {
            for (int[] k : new int[][]{{0, 0}, {b.fw(), 0}, {0, b.fd()}, {b.fw(), b.fd()}}) {
                r = Math.max(r, Math.hypot(b.origin().getX() + k[0] - c.getX(), b.origin().getZ() + k[1] - c.getZ()));
            }
        }
        for (int[] p : village.plots) {
            for (int[] k : new int[][]{{0, 0}, {p[2], 0}, {0, p[3]}, {p[2], p[3]}}) {
                r = Math.max(r, Math.hypot(p[0] + k[0] - c.getX(), p[1] + k[1] - c.getZ()));
            }
        }
        for (var b : village.getBuildings().values()) {
            r = Math.max(r, Math.hypot(b.getCenter().getX() - c.getX(), b.getCenter().getZ() - c.getZ()) + 5);
        }
        return (int) Math.ceil(r) + 6;
    }
}
