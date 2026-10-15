package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A real lumberjack: finds a real tree, walks to it, fells the trunk log by log (every log is an item he carries
 * away), and plants a sapling where it stood. Villages keep a stand of trees: nothing is cut when fewer than six
 * remain around, or when the stores already hold plenty of logs.
 */
public final class Lumberjack {
    private static final int RADIUS = 72;
    private static final int MIN_TREES_LEFT = 6;
    private static final int LOG_QUOTA = 256;
    private static final int LOGS_PER_SECOND = 2;
    /** Trees nobody could get to lately, by position, until (world time): passed over for a while. */
    private static final java.util.Map<Long, Long> UNREACHABLE = new java.util.HashMap<>();
    /** Per lumberjack: what he is heading for (a tree or a wood) and since when. */
    private static final java.util.Map<java.util.UUID, long[]> HEADING = new java.util.HashMap<>();

    /** Ticks spent on the way to the same tree or wood; restarts when the goal changes. */
    private static long heading(VillagerEntityMCA v, BlockPos goal, long now) {
        long[] h = HEADING.get(v.getUuid());
        if (h == null || h[0] != goal.asLong()) {
            HEADING.put(v.getUuid(), new long[]{goal.asLong(), now});
            return 0;
        }
        return now - h[1];
    }

    private Lumberjack() {
    }

    public static boolean work(VillagerEntityMCA v, Village village) {
        return work(v, village, false);
    }

