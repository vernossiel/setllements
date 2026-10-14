package net.mca;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

public interface TagsMCA {
    interface Blocks {
        TagKey<Block> TOMBSTONES = register("tombstones");

        static void bootstrap() {
        }

        static TagKey<Block> register(String path) {
            return TagKey.of(RegistryKeys.BLOCK, new Identifier(MCA.MOD_ID, path));
        }
    }

    interface Items {
        TagKey<Item> VILLAGER_EGGS = register("villager_eggs");
        TagKey<Item> ZOMBIE_EGGS = register("zombie_eggs");
        TagKey<Item> VILLAGER_PLANTABLE = register("villager_plantable");
        TagKey<Item> BABIES = register("babies");

        static void bootstrap() {
        }

        static TagKey<Item> register(String path) {
            return TagKey.of(RegistryKeys.ITEM, new Identifier(MCA.MOD_ID, path));
        }
    }
}
