package net.mca.settlements;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Soft compatibility with Pyrotech Reborn. Nothing here links against Pyrotech; blocks are looked up by id at runtime,
 * so the mod works the same with or without it. With Pyrotech the smithy gets a granite anvil and a bloomery instead of
 * the vanilla anvil and furnace, whose recipes Pyrotech removes.
 */
public final class Compat {
    private Compat() {
    }

    public static Block block(String namespace, String path) {
        Identifier id = new Identifier(namespace, path);
        return Registries.BLOCK.containsId(id) ? Registries.BLOCK.get(id) : null;
    }

    public static boolean pyrotech() {
        return block("pyrotech", "bloomery") != null;
    }

    public static boolean isAir(Block b) {
        return b == null || b == Blocks.AIR;
    }
}
