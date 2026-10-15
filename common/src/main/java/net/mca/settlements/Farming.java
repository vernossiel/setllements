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