    /** {@code force}: a builder short of wood fells a tree even when the stores are full or the forest is thin. */
    public static boolean work(VillagerEntityMCA v, Village village, boolean force) {
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        if (!force && village != null && stockLogs(village) >= LOG_QUOTA) return false;

        if (world.getTime() < v.settlementsNoWorkUntil) return false; // no trees around, look again later

        // out looking for a wood further away
        if (v.settlementsExplore != null && v.lumberTarget == null) {
            BlockPos e = v.settlementsExplore;
            // a wood he cannot get to in a minute and a half is given up: he looks around where he is
            if (v.squaredDistanceTo(e.getX() + 0.5, v.getY(), e.getZ() + 0.5) > 64 && heading(v, e, world.getTime()) < 1800) {
                if (Move.to(v, e.getX() + 0.5, e.getY(), e.getZ() + 0.5, 0.6)) return false;
            }
            v.settlementsExplore = null; // arrived: look around here
        }
        if (v.lumberTarget == null || !world.getBlockState(v.lumberTarget).isIn(BlockTags.LOGS)) {
            v.lumberTarget = null;
            List<BlockPos> trees = trees(world, v.getBlockPos(), village);
            if (trees.size() <= (force ? 1 : MIN_TREES_LEFT) && !force && v.settlementsExploreTries < 6) {
                // no wood worth cutting around here: go further and look again
                BlockPos from = village != null ? new BlockPos(village.getCenter()) : v.getBlockPos();
                for (int attempt = 0; attempt < 8; attempt++) {
                    double a = world.random.nextDouble() * Math.PI * 2;
                    int dist = 48 + world.random.nextInt(40) + 16 * v.settlementsExploreTries;
                    int x = from.getX() + (int) (Math.cos(a) * dist), z = from.getZ() + (int) (Math.sin(a) * dist);
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    v.settlementsExplore = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    v.settlementsExploreTries++;
                    Speech.say(v, "settlements.say.lumber.explore");
                    return false;
                }
            }
            if (trees.size() <= (force ? 1 : MIN_TREES_LEFT)) {
                v.settlementsExploreTries = 0;
                // leave the forest alone; with no forest at all the village has to ask for wood
                v.settlementsNoWorkUntil = world.getTime() + 1200;
                if (!force) {
                    Speech.say(v, trees.isEmpty() ? "settlements.say.lumber.none" : "settlements.say.lumber.sparse");
                    if (trees.isEmpty()) Needs.set(v, Needs.Need.PLANKS);
                }
                return false;
            }
            // the nearest tree to him first; once the village has planted a grove, trees close to the village are preferred
            BlockPos center = village == null ? v.getBlockPos() : new BlockPos(village.getCenter());
            double near = village != null && village.groveSaplings >= 6 ? 1.0 : 0.0;
            trees.sort((a, b) -> Double.compare(
                    a.getSquaredDistance(v.getBlockPos()) + near * 3 * a.getSquaredDistance(center),
                    b.getSquaredDistance(v.getBlockPos()) + near * 3 * b.getSquaredDistance(center)));
            v.lumberTarget = trees.get(0);
            v.settlementsExploreTries = 0;
        }
        BlockPos base = v.lumberTarget;
        double hx = base.getX() + 0.5 - v.getX(), hz = base.getZ() + 0.5 - v.getZ();
        if (hx * hx + hz * hz > 12 || Math.abs(base.getY() - v.getY()) > 3) {
            // a tree he cannot reach (on a cliff, in a thicket, across water) is left for another, not stared at
            boolean moving = Move.to(v, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0.6);
            if (!moving || heading(v, base, world.getTime()) > 900) {
                UNREACHABLE.put(base.asLong(), world.getTime() + 12000);
                v.lumberTarget = null;
            }
            return false;
        }

        // fell the trunk: the connected logs, lowest first
        List<BlockPos> trunk = trunk(world, base);
        if (trunk.isEmpty()) {
            v.lumberTarget = null;
            return false;
        }
        v.getLookControl().lookAt(base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5);
        for (int i = 0; i < v.getInventory().size(); i++) {
            if (v.getInventory().getStack(i).getItem() instanceof net.minecraft.item.AxeItem) {
                v.setStackInHand(v.getDominantHand(), v.getInventory().getStack(i).copy()); // shown in hand only while chopping
                break;
            }
        }
        int cut = 0, budget = Prosperity.ops(v, village, LOGS_PER_SECOND);
        if (budget == 0) return true; // a tired man swings slowly
        Gear.face(v, base);
        for (BlockPos p : trunk) {
            if (cut >= budget) break;
            if (!Gear.wear(v, net.minecraft.item.AxeItem.class)) break; // the axe broke: a new one first
            BlockState s = world.getBlockState(p);
            for (ItemStack drop : Block.getDroppedStacks(s, world, p, null)) v.getInventory().addStack(drop);
            Move.clearFalling(world, p, d -> v.getInventory().addStack(d));
            world.setBlockState(p, Blocks.AIR.getDefaultState());
            cut++;
        }
        v.swingHand(v.getDominantHand());
        Speech.say(v, "settlements.say.lumber.fell");
        if (cut >= trunk.size()) {
            plant(v, world, base);
            if (village != null) plantGrove(v, village, world);
            v.lumberTarget = null;
            v.setStackInHand(v.getDominantHand(), ItemStack.EMPTY);
        }
        return true;
    }

