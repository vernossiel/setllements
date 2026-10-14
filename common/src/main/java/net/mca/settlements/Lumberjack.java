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

    private Lumberjack() {
    }

    public static boolean work(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        if (village != null && stockLogs(village) >= LOG_QUOTA) return false;

        if (world.getTime() < v.settlementsNoWorkUntil) return false; // no trees around, look again later

        if (v.lumberTarget == null || !world.getBlockState(v.lumberTarget).isIn(BlockTags.LOGS)) {
            v.lumberTarget = null;
            List<BlockPos> trees = trees(world, v.getBlockPos(), village);
            if (trees.size() <= MIN_TREES_LEFT) {
                // leave the forest alone; with no forest at all the village has to ask for wood
                v.settlementsNoWorkUntil = world.getTime() + 1200;
                Speech.say(v, trees.isEmpty() ? "settlements.say.lumber.none" : "settlements.say.lumber.sparse");
                if (trees.isEmpty()) Needs.set(v, Needs.Need.PLANKS);
                return false;
            }
            trees.sort((a, b) -> Double.compare(a.getSquaredDistance(v.getBlockPos()), b.getSquaredDistance(v.getBlockPos())));
            v.lumberTarget = trees.get(0);
        }
        BlockPos base = v.lumberTarget;
        if (v.squaredDistanceTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5) > 12) {
            v.getNavigation().startMovingTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0.6);
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
        int cut = 0;
        for (BlockPos p : trunk) {
            if (cut >= LOGS_PER_SECOND) break;
            BlockState s = world.getBlockState(p);
            for (ItemStack drop : Block.getDroppedStacks(s, world, p, null)) v.getInventory().addStack(drop);
            world.setBlockState(p, Blocks.AIR.getDefaultState());
            cut++;
        }
        v.swingHand(v.getDominantHand());
        Speech.say(v, "settlements.say.lumber.fell");
        if (cut >= trunk.size()) {
            plant(v, world, base);
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
                for (int y = top - 1; y >= top - 12 && y > world.getBottomY(); y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!world.getBlockState(p).isIn(BlockTags.LOGS)) continue;
                    BlockPos below = p.down();
                    BlockState bs = world.getBlockState(below);
                    if (bs.isIn(BlockTags.LOGS) || !bs.isSolidBlock(world, below)) break;
                    if (village != null && Protection.severity(world, village, p, world.getBlockState(p)) > 0) break;
                    if (leavesAbove(world, p)) out.add(p);
                    break;
                }
            }
        }
        return out;
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
}
