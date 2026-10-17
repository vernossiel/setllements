package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** Where the night's monsters may not spawn: inside a village, its houses and a margin round them. */
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
            if (v.isWithinBorder(pos, MARGIN)) {
                in = true;
                break;
            }
        }
        if (CACHE.size() > 4096) CACHE.clear();
        CACHE.put(chunk, new long[]{in ? 1 : 0, now + 600}); // looked at again every half minute: the village grows
        return in;
    }
}
