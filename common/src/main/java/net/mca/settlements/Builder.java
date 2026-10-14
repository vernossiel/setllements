package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Building;
import net.mca.server.world.data.Village;
import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A builder villager raises a small 5x5 hut, one block per second, paying every block from the village treasury.
 * Floor and roof are planks, walls are cobblestone when the village has it, otherwise planks. A bed (3 wool + 3 planks)
 * is placed last, which is what makes MCA register the house. Logs are sawn into planks on demand.
 */
public final class Builder {
    private enum Kind { FLOOR, WALL, ROOF, DOOR, BED_FOOT, BED_HEAD }

    private record Step(int dx, int dy, int dz, Kind kind) {
    }

    private static final int SIZE = 5;
    private static final int WALL_HEIGHT = 3;
    private static final List<Step> STEPS = buildSteps();
    private static final int PLANKS_NEEDED_TO_START = 70;

    private Builder() {
    }

    private static List<Step> buildSteps() {
        List<Step> l = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) l.add(new Step(x, 0, z, Kind.FLOOR));
        for (int y = 1; y <= WALL_HEIGHT; y++) {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    boolean edge = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                    boolean door = x == 2 && z == SIZE - 1 && y <= 2;
                    if (edge && !door) l.add(new Step(x, y, z, Kind.WALL));
                }
            }
        }
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) l.add(new Step(x, WALL_HEIGHT + 1, z, Kind.ROOF));
        l.add(new Step(2, 1, SIZE - 1, Kind.DOOR));
        l.add(new Step(1, 1, 2, Kind.BED_FOOT));
        l.add(new Step(1, 1, 3, Kind.BED_HEAD));
        return l;
    }

    // ---- treasury helpers ----

    private static int planks(Village v) {
        int n = 0;
        for (ItemStack s : v.storageBuffer) {
            if (s.isIn(ItemTags.PLANKS)) n += s.getCount();
            else if (s.isIn(ItemTags.LOGS)) n += s.getCount() * 4;
        }
        return n;
    }

    private static int count(Village v, java.util.function.Predicate<ItemStack> p) {
        int n = 0;
        for (ItemStack s : v.storageBuffer) if (p.test(s)) n += s.getCount();
        return n;
    }

    private static Item planksOf(Item log) {
        String path = Registries.ITEM.getId(log).getPath().replace("stripped_", "");
        for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae", "_block"}) {
            if (path.endsWith(suffix)) {
                Item item = Registries.ITEM.get(new Identifier(path.substring(0, path.length() - suffix.length()) + "_planks"));
                if (item != Items.AIR) return item;
            }
        }
        return Items.OAK_PLANKS;
    }

    /** Takes one plank from the treasury, sawing a log if needed. Returns the plank item or null. */
    private static Item takePlank(Village v) {
        for (Iterator<ItemStack> it = v.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (s.isIn(ItemTags.PLANKS)) {
                Item item = s.getItem();
                s.decrement(1);
                if (s.isEmpty()) it.remove();
                v.markDirty();
                return item;
            }
        }
        for (Iterator<ItemStack> it = v.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (s.isIn(ItemTags.LOGS)) {
                Item planks = planksOf(s.getItem());
                s.decrement(1);
                if (s.isEmpty()) it.remove();
                // 4 planks from the log, one is used right now, the others go back to the treasury
                v.storageBuffer.add(0, new ItemStack(planks, 3));
                v.markDirty();
                return planks;
            }
        }
        return null;
    }

    private static boolean takeCobble(Village v) {
        for (Iterator<ItemStack> it = v.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (s.isOf(Items.COBBLESTONE)) {
                s.decrement(1);
                if (s.isEmpty()) it.remove();
                v.markDirty();
                return true;
            }
        }
        return false;
    }

    private static boolean takeWool(Village v, int n) {
        if (count(v, s -> s.isIn(ItemTags.WOOL)) < n) return false;
        for (Iterator<ItemStack> it = v.storageBuffer.iterator(); it.hasNext() && n > 0; ) {
            ItemStack s = it.next();
            if (!s.isIn(ItemTags.WOOL)) continue;
            int d = Math.min(n, s.getCount());
            s.decrement(d);
            n -= d;
            if (s.isEmpty()) it.remove();
        }
        v.markDirty();
        return true;
    }

    // ---- site selection ----

    private static BlockPos findSite(ServerWorld world, Village village) {
        BlockPos c = new BlockPos(village.getCenter());
        var rand = world.random;
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = c.getX() + rand.nextBetween(-36, 36);
            int z = c.getZ() + rand.nextBetween(-36, 36);
            if (Math.abs(x - c.getX()) < 10 && Math.abs(z - c.getZ()) < 10) continue;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            boolean ok = true;
            for (int dx = -1; dx <= SIZE && ok; dx++) {
                for (int dz = -1; dz <= SIZE; dz++) {
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                    BlockState ground = world.getBlockState(new BlockPos(x + dx, y - 1, z + dz));
                    if (!ground.isSolidBlock(world, new BlockPos(x + dx, y - 1, z + dz)) || !ground.getFluidState().isEmpty()) {
                        ok = false;
                        break;
                    }
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
            if (!ok || maxY - minY > 1) continue;
            BlockPos origin = new BlockPos(x, maxY, z);
            boolean overlaps = false;
            for (Building b : village.getBuildings().values()) {
                for (int dx = 0; dx < SIZE && !overlaps; dx += SIZE - 1) {
                    for (int dz = 0; dz < SIZE; dz += SIZE - 1) {
                        if (b.containsPos(origin.add(dx, 1, dz))) {
                            overlaps = true;
                            break;
                        }
                    }
                }
            }
            if (!overlaps) return origin;
        }
        return null;
    }

    // ---- main entry ----

    /**
     * One builder second. Returns true when the villager actually worked (so the caller drains energy).
     */
    public static boolean work(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world) || village == null) return false;

        if (village.buildOrigin == null) {
            if (village.getPopulation() < village.getMaxPopulation()) return false; // enough beds
            if (planks(village) < PLANKS_NEEDED_TO_START) return false;
            BlockPos site = findSite(world, village);
            if (site == null) return false;
            village.buildOrigin = site;
            village.buildStep = 0;
            village.markDirty();
        }

        BlockPos origin = village.buildOrigin;
        Step step = STEPS.get(village.buildStep);
        BlockPos target = origin.add(step.dx(), step.dy(), step.dz());

        if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 36) {
            v.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
            return false;
        }

        BlockState state = stateFor(step, village, world);
        if (state == null) return false; // waiting for materials

        BlockState old = world.getBlockState(target);
        if (!old.isAir() && !old.isReplaceable() && !old.isIn(BlockTags.LEAVES)) {
            // something solid is in the way, skip this block instead of getting stuck forever
            advance(village);
            return false;
        }
        world.setBlockState(target, state);
        if (step.kind() == Kind.DOOR) {
            world.setBlockState(target.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        world.playSound(null, target, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 1.0f);
        v.swingHand(v.getDominantHand());
        advance(village);
        return true;
    }

    private static void advance(Village village) {
        village.buildStep++;
        if (village.buildStep >= STEPS.size()) {
            village.buildOrigin = null;
            village.buildStep = 0;
        }
        village.markDirty();
    }

    /** Pays for the step and returns the state to place, or null if the treasury cannot pay. */
    private static BlockState stateFor(Step step, Village village, ServerWorld world) {
        switch (step.kind()) {
            case WALL: {
                if (takeCobble(village)) return Blocks.COBBLESTONE.getDefaultState();
                return plank(village);
            }
            case FLOOR:
            case ROOF:
                return plank(village);
            case DOOR: {
                if (planks(village) < 6) return null;
                for (int i = 0; i < 6; i++) takePlank(village);
                return Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            }
            case BED_FOOT: {
                if (planks(village) < 3 || count(village, s -> s.isIn(ItemTags.WOOL)) < 3) return null;
                takeWool(village, 3);
                for (int i = 0; i < 3; i++) takePlank(village);
                return Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.SOUTH).with(BedBlock.PART, BedPart.FOOT);
            }
            case BED_HEAD:
                return Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.SOUTH).with(BedBlock.PART, BedPart.HEAD);
            default:
                return null;
        }
    }

    private static BlockState plank(Village village) {
        Item p = takePlank(village);
        if (p == null) return null;
        Block b = Block.getBlockFromItem(p);
        return b == Blocks.AIR ? Blocks.OAK_PLANKS.getDefaultState() : b.getDefaultState();
    }
}
