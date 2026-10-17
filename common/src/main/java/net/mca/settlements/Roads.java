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

    private static boolean city(Village village) {
        return village != null && village.getId() >= 0 && Prosperity.tier(village) >= Tiers.CITY;
    }

    private static void pave(ServerWorld world, Village village, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, y - 1, z);
        BlockState g = world.getBlockState(ground);
        if (!world.getBlockState(ground.up()).isAir()) return;
        // a city paves its streets in stone: cobbles and dressed stone, laid as the stores allow
        if (city(village) && (pavable(g) || g.isOf(Blocks.DIRT_PATH))
                && Builder.takeItem(village, s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE), 1)) {
            Palette pal = Palette.of(village);
            world.setBlockState(ground, (Math.floorMod(x * 31 + z * 17, 3) == 0 ? pal.stone() : pal.accent()).getDefaultState());
            return;
        }
        if (pavable(g)) world.setBlockState(ground, Blocks.DIRT_PATH.getDefaultState());
    }

    /**
     * The cells of the road from a building's front to the plaza, as {x, z}; a lamp post spot as {x, z, 1}. The road
     * follows the village's streets (see Layout): out of the door onto the street, along it to the main street, and
     * down the main street to the plaza. Streets are three wide.
     */
    public static java.util.List<int[]> route(Village village, BlockPos front) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        BlockPos c = new BlockPos(village.getCenter());
        int g = Layout.GRID;
        int fx = front.getX(), fz = front.getZ();
        // the street line nearest the door, east-west (z = sz) or north-south (x = sx)
        int sz = c.getZ() + Math.round((fz - c.getZ()) / (float) g) * g, sx = c.getX() + Math.round((fx - c.getX()) / (float) g) * g;
        java.util.List<int[]> path = new java.util.ArrayList<>();
        if (Math.abs(fz - sz) <= Math.abs(fx - sx)) {
            path.add(new int[]{fx, fz});
            path.add(new int[]{fx, sz});      // out onto the street
            path.add(new int[]{c.getX(), sz}); // along it to the main north-south street
            path.add(new int[]{c.getX(), c.getZ()});
        } else {
            path.add(new int[]{fx, fz});
            path.add(new int[]{sx, fz});
            path.add(new int[]{sx, c.getZ()});
            path.add(new int[]{c.getX(), c.getZ()});
        }
        int steps = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            int x = path.get(i)[0], z = path.get(i)[1], x1 = path.get(i + 1)[0], z1 = path.get(i + 1)[1];
            boolean street = i > 0; // the walk out of the door is a path, the streets are wide
            while ((x != x1 || z != z1) && steps++ < 160) {
                if (Math.hypot(x - c.getX(), z - c.getZ()) <= 5) return out; // the plaza
                boolean alongX = x != x1;
                out.add(new int[]{x, z});
                if (street) {
                    out.add(alongX ? new int[]{x, z - 1} : new int[]{x - 1, z});
                    out.add(alongX ? new int[]{x, z + 1} : new int[]{x + 1, z});
                    if (steps % 10 == 5) out.add(alongX ? new int[]{x, z + 2, 1} : new int[]{x + 2, z, 1});
                }
                if (alongX) x += Integer.signum(x1 - x);
                else z += Integer.signum(z1 - z);
            }
        }
        return out;
    }

    /** The paved ring around the well, as {x, z}. */
    public static java.util.List<int[]> plazaCells(Village village) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        BlockPos c = new BlockPos(village.getCenter());
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > 3.4 && dist <= 5.6) out.add(new int[]{c.getX() + dx, c.getZ() + dz});
            }
        }
        return out;
    }

    // ---------- bridges ----------

    /** The top block of a column, water counted (the surface of a river is the bottom of the gap). */
    private static int top(ServerWorld world, int x, int z) {
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static boolean wet(ServerWorld world, int x, int y, int z) {
        return !world.getFluidState(new BlockPos(x, y, z)).isEmpty();
    }

    /**
     * Where the road crosses a gap - a ravine, a gully, a stream or a river - with ground on both sides of it at much
     * the same height along the road: the height of the deck (the y of its planks, level with the banks), else
     * Integer.MIN_VALUE. The villagers went down into every gully and up the far side, or round, or not at all.
     */
    static int bridgeLevel(ServerWorld world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return Integer.MIN_VALUE;
        int g = top(world, x, z);
        boolean water = wet(world, x, g, z);
        int best = Integer.MIN_VALUE;
        for (int[] ax : new int[][]{{1, 0}, {0, 1}}) {
            int a = bank(world, x, z, ax[0], ax[1], g, water), b = bank(world, x, z, -ax[0], -ax[1], g, water);
            if (a == Integer.MIN_VALUE || b == Integer.MIN_VALUE || Math.abs(a - b) > 2) continue;
            best = Math.max(best, Math.min(a, b));
        }
        return best;
    }

    /** The first bank along a direction (within ten blocks) standing well above the gap's floor, or MIN_VALUE. */
    private static int bank(ServerWorld world, int x, int z, int sx, int sz, int g, boolean water) {
        for (int d = 1; d <= 10; d++) {
            int nx = x + sx * d, nz = z + sz * d;
            if (!world.isChunkLoaded(nx >> 4, nz >> 4)) return Integer.MIN_VALUE;
            int t = top(world, nx, nz);
            if (wet(world, nx, t, nz)) continue; // the river goes on
            if (t >= g + (water ? 1 : 2)) return t;
        }
        return Integer.MIN_VALUE;
    }

    /** A rail put along the edge of a deck (a fence on a plank, nothing above): on the road itself it must go. */
    private static boolean rail(ServerWorld world, int x, int z) {
        int t = top(world, x, z);
        BlockPos p = new BlockPos(x, t, z);
        return world.getBlockState(p).isIn(net.minecraft.registry.tag.BlockTags.FENCES) && world.getBlockState(p.up()).isAir()
                && world.getBlockState(p.down()).isIn(net.minecraft.registry.tag.BlockTags.PLANKS);
    }

    /** Where the builder stands to lay this cell: the deck when there is one to be, else the ground. */
    public static int standY(ServerWorld world, int x, int z) {
        int lv = bridgeLevel(world, x, z);
        return lv != Integer.MIN_VALUE ? lv + 1 : top(world, x, z) + 1;
    }

    /**
     * A plank of the deck at this cell, at the level of the banks; the side of it that looks out over the gap given a
     * plank more with a fence on it, the rail; and every fourth cell over a deep place a post of logs down to the ground.
     */
    private static void deck(ServerWorld world, Village village, int x, int z, int level) {
        Palette pal = Palette.of(village);
        BlockPos d = new BlockPos(x, level, z);
        if (!world.getBlockState(d).isReplaceable() && !world.getBlockState(d).isAir() && world.getFluidState(d).isEmpty()) return;
        if (!Builder.takeItem(village, s -> s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS), 1)) return;
        world.setBlockState(d, pal.planks().getDefaultState());
        if (world.getBlockState(d.up()).isIn(net.minecraft.registry.tag.BlockTags.FENCES)) world.setBlockState(d.up(), Blocks.AIR.getDefaultState());
        rails(world, village, d);
        if (Math.floorMod(x + z, 4) == 0) { // a post under it, down to the bed of the gap
            BlockPos q = d.down();
            for (int i = 0; i < 14 && (world.getBlockState(q).isAir() || world.getBlockState(q).isReplaceable() || !world.getFluidState(q).isEmpty()); i++, q = q.down()) {
                if (!Builder.takeItem(village, s -> s.isIn(net.minecraft.registry.tag.ItemTags.LOGS), 1)) break;
                world.setBlockState(q, pal.log().getDefaultState());
            }
        }
    }

    /** The sides of a plank of the deck that look out over the gap: a plank more, and a fence on it. */
    private static void rails(ServerWorld world, Village village, BlockPos d) {
        Palette pal = Palette.of(village);
        int level = d.getY();
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos n = d.offset(dir);
            if (!world.getBlockState(n).isAir() && !world.getBlockState(n).isReplaceable() && world.getFluidState(n).isEmpty()) continue;
            if (!world.getBlockState(n.up()).isAir()) continue;
            if (bridgeLevel(world, n.getX(), n.getZ()) == Integer.MIN_VALUE && top(world, n.getX(), n.getZ()) >= level - 1) continue; // ground there
            if (!Builder.takeItem(village, s -> s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS), 1)) break;
            world.setBlockState(n, pal.planks().getDefaultState());
            world.setBlockState(n.up(), pal.fence().getDefaultState());
        }
    }

    /** Whether there is work at this cell: a road cell to lay or mend, a lamp post missing (when there is a torch for it). */
    public static boolean needs(ServerWorld world, Village village, int[] cell) {
        if (cell.length < 3 && (bridgeLevel(world, cell[0], cell[1]) != Integer.MIN_VALUE || rail(world, cell[0], cell[1]))) return true;
        if (cell.length < 3) return broken(world, village, cell[0], cell[1]);
        int x = cell[0], z = cell[1];
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        BlockState top = world.getBlockState(p.down());
        if (top.isOf(Blocks.TORCH) || top.isOf(Blocks.LANTERN) || top.isIn(net.minecraft.registry.tag.BlockTags.FENCES)) return false;
        return world.getBlockState(p).isAir() && world.getBlockState(p.down()).isSolidBlock(world, p.down())
                && village.storageBuffer.stream().anyMatch(s -> s.isOf(Items.TORCH));
    }

    /** Lays the work at a cell: the road, or the lamp post. */
    public static void work(ServerWorld world, Village village, int[] cell) {
        if (cell.length < 3 && rail(world, cell[0], cell[1])) { // the rail of the lane beside: this lane is the road
            BlockPos f = new BlockPos(cell[0], top(world, cell[0], cell[1]), cell[1]);
            world.setBlockState(f, Blocks.AIR.getDefaultState());
            rails(world, village, f.down()); // and the rail goes along its own outer side instead
            return;
        }
        int lv = cell.length < 3 ? bridgeLevel(world, cell[0], cell[1]) : Integer.MIN_VALUE;
        if (lv != Integer.MIN_VALUE) deck(world, village, cell[0], cell[1], lv);
        else if (cell.length < 3) mend(world, village, cell[0], cell[1]);
        else lamp(world, village, cell[0], cell[1]);
    }

    /** A road cell that needs mending: grass grew back over it, or somebody dug it up. */
    public static boolean broken(ServerWorld world, Village village, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, y - 1, z);
        BlockState g = world.getBlockState(ground);
        if ((pavable(g) || city(village) && g.isOf(Blocks.DIRT_PATH)) && world.getBlockState(ground.up()).isAir()) return true;
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
        pave(world, village, x, z);
    }

    /** Straight road from the front of a building to the plaza ring. */
    public static void connect(ServerWorld world, Village village, BlockPos front) {
        BlockPos c = new BlockPos(village.getCenter());
        int x0 = front.getX(), z0 = front.getZ(), x1 = c.getX(), z1 = c.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0), sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz, steps = 0;
        while (steps++ < 90 && Math.hypot(x0 - x1, z0 - z1) > 5) {
            pave(world, village, x0, z0);
            pave(world, village, x0 + (dx > dz ? 0 : 1), z0 + (dx > dz ? 1 : 0)); // two wide
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
                if (dist > 3.4 && dist <= 5.6) pave(world, village, c.getX() + dx, c.getZ() + dz);
            }
        }
    }

    private static void lamp(ServerWorld world, Village village, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        if (!world.getBlockState(p).isAir() || !world.getBlockState(p.down()).isSolidBlock(world, p.down())) return;
        // beside the street, never on it: where two roads cross or bend the spot two blocks off one is on the other
        BlockState under = world.getBlockState(p.down());
        if (under.isOf(Blocks.DIRT_PATH) || under.isOf(Blocks.FARMLAND) || !Layout.land(under)) return;
        for (BlockPos q : BlockPos.iterate(p.add(-5, -2, -5), p.add(5, 2, 5))) { // and not one lamp right by another
            BlockState s = world.getBlockState(q);
            if (s.isOf(Blocks.TORCH) || s.isOf(Blocks.LANTERN)) {
                if (world.getBlockState(q.down()).isIn(net.minecraft.registry.tag.BlockTags.FENCES)) return;
            }
        }
        if (!Builder.takeItem(village, s -> s.isOf(Items.TORCH), 1)) return;
        world.setBlockState(p, Blocks.OAK_FENCE.getDefaultState());
        boolean lantern = village.getId() >= 0 && Prosperity.tier(village) >= Tiers.WEALTHY
                && (Builder.takeItem(village, s -> s.isOf(Items.IRON_INGOT), 1) || Builder.takeItem(village, s -> s.isOf(Items.IRON_NUGGET), 8));
        world.setBlockState(p.up(), (lantern ? Blocks.LANTERN : Blocks.TORCH).getDefaultState()); // a lantern needs iron
    }
}