    private static int stockLogs(Village village) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) n += s.getCount();
        return n;
    }

    /** Bases of real trees nearby: a log on the ground with leaves above it, not part of a village building. */
    private static List<BlockPos> trees(ServerWorld world, BlockPos center, Village village) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx += 1) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += 1) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                for (int y = top - 1; y >= top - 40 && y > world.getBottomY(); y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState here = world.getBlockState(p);
                    if (!here.isIn(BlockTags.LOGS)) {
                        if (here.isIn(BlockTags.LEAVES) || !here.isSolidBlock(world, p)) continue; // crown, snow, air
                        break; // the ground: no tree in this column
                    }
                    BlockPos below = p.down();
                    BlockState bs = world.getBlockState(below);
                    if (bs.isIn(BlockTags.LOGS)) continue; // still inside the trunk, go down to its base
                    if (!bs.isSolidBlock(world, below)) break;
                    if (village != null && planted(village, p)) break; // the village's own timber, not a tree
                    Long bad = UNREACHABLE.get(p.asLong());
                    if (bad != null && bad > world.getTime()) break;
                    if (leavesAbove(world, p)) out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean planted(Village village, BlockPos p) {
        for (Built b : village.built) if (b.contains(p)) return true;
        for (House h : village.houses) {
            BlockPos o = h.origin();
            if (p.getX() >= o.getX() - 1 && p.getX() <= o.getX() + h.fw() && p.getZ() >= o.getZ() - 1 && p.getZ() <= o.getZ() + h.fd()) return true;
        }
        return false;
    }

    private static boolean leavesAbove(ServerWorld world, BlockPos base) {
        BlockPos p = base;
        for (int i = 0; i < 28; i++) {
            p = p.up();
            BlockState s = world.getBlockState(p);
            if (s.isIn(BlockTags.LEAVES)) return true;
            if (!s.isIn(BlockTags.LOGS)) return false;
        }
        return false;
    }

    /** Connected logs of this tree, bottom first (flood fill over the trunk and its branches, at most 64). */
    private static List<BlockPos> trunk(ServerWorld world, BlockPos base) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        seen.add(base);
        while (!queue.isEmpty() && out.size() < 64) {
            BlockPos p = queue.poll();
            if (!world.getBlockState(p).isIn(BlockTags.LOGS)) continue;
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.add(dx, dy, dz);
                        if (seen.add(n)) queue.add(n);
                    }
                }
            }
        }
        out.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        return out;
    }

    /** A new tree for the one that was cut: the sapling matching the log. */
    private static void plant(VillagerEntityMCA v, ServerWorld world, BlockPos base) {
        BlockPos ground = base.down();
        BlockState g = world.getBlockState(ground);
        if (!(g.isOf(Blocks.DIRT) || g.isOf(Blocks.GRASS_BLOCK) || g.isOf(Blocks.PODZOL) || g.isOf(Blocks.COARSE_DIRT) || g.isOf(Blocks.ROOTED_DIRT))) return;
        if (!world.getBlockState(base).isAir()) return;
        String path = Registries.BLOCK.getId(world.getBlockState(base).getBlock()).getPath();
        // the log is already gone, so look at what we carried away
        String wood = "oak";
        for (int i = 0; i < v.getInventory().size(); i++) {
            ItemStack s = v.getInventory().getStack(i);
            if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
                String id = Registries.ITEM.getId(s.getItem()).getPath();
                for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
                    if (id.endsWith(suffix)) {
                        wood = id.replace("stripped_", "").replace(suffix, "");
                        break;
                    }
                }
            }
        }
        Block sapling = Registries.BLOCK.get(new Identifier(wood + "_sapling"));
        if (sapling == Blocks.AIR) sapling = Blocks.OAK_SAPLING;
        world.setBlockState(base, sapling.getDefaultState());
        Speech.say(v, "settlements.say.lumber.plant");
    }

    /** Saplings from the branches go into the ground close to the village, so that wood grows where it is needed. */
    private static void plantGrove(VillagerEntityMCA v, Village village, ServerWorld world) {
        BlockPos c = new BlockPos(village.getCenter());
        for (int attempt = 0; attempt < 12; attempt++) {
            int dist = 14 + world.random.nextInt(16);
            double angle = world.random.nextDouble() * Math.PI * 2;
            int x = c.getX() + (int) (Math.cos(angle) * dist), z = c.getZ() + (int) (Math.sin(angle) * dist);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            BlockState ground = world.getBlockState(p.down());
            if (!(ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.PODZOL))) continue;
            if (!world.getBlockState(p).isAir()) continue;
            boolean crowded = false;
            for (int[] plot : village.plots) {
                if (x >= plot[0] - 2 && x <= plot[0] + plot[2] + 2 && z >= plot[1] - 2 && z <= plot[1] + plot[3] + 2) crowded = true;
            }
            for (BlockPos q : BlockPos.iterate(p.add(-2, 0, -2), p.add(2, 2, 2))) {
                if (!world.getBlockState(q).isAir() && !world.getBlockState(q).isReplaceable()) crowded = true;
            }
            if (crowded) continue;
            world.setBlockState(p, Blocks.OAK_SAPLING.getDefaultState());
            village.groveSaplings++;
            village.markDirty();
            return;
        }
    }
}
