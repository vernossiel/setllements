package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * The village's own border. The builder walks the line first and sets a marker post every few steps (the line
 * follows the village: the farthest of its buildings plus a margin); then a palisade of logs goes up along it with
 * torches on top and gates where the roads leave the village and at the four points of the compass. A town later
 * replaces it with a stone wall with crenels on the same line.
 */
public final class Walls {
    private Walls() {
    }

    /** The radius of the border for this village now. */
    public static int radius(Village village) {
        return Math.max(16, Math.min(64, Bounds.radius(village) + 3));
    }

    /** The ring cells around the center, in order round the circle. */
    private static List<int[]> ring(int r) {
        List<int[]> cells = new ArrayList<>();
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                double d = Math.hypot(dx, dz);
                if (d >= r - 0.5 && d < r + 0.5) cells.add(new int[]{dx, dz});
            }
        }
        cells.sort((a, b) -> Double.compare(Math.atan2(a[1], a[0]), Math.atan2(b[1], b[0])));
        return cells;
    }

    public static List<Step> plan(Village v, boolean stone) {
        ServerWorld world = v.getWorld();
        BlockPos o = v.buildOrigin;
        if (world == null || o == null) return List.of();
        int r = v.buildW;
        List<int[]> cells = ring(r);
        List<Step> l = new ArrayList<>();
        int[] ground = new int[cells.size()];
        boolean[] gate = new boolean[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            int x = o.getX() + cells.get(i)[0], z = o.getZ() + cells.get(i)[1];
            world.getChunk(x >> 4, z >> 4);
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            ground[i] = y - o.getY();
            boolean road = world.getBlockState(new BlockPos(x, y - 1, z)).isOf(Blocks.DIRT_PATH);
            boolean cardinal = Math.abs(cells.get(i)[0]) <= 1 || Math.abs(cells.get(i)[1]) <= 1;
            gate[i] = road || cardinal || !world.getFluidState(new BlockPos(x, y - 1, z)).isEmpty();
        }
        // widen every gate by a cell on each side
        boolean[] open = gate.clone();
        for (int i = 0; i < cells.size(); i++) {
            if (gate[i]) {
                open[Math.floorMod(i - 1, cells.size())] = true;
                open[Math.floorMod(i + 1, cells.size())] = true;
            }
        }
        if (!stone) {
            // first the line is marked out
            for (int i = 0; i < cells.size(); i += 8) l.add(new Step(cells.get(i)[0], ground[i], cells.get(i)[1], Kind.FENCE));
        }
        for (int i = 0; i < cells.size(); i++) {
            int dx = cells.get(i)[0], dz = cells.get(i)[1], g = ground[i];
            boolean prevOpen = open[Math.floorMod(i - 1, cells.size())], nextOpen = open[Math.floorMod(i + 1, cells.size())];
            if (open[i]) continue;
            boolean post = prevOpen || nextOpen; // the gateposts stand taller
            if (stone) {
                for (int y = g - 1; y <= g + 2; y++) l.add(new Step(dx, y, dz, Kind.STONE));
                if (i % 2 == 0 || post) l.add(new Step(dx, g + 3, dz, post ? Kind.ACCENT : Kind.STONE));
                if (i % 12 == 0) l.add(new Step(dx, g + 4, dz, Kind.TORCH));
            } else {
                for (int y = g; y <= g + 2; y++) l.add(new Step(dx, y, dz, Kind.PILLAR));
                if (post) l.add(new Step(dx, g + 3, dz, Kind.PILLAR));
                if (i % 10 == 0 || post) l.add(new Step(dx, g + (post ? 4 : 3), dz, Kind.TORCH));
            }
        }
        return l;
    }
}
