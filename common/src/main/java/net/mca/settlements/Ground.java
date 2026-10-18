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
        Doing.event(v, fill ? "fills" : "levels", t, world.getBlockState(t));
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
        if (spill(village, world, c, r)) return true;
        for (int ring = 1; ring <= r; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = c.getX() + dx, z = c.getZ() + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    int h = top(world, x, z);
                    if (Math.abs(h - c.getY()) > 12) continue;
                    BlockPos at = new BlockPos(x, h, z);
                    // a pit before a door: the ground before it dug out two blocks and more below its sill, so that
                    // nobody gets up to it from the road (a house stood shut to its people so); filled up, road or not
                    if (beforeDoor(world, at) && world.getBlockState(at).isReplaceable() && world.getFluidState(at).isEmpty()
                            && village.getBuildingAt(at).isEmpty()) {
                        TODO.put(village.getId(), at);
                        FILL.put(village.getId(), true);
                        return true;
                    }
                    // a hollow: banks two blocks higher on both sides within three steps, along either line (a pit or
                    // a trench some blocks wide, a road along its bottom or not - a natural hollow by the houses was
                    // left as it was: only lone holes counted)
                    if (hollow(world, x, h, z) && world.getBlockState(at).isReplaceable() && world.getFluidState(at).isEmpty()
                            && fillable(world, village, at)) {
                        TODO.put(village.getId(), at);
                        FILL.put(village.getId(), true);
                        return true;
                    }
                    if (!open(world, village, at)) continue;
                    int higher = 0, lower = 0, n = 0;
                    for (int ox = -1; ox <= 1; ox++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            if (ox == 0 && oz == 0) continue;
                            int nh = top(world, x + ox, z + oz);
                            // only the land counts: a wall or a roof beside it is no bank of earth (the gap between two
                            // houses was taken for a hole, and filled with earth up to their eaves)
                            if (!natural(world.getBlockState(new BlockPos(x + ox, nh - 1, z + oz)))) continue;
                            n++;
                            if (nh > h) higher++;
                            if (nh < h - 1) lower++;
                        }
                    }
                    BlockPos ground = at.down();
                    // a trench (two wide, or along a road) as well as a pit: the ground two blocks round counted, the
                    // road's own cells with it (a ditch dug beside the road was left open: its banks were the road)
                    int far = 0, farN = 0;
                    for (int ox = -2; ox <= 2; ox++) {
                        for (int oz = -2; oz <= 2; oz++) {
                            if (ox == 0 && oz == 0) continue;
                            int nh = top(world, x + ox, z + oz);
                            BlockState nb = world.getBlockState(new BlockPos(x + ox, nh - 1, z + oz));
                            if (!natural(nb) && !nb.isOf(Blocks.DIRT_PATH)) continue;
                            farN++;
                            if (nh > h) far++;
                        }
                    }
                    boolean ditch = farN >= 12 && far * 10 >= farN * 6 && natural(world.getBlockState(ground));
                    if (n < 6 && !ditch) continue; // walled about by buildings: not the village's ground to shape
                    if ((higher >= 6 || ditch) && world.getBlockState(at).isReplaceable() && world.getFluidState(at).isEmpty() && natural(world.getBlockState(ground))) {
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

    /**
     * Water running out over the ground (a field's bank broken, a pond's edge dug away): the gap it runs out of is
     * filled with earth, next to the still water first, and the rest of it dries up of itself. A field's water ran
     * out over half the village and nobody stopped it.
     */
    private static boolean spill(Village village, ServerWorld world, BlockPos c, int r) {
        for (int ring = 1; ring <= r; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = c.getX() + dx, z = c.getZ() + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    BlockPos w = new BlockPos(x, top(world, x, z) - 1, z);
                    var f = world.getFluidState(w);
                    if (!f.isIn(net.minecraft.registry.tag.FluidTags.WATER) || f.isStill()) continue;
                    if (Math.abs(w.getY() - c.getY()) > 12) continue;
                    // the cell the water leaves its pool by: running water with still water beside it, on the level
                    boolean mouth = false;
                    for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                        var nf = world.getFluidState(w.offset(d));
                        if (nf.isIn(net.minecraft.registry.tag.FluidTags.WATER) && nf.isStill()) mouth = true;
                    }
                    if (!mouth || !world.getBlockState(w.down()).isSolidBlock(world, w.down())) continue;
                    boolean ours = false; // not a stream of the land's own running through: only where it spilled on the village's ground
                    for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                        BlockState g = world.getBlockState(w.offset(d).down());
                        if (g.isOf(Blocks.FARMLAND) || g.isOf(Blocks.DIRT_PATH) || g.isOf(Blocks.GRASS_BLOCK)) ours = true;
                    }
                    if (!ours) continue;
                    TODO.put(village.getId(), w);
                    FILL.put(village.getId(), true);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hollow(ServerWorld world, int x, int h, int z) {
        for (int[] ax : new int[][]{{1, 0}, {0, 1}}) {
            boolean a = false, b = false;
            for (int i = 1; i <= 3 && !a; i++) a = bank(world, x + ax[0] * i, z + ax[1] * i, h);
            for (int i = 1; i <= 3 && !b; i++) b = bank(world, x - ax[0] * i, z - ax[1] * i, h);
            if (a && b) return true;
        }
        return false;
    }

    /** The land (not a wall or a roof: the gap between two houses is no hollow) two blocks or more above {@code h}. */
    private static boolean bank(ServerWorld world, int x, int z, int h) {
        int t = top(world, x, z);
        if (t < h + 2 || t > h + 6) return false;
        BlockState s = world.getBlockState(new BlockPos(x, t - 1, z));
        return natural(s) || s.isOf(Blocks.DIRT_PATH);
    }

    /** Ground of the village that may be filled up: not a building, a field, a plot, the mine, water or a tree; a road may. */
    private static boolean fillable(ServerWorld world, Village village, BlockPos at) {
        for (Built b : village.built) if (b.contains(at) || b.contains(at.down())) return false;
        for (int[] plot : village.plots) if (at.getX() >= plot[0] - 1 && at.getX() <= plot[0] + plot[2] && at.getZ() >= plot[1] - 1 && at.getZ() <= plot[1] + plot[3]) return false;
        if (village.getBuildingAt(at).isPresent() || village.getBuildingAt(at.down()).isPresent()) return false;
        if (village.mineOrigin != null && at.isWithinDistance(village.mineOrigin, 6)) return false;
        BlockState g = world.getBlockState(at.down());
        if (g.isOf(Blocks.FARMLAND) || !world.getFluidState(at.down()).isEmpty()) return false;
        return world.isSkyVisible(at) && (natural(g) || g.isOf(Blocks.DIRT_PATH));
    }

    /** A door within two blocks whose sill stands two to four blocks above this spot: a pit in the way to it. */
    static boolean beforeDoor(ServerWorld world, BlockPos at) {
        for (BlockPos q : BlockPos.iterate(at.add(-2, 2, -2), at.add(2, 4, 2))) {
            BlockState st = world.getBlockState(q);
            if (st.getBlock() instanceof net.minecraft.block.DoorBlock && st.get(net.minecraft.block.DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.LOWER
                    && Math.abs(q.getX() - at.getX()) + Math.abs(q.getZ() - at.getZ()) <= 2) return true;
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
