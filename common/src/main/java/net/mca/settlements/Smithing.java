package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The smith works the smithy's furnace for real: loads raw ore and fuel, takes out the ingots and hammers at the anvil
 * while the ore melts. With Pyrotech the furnace is a bloomery, which is not a vanilla furnace, so the smith smelts
 * by hand at double fuel cost instead.
 */
public final class Smithing {
    private static final long FALLBACK_AFTER = 1200;
    private static final Map<BlockPos, Long> LOADED_AT = new HashMap<>();
    private static final Map<Item, Item> INGOT = Map.of(
            Items.RAW_IRON, Items.IRON_INGOT, Items.RAW_GOLD, Items.GOLD_INGOT, Items.RAW_COPPER, Items.COPPER_INGOT);

    private Smithing() {
    }

    public static boolean work(VillagerEntityMCA v, Village village) {
        Doing.set(v, "settlements.doing.smith");
        if (v.getWorld() instanceof ServerWorld fw && Forge.work(v, village, fw)) return true; // tools for those waiting first
        BlockPos fp = village.smithyFurnace;
        if (fp == null || !(v.getWorld() instanceof ServerWorld world) || !world.isChunkLoaded(fp.getX() >> 4, fp.getZ() >> 4)) return false;
        BlockPos anvil = village.smithyAnvil != null ? village.smithyAnvil : fp;
        if (v.squaredDistanceTo(fp.getX() + 0.5, fp.getY(), fp.getZ() + 0.5) > 25) {
            Move.to(v, fp.getX() + 0.5, fp.getY(), fp.getZ() + 0.5, 0.6);
            return false;
        }

        if (world.getBlockEntity(fp) instanceof AbstractFurnaceBlockEntity furnace) {
            ItemStack out = furnace.getStack(2);
            if (!out.isEmpty()) {
                Workforce.addToTreasury(village, out.copy());
                furnace.setStack(2, ItemStack.EMPTY);
                v.swingHand(v.getDominantHand());
                return true;
            }
            ItemStack input = furnace.getStack(0);
            if (!input.isEmpty()) {
                long since = world.getTime() - LOADED_AT.getOrDefault(fp, world.getTime());
                if (since > FALLBACK_AFTER && INGOT.containsKey(input.getItem())) {
                    furnace.setStack(2, new ItemStack(INGOT.get(input.getItem()), input.getCount()));
                    furnace.setStack(0, ItemStack.EMPTY);
                    LOADED_AT.remove(fp);
                }
                return hammer(v, world, anvil);
            }
            ItemStack raw = takeRaw(village);
            if (raw.isEmpty()) return false;
            if (furnace.getStack(1).isEmpty()) {
                ItemStack fuel = takeFuel(village);
                if (!fuel.isEmpty()) furnace.setStack(1, fuel);
            }
            furnace.setStack(0, raw);
            LOADED_AT.put(fp, world.getTime());
            v.swingHand(v.getDominantHand());
            return true;
        }

        // not a vanilla furnace (Pyrotech bloomery): smelt by hand every few seconds
        if (v.age % 200 != 0) return hammer(v, world, anvil);
        ItemStack raw = takeRaw(village);
        if (raw.isEmpty()) return false;
        ItemStack fuel = takeFuel(village);
        if (fuel.isEmpty()) {
            Workforce.addToTreasury(village, raw);
            return false;
        }
        int n = raw.getCount();
        Workforce.addToTreasury(village, new ItemStack(INGOT.get(raw.getItem()), n));
        v.swingHand(v.getDominantHand());
        return true;
    }

    /** The smith walks to the anvil and strikes it while the furnace works. */
    private static boolean hammer(VillagerEntityMCA v, ServerWorld world, BlockPos anvil) {
        if (v.squaredDistanceTo(anvil.getX() + 0.5, anvil.getY(), anvil.getZ() + 0.5) > 6) {
            Move.to(v, anvil.getX() + 0.5, anvil.getY(), anvil.getZ() + 0.5, 0.6);
            return false;
        }
        Gaze.at(v, anvil.getX() + 0.5, anvil.getY() + 0.5, anvil.getZ() + 0.5);
        v.swingHand(v.getDominantHand());
        if (v.age % 60 == 0) world.playSound(null, anvil, SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.5f, 1.2f);
        return true;
    }

    private static ItemStack takeRaw(Village village) {
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (!INGOT.containsKey(s.getItem())) continue;
            int n = Math.min(8, s.getCount());
            ItemStack part = new ItemStack(s.getItem(), n);
            s.decrement(n);
            if (s.isEmpty()) it.remove();
            village.markDirty();
            return part;
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack takeFuel(Village village) {
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)) {
                s.decrement(1);
                if (s.isEmpty()) it.remove();
                village.markDirty();
                return new ItemStack(Items.COAL, 1);
            }
        }
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (s.isIn(ItemTags.PLANKS)) {
                int n = Math.min(4, s.getCount());
                ItemStack fuel = new ItemStack(s.getItem(), n);
                s.decrement(n);
                if (s.isEmpty()) it.remove();
                village.markDirty();
                return fuel;
            }
        }
        return ItemStack.EMPTY;
    }
}
