package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.Iterator;
import java.util.List;

/**
 * The village hearth: a campfire with four benches in front of the town hall. Villagers gather there in the morning,
 * take their breaks and lunch there and spend the evening around it. Everyone takes his own place around the fire
 * instead of crowding into one block.
 */
public final class Camp {
    private static final int SEARCH_RADIUS_SQ = 64 * 64;
    private static final int[][] SLOTS = {{2, 2}, {-2, 2}, {2, -2}, {-2, -2}, {3, 1}, {-3, -1}, {1, -3}, {-1, 3}, {3, -1}, {-3, 1}, {-1, -3}, {1, 3}};

    private Camp() {
    }

    public static BlockPos nearest(Village village, ServerWorld world, VillagerEntityMCA v) {
        BlockPos best = null;
        double bestD = SEARCH_RADIUS_SQ;
        for (Iterator<BlockPos> it = village.campfires.iterator(); it.hasNext(); ) {
            BlockPos p = it.next();
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (!world.getBlockState(p).isOf(Blocks.CAMPFIRE)) {
                it.remove();
                village.markDirty();
                continue;
            }
            double d = v.squaredDistanceTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** The hearth by the town hall: built by the founders together with the hall. */
    public static BlockPos hearth(Village village, ServerWorld world) {
        if (village.hallPos == null) return null;
        for (BlockPos p : village.campfires) if (p.isWithinDistance(village.hallPos, 20) && world.getBlockState(p).isOf(Blocks.CAMPFIRE)) return p;
        return null;
    }

    public static BlockPos ensureHearth(Village village, ServerWorld world) {
        BlockPos h = hearth(village, world);
        if (h != null || village.hallPos == null) return h;
        BlockPos c = village.hallPos;
        for (int r = 7; r <= 14; r++) {
            for (int a = 0; a < 16; a++) {
                double ang = a * Math.PI / 8;
                int x = c.getX() + (int) Math.round(Math.cos(ang) * r), z = c.getZ() + (int) Math.round(Math.sin(ang) * r);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y, z);
                if (fits(world, village, p)) {
                    build(world, p);
                    village.campfires.add(p);
                    village.markDirty();
                    return p;
                }
            }
        }
        return null;
    }

    private static boolean fits(ServerWorld world, Village village, BlockPos p) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos q = p.add(dx, 0, dz);
                BlockState ground = world.getBlockState(q.down());
                if (!ground.isSolidBlock(world, q.down()) || !ground.getFluidState().isEmpty()) return false;
                if (!world.getBlockState(q).isReplaceable() || !world.getBlockState(q.up()).isReplaceable()) return false;
                if (Protection.insideStructure(village, q)) return false;
            }
        }
        for (int[] plot : village.plots) {
            if (p.getX() + 4 > plot[0] && p.getX() - 4 < plot[0] + plot[2] && p.getZ() + 4 > plot[1] && p.getZ() - 4 < plot[1] + plot[3]) return false;
        }
        return true;
    }

    /** Campfire, a ring of gravel-free trodden ground and four benches facing the fire. */
    private static void build(ServerWorld world, BlockPos p) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos q = p.add(dx, 0, dz);
                if (!world.getBlockState(q).isAir()) world.setBlockState(q, Blocks.AIR.getDefaultState());
                if (Math.abs(dx) + Math.abs(dz) <= 3 && world.getBlockState(q.down()).isOf(Blocks.GRASS_BLOCK)) world.setBlockState(q.down(), Blocks.DIRT_PATH.getDefaultState());
            }
        }
        world.setBlockState(p, Blocks.CAMPFIRE.getDefaultState());
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos seat = p.offset(d, 3);
            for (BlockPos s : List.of(seat, seat.offset(d.rotateYClockwise()), seat.offset(d.rotateYCounterclockwise()))) {
                if (s == seat || world.random.nextInt(3) > 0) {
                    world.setBlockState(s, Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, d));
                }
            }
        }
        world.setBlockState(p.add(2, 0, 3).add(1, 0, 0), Blocks.LANTERN.getDefaultState());
    }

    /** This villager's own place by the fire: a fixed slot, or the next free one. */
    private static BlockPos slot(VillagerEntityMCA v, ServerWorld world, BlockPos fire) {
        int start = Math.floorMod(v.getUuid().hashCode(), SLOTS.length);
        for (int i = 0; i < SLOTS.length; i++) {
            int[] s = SLOTS[(start + i) % SLOTS.length];
            BlockPos p = fire.add(s[0], 0, s[1]);
            if (!Move.passable(world, p) || !Move.passable(world, p.up())) continue;
            boolean taken = !world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(p).expand(0.2), o -> o != v).isEmpty();
            if (!taken) return p;
        }
        return fire.add(SLOTS[start][0], 0, SLOTS[start][1]);
    }

    /** Walks to his place at the fire. Returns true while the villager is there. */
    public static boolean goAndSit(VillagerEntityMCA v, Village village) {
        if (village == null || !(v.getWorld() instanceof ServerWorld world)) return false;
        BlockPos fire = hearth(village, world);
        if (fire == null || !fire.isWithinDistance(v.getBlockPos(), 64)) {
            BlockPos near = nearest(village, world, v);
            if (near != null) fire = near;
        }
        if (fire == null) fire = ensureHearth(village, world);
        if (fire == null) return false;
        BlockPos place = slot(v, world, fire);
        if (v.squaredDistanceTo(place.getX() + 0.5, place.getY(), place.getZ() + 0.5) > 2.5) {
            Move.to(v, place.getX() + 0.5, place.getY(), place.getZ() + 0.5, 0.55);
            return false;
        }
        v.getNavigation().stop();
        v.getLookControl().lookAt(fire.getX() + 0.5, fire.getY() + 0.5, fire.getZ() + 0.5);
        return true;
    }
}
