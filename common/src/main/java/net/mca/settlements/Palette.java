package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Building materials of the land the village stands in: spruce and cobblestone in the taiga, birch in the birch
 * woods, sandstone in the desert, acacia in the savanna... The roof takes a darker wood for contrast.
 */
public record Palette(String wood, String roofWood, Block stone, Block stoneStairs, Block accent, Block flower) {
    private static final Map<Integer, Palette> CACHE = new HashMap<>();
    private static final Palette OAK = new Palette("oak", "spruce", Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICKS, Blocks.POTTED_POPPY);

    /** Set while a test building is placed outside any village: the materials of the land it stands in. */
    static Palette OVERRIDE = null;

    public static Palette of(Village village) {
        if (OVERRIDE != null) return OVERRIDE;
        Palette p = CACHE.get(village.getId());
        if (p != null) return p;
        ServerWorld world = village.getWorld();
        if (world == null) return OAK;
        BlockPos c = new BlockPos(village.getCenter());
        String biome = world.getBiome(c).getKey().map(k -> k.getValue().getPath()).orElse("plains");
        p = forBiome(biome);
        CACHE.put(village.getId(), p);
        return p;
    }

    static Palette forBiome(String b) {
        if (b.contains("desert") || b.contains("badlands"))
            return new Palette("birch", "jungle", Blocks.SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.CUT_SANDSTONE, Blocks.POTTED_DEAD_BUSH);
        if (b.contains("taiga") || b.contains("snowy") || b.contains("grove") || b.contains("frozen") || b.contains("ice"))
            return new Palette("spruce", "dark_oak", Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICKS, Blocks.POTTED_FERN);
        if (b.contains("birch")) return new Palette("birch", "spruce", Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICKS, Blocks.POTTED_OXEYE_DAISY);
        if (b.contains("dark_forest")) return new Palette("dark_oak", "spruce", Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.MOSSY_STONE_BRICKS, Blocks.POTTED_RED_MUSHROOM);
        if (b.contains("jungle") || b.contains("bamboo"))
            return new Palette("jungle", "jungle", Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_STAIRS, Blocks.MOSSY_STONE_BRICKS, Blocks.POTTED_BAMBOO);
        if (b.contains("savanna")) return new Palette("acacia", "acacia", Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.TERRACOTTA, Blocks.POTTED_ORANGE_TULIP);
        if (b.contains("swamp") || b.contains("mangrove"))
            return new Palette("mangrove", "spruce", Blocks.MUD_BRICKS, Blocks.MUD_BRICK_STAIRS, Blocks.PACKED_MUD, Blocks.POTTED_BLUE_ORCHID);
        if (b.contains("cherry")) return new Palette("cherry", "spruce", Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS, Blocks.POTTED_PINK_TULIP);
        if (b.contains("meadow") || b.contains("flower")) return new Palette("oak", "spruce", Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICKS, Blocks.POTTED_ALLIUM);
        return OAK;
    }

    private static Block get(String id, Block fallback) {
        Block b = Registries.BLOCK.get(new Identifier(id));
        return b == Blocks.AIR ? fallback : b;
    }

    public Block planks() { return get(wood + "_planks", Blocks.OAK_PLANKS); }
    public Block log() { return get(wood + "_log", Blocks.OAK_LOG); }
    public Block strippedLog() { return get("stripped_" + wood + "_log", Blocks.STRIPPED_OAK_LOG); }
    public Block door() { return get(wood + "_door", Blocks.OAK_DOOR); }
    public Block fence() { return get(wood + "_fence", Blocks.OAK_FENCE); }
    public Block trapdoor() { return get(wood + "_trapdoor", Blocks.OAK_TRAPDOOR); }
    public Block plate() { return get(wood + "_pressure_plate", Blocks.OAK_PRESSURE_PLATE); }
    public Block leaves() { return get(wood + "_leaves", Blocks.OAK_LEAVES); }
    public Block roofStairs() { return get(roofWood + "_stairs", Blocks.SPRUCE_STAIRS); }
    public Block roofSlab() { return get(roofWood + "_slab", Blocks.SPRUCE_SLAB); }
    public Block roofPlanks() { return get(roofWood + "_planks", Blocks.SPRUCE_PLANKS); }
    public Block stairs() { return get(wood + "_stairs", Blocks.OAK_STAIRS); }
    public Block fenceGate() { return get(wood + "_fence_gate", Blocks.OAK_FENCE_GATE); }
    /** A city's plastered walls above its stone ground floors. */
    public Block plaster() {
        if (stone == Blocks.SANDSTONE) return Blocks.SMOOTH_SANDSTONE;
        if (stone == Blocks.MUD_BRICKS) return Blocks.PACKED_MUD;
        return Blocks.WHITE_TERRACOTTA;
    }

    /** A city's roof tiles: red sandstone in the desert, brick in the savanna, slate elsewhere. */
    public Block tiles() {
        if (stone == Blocks.SANDSTONE) return Blocks.SMOOTH_RED_SANDSTONE;
        if (accent == Blocks.TERRACOTTA) return Blocks.BRICKS;
        return Blocks.DEEPSLATE_TILES;
    }

    public Block tileStairs() {
        if (stone == Blocks.SANDSTONE) return Blocks.SMOOTH_RED_SANDSTONE_STAIRS;
        if (accent == Blocks.TERRACOTTA) return Blocks.BRICK_STAIRS;
        return Blocks.DEEPSLATE_TILE_STAIRS;
    }

    public Block plainFlower() { return flower instanceof net.minecraft.block.FlowerPotBlock pot ? pot.getContent() : Blocks.POPPY; }
}
