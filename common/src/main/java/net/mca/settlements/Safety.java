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

    private static long refused, allowed, lastLog;

    /** For the record: how many monster spawns were turned away from the villages, and how many let through elsewhere. */
    public static void count(boolean in) {
        if (in) refused++;
        else allowed++;
        long now = System.currentTimeMillis();
        if (now - lastLog > 60000) {
            lastLog = now;
            net.mca.MCA.LOGGER.info("[Settlements] monster spawns: {} refused in villages, {} allowed elsewhere (last minute)", refused, allowed);
            refused = allowed = 0;
        }
    }

    public static boolean inVillage(ServerWorld world, BlockPos pos) {
        // the chunk and its sixteen-block band of height: the answer below ground (a cave far under the village) is not
        // the answer for the roofs above it (cached by the chunk alone, one look in a cave let skeletons out on the roofs)
        long chunk = (((long) (pos.getX() >> 4) & 0x3FFFFFL) << 42) | (((long) (pos.getZ() >> 4) & 0x3FFFFFL) << 20) | ((pos.getY() >> 4) & 0xFFFFFL);
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
