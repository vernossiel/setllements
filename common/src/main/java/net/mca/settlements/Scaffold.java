package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builders reach as far as a man's arm, no further. For the upper walls and the roof they put up scaffolding beside
 * the building, a column of it at a time, and climb it block by block; to work elsewhere they come down and put up
 * another. When the building stands, the scaffolding comes down.
 */
public final class Scaffold {
    /** How far a builder reaches from where he stands, sideways and up. */
    public static final double REACH = 4.0;
    private static final Map<Integer, Set<BlockPos>> PUT_UP = new HashMap<>();

    private Scaffold() {
    }

    /** True when the block at {@code target} is within his reach from where he stands. */
    public static boolean inReach(VillagerEntityMCA v, BlockPos target) {
        double dx = target.getX() + 0.5 - v.getX(), dz = target.getZ() + 0.5 - v.getZ();
        double dy = target.getY() - v.getY();
        return dx * dx + dz * dz <= REACH * REACH && dy <= 3.5 && dy >= -3;
    }

    private static boolean onScaffold(ServerWorld world, VillagerEntityMCA v) {
        return world.getBlockState(v.getBlockPos().down()).isOf(Blocks.SCAFFOLDING);
    }

    private static boolean open(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        return s.isAir() || s.isReplaceable() && s.getFluidState().isEmpty();
    }

    /**
     * A column beside the target, outside the building, where a man standing at the target's height has it in
     * front of him: open from the ground up to his head.
     */
    private static BlockPos columnFor(ServerWorld world, VillagerEntityMCA v, BlockPos target, Built site) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int r = 1; r <= 2; r++) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos c = target.offset(d, r);
                if (site != null && site.contains(c) && r == 1 && insideWalls(site, c)) continue;
                int feet = target.getY() - 1;
                if (!open(world, c.withY(feet)) || !open(world, c.withY(feet + 1))) continue;
                int ground = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
                if (ground > feet) continue;
                boolean clear = true;
                for (int y = ground; y < feet && clear; y++) if (!open(world, c.withY(y)) && !world.getBlockState(c.withY(y)).isOf(Blocks.SCAFFOLDING)) clear = false;
                if (!clear) continue;
                double dist = c.getSquaredDistance(v.getBlockPos());
                if (dist < bd) {
                    bd = dist;
                    best = new BlockPos(c.getX(), ground, c.getZ());
                }
            }
        }
        return best;
    }

    private static boolean insideWalls(Built site, BlockPos p) {
        BlockPos o = site.origin();
        return p.getX() > o.getX() && p.getX() < o.getX() + site.fw() - 1 && p.getZ() > o.getZ() && p.getZ() < o.getZ() + site.fd() - 1;
    }

    /**
     * One second of getting to a block too high to reach from the ground: down off the scaffolding he is on if it is
     * the wrong one, over to the foot of the right column, then up it a block at a time, putting it up as he goes.
     * True while he is on his way; false when there is no way up (he works from where he can).
     */
    public static boolean approach(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos target, Built site) {
        BlockPos col = columnFor(world, v, target, site);
        if (col == null) return false;
        v.fallDistance = 0; // on the scaffolding a step down is a step, not a fall
        boolean onIt = Math.abs(v.getX() - (col.getX() + 0.5)) < 0.8 && Math.abs(v.getZ() - (col.getZ() + 0.5)) < 0.8;
        if (!onIt && onScaffold(world, v)) {
            // down the scaffolding he is on, a block at a time
            v.getNavigation().stop();
            v.refreshPositionAndAngles(v.getX(), Math.floor(v.getY()) - 1, v.getZ(), v.getYaw(), v.getPitch());
            return true;
        }
        if (!onIt) {
            Move.to(v, col.getX() + 0.5, col.getY(), col.getZ() + 0.5, 0.6);
            return true;
        }
        int feet = target.getY() - 1, at = (int) Math.floor(v.getY() + 0.01);
        v.getNavigation().stop();
        Gaze.at(v, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (at < feet) {
            // up a block: the scaffolding under his feet, and he steps up onto it
            BlockPos under = new BlockPos(col.getX(), at, col.getZ());
            if (open(world, under)) {
                world.setBlockState(under, Blocks.SCAFFOLDING.getDefaultState().with(ScaffoldingBlock.DISTANCE, 0));
                world.playSound(null, under, SoundEvents.BLOCK_SCAFFOLDING_PLACE, SoundCategory.BLOCKS, 0.7f, 1f);
                PUT_UP.computeIfAbsent(village.getId(), k -> new HashSet<>()).add(under.toImmutable());
                v.swingHand(v.getDominantHand());
            }
            v.refreshPositionAndAngles(col.getX() + 0.5, at + 1, col.getZ() + 0.5, v.getYaw(), v.getPitch());
            return true;
        }
        if (at > feet + 1) {
            v.refreshPositionAndAngles(col.getX() + 0.5, at - 1, col.getZ() + 0.5, v.getYaw(), v.getPitch());
            return true;
        }
        return false; // up there: in reach
    }

    /** The building stands: the scaffolding comes down. */
    public static void takeDown(Village village, ServerWorld world) {
        Set<BlockPos> s = PUT_UP.remove(village.getId());
        if (s == null) return;
        for (BlockPos p : s) if (world.getBlockState(p).isOf(Blocks.SCAFFOLDING)) world.setBlockState(p, Blocks.AIR.getDefaultState());
        // anyone still up there comes down safely
        for (VillagerEntityMCA r : village.getResidents(world)) {
            if (s.contains(r.getBlockPos().down()) || world.getBlockState(r.getBlockPos().down()).isAir() && s.stream().anyMatch(p -> p.getX() == r.getBlockX() && p.getZ() == r.getBlockZ())) {
                int g = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, r.getBlockX(), r.getBlockZ());
                r.refreshPositionAndAngles(r.getX(), g, r.getZ(), r.getYaw(), r.getPitch());
                r.fallDistance = 0;
            }
        }
    }
}
