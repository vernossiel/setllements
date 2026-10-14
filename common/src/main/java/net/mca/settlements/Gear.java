package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.world.data.Village;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;

import java.util.Iterator;

/**
 * Villagers craft their own tools and armor from the village treasury (the tax storage).
 * A poor village only affords wood/leather, a rich one gets iron and diamond.
 * Tiers: 0 wood, 1 stone/leather, 2 iron, 3 diamond.
 */
public final class Gear {
    private static final int TOOL_COST = 2;
    private static final int ARMOR_SET_COST = 6;
    private static final int RESERVE = 4; // never spend the last items of a material

    private Gear() {
    }

    private static Item material(int tier) {
        return switch (tier) {
            case 1 -> Items.COBBLESTONE;
            case 2 -> Items.IRON_INGOT;
            default -> Items.DIAMOND;
        };
    }

    private static int count(Village village, Item item) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (s.isOf(item)) n += s.getCount();
        return n;
    }

    private static boolean take(Village village, Item item, int amount) {
        if (count(village, item) < amount) return false;
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext() && amount > 0; ) {
            ItemStack s = it.next();
            if (!s.isOf(item)) continue;
            int d = Math.min(amount, s.getCount());
            s.decrement(d);
            amount -= d;
            if (s.isEmpty()) it.remove();
        }
        village.markDirty();
        return true;
    }

    /** Best tier the treasury can pay for the given cost, 0 if nothing. */
    private static int affordableTier(Village village, int cost) {
        for (int tier = 3; tier >= 1; tier--) {
            if (count(village, material(tier)) >= cost + RESERVE) return tier;
        }
        return 0;
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
        return net.minecraft.registry.Registries.ITEM.get(new net.minecraft.util.Identifier(m[tier] + "_" + kind));
    }

    /** Make sure the villager has a tool for the chore, upgrading it when the village can afford it. */
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
        int tier = village == null ? 0 : affordableTier(village, TOOL_COST);
        if (slot >= 0 && have >= tier) return;
        if (tier > 0 && !take(village, material(tier), TOOL_COST)) tier = 0;
        if (slot >= 0 && have >= tier) return;
        Item item = tool(chore, tier);
        if (item == null) return;
        if (slot >= 0) inv.setStack(slot, new ItemStack(item));
        else inv.addStack(new ItemStack(item));
    }

    /** Craft a better armor set when the village is wealthy enough; poor villages go without. */
    public static void equipArmor(VillagerEntityMCA v, Village village) {
        if (village == null) return;
        int tier = affordableTier(village, ARMOR_SET_COST);
        if (tier == 0) return;
        ArmorMaterial target = switch (tier) {
            case 1 -> ArmorMaterials.CHAIN;
            case 2 -> ArmorMaterials.IRON;
            default -> ArmorMaterials.DIAMOND;
        };
        if (v.getEquippedStack(EquipmentSlot.CHEST).getItem() instanceof ArmorItem a && a.getMaterial().getProtection(ArmorItem.Type.CHESTPLATE) >= target.getProtection(ArmorItem.Type.CHESTPLATE)) {
            return;
        }
        if (!take(village, material(tier), ARMOR_SET_COST)) return;
        Item[] set = switch (tier) {
            case 1 -> new Item[]{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
            case 2 -> new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
            default -> new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
        };
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) v.equipStack(slots[i], new ItemStack(set[i]));
    }
}
