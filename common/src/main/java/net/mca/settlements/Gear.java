package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.world.data.Village;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;

import java.util.Iterator;
import java.util.function.Predicate;

/**
 * Villagers craft tools and armor from what the village actually holds in its treasury.
 * Logs give wooden tools, cobblestone stone tools, iron ingots iron, diamonds diamond.
 * A village with no stock stays poor. Real recipe costs: 3 material (+ sticks from one log) per axe/pickaxe,
 * 2 per hoe/sword, 24 per armor set (leather or iron or diamond).
 */
public final class Gear {
    private static final Predicate<ItemStack> LOG = s -> s.isIn(ItemTags.LOGS);
    private static final Predicate<ItemStack> PLANKS = s -> s.isIn(ItemTags.PLANKS);
    private static final Predicate<ItemStack> COBBLE = s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE) || s.isOf(Items.BLACKSTONE);
    private static final Predicate<ItemStack> IRON = s -> s.isOf(Items.IRON_INGOT);
    private static final Predicate<ItemStack> DIAMOND = s -> s.isOf(Items.DIAMOND);
    private static final Predicate<ItemStack> LEATHER = s -> s.isOf(Items.LEATHER);

    private Gear() {
    }

    private static int count(Village village, Predicate<ItemStack> p) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (p.test(s)) n += s.getCount();
        return n;
    }

    private static void take(Village village, Predicate<ItemStack> p, int amount) {
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext() && amount > 0; ) {
            ItemStack s = it.next();
            if (!p.test(s)) continue;
            int d = Math.min(amount, s.getCount());
            s.decrement(d);
            amount -= d;
            if (s.isEmpty()) it.remove();
        }
        village.markDirty();
    }

    private static Predicate<ItemStack> material(int tier) {
        return switch (tier) {
            case 0 -> PLANKS;
            case 1 -> COBBLE;
            case 2 -> IRON;
            default -> DIAMOND;
        };
    }

    /** Wooden tools need planks too; one log counts as four planks, so logs are taken first. */
    private static boolean canPay(Village village, int tier, int units) {
        if (count(village, LOG) < 1 && count(village, PLANKS) < 2) return false; // sticks
        if (tier == 0) return count(village, LOG) * 4 + count(village, PLANKS) >= units + 2;
        return count(village, material(tier)) >= units;
    }

    private static void pay(Village village, int tier, int units) {
        // sticks: 2 planks, made from one log when possible
        if (count(village, PLANKS) >= 2) take(village, PLANKS, 2);
        else take(village, LOG, 1);
        if (tier == 0) {
            int planks = Math.min(units, count(village, PLANKS));
            take(village, PLANKS, planks);
            int rest = units - planks;
            if (rest > 0) take(village, LOG, (rest + 3) / 4);
        } else {
            take(village, material(tier), units);
        }
    }

    private static int toolTier(ItemStack s) {
        if (s.getItem() instanceof ToolItem t) {
            ToolMaterial m = t.getMaterial();
            if (m == ToolMaterials.DIAMOND || m == ToolMaterials.NETHERITE) return 3;
            if (m == ToolMaterials.IRON) return 2;
            if (m == ToolMaterials.STONE) return 1;
        }
        return 0;
    }

    private static Item tool(Chore chore, int tier) {
        String[] m = {"wooden", "stone", "iron", "diamond"};
        String kind = switch (chore) {
            case CHOP -> "axe";
            case PROSPECT -> "pickaxe";
            case HARVEST -> "hoe";
            case HUNT -> "sword";
            default -> null;
        };
        if (kind == null) return null;
        return Registries.ITEM.get(new Identifier(m[tier] + "_" + kind));
    }

    private static int toolUnits(Chore chore) {
        return chore == Chore.HARVEST || chore == Chore.HUNT ? 2 : 3;
    }

    /** Make sure the villager has a tool for the chore, crafting a better one if the stock allows. */
    public static void equipTool(VillagerEntityMCA v, Village village, Chore chore) {
        Class<?> type = chore.getToolType();
        if (type == null) return;
        Inventory inv = v.getInventory();
        int slot = -1, have = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (type.isInstance(s.getItem())) {
                slot = i;
                have = toolTier(s);
                break;
            }
        }
        int units = toolUnits(chore);
        if (village != null) {
            for (int tier = 3; tier > have && tier >= 0; tier--) {
                if (!canPay(village, tier, units)) continue;
                pay(village, tier, units);
                ItemStack crafted = new ItemStack(tool(chore, tier));
                if (slot >= 0) inv.setStack(slot, crafted);
                else inv.addStack(crafted);
                return;
            }
        }
        // the very first tool of a village with nothing in stock: improvised from scrap wood
        if (slot < 0) inv.addStack(new ItemStack(tool(chore, 0)));
    }

    /** Craft a full armor set from leather, iron or diamond if the treasury holds enough. */
    public static void equipArmor(VillagerEntityMCA v, Village village) {
        if (village == null) return;
        int tier = -1;
        ArmorMaterial target = null;
        if (count(village, DIAMOND) >= 24) { tier = 3; target = ArmorMaterials.DIAMOND; }
        else if (count(village, IRON) >= 24) { tier = 2; target = ArmorMaterials.IRON; }
        else if (count(village, LEATHER) >= 24) { tier = 0; target = ArmorMaterials.LEATHER; }
        if (target == null) return;
        if (v.getEquippedStack(EquipmentSlot.CHEST).getItem() instanceof ArmorItem a
                && a.getMaterial().getProtection(ArmorItem.Type.CHESTPLATE) >= target.getProtection(ArmorItem.Type.CHESTPLATE)) {
            return;
        }
        take(village, tier == 3 ? DIAMOND : tier == 2 ? IRON : LEATHER, 24);
        Item[] set = switch (tier) {
            case 3 -> new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
            case 2 -> new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
            default -> new Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
        };
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) v.equipStack(slots[i], new ItemStack(set[i]));
    }
}
