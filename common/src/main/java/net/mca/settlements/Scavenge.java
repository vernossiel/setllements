package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A builder who runs out of materials does not stand around: he picks up what lies about, felles a tree when only
 * wood will do, and otherwise digs the ground and stone around the village, so the houses are raised from the blocks
 * that surround them.
 */
public final class Scavenge {
    private static final int RADIUS = 18;
    private static final Set<Item> FILLER = Set.of(Items.COBBLESTONE, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE,
            Items.COBBLED_DEEPSLATE, Items.SANDSTONE, Items.SAND, Items.DIRT, Items.COARSE_DIRT);
    private static final Set<Block> SOURCES = Set.of(Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
            Blocks.SANDSTONE, Blocks.SAND, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.PODZOL);
    private static final Set<Block> STONES = Set.of(Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
            Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.BROWN_TERRACOTTA,
            Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA); // (the desert's stone and the badlands' clay too)

    private Scavenge() {
    }

    /** Blocks that can stand in for planks in floors, walls and roofs. */
    public static boolean isFiller(ItemStack s) {
        return FILLER.contains(s.getItem());
    }

    public static int stockFiller(Village v) {
        int n = 0;
        for (ItemStack s : v.storageBuffer) if (isFiller(s)) n += s.getCount();
        return n;
    }

    private static boolean wanted(ItemStack s, Needs.Need need, boolean anyBlock) {
        if (anyBlock && (isFiller(s) || s.isIn(ItemTags.PLANKS) || s.isIn(ItemTags.LOGS))) return true;
        return need != Needs.Need.NONE && need.matches(s);
    }

    /** One second of foraging. True when the villager did something useful. */
    public static boolean work(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need, boolean anyBlock) {
        // 1. things lying around
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        Box box = v.getBoundingBox().expand(24, 8, 24);
        for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, box, e -> e.isAlive() && e.age > 100 && wanted(e.getStack(), need, anyBlock))) {
            if (world.getClosestPlayer(e, 6) != null) continue; // somebody is handling it
            double d = e.squaredDistanceTo(v);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        if (best != null) {
            if (bd <= 6) {
                Workforce.addToTreasury(village, best.getStack().copy());
                best.discard();
                v.swingHand(v.getDominantHand());
            } else {
                Move.to(v, best, 0.65);
            }
            return true;
        }

        // 2. only wood will do: fell a tree
        // (on his way to the tree is at it too: taken for nothing done, the builder's own felling was set aside for a minute
        // and a half each time he set off, and with no woodsman in the village he never got to a tree at all)
        if (!anyBlock && need == Needs.Need.PLANKS) {
            if (!Gear.has(v, net.minecraft.item.AxeItem.class)) Gear.equipTool(v, village, net.mca.entity.ai.Chore.CHOP);
            if (Lumberjack.work(v, village, true) || v.lumberTarget != null) return true;
        }

        // 3. dig the ground and stone around (enough is enough: no craters around the village)
        if (anyBlock && stockFiller(village) >= 256) return false;
        BlockPos t = v.scavengeTarget;
        if (t != null && (v.scavengeSec++ > 30 || !valid(world, village, t, need, anyBlock))) {
            t = null;
            v.scavengeTarget = null;
        }
        if (t == null) {
            t = pick(world, village, v.getBlockPos(), need, anyBlock);
            if (t == null) return false;
            v.scavengeTarget = t;
            v.scavengeSec = 0;
        }
        if (v.squaredDistanceTo(t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5) > 9) {
            Move.to(v, t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5, 0.65);
            return true;
        }
        if (Dig.busy(v)) return true;
        if (!Dig.sees(v, t)) {
            Move.to(v, t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5, 0.65);
            return true;
        }
        // with a pick, a crack at a time; the stone falls and is picked up (see the things lying around, above)
        for (int i = 0; i < v.getInventory().size(); i++) {
            if (v.getInventory().getStack(i).getItem() instanceof net.minecraft.item.PickaxeItem) {
                v.setStackInHand(v.getDominantHand(), v.getInventory().getStack(i).copy());
                break;
            }
        }
        if (world.getBlockState(t.up()).isOf(Blocks.SNOW)) world.breakBlock(t.up(), false);
        Dig.start(v, t);
        v.scavengeTarget = null;
        return true;
    }

    /** Only a stone outcrop is worth breaking: nobody digs up the village's ground for filler. */
    private static boolean source(BlockState s, Needs.Need need, boolean anyBlock) {
        return (anyBlock || need == Needs.Need.COBBLE) && STONES.contains(s.getBlock());
    }

    private static boolean valid(ServerWorld world, Village village, BlockPos p, Needs.Need need, boolean anyBlock) {
        return source(world.getBlockState(p), need, anyBlock) && !inPlot(village, p) && bump(world, village, p);
    }

    /**
     * A block that sticks up: at least three of its neighbours lie lower, and it is no lower than the village ground.
     * Taking it levels the land; nobody digs pits, trenches or tunnels for stone (that is what the mine is for).
     */
    private static boolean bump(ServerWorld world, Village village, BlockPos p) {
        int lower = 0;
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos n = p.offset(d);
            if (world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, n.getX(), n.getZ()) - 1 < p.getY()) lower++;
        }
        BlockPos c = new BlockPos(village.getCenter());
        int ground = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()) - 1;
        return lower >= 3 && p.getY() >= ground;
    }

    private static boolean nearWater(ServerWorld world, BlockPos p) {
        for (var dir : net.minecraft.util.math.Direction.values()) if (!world.getFluidState(p.offset(dir)).isEmpty()) return true;
        return false;
    }

    private static boolean inPlot(Village village, BlockPos p) {
        for (int[] plot : village.plots) {
            if (p.getX() >= plot[0] - 2 && p.getX() <= plot[0] + plot[2] + 2 && p.getZ() >= plot[1] - 2 && p.getZ() <= plot[1] + plot[3] + 2) return true;
        }
        return village.hallPos != null && village.hallPos.isWithinDistance(p, 6);
    }

    /** One of the nearest diggable surface blocks outside the village's own plots (stone first). */
    private static BlockPos pick(ServerWorld world, Village village, BlockPos from, Needs.Need need, boolean anyBlock) {
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = from.getX() + dx, z = from.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos p = new BlockPos(x, y, z);
                if (world.getBlockState(p).isOf(Blocks.SNOW)) p = p.down();
                if (!source(world.getBlockState(p), need, anyBlock) || inPlot(village, p) || !bump(world, village, p)) continue;
                if (nearWater(world, p) || Protection.insideStructure(village, p) || Protection.insideStructure(village, p.up())) continue;
                found.add(p);
            }
        }
        if (found.isEmpty()) return null;
        found.sort((a, b) -> {
            boolean sa = STONES.contains(world.getBlockState(a).getBlock()), sb = STONES.contains(world.getBlockState(b).getBlock());
            int ca = (int) a.getSquaredDistance(from) - (sa ? 120 : 0), cb = (int) b.getSquaredDistance(from) - (sb ? 120 : 0);
            return Integer.compare(ca, cb);
        });
        return found.get(world.random.nextInt(Math.min(4, found.size())));
    }
}
