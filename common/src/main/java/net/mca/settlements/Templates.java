package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;
import net.minecraft.block.*;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Buildings taken over from MineColonies (https://github.com/ldtteam/minecolonies, GPL-3.0; the designs are by its
 * schematic builders, see each template's "credit"): converted to plain vanilla blocks, turned to face south, and
 * built here by our own builders block by block, paid for from the village stores. The timber follows the village's
 * own wood, so a northern longhouse is built of birch in a birch wood and of oak on the plains.
 *
 * A design name like {@code "home-mc:residence3"} in the tier table stands for "the residence, level 3, in whatever
 * MineColonies style suits the village"; once chosen it becomes {@code "home-mc.nordic.residence3"} for good.
 */
public final class Templates {
    public record Template(String id, int w, int d, int h, int ground, BlockState[] palette, short[] data, String credit) {
        int at(int x, int y, int z) {
            return data[(y * d + z) * w + x] & 0xFFFF;
        }
    }

    private static final Map<String, Template> CACHE = new HashMap<>();
    private static final String ROOT = "/assets/mca/settlements/templates/";

    private Templates() {
    }

    /** A placeholder in the tier table, not yet tied to a style. */
    public static boolean placeholder(String type) {
        return type != null && type.contains("-mc:");
    }

    public static boolean isTemplate(String type) {
        return type != null && type.contains("-mc.");
    }

    /** The template a concrete design name stands for, or null. */
    public static Template of(String type) {
        if (!isTemplate(type)) return null;
        String rest = type.substring(type.indexOf("-mc.") + 4); // style.name
        int dot = rest.indexOf('.');
        if (dot < 0) return null;
        return load(rest.substring(0, dot) + "/" + rest.substring(dot + 1));
    }

    /** The MineColonies style that suits a village's land; null where none does (we keep our own designs there). */
    static String style(Village village) {
        ServerWorld world = village.getWorld();
        if (world == null) return "nordic";
        String biome = world.getBiome(new BlockPos(village.getCenter())).getKey().map(k -> k.getValue().getPath()).orElse("plains");
        if (biome.contains("desert") || biome.contains("badlands") || biome.contains("beach") || biome.contains("ocean")) return null;
        return "nordic"; // in its timber of the place: acacia in the savanna, jungle wood in the jungle
    }

    /** Turns a placeholder into a concrete design for this village (the style of its land), or null if there is none. */
    public static String resolve(String placeholder, Village village) {
        int i = placeholder.indexOf("-mc:");
        String base = placeholder.substring(0, i), name = placeholder.substring(i + 4);
        String style = style(village);
        if (style == null) return null;
        for (String s : new String[]{style, "nordic"}) { // a style lacking that building borrows the northern one
            String type = base + "-mc." + s + "." + name;
            if (of(type) != null) return type;
        }
        return null;
    }

    private static synchronized Template load(String id) {
        if (CACHE.containsKey(id)) return CACHE.get(id);
        Template t = null;
        try (InputStream in = Templates.class.getResourceAsStream(ROOT + id + ".json")) {
            if (in != null) {
                com.google.gson.JsonObject j = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                int w = j.get("w").getAsInt(), d = j.get("d").getAsInt(), h = j.get("h").getAsInt(), ground = j.get("ground").getAsInt();
                com.google.gson.JsonArray pa = j.getAsJsonArray("palette");
                BlockState[] palette = new BlockState[pa.size()];
                for (int k = 0; k < palette.length; k++) palette[k] = parse(pa.get(k).getAsString());
                byte[] raw = new GZIPInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(j.get("data").getAsString()))).readAllBytes();
                ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                short[] data = new short[w * d * h];
                for (int k = 0; k < data.length; k++) data[k] = bb.getShort();
                t = new Template(id, w, d, h, ground, palette, data, j.has("credit") ? j.get("credit").getAsString() : "");
            }
        } catch (Exception e) {
            t = null;
        }
        CACHE.put(id, t);
        return t;
    }

    private static BlockState parse(String s) {
        try {
            return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), s, false).blockState();
        } catch (Exception e) {
            return Blocks.AIR.getDefaultState();
        }
    }

    /** Things put in once the walls stand. */
    static boolean fitting(BlockState s) {
        Block b = s.getBlock();
        return b instanceof TorchBlock || b instanceof LanternBlock || b instanceof CarpetBlock || b instanceof BedBlock || b instanceof ChestBlock
                || b instanceof BarrelBlock || b instanceof FlowerPotBlock || b instanceof AbstractBannerBlock || b instanceof LadderBlock
                || b instanceof AbstractSignBlock || b instanceof CraftingTableBlock || b instanceof AbstractFurnaceBlock || b instanceof LecternBlock
                || b instanceof AnvilBlock || b instanceof BrewingStandBlock || b instanceof AbstractCauldronBlock || b instanceof ComposterBlock
                || b instanceof GrindstoneBlock || b instanceof StonecutterBlock || b instanceof EnchantingTableBlock || b instanceof ButtonBlock
                || b instanceof LeverBlock || b instanceof ChainBlock || b instanceof AbstractCandleBlock || b instanceof PlantBlock
                || b instanceof TripwireHookBlock || b instanceof CampfireBlock || b instanceof BellBlock || b == Blocks.BOOKSHELF;
    }

    /** The plan of a template building, in our own terms (y = 0 the first layer above the ground). */
    public static List<Step> steps(String type) {
        Template t = of(type);
        List<Step> out = new ArrayList<>();
        if (t == null) return out;
        for (int y = 0; y < t.h(); y++) {
            int dy = y - t.ground();
            for (int z = 0; z < t.d(); z++) {
                for (int x = 0; x < t.w(); x++) {
                    int i = t.at(x, y, z);
                    if (i == 0xFFFF) continue; // the ground as it is
                    BlockState s = t.palette()[i];
                    if (s.isAir()) {
                        out.add(new Step(x, dy, z, dy < 0 ? Kind.DIG : Kind.CLEAR));
                        continue;
                    }
                    out.add(new Step(x, dy, z, fitting(s) ? Kind.TPL_FIT : Kind.TPL, i));
                }
            }
        }
        return out;
    }

    /** The block of a template step, its timber in the village's wood, turned with the building. */
    static BlockState state(Village village, Step step, int rot) {
        Template t = of(village.buildType);
        if (t == null || step.face() < 0 || step.face() >= t.palette().length) return null;
        BlockState s = reskin(t.palette()[step.face()], Palette.of(village));
        return s.rotate(BlockRotation.values()[rot & 3]);
    }

    /** Northern spruce becomes the village's own wood. */
    static BlockState reskin(BlockState s, Palette pal) {
        if (pal.wood().equals("spruce")) return s;
        Identifier id = Registries.BLOCK.getId(s.getBlock());
        if (!id.getNamespace().equals("minecraft") || !id.getPath().contains("spruce")) return s;
        Identifier other = new Identifier("minecraft", id.getPath().replace("spruce", pal.wood()));
        if (!Registries.BLOCK.containsId(other)) return s;
        return Registries.BLOCK.get(other).getStateWithProperties(s);
    }
}
