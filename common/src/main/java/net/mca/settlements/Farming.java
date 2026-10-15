package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.Map;

/**
 * The farmer keeps his own fields, not just reaps them: trampled earth is hoed back into tilth, bare beds are sown,
 * and the growing crop is fed - bone meal from the stores, or from the compost heap he fills with the surplus seed
 * and chaff. One thing at a time, within arm's reach of where he is working.
 */
public final class Farming {
    private static final Map<Item, Block> SOW = Map.of(Items.WHEAT_SEEDS, Blocks.WHEAT, Items.CARROT, Blocks.CARROTS,
            Items.POTATO, Blocks.POTATOES, Items.BEETROOT_SEEDS, Blocks.BEETROOTS);

    private Farming() {
    }

    /** Once a second while the farmer is at his work. */
    public static void tend(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null || Dig.busy(v)) return;
        Built farm = null;
        for (Built b : village.built) {
            if (!b.type().equals("farm")) continue;
            if (b.contains(v.getBlockPos()) || b.origin().isWithinDistance(v.getBlockPos(), 10)) {
                farm = b;
                break;
            }
        }
        if (farm == null) return;
        int y = farm.origin().getY() - 1; // the tilled ground of the field
        BlockPos feet = v.getBlockPos();
        BlockPos till = null, sow = null, feed = null;
        for (BlockPos p : BlockPos.iterate(feet.getX() - 4, y, feet.getZ() - 4, feet.getX() + 4, y, feet.getZ() + 4)) {
            if (!inside(farm, p) || !world.getBlockState(p.up()).isAir() && !(world.getBlockState(p.up()).getBlock() instanceof CropBlock)) continue;
            BlockState s = world.getBlockState(p), above = world.getBlockState(p.up());
            if ((s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.COARSE_DIRT)) && above.isAir() && till == null && nearWater(world, p)) till = p.toImmutable();
            else if (s.isOf(Blocks.FARMLAND) && above.isAir() && sow == null) sow = p.up().toImmutable();
            else if (above.getBlock() instanceof CropBlock crop && !crop.isMature(above) && feed == null) feed = p.up().toImmutable();
        }
        if (till != null && Dig.sees(v, till)) {
            if (!Gear.has(v, HoeItem.class)) Gear.equipTool(v, village, Chore.HARVEST);
            if (!Gear.wear(v, HoeItem.class)) return;
            Gear.face(v, till);
            world.setBlockState(till, Blocks.FARMLAND.getDefaultState());
            world.playSound(null, till, SoundEvents.ITEM_HOE_TILL, SoundCategory.BLOCKS, 1f, 1f);
            v.swingHand(v.getDominantHand());
            Doing.set(v, "settlements.doing.farm.till");
            return;
        }
        if (sow != null && Dig.sees(v, sow)) {
            Block crop = takeSeed(v, village);
            if (crop != null) {
                Gear.face(v, sow);
                world.setBlockState(sow, crop.getDefaultState());
                world.playSound(null, sow, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1f, 1f);
                v.swingHand(v.getDominantHand());
                Doing.set(v, "settlements.doing.farm.sow");
                return;
            }
        }
        // the crop fed now and then: the stores' bone meal, or the compost made from the surplus
        if (feed != null && world.random.nextInt(6) == 0 && Dig.sees(v, feed) && (take(village, Items.BONE_MEAL, 1) || compost(village))) {
            BlockState s = world.getBlockState(feed);
            if (s.getBlock() instanceof CropBlock crop) {
                Gear.face(v, feed);
                crop.applyGrowth(world, feed, s);
                world.syncWorldEvent(1505, feed, 15); // the green sparkle of bone meal
                v.swingHand(v.getDominantHand());
                Doing.set(v, "settlements.doing.farm.feed");
                if (world.random.nextInt(8) == 0) Speech.say(v, "settlements.say.farm.feed");
            }
        }
    }

    private static final Map<java.util.UUID, BlockPos> TARGET = new java.util.HashMap<>();
    private static final Map<java.util.UUID, Long> LOOKED = new java.util.HashMap<>();
    private static final int FIELD_RADIUS = 48;

    /**
     * A farmer's day on the village's fields, whoever laid them out (the old fields of the village too, not only the
     * farms our builders make): the ripe crop is reaped and the bed sown again at once, a bare bed sown, the growing
     * crop fed now and then. He goes to the nearest bed that wants something done; true while there is any.
     */
    public static boolean fields(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null || Dig.busy(v)) return false;
        java.util.UUID id = v.getUuid();
        BlockPos t = TARGET.get(id);
        if (t != null && want(world, t) == 0) t = null;
        if (t == null) {
            if (world.getTime() < LOOKED.getOrDefault(id, 0L)) return false; // looked a moment ago: nothing to do
            t = look(v, village, world);
            LOOKED.put(id, world.getTime() + (t == null ? 400 : 0));
            if (t == null) {
                TARGET.remove(id);
                return false;
            }
            TARGET.put(id, t);
        }
        int what = want(world, t);
        if (v.squaredDistanceTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5) > 6.25) {
            Move.to(v, t.getX() + 0.5, t.getY(), t.getZ() + 0.5, 0.55);
            Doing.set(v, what == 1 ? "settlements.doing.farm.reap" : what == 2 ? "settlements.doing.farm.sow" : what == 4 ? "settlements.doing.farm.till" : "settlements.doing.farm.feed");
            return true;
        }
        v.getNavigation().stop();
        Gear.face(v, t);
        BlockState s = world.getBlockState(t);
        if (what == 1 && s.getBlock() instanceof CropBlock crop) {
            // reaped into his bag (it goes to the stores with the rest), the bed sown again with the seed of it
            for (ItemStack drop : Block.getDroppedStacks(s, world, t, null, v, v.getMainHandStack())) {
                ItemStack left = v.getInventory().addStack(drop);
                if (!left.isEmpty()) Block.dropStack(world, t, left);
            }
            world.setBlockState(t, crop.withAge(0));
            world.playSound(null, t, SoundEvents.BLOCK_CROP_BREAK, SoundCategory.BLOCKS, 1f, 1f);
            Doing.set(v, "settlements.doing.farm.reap");
        } else if (what == 2) {
            Block crop = takeSeed(v, village);
            if (crop == null) {
                TARGET.remove(id);
                LOOKED.put(id, world.getTime() + 600); // nothing to sow with
                return false;
            }
            world.setBlockState(t, crop.getDefaultState());
            world.playSound(null, t, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1f, 1f);
            Doing.set(v, "settlements.doing.farm.sow");
        } else if (what == 4) {
            // a new bed hoed beside the old ones (by hand if there is no hoe), to be sown next
            if (!Gear.has(v, HoeItem.class)) Gear.equipTool(v, village, Chore.HARVEST);
            if (Gear.has(v, HoeItem.class)) Gear.wear(v, HoeItem.class);
            world.setBlockState(t.down(), Blocks.FARMLAND.getDefaultState());
            world.playSound(null, t, SoundEvents.ITEM_HOE_TILL, SoundCategory.BLOCKS, 1f, 1f);
            Doing.set(v, "settlements.doing.farm.till");
        } else if (what == 3 && s.getBlock() instanceof CropBlock crop && (take(village, Items.BONE_MEAL, 1) || compost(village))) {
            crop.applyGrowth(world, t, s);
            world.syncWorldEvent(1505, t, 15);
            Doing.set(v, "settlements.doing.farm.feed");
        } else {
            TARGET.remove(id);
            return true;
        }
        v.swingHand(v.getDominantHand());
        TARGET.remove(id); // the next bed
        return true;
    }

    /** 1 a ripe crop, 2 a bare bed, 3 a growing crop (fed only when there is bone meal to spare), 0 nothing. */
    private static int want(ServerWorld world, BlockPos crop) {
        BlockState s = world.getBlockState(crop), under = world.getBlockState(crop.down());
        if (!under.isOf(Blocks.FARMLAND)) return newBed(world, crop.down()) && s.isAir() ? 4 : 0;
        if (s.getBlock() instanceof CropBlock c) return c.isMature(s) ? 1 : 3;
        return s.isAir() ? 2 : 0;
    }

    /** Earth beside a bed of the field, near water: where the field can grow by a bed. */
    private static boolean newBed(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (!(s.isOf(Blocks.DIRT) || s.isOf(Blocks.GRASS_BLOCK))) return false;
        boolean beside = false;
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) if (world.getBlockState(p.offset(d)).isOf(Blocks.FARMLAND)) beside = true;
        return beside && nearWater(world, p);
    }

    /** The nearest bed round the village that wants something done: ripe first, then bare; growing ones only with bone meal in the stores. */
    private static BlockPos look(VillagerEntityMCA v, Village village, ServerWorld world) {
        BlockPos c = new BlockPos(village.getCenter());
        boolean meal = count(village, Items.BONE_MEAL) > 0 || count(village, Items.WHEAT_SEEDS) >= 24;
        boolean seed = false;
        for (Item i : SOW.keySet()) if (count(village, i) > 0 || v.getInventory().count(i) > 0) seed = true;
        BlockPos best = null, bed = null;
        double bd = Double.MAX_VALUE, bedD = Double.MAX_VALUE;
        int beds = 0, most = 40 + 24 * Prosperity.tier(village);
        for (int dx = -FIELD_RADIUS; dx <= FIELD_RADIUS; dx++) {
            for (int dz = -FIELD_RADIUS; dz <= FIELD_RADIUS; dz++) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                // the crop stands on the farmland: the top is the farmland itself (a bare bed or a young crop) or one above
                for (int yy = y; yy >= y - 1; yy--) {
                    BlockPos p = new BlockPos(x, yy, z);
                    if (world.getBlockState(p.down()).isOf(Blocks.FARMLAND)) beds++;
                    int w = want(world, p);
                    if (w == 4) {
                        double d = v.squaredDistanceTo(x + 0.5, yy, z + 0.5);
                        if (seed && d < bedD) {
                            bedD = d;
                            bed = p;
                        }
                        break;
                    }
                    if (w == 0 || w == 2 && !seed || w == 3 && !meal) continue;
                    double d = v.squaredDistanceTo(x + 0.5, yy, z + 0.5) + (w == 1 ? 0 : w == 2 ? 200 : 900);
                    if (d < bd) {
                        bd = d;
                        best = p;
                    }
                    break;
                }
            }
        }
        // nothing to do in the fields: they are made bigger, a bed at a time, while there is seed and use for more
        return best != null ? best : beds < most ? bed : null;
    }

    /** Within the fence, not on it or outside it. */
    private static boolean inside(Built farm, BlockPos p) {
        BlockPos o = farm.origin();
        return p.getX() > o.getX() && p.getX() < o.getX() + farm.fw() - 1 && p.getZ() > o.getZ() && p.getZ() < o.getZ() + farm.fd() - 1;
    }

    private static boolean nearWater(ServerWorld world, BlockPos p) {
        for (BlockPos q : BlockPos.iterate(p.add(-4, 0, -4), p.add(4, 1, 4))) if (world.getFluidState(q).isIn(net.minecraft.registry.tag.FluidTags.WATER)) return true;
        return false;
    }

    private static Block takeSeed(VillagerEntityMCA v, Village village) {
        var inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            Block b = SOW.get(s.getItem());
            if (b != null) {
                s.decrement(1);
                return b;
            }
        }
        for (Map.Entry<Item, Block> e : SOW.entrySet()) if (take(village, e.getKey(), 1)) return e.getValue();
        return null;
    }

    /** The compost heap: seven of the surplus seed (keeping a good stock back for sowing) make a bone meal. */
    private static boolean compost(Village village) {
        if (count(village, Items.WHEAT_SEEDS) < 24) return false;
        take(village, Items.WHEAT_SEEDS, 7);
        return true;
    }

    private static int count(Village village, Item item) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (s.isOf(item)) n += s.getCount();
        return n;
    }

    private static boolean take(Village village, Item item, int n) {
        if (count(village, item) < n) return false;
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext() && n > 0; ) {
            ItemStack s = it.next();
            if (!s.isOf(item)) continue;
            int d = Math.min(n, s.getCount());
            s.decrement(d);
            n -= d;
            if (s.isEmpty()) it.remove();
        }
        village.markDirty();
        return true;
    }
}
