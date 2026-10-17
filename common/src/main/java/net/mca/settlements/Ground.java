package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * The village keeps its ground as people keep a yard: a hole is filled in, a crater after a blast is filled up from
 * its edges inward, a lump of earth or a stray pillar of cobble somebody left standing is taken down. The builders do
 * it when they have no building in hand or wait on material, a block at a time, walking to each.
 * <p>
 * The rule is the one a man goes by with a spade: a spot lower than nearly all the ground round it gets a block of
 * earth, a spot higher than all of it loses its top block. Buildings, fields, roads, water and trees are left alone.
 */
public final class Ground {
    private static final Map<Integer, BlockPos> TODO = new HashMap<>(); // village -> the spot being seen to (y: where the block goes, or the block to take)
    private static final Map<Integer, Boolean> FILL = new HashMap<>();
    private static final Map<Integer, Long> NEXT_LOOK = new HashMap<>();
    private static final Map<Integer, Long> SINCE = new HashMap<>();

    private Ground() {
    }

    /** A second of it for a builder with nothing better to do. True while he is at it. */
    public static boolean work(VillagerEntityMCA v, Village village, ServerWorld world) {
        int id = village.getId();
        BlockPos t = TODO.get(id);
        long now = world.getTime();
        if (t == null) {
            if (now < NEXT_LOOK.getOrDefault(id, 0L)) return false;
            if (!look(village, world)) {
                NEXT_LOOK.put(id, now + 20 * 60); // the ground is as it should be: a look again in a minute
                return false;
            }
            t = TODO.get(id);
            SINCE.put(id, now);
        }
        boolean fill = FILL.getOrDefault(id, true);
        if (fill ? !world.getBlockState(t).isReplaceable() : !removable(world, t)) { // done already (or something else came of it)
            TODO.remove(id);
            return true;
        }
        if (now - SINCE.getOrDefault(id, now) > 20 * 60) { // a minute and no getting to it: another spot
            TODO.remove(id);
            NEXT_LOOK.put(id, now + 20 * 10);
            return false;
        }
        Doing.set(v, fill ? "settlements.doing.ground.fill" : "settlements.doing.ground.level");
        double dx = t.getX() + 0.5 - v.getX(), dz = t.getZ() + 0.5 - v.getZ(), dy = t.getY() - v.getY();
        if (dx * dx + dz * dz > 3.5 * 3.5 || dy > 3 || dy < -3) {
            Move.to(v, t.getX() + 0.5, t.getY() + (fill ? 1 : 0), t.getZ() + 0.5, 0.6);
            return true;
        }
        v.getNavigation().stop();
        Gear.face(v, t);
        v.swingHand(v.getDominantHand());
        if (fill) {
            if (v.getBoundingBox().intersects(new net.minecraft.util.math.Box(t))) return true; // not onto his own feet
            boolean grassy = world.getBlockState(t.down()).isOf(Blocks.GRASS_BLOCK) || world.isSkyVisible(t.up());
            if (world.getBlockState(t.down()).isOf(Blocks.GRASS_BLOCK)) world.setBlockState(t.down(), Blocks.DIRT.getDefaultState());
            world.setBlockState(t, (grassy ? Blocks.GRASS_BLOCK : Blocks.DIRT).getDefaultState());
            world.playSound(null, t, SoundEvents.BLOCK_GRAVEL_PLACE, SoundCategory.BLOCKS, 0.8f, 1f);
        } else {
            Gear.toolFor(v, village, world.getBlockState(t));
            world.breakBlock(t, false, v); // what it was made of goes into the hole next to it, near enough
        }
        TODO.remove(id);
        return true;
    }

    /** The next spot of the village's ground to see to, nearest the middle first. */
    private static boolean look(Village village, ServerWorld world) {
        BlockPos c = new BlockPos(village.getCenter());
        int r = Math.min(48, Math.max(16, Bounds.radius(village)));
        for (int ring = 1; ring <= r; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = c.getX() + dx, z = c.getZ() + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    int h = top(world, x, z);
                    if (Math.abs(h - c.getY()) > 12) continue;
                    BlockPos at = new BlockPos(x, h, z);
                    if (!open(world, village, at)) continue;
                    int higher = 0, lower = 0, n = 0;
                    for (int ox = -1; ox <= 1; ox++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            if (ox == 0 && oz == 0) continue;
                            int nh = top(world, x + ox, z + oz);
                            n++;
                            if (nh > h) higher++;
                            if (nh < h - 1) lower++;
                        }
                    }
                    BlockPos ground = at.down();
                    if (higher >= 6 && world.getBlockState(at).isReplaceable() && world.getFluidState(at).isEmpty() && natural(world.getBlockState(ground))) {
                        TODO.put(village.getId(), at);
                        FILL.put(village.getId(), true);
                        return true;
                    }
                    if (lower == n && removable(world, ground)) {
                        TODO.put(village.getId(), ground);
                        FILL.put(village.getId(), false);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The first open block over the ground at x, z (leaves, plants and snow count as open). */
    private static int top(ServerWorld world, int x, int z) {
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** Ground nobody has a use for as it is: not a building, a field, a road, a plot, water or a tree. */
    private static boolean open(ServerWorld world, Village village, BlockPos at) {
        for (Built b : village.built) if (b.contains(at) || b.contains(at.down())) return false;
        for (int[] plot : village.plots) if (at.getX() >= plot[0] - 1 && at.getX() <= plot[0] + plot[2] && at.getZ() >= plot[1] - 1 && at.getZ() <= plot[1] + plot[3]) return false;
        if (village.getBuildingAt(at).isPresent() || village.getBuildingAt(at.down()).isPresent()) return false;
        if (village.mineOrigin != null && at.isWithinDistance(village.mineOrigin, 6)) return false;
        BlockState g = world.getBlockState(at.down());
        if (g.isOf(Blocks.DIRT_PATH) || g.isOf(Blocks.FARMLAND) || !world.getFluidState(at).isEmpty() || !world.getFluidState(at.down()).isEmpty()) return false;
        return world.isSkyVisible(at);
    }

    private static boolean natural(BlockState s) {
        return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.SAND) || s.isOf(Blocks.GRAVEL)
                || s.isOf(Blocks.COBBLESTONE) || s.isOf(Blocks.CLAY);
    }

    /** A lump that can come off: earth, stone, cobble, gravel; or anything a player left standing there. */
    private static boolean removable(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (s.isAir() || s.getHardness(world, p) < 0 || s.getBlock() instanceof net.minecraft.block.BlockWithEntity || s.isIn(BlockTags.LOGS)) return false;
        return natural(s) || Unstick.playerPut(p) && s.getHardness(world, p) <= 3.0f;
    }
}
