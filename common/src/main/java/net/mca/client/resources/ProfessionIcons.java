package net.mca.client.resources;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;

public class ProfessionIcons {
    public static final Map<String, ItemStack> ICONS = new HashMap<>();

    static {
        ICONS.put(null, Items.APPLE.getDefaultStack());
        ICONS.put("nitwit", Items.FLOWER_POT.getDefaultStack());
        ICONS.put("armorer", Items.BLAST_FURNACE.getDefaultStack());
        ICONS.put("butcher", Items.SMOKER.getDefaultStack());
        ICONS.put("cartographer", Items.CARTOGRAPHY_TABLE.getDefaultStack());
        ICONS.put("cleric", Items.BREWING_STAND.getDefaultStack());
        ICONS.put("farmer", Items.COMPOSTER.getDefaultStack());
        ICONS.put("fisherman", Items.BARREL.getDefaultStack());
        ICONS.put("fletcher", Items.FLETCHING_TABLE.getDefaultStack());
        ICONS.put("leatherworker", Items.CAULDRON.getDefaultStack());
        ICONS.put("librarian", Items.LECTERN.getDefaultStack());
        ICONS.put("mason", Items.STONECUTTER.getDefaultStack());
        ICONS.put("shepherd", Items.LOOM.getDefaultStack());
        ICONS.put("toolsmith", Items.SMITHING_TABLE.getDefaultStack());
        ICONS.put("weaponsmith", Items.GRINDSTONE.getDefaultStack());

        ICONS.put("mca.outlaw", Items.BLACK_BANNER.getDefaultStack());
        ICONS.put("mca.guard", Items.IRON_SWORD.getDefaultStack());
        ICONS.put("mca.archer", Items.BOW.getDefaultStack());
        ICONS.put("mca.adventurer", Items.MAP.getDefaultStack());
        ICONS.put("mca.mercenary", Items.EMERALD.getDefaultStack());
        ICONS.put("mca.cultist", Items.BOOK.getDefaultStack());
    }
}
