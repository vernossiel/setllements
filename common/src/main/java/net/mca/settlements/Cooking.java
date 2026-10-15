package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The cook works the tavern's real blocks: loads raw food and fuel into the smoker, collects what it cooked, puts the
 * meals in the barrel and bakes bread at the crafting table. If the smoker does not cook (some mods remove the
 * recipes), the food is finished by hand after a while, so the kitchen never stalls.
 */
public final class Cooking {
    private static final int BARREL_TARGET = 40;
    private static final long FALLBACK_AFTER = 1200;
    private static final Map<BlockPos, Long> LOADED_AT = new HashMap<>();

    private static final Map<Item, Item> RECIPES = Map.ofEntries(
            Map.entry(Items.BEEF, Items.COOKED_BEEF),
            Map.entry(Items.PORKCHOP, Items.COOKED_PORKCHOP),
            Map.entry(Items.CHICKEN, Items.COOKED_CHICKEN),
            Map.entry(Items.MUTTON, Items.COOKED_MUTTON),
            Map.entry(Items.RABBIT, Items.COOKED_RABBIT),
            Map.entry(Items.COD, Items.COOKED_COD),
            Map.entry(Items.SALMON, Items.COOKED_SALMON),
            Map.entry(Items.POTATO, Items.BAKED_POTATO));

    public static boolean isRaw(ItemStack s) {
        return RECIPES.containsKey(s.getItem());
    }

    private Cooking() {
    }

    /** One cook second. Returns true when the cook actually worked. */
    public static boolean work(VillagerEntityMCA v, Village village) {
        Doing.set(v, "settlements.doing.cook");
        Tavern t = village.tavern;
        if (t == null || !(v.getWorld() instanceof ServerWorld world)) return false;
        if (!world.isChunkLoaded(t.smoker().getX() >> 4, t.smoker().getZ() >> 4)) return false;

        if (v.squaredDistanceTo(t.barrel().getX() + 0.5, t.barrel().getY(), t.barrel().getZ() + 0.5) > 9) {
            Move.to(v, t.barrel().getX() + 0.5, t.barrel().getY(), t.barrel().getZ() + 0.5, 0.6);
            return false;
        }
        if (!(world.getBlockEntity(t.smoker()) instanceof AbstractFurnaceBlockEntity smoker) || !(world.getBlockEntity(t.barrel()) instanceof Inventory barrel)) return false;

        // 1. collect the finished food
        ItemStack out = smoker.getStack(2);
        if (!out.isEmpty()) {
            ItemStack rest = Tavern.insert(barrel, out);
            smoker.setStack(2, rest);
            Speech.say(v, "settlements.say.cook.ready");
            v.swingHand(v.getDominantHand());
            return true;
        }
        if (Tavern.mealsIn(barrel) >= BARREL_TARGET) return false;

        // 2. the smoker is busy, or stuck without a working recipe
        ItemStack input = smoker.getStack(0);
        if (!input.isEmpty()) {
            long since = world.getTime() - LOADED_AT.getOrDefault(t.smoker(), world.getTime());
            if (since > FALLBACK_AFTER && RECIPES.containsKey(input.getItem())) {
                Item cooked = RECIPES.get(input.getItem());
                smoker.setStack(2, new ItemStack(cooked, input.getCount()));
                smoker.setStack(0, ItemStack.EMPTY);
                LOADED_AT.remove(t.smoker());
            }
            return false;
        }

        // 3. load raw food and fuel from the treasury
        ItemStack raw = takeRaw(village);
        if (!raw.isEmpty()) {
            if (Needs.get(v) == Needs.Need.MEAT) Needs.set(v, Needs.Need.NONE);
            if (smoker.getStack(1).isEmpty()) {
                ItemStack fuel = takeFuel(village);
                if (!fuel.isEmpty()) smoker.setStack(1, fuel);
            }
            smoker.setStack(0, raw);
            LOADED_AT.put(t.smoker(), world.getTime());
            v.swingHand(v.getDominantHand());
            return true;
        }

        // 4. nothing to cook: bake bread at the crafting table
        if (Builder.takeItem(village, s -> s.isOf(Items.WHEAT), 3)) {
            Tavern.insert(barrel, new ItemStack(Items.BREAD));
            v.swingHand(v.getDominantHand());
            return true;
        }
        // nothing to cook at all: ask for it
        if (Tavern.mealsIn(barrel) < BARREL_TARGET / 2 && Needs.get(v) != Needs.Need.FOOD) Needs.set(v, Needs.Need.MEAT);
        return false;
    }

    private static ItemStack takeRaw(Village village) {
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (!RECIPES.containsKey(s.getItem())) continue;
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
