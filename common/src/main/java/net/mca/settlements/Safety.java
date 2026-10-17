package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the night's monsters may not spawn: inside a village - its houses, all it has built and laid out, the ground
 * within its palisade - and a margin round it. The border grows as the village does: it is measured again every half
 * minute from what stands there now.
 */
public final class Safety {
    /** How far beyond the village's buildings the ground is its own. */
    public static final int MARGIN = 16;
    private static final Map<Long, long[]> CACHE = new HashMap<>(); // chunk -> {inside 1/0, till}

    private Safety() {
    }

    public static boolean inVillage(ServerWorld world, BlockPos pos) {
        long chunk = ((long) (pos.getX() >> 4) << 32) ^ ((pos.getZ() >> 4) & 0xFFFFFFFFL);
        long now = world.getTime();
        long[] c = CACHE.get(chunk);
        if (c != null && now < c[1]) return c[0] == 1;
        boolean in = false;
        for (Village v : VillageManager.get(world)) {
            // the village as far as it has grown: its houses, and everything it has built and laid out - fields, mine,
            // pens - and once it has a palisade, all within it; re-measured every half minute, so it grows with the village
            if (v.isWithinBorder(pos, MARGIN)) {
                in = true;
                break;
            }
            BlockPos mid = new BlockPos(v.getCenter());
            int r = Math.max(Bounds.radius(v), v.palisadeR) + MARGIN / 2;
            double dx = pos.getX() - mid.getX(), dz = pos.getZ() - mid.getZ();
            if (dx * dx + dz * dz <= (double) r * r && Math.abs(pos.getY() - mid.getY()) <= 40) {
                in = true;
                break;
            }
        }
        if (CACHE.size() > 4096) CACHE.clear();
        CACHE.put(chunk, new long[]{in ? 1 : 0, now + 600}); // looked at again every half minute: the village grows
        return in;
    }
}
