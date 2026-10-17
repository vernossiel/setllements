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

    /** The MineColonies style that suits a village's land. */
    static String style(Village village) {
        ServerWorld world = village.getWorld();
        if (world == null) return "medievaloak";
        String biome = world.getBiome(new BlockPos(village.getCenter())).getKey().map(k -> k.getValue().getPath()).orElse("plains");
        if (biome.contains("badlands")) return "badlands"; // terracotta and red sandstone (MineColonies' desert buildings stand in for the rest)
        if (biome.contains("desert")) return "sandstone";
        if (biome.contains("snow") || biome.contains("taiga") || biome.contains("frozen") || biome.contains("ice") || biome.contains("grove")
                || biome.contains("slope") || biome.contains("peak")) return "nordic";
        if (biome.contains("birch")) return "medievalbirch";
        if (biome.contains("dark_forest")) return "medievaldarkoak";
        return "medievaloak"; // in the timber of the place: acacia in the savanna, jungle wood in the jungle
    }

    /** The timber a style is drawn in (swapped for the village's own), or null for a style of stone. */
    private static String styleWood(String style) {
        return switch (style) {
            case "nordic" -> "spruce";
            case "medievaloak" -> "oak";
            case "medievalbirch" -> "birch";
            case "medievaldarkoak" -> "dark_oak";
            case "sandstone", "badlands" -> "oak"; // (their doors, shutters and furniture: in whatever wood the village has)
            default -> null;
        };
    }

    /** The files that can stand for a logical building name ("home3": a residence, or a desert tower house...). */
    private static String[] aliases(String name) {
        String n = name.replaceAll("\\d", ""), lv = name.replaceAll("\\D", "");
        return switch (n) {
            // a hamlet's huts are ours (a MineColonies residence is a proper house already: the village's)
            case "hut" -> new String[]{"dwelling" + lv, "dwelling" + lv + "b", "dwelling" + lv + "c", "dwelling" + lv + "d", "dwelling" + lv + "e"};
            case "home" -> lv.equals("1") ? new String[]{"residence1", "residencetower1"}
                    : new String[]{"residence" + lv, "residencetower" + lv, "residencelarge" + lv, "residenceluxuryred" + lv, "residenceluxuryblue" + lv,
                    "dwelling" + lv, "dwelling" + lv + "b", "dwelling" + lv + "c", "dwelling" + lv + "d", "dwelling" + lv + "e"}; // (ours among them: five for each station)
            case "bakery" -> new String[]{"baker" + lv, "bakery" + lv};
            default -> new String[]{name};
        };
    }

    /** The logical name of a template file ("residencetower3" -> "home3"). */
    static String logical(String file) {
        String n = file.replaceAll("\\d", ""), lv = file.replaceAll("\\D", "");
        if (n.startsWith("dwelling") && lv.equals("1")) return "hut1";
        if (n.startsWith("residence") || n.startsWith("dwelling")) return "home" + lv;
        if (n.equals("baker")) return "bakery" + lv;
        return file;
    }

    /** Turns a placeholder into a concrete design for this village (the style of its land), or null if there is none. */
    public static String resolve(String placeholder, Village village) {
        int i = placeholder.indexOf("-mc:");
        String base = placeholder.substring(0, i), name = placeholder.substring(i + 4);
        String style = style(village);
        if (style == null) return null;
        List<String> found = new ArrayList<>();
        for (String st : styles(style)) {
            for (String file : aliases(name)) {
                String type = base + "-mc." + st + "." + file;
                if (of(type) != null) found.add(type);
            }
            if (!found.isEmpty()) break;
        }
        if (found.isEmpty()) return null;
        return found.get(Math.floorMod((int) (village.getId() * 31L + found.size() * 7L + (village.getWorld() == null ? 0 : village.getWorld().getTime() / 24000L)), found.size()));
    }

    /** Every concrete design a placeholder can stand for in this village's style (see resolve). */
    public static List<String> resolveAll(String placeholder, Village village) {
        List<String> found = new ArrayList<>();
        int i = placeholder.indexOf("-mc:");
        if (i < 0) return found;
        String base = placeholder.substring(0, i), name = placeholder.substring(i + 4);
        String style = style(village);
        if (style == null) return found;
        for (String st : styles(style)) {
            for (String file : aliases(name)) {
                String type = base + "-mc." + st + "." + file;
                if (of(type) != null && !found.contains(type)) found.add(type);
            }
            if (!found.isEmpty()) break;
        }
        return found;
    }

    /** The style of the land, and the one to fall back on where it has no such building (the badlands: the desert's). */
    private static List<String> styles(String style) {
        return style.equals("badlands") ? List.of("badlands", "sandstone") : List.of(style);
    }

    /** Every template there is, as "style/file" (from the index written with them), optionally of one style only. */
    public static List<String> all(String style) {
        List<String> out = new ArrayList<>();
        try (InputStream in = Templates.class.getResourceAsStream(ROOT + "index.txt")) {
            if (in == null) return out;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                line = line.trim();
                if (!line.isEmpty() && (style == null || line.startsWith(style + "/"))) out.add(line);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** A concrete design name for a template file ("medievaloak/tavern1" -> "tpl-mc.medievaloak.tavern1"). */
    public static String design(String styleFile) {
        return "tpl-mc." + styleFile.replace('/', '.');
    }

    private static synchronized Template load(String id) {
        if (CACHE.containsKey(id)) return CACHE.get(id);
        Template t = null;
        try (InputStream in = Templates.class.getResourceAsStream(ROOT + id + ".json")) {
            if (in != null) {
                com.google.gson.JsonObject j = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                int w = j.get("w").getAsInt(), d = j.get("d").getAsInt(), h = j.get("h").getAsInt();
                int ground = j.get("ground").getAsInt();
                com.google.gson.JsonArray pa = j.getAsJsonArray("palette");
                BlockState[] palette = new BlockState[pa.size()];
                for (int k = 0; k < palette.length; k++) palette[k] = parse(pa.get(k).getAsString());
                byte[] raw = new GZIPInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(j.get("data").getAsString()))).readAllBytes();
                ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                short[] data = new short[w * d * h];
                for (int k = 0; k < data.length; k++) data[k] = bb.getShort();
                // the floor goes on the ground: a template whose bottom layers are the ground as it is, or only a few
                // posts, has its "ground" at the first layer with a real floor in it (the founders' hall of three styles
                // stood three blocks up in the air on its posts)
                for (int y = 0; y < h; y++) {
                    int solid = 0;
                    for (int k = y * w * d; k < (y + 1) * w * d; k++) {
                        int i = data[k] & 0xFFFF;
                        if (i != 0xFFFF && !palette[i].isAir()) solid++;
                    }
                    if (solid >= 0.15 * w * d) {
                        ground = Math.max(ground, y);
                        break;
                    }
                }
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
                || b instanceof BarrelBlock || b instanceof FlowerPotBlock || b instanceof AbstractBannerBlock
                || b instanceof AbstractSignBlock || b instanceof CraftingTableBlock || b instanceof AbstractFurnaceBlock || b instanceof LecternBlock
                || b instanceof AnvilBlock || b instanceof BrewingStandBlock || b instanceof AbstractCauldronBlock || b instanceof ComposterBlock
                || b instanceof GrindstoneBlock || b instanceof StonecutterBlock || b instanceof EnchantingTableBlock || b instanceof ButtonBlock
                || b instanceof LeverBlock || b instanceof ChainBlock || b instanceof AbstractCandleBlock || b instanceof PlantBlock
                || b instanceof TripwireHookBlock || b instanceof CampfireBlock || b instanceof BellBlock || b == Blocks.BOOKSHELF;
    }

    private static final Map<String, Integer> TURN = new HashMap<>();

    /**
     * The quarter turns that bring a template's front door round to the south, the side our own plans have it on and
     * the side the street is laid out to (MineColonies draws its houses facing every way: the door came out by the
     * fence, the road ran to the back wall). Added to the turn a site is given, when it is chosen.
     */
    public static int entryTurn(String type) {
        Template t = of(type);
        if (t == null) return 0;
        return TURN.computeIfAbsent(t.id(), k -> {
            int bx = -1, by = Integer.MAX_VALUE, bz = -1, bedge = Integer.MAX_VALUE;
            for (int y = 0; y < t.h(); y++) {
                for (int z = 0; z < t.d(); z++) {
                    for (int x = 0; x < t.w(); x++) {
                        int i = t.at(x, y, z);
                        if (i == 0xFFFF) continue;
                        BlockState s = t.palette()[i];
                        if (!(s.getBlock() instanceof DoorBlock) || s.get(DoorBlock.HALF) != net.minecraft.block.enums.DoubleBlockHalf.LOWER) continue;
                        int edge = Math.min(Math.min(x, t.w() - 1 - x), Math.min(z, t.d() - 1 - z));
                        // the lowest door, and of those the one nearest the outside: the front door
                        if (y < by || y == by && edge < bedge) {
                            by = y;
                            bedge = edge;
                            bx = x;
                            bz = z;
                        }
                    }
                }
            }
            if (bx < 0) return 0;
            int[] dist = {bz, t.w() - 1 - bx, t.d() - 1 - bz, bx}; // north, east, south, west
            int side = 0;
            for (int i = 1; i < 4; i++) if (dist[i] < dist[side]) side = i;
            return Math.floorMod(2 - side, 4);
        });
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
        String style = t.id().substring(0, t.id().indexOf('/'));
        BlockState s = reskin(t.palette()[step.face()], Palette.of(village), style);
        return s.rotate(net.minecraft.util.BlockRotation.values()[rot & 3]);
    }

    /** The style's own timber becomes the village's wood. */
    static BlockState reskin(BlockState s, Palette pal, String style) {
        // the stone of the land too: the cobble of a design laid in mossy cobble in the jungle, mud brick in the swamp,
        // stone brick among the cherries (the desert's designs are drawn in sandstone already)
        if (!style.equals("sandstone") && pal.stone() != Blocks.COBBLESTONE && pal.stone() != Blocks.SANDSTONE) {
            Block b = s.getBlock();
            if (b == Blocks.COBBLESTONE) return pal.stone().getDefaultState();
            if (b == Blocks.COBBLESTONE_STAIRS) return pal.stoneStairs().getStateWithProperties(s);
        }
        String from = styleWood(style);
        if (from == null || pal.wood().equals(from)) return s;
        Identifier id = Registries.BLOCK.getId(s.getBlock());
        String path = id.getPath();
        if (!id.getNamespace().equals("minecraft")) return s;
        boolean match = path.startsWith(from + "_") || path.startsWith("stripped_" + from + "_");
        if (from.equals("oak") && (path.startsWith("dark_oak") || path.startsWith("stripped_dark_oak"))) match = false;
        if (!match) return s;
        Identifier other = new Identifier("minecraft", path.startsWith("stripped_") ? "stripped_" + pal.wood() + path.substring(9 + from.length())
                : pal.wood() + path.substring(from.length()));
        if (!Registries.BLOCK.containsId(other)) return s;
        return Registries.BLOCK.get(other).getStateWithProperties(s);
    }
}
