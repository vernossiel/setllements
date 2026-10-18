package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Building;
import net.mca.server.world.data.Village;
import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;
import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The village planner and builder. It decides what the village needs next (storage, houses, more floors on
 * existing houses, a mine), picks a site, then builder villagers place the plan block by block, paying every
 * block from the village treasury. Logs are sawn into planks on demand, mines feed the treasury with stone and ore.
 */
public final class Builder {
    public static int opsPerSecond = 2; // the debug command raises it
    private static final int MAX_TRIVIAL_SKIPS = 60;
    private static final Map<String, List<Step>> CACHE = new HashMap<>();

    private Builder() {
    }

    // ---------- plans ----------

    /** Number of steps of the running project, for the progress shown on the notice board. */
    public static int stepsOf(Village v) {
        return v.buildOrigin == null || v.buildType == null ? 0 : plan(v).size();
    }

    private static List<Step> plan(Village v) {
        if (v.buildType.equals("wall") || v.buildType.equals("palisade")) {
            String key = v.buildType + "|" + v.buildW + "|" + (v.buildOrigin == null ? 0 : v.buildOrigin.asLong());
            return CACHE.computeIfAbsent(key, k -> Walls.plan(v, v.buildType.equals("wall")));
        }
        return stepsFor(v.buildType, v.buildW, v.buildD, v.buildF);
    }

    /** The plan of a building type with its parameters (cached). */
    public static List<Step> stepsFor(String type, int w, int d, int f) {
        String key = type + "|" + w + "|" + d + "|" + f;
        return CACHE.computeIfAbsent(key, k -> ordered(type.equals("mine") ? null : type,
                type.equals("mine") ? rawSteps(type, w, d, f) : Templates.isTemplate(type) ? Decor.dressTemplate(type, rawSteps(type, w, d, f)) : Decor.dress(type, rawSteps(type, w, d, f))));
    }

    /** Fittings go in once the shell stands: furniture, light, decoration. */
    private static boolean fitting(Kind k) {
        return switch (k) {
            case BED_FOOT, BED_HEAD, CHEST, HALL_CHEST, CRAFT, CARPET, TORCH, LANTERN, BARREL, BOOKSHELF, LECTERN, SMOKER, FURNACE, ANVIL,
                 GRINDSTONE, SMITHING, STONECUTTER, CAULDRON, COMPOSTER, ARMOR_STAND, PLATE, SEAT_W, SEAT_E, PEW, POT, FLOWER, HEDGE,
                 HAY, PUMPKIN, TARGET, CHAIN, BELL, SIGN, SMOKE, CLOCK, SHUTTER, WOOL, LADDER, SHELF, BANNER, FRAME, PROP, TPL_FIT -> true;
            default -> false;
        };
    }

    /**
     * The order a man builds in: the ground first, then layer by layer upwards, each block next to the one before,
     * walking round the walls; the fittings last. What stands at a spot in the end is the last step for it.
     */
    static List<Step> ordered(String type, List<Step> raw) {
        if (type == null) return raw;
        java.util.LinkedHashMap<Long, Step> last = new java.util.LinkedHashMap<>();
        for (Step st : raw) {
            long key = BlockPos.asLong(st.dx(), st.dy(), st.dz());
            last.remove(key);
            last.put(key, st);
        }
        java.util.TreeMap<Integer, List<Step>> clears = new java.util.TreeMap<>(), shell = new java.util.TreeMap<>(), fit = new java.util.TreeMap<>();
        // the ladders go up with the walls, each at the end of its layer (the wall behind it standing): left with the
        // fittings, they went in after the roof, and the roof and the loft had no way up to them from inside
        java.util.Map<Integer, List<Step>> ladders = new java.util.HashMap<>();
        for (Step st : last.values()) {
            if (st.kind() == Kind.LADDER) {
                ladders.computeIfAbsent(st.dy(), y -> new ArrayList<>()).add(st);
                shell.computeIfAbsent(st.dy(), y -> new ArrayList<>());
                continue;
            }
            var map = st.kind() == Kind.CLEAR || st.kind() == Kind.DIG ? clears : fitting(st.kind()) ? fit : shell;
            map.computeIfAbsent(st.dy(), y -> new ArrayList<>()).add(st);
        }
        List<Step> out = new ArrayList<>(raw.size());
        for (List<Step> layer : clears.values()) out.addAll(layer);
        int[] at = {0, 0};
        for (var group : List.of(shell, fit)) {
            for (var entry : group.entrySet()) {
                List<Step> layer = entry.getValue();
                // nearest next: a wall goes up round the house, not in jumps from one side to the other
                List<Step> left = new ArrayList<>(layer);
                while (!left.isEmpty()) {
                    int bi = 0;
                    long bd = Long.MAX_VALUE;
                    for (int i = 0; i < left.size(); i++) {
                        Step c = left.get(i);
                        long dd = (long) (c.dx() - at[0]) * (c.dx() - at[0]) + (long) (c.dz() - at[1]) * (c.dz() - at[1]);
                        if (dd < bd) {
                            bd = dd;
                            bi = i;
                        }
                    }
                    Step next = left.remove(bi);
                    out.add(next);
                    at[0] = next.dx();
                    at[1] = next.dz();
                }
                if (group == shell) out.addAll(ladders.getOrDefault(entry.getKey(), List.of()));
            }
        }
        return out;
    }

    private static List<Step> rawSteps(String type, int w, int d, int f) {
        return switch (type) {
            case "house" -> Blueprints.house(w, d, f);
            case "upgrade" -> Blueprints.addFloor(w, d, f);
            case "storage" -> Blueprints.storage();
            case "smithy" -> Blueprints.smithy();
            case "armory" -> Blueprints.armory();
            case "tavern" -> Blueprints.tavern();
            case "townhall" -> Blueprints.townhall();
            case "board" -> Blueprints.board();
            case "hallup" -> Blueprints.hallUpgrade(w, d, f);
            case "pen" -> Blueprints.pen();
            case "mine" -> Blueprints.mine();
            case "well" -> Blueprints.well();
            case "farm" -> Blueprints.farm();
            case "tower" -> Blueprints.tower();
            default -> Templates.isTemplate(type) ? Templates.steps(type) : Buildings.plan(type, w, d, f);
        };
    }

    private static List<Step> wallPlan(Village v) {
        ServerWorld world = v.getWorld();
        BlockPos o = v.buildOrigin;
        if (world == null || o == null) return List.of();
        int half = (v.buildW - 1) / 2;
        return Blueprints.wall(half, (dx, dz) -> {
            int x = o.getX() + dx, z = o.getZ() + dz;
            world.getChunk(x >> 4, z >> 4);
            return world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z) - o.getY();
        });
    }

    // ---------- treasury ----------

    /** While the founders raise the town hall, materials come with the founding and are not taken from the stores. */
    private static boolean FREE = false;

    /** The station whose materials a building is put up in; -1: the village's own. */
    static int MAT_TIER = -1;

    private static int count(Village v, Predicate<ItemStack> p) {
        if (FREE) return 9999;
        int n = 0;
        for (ItemStack s : v.storageBuffer) if (p.test(s)) n += s.getCount();
        return n;
    }

    private static int planks(Village v) {
        return count(v, s -> s.isIn(ItemTags.PLANKS)) + 4 * count(v, s -> s.isIn(ItemTags.LOGS));
    }

    private static Item planksOf(Item log) {
        String path = Registries.ITEM.getId(log).getPath().replace("stripped_", "");
        for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
            if (path.endsWith(suffix)) {
                Item item = Registries.ITEM.get(new Identifier(path.substring(0, path.length() - suffix.length()) + "_planks"));
                if (item != Items.AIR) return item;
            }
        }
        return Items.OAK_PLANKS;
    }

    private static boolean take(Village v, Predicate<ItemStack> p, int amount) {
        if (FREE) return true;
        if (count(v, p) < amount) return false;
        for (Iterator<ItemStack> it = v.storageBuffer.iterator(); it.hasNext() && amount > 0; ) {
            ItemStack s = it.next();
            if (!p.test(s)) continue;
            int d = Math.min(amount, s.getCount());
            s.decrement(d);
            amount -= d;
            if (s.isEmpty()) it.remove();
        }
        v.markDirty();
        return true;
    }

    /** One plank, sawing a log when no plank is left. Null if the treasury has neither. */
    private static Item takePlank(Village v) {
        if (FREE) return Items.OAK_PLANKS;
        for (Iterator<ItemStack> it = v.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (s.isIn(ItemTags.PLANKS)) {
                Item item = s.getItem();
                s.decrement(1);
                if (s.isEmpty()) it.remove();
                v.markDirty();
                return item;
            }
        }
        for (Iterator<ItemStack> it = v.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (s.isIn(ItemTags.LOGS)) {
                Item planks = planksOf(s.getItem());
                s.decrement(1);
                if (s.isEmpty()) it.remove();
                v.storageBuffer.add(0, new ItemStack(planks, Buildings.has(v, "sawmill") ? 5 : 3)); // the saw wastes less
                v.markDirty();
                return planks;
            }
        }
        return null;
    }

    private static boolean takePlanks(Village v, int n) {
        if (planks(v) < n) return false;
        for (int i = 0; i < n; i++) takePlank(v);
        return true;
    }

    // stone for building: cobble, and in the desert the sandstone its quarries give
    private static final Predicate<ItemStack> COBBLE = s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE) || s.isOf(Items.SANDSTONE) || s.isOf(Items.RED_SANDSTONE);
    /** What a desert or the badlands builds with: its sandstone and sand, its terracotta, and cobble if there is any. */
    private static final Predicate<ItemStack> SANDISH = s -> COBBLE.test(s) || s.isOf(Items.SAND) || s.isOf(Items.RED_SAND)
            || s.isOf(Items.SMOOTH_SANDSTONE) || s.isOf(Items.CUT_SANDSTONE) || Registries.ITEM.getId(s.getItem()).getPath().contains("terracotta");
    private static final Predicate<ItemStack> LOG = s -> s.isIn(ItemTags.LOGS);
    private static final Predicate<ItemStack> GLASS = s -> s.isOf(Items.GLASS_PANE) || s.isOf(Items.GLASS);
    private static final Predicate<ItemStack> WOOL = s -> s.isIn(ItemTags.WOOL);

    public static int stockPlanks(Village v) {
        return planks(v) + Scavenge.stockFiller(v);
    }

    public static int stockCobble(Village v) {
        return count(v, COBBLE);
    }

    public static int stockIron(Village v) {
        return count(v, x -> x.isOf(Items.IRON_INGOT) || x.isOf(Items.RAW_IRON));
    }

    /** Wool for beds, or what stands in for it: a straw mattress (a hay bale, or three sheaves of wheat). */
    public static int stockWool(Village v) {
        return count(v, WOOL) + count(v, x -> x.isOf(Items.HAY_BLOCK)) + count(v, x -> x.isOf(Items.WHEAT)) / 3;
    }

    /**
     * The filling of {@code n} beds: wool, or failing it straw - a hay bale or three sheaves a bed. (Houses waited on
     * wool from sheep nobody could find; the village stood still four days with 900 boards in its stores.)
     */
    private static boolean takeBedding(Village v, int n) {
        if (stockWool(v) < n) return false;
        for (int i = 0; i < n; i++) {
            if (!take(v, WOOL, 1) && !take(v, x -> x.isOf(Items.HAY_BLOCK), 1)) take(v, x -> x.isOf(Items.WHEAT), 3);
        }
        return true;
    }

    /** A place for the mine's entrance: used by the miner, who opens the mine himself. */
    public static BlockPos chooseMineSite(ServerWorld world, Village village) {
        // not where the shaft would run into water or lava (a lake, a lava pool on the surface, an aquifer)
        // level ground under the shed and before its doors first: on a slope the first steps stood a block proud of the
        // grass and the shaft broke out of the hillside beside the shed
        BlockPos fallback = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            BlockPos o = findSite(world, village, 4, 4);
            if (o == null) break;
            if (!dryUnder(world, o) || !clearOver(world, village, o)) continue;
            if (flatMouth(world, o)) return o;
            if (fallback == null) fallback = o;
        }
        return fallback;
    }

    /** The ground under the shed, its porch and the first steps all at the one level. */
    private static boolean flatMouth(ServerWorld world, BlockPos o) {
        for (int x = -1; x <= 2; x++) {
            for (int z = -2; z <= 4; z++) {
                if (Layout.ground(world, o.getX() + x, o.getZ() + z) != o.getY()) return false;
            }
        }
        return true;
    }

    /**
     * Nothing built over the whole run of the shaft, not just its 4x4 mouth: the stair goes down a long way to one side,
     * and one was dug right in through the floor of a house beside it (the miner stood stuck in the house).
     */
    private static boolean clearOver(ServerWorld world, Village village, BlockPos o) {
        int depth = Blueprints.MINE_DEPTH;
        for (int x = -3; x <= 6; x++) {
            for (int z = -3; z <= depth + 7; z++) {
                BlockPos p = o.add(x, 0, z);
                if (Layout.ground(world, p.getX(), p.getZ()) == Integer.MIN_VALUE && !world.getFluidState(p.down()).isEmpty()) return false;
                for (Building b : village.getBuildings().values()) if (!b.getBuildingType().grouped() && b.containsPos(p.up())) return false;
                for (Built b : village.built) if (b.contains(p)) return false;
                int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                BlockState t = world.getBlockState(new BlockPos(p.getX(), top - 1, p.getZ()));
                if (!Layout.land(t) && !t.isIn(net.minecraft.registry.tag.BlockTags.LOGS) && !t.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) return false; // a roof, a floor, a fence
            }
        }
        return true;
    }

    private static boolean dryUnder(ServerWorld world, BlockPos o) {
        int depth = Blueprints.MINE_DEPTH;
        for (int x = -3; x <= 5; x++) {
            for (int z = -3; z <= depth + 7; z++) {
                for (int y = 2; y >= -depth - 2; y -= 1) {
                    BlockPos p = o.add(x, y, z);
                    if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) return false;
                    if (!world.getFluidState(p).isEmpty()) return false;
                }
            }
        }
        // nor into a cave or a ravine: all of the rock the shaft and its galleries go through is whole before it is dug
        // (two men fell to their deaths where a shaft ran out into a gorge)
        int hollow = 0;
        for (int x = -2; x <= 3; x++) {
            for (int z = 0; z <= depth + 6; z++) {
                for (int y = -3; y >= -depth - 4; y--) { // (below the top of the ground: a slope is no cave)
                    BlockPos p = o.add(x, y, z);
                    if (world.getBlockState(p).isAir() && ++hollow > 3) return false;
                }
            }
        }
        return true;
    }

    // ---------- planner ----------

    /** The project the planner started last, so the builder can say what he is about to do. */
    static String announceProject = null;

    private static boolean plan(Village village, ServerWorld world) {
        int planks = planks(village);
        var rand = world.random;
        // people without a roof and not wood enough for their house yet: the village saves its timber for it - no pen, no
        // charcoal pit, no tower in the meantime (only the hall and the fields, which feed them)
        boolean saving = false;
        for (Plan.Status st : Plan.pending(village)) if (st.goal().id().equals("housing") && !st.ready()) saving = true;
        for (Plan.Status st : Plan.pending(village)) {
            if (!st.ready()) continue;
            if (saving && st.goal().planks() > 0 && !java.util.Set.of("townhall", "farm", "well").contains(st.goal().id())) continue;
            switch (st.goal().id()) {
                case "well" -> {
                    Layout.Slot slot = Layout.center(world, village, "well");
                    village.wellBuilt = true; // a blocked center is simply skipped
                    if (slot != null) return startAt(village, Tiers.design("well", Prosperity.tier(village), rand, village), 0, 0, 0, slot);
                }
                case "storage" -> {
                    if (startRing(village, world, "storage", 0, 0, 0, 0, 3)) return true;
                }
                case "housing" -> {
                    for (House h : village.houses) {
                        // a floor goes only on the plain house it was drawn for: put on a porch or an annex house it stood
                        // askew on the walls below, and the repairs (going by the old plan) roofed over its stairs again
                        boolean plain = village.built.stream().anyMatch(b -> b.origin().equals(h.origin()) && b.type().equals("house"));
                        // (no more storeys put on the old drawn houses: a new home goes up instead - ten days went on
                        // a second floor and a loft for an old house with a ladder up through it)
                        if (false && plain && h.floors() < 3 && h.d() >= 7 && planks >= 130) {
                            village.buildType = "upgrade";
                            village.buildOrigin = h.origin();
                            village.buildW = h.w();
                            village.buildD = h.d();
                            village.buildF = h.floors();
                            village.buildRot = h.rot();
                            village.buildStep = 0;
                            village.markDirty();
                            return true;
                        }
                    }
                    // different homes for different means: huts in a young hamlet, family houses with gardens later,
                    // two-storey houses for a prosperous town
                    Plan.Level lv = Plan.level(village);
                    double roll = rand.nextDouble();
                    // homes after the village's means: huts and longhouses when poor, family houses when it gets by,
                    // mansions and town houses when it is wealthy
                    int tier = Prosperity.tier(village);
                    int wealth = Math.min(2, tier);
                    // (the hamlet's homes too: it used to put up the old drawn houses, the new ones never)
                    String kind = Tiers.design("home", tier, rand, null);
                    int need = switch (kind) {
                        case "mansion", "wealden" -> 340;
                        case "rowhouse" -> 220;
                        case "cruck" -> 0;
                        case "manor", "farmhouse" -> 260;
                        case "annex", "tallhouse", "twin", "cottage" -> 180;
                        case "hut" -> 0;
                        default -> 110;
                    };
                    if (planks >= need && roll < 0.8) {
                        int[] dm = SHOWCASE.getOrDefault(kind, new int[]{0, 0, 1});
                        if (kind.equals("house")) { // an ordinary house comes in sizes, larger the better off the village
                            dm = new int[]{7 + rand.nextInt(2) + wealth, 7 + rand.nextInt(2) + wealth / 2, wealth >= 2 || rand.nextInt(3) == 0 ? 2 : 1};
                        }
                        if (startRing(village, world, kind, dm[0], dm[1], dm[2], 0, 4)) return true;
                    }
                    if (planks >= 320 && startRing(village, world, "house", 6 + rand.nextInt(3), 7 + rand.nextInt(2), 3, 0, 3)) return true;
                    if (planks >= 200 && startRing(village, world, "house", 6 + rand.nextInt(3), 7 + rand.nextInt(2), 2, 0, 3)) return true;
                    if (startRing(village, world, "house", 6, 7, 1, 0, 3)) return true;
                }
                case "farm" -> {
                    if (startRing(village, world, "farm", 0, 0, 0, 0, 5)) return true; // just past the streets, not far off in the wilds
                }
                case "hallup" -> {
                    House h = village.hall;
                    if (h != null) {
                        village.buildType = "hallup";
                        village.buildOrigin = h.origin();
                        village.buildW = h.w();
                        village.buildD = h.d();
                        village.buildF = h.floors();
                        village.buildRot = h.rot();
                        village.buildStep = 0;
                        village.markDirty();
                        announceProject = "hallup";
                        return true;
                    }
                }
                case "mine" -> {
                    // the miner opens the mine himself, the builder has nothing to do with it
                }
                case "smithy" -> {
                    if (startRing(village, world, "smithy", 0, 0, 0, 0, 3)) return true;
                }
                case "armory" -> {
                    if (startRing(village, world, "armory", 0, 0, 0, 0, 3)) return true;
                }
                case "tower" -> {
                    // the first one near the middle, where the archer can see the houses from it; the others out on the edges
                    if (village.towers < 1 ? startRing(village, world, "tower", 0, 0, 0, 1, 3) : startRing(village, world, "tower", 0, 0, 0, 4, 5)) return true;
                }
                case "townhall" -> {
                    if (startRing(village, world, "townhall", 0, 0, 0, 0, 3)) return true;
                    Layout.Slot hs = Layout.center(world, village, "townhall");
                    if (hs != null) return startAt(village, "townhall", 0, 0, 0, hs);
                    if (start(village, world, "townhall", Blueprints.HALL_W, Blueprints.HALL_D, 0)) return true;
                }
            case "pen" -> {
                    if (startRing(village, world, "pen", 0, 0, 0, 2, 4)) return true;
                }
                case "tavern" -> {
                    if (startRing(village, world, "tavern", 0, 0, 0, 0, 3)) return true;
                }
                case "sawmill", "mason", "barracks", "chapel" -> {
                    if (startRing(village, world, st.goal().id(), 0, 0, 0, 1, 4)) return true;
                }
                case "charcoal", "mill" -> {
                    if (startRing(village, world, st.goal().id(), 0, 0, 0, 2, 5)) return true;
                }
                case "granary", "jail", "market" -> {
                    if (startRing(village, world, st.goal().id(), 0, 0, 0, 0, 3)) return true;
                }
                case "rebuild" -> {
                    if (Rebuild.start(village, world)) return true;
                }
                case "renovate" -> {
                    if (Rebuild.renovate(village, world)) return true;
                }
                case "newhome" -> {
                    // the house a household paid for, on a site of its own (the old one had no room for it)
                    String kind = Tiers.design("home", Rebuild.commissionTier(village), rand, null);
                    int[] dm = kind.equals("house") ? new int[]{8, 8, 2} : SHOWCASE.getOrDefault(kind, new int[]{0, 0, 1});
                    java.util.UUID payer = village.commissionElsewhere;
                    if (startRing(village, world, kind, dm[0], dm[1], dm[2], 0, 5)) {
                        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
                        int fw = (village.buildRot & 1) == 0 ? fp[0] : fp[1], fd = (village.buildRot & 1) == 0 ? fp[1] : fp[0];
                        village.reserved.put(village.buildOrigin.add(fw / 2, 1, fd / 2).asLong(), payer);
                        village.commissionElsewhere = null;
                        NEWHOME_TRIES.remove(village.getId());
                        return true;
                    }
                    if (NEWHOME_TRIES.merge(village.getId(), 1, Integer::sum) >= 20) { // no site to be had at all: the money back
                        NEWHOME_TRIES.remove(village.getId());
                        village.commissionElsewhere = null;
                        Rebuild.refund(village, world, payer);
                    }
                }
                case "shop" -> {
                    if (startRing(village, world, "shop", 0, 0, 0, 0, 3)) return true;
                }
                case "board" -> {
                    if (startRing(village, world, "board", 0, 0, 0, 0, 1)) return true;
                    if (start(village, world, "board", 3, 2, 0)) return true;
                }
                case "palisade", "wall" -> {
                    // the border round the village: marked out by the builder, then raised along the line
                    BlockPos c = new BlockPos(village.getCenter());
                    int cy = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
                    int r = st.goal().id().equals("wall") && village.palisadeR > 0 ? village.palisadeR : Walls.radius(village);
                    village.buildType = st.goal().id();
                    village.buildOrigin = new BlockPos(c.getX(), cy, c.getZ());
                    village.buildRot = 0;
                    village.buildW = r;
                    village.buildD = r;
                    village.buildF = 0;
                    village.buildStep = 0;
                    village.markDirty();
                    announceProject = st.goal().id();
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static final java.util.Map<Integer, Long> NEXT_LOOK = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Integer> NEWHOME_TRIES = new java.util.HashMap<>();

    private static final java.util.Map<Integer, long[]> STEP_AT = new java.util.HashMap<>(); // {step, since}
    private static final java.util.Map<Integer, Integer> LONG_ARM = new java.util.HashMap<>(); // village -> the step he lays from where he is

    /** While the hall is first looked for: only a site close by the bell will do. */
    private static boolean NEAR_ONLY = false;

    /** The last building no site was found for (said in /settle why). */
    private static String lastNoSite;

    private static boolean startRing(Village village, ServerWorld world, String type, int w, int d, int floors, int minRing, int maxRing) {
        if (type.equals("house") && w > 0) w |= 1; // an odd width: the door in the middle of the front, the windows even either side of it
        if (!Templates.placeholder(type)) type = Tiers.design(type, Prosperity.tier(village), world.random, village); // the design that suits the village's station
        if (Templates.placeholder(type)) {
            // a MineColonies building: in the style of the village's land (or our own design where none suits)
            String concrete = Templates.resolve(type, village);
            if (concrete == null) return false;
            Templates.Template t = Templates.of(concrete);
            type = concrete;
            w = t.w();
            d = t.d();
            floors = 1;
        }
        Layout.Slot slot = Layout.find(world, village, type, w, d, minRing, maxRing);
        if (slot != null && NEAR_ONLY) {
            BlockPos c = new BlockPos(village.getCenter());
            BlockPos o = slot.origin();
            if (Math.hypot(o.getX() + slot.fw() / 2.0 - c.getX(), o.getZ() + slot.fd() / 2.0 - c.getZ()) > 32 || Math.abs(o.getY() - c.getY()) > 12) slot = null;
        }
        if (slot == null) {
            lastNoSite = type;
            if (Tiers.isHome(type)) net.mca.MCA.LOGGER.info("[Settlements] {}: no site for a {} ({}x{}) anywhere", village.getName(), type, w, d);
            return false;
        }
        lastNoSite = null;
        return startAt(village, type, w, d, floors, slot);
    }

    /**
     * The site made ready: the ground under the building cut and filled to the floor's level, in the land's own earth
     * (sand in the desert, grass and earth elsewhere), and round it a gentle slope down (or up) to the ground about, so
     * that nothing stands on a box of earth and every door can be walked up to.
     */
    static void levelSite(ServerWorld world, Village village, BlockPos o, int fw, int fd) {
        Block top = Blocks.GRASS_BLOCK, under = Blocks.DIRT;
        int sand = 0, red = 0, all = 0;
        for (int x = o.getX() - 4; x <= o.getX() + fw + 3; x += 2) {
            for (int z = o.getZ() - 4; z <= o.getZ() + fd + 3; z += 2) {
                BlockPos g = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                BlockState s = world.getBlockState(g);
                all++;
                if (s.isOf(Blocks.SAND)) sand++;
                else if (s.isOf(Blocks.RED_SAND)) red++;
            }
        }
        if (sand * 2 > all) {
            top = Blocks.SAND;
            under = Blocks.SANDSTONE;
        } else if (red * 2 > all) {
            top = Blocks.RED_SAND;
            under = Blocks.RED_SANDSTONE;
        }
        // how much higher the land rises round the site: on a hillside the slope cut back is as wide as the hill is
        // high (up to eight blocks), so it eases down a block at a time instead of leaving the house in a pit of
        // sheer earth walls
        int rise = 0;
        for (int x = o.getX() - 4; x <= o.getX() + fw + 3; x++) {
            for (int z = o.getZ() - 4; z <= o.getZ() + fd + 3; z++) {
                int g = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (natural(world.getBlockState(new BlockPos(x, g - 1, z)))) rise = Math.max(rise, g - o.getY());
            }
        }
        final int RAMP = Math.max(3, Math.min(8, rise));
        for (int x = o.getX() - 1 - RAMP; x <= o.getX() + fw + RAMP; x++) {
            for (int z = o.getZ() - 1 - RAMP; z <= o.getZ() + fd + RAMP; z++) {
                // how far outside the footprint (and the step round it): 0 on it, then 1, 2, 3 down the slope
                int out = Math.max(Math.max(o.getX() - 1 - x, x - (o.getX() + fw)), Math.max(o.getZ() - 1 - z, z - (o.getZ() + fd)));
                out = Math.max(0, out);
                if (out == 0) {
                    // trees standing on the site are felled and the logs go to the village's stores (left lying on
                    // the grass, nobody ever came for them), branches and leaves hanging over it cut back
                    for (int y = o.getY() + 20; y >= o.getY() - 2; y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = world.getBlockState(p);
                        if (s.isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
                            fell(world, village, p); // the whole tree, not just what stands over the site (its top was left hanging)
                        } else if (y > o.getY() + 2 && s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) world.breakBlock(p, false);
                    }
                }
                int ground = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z); // first free block above the ground
                // the slope keeps off whatever is built about the site (a floor, a path, a wall): only bare land is shaped
                if (out > 0 && !natural(world.getBlockState(new BlockPos(x, ground - 1, z)))) continue;
                // the level wanted here: the floor on the site, easing towards the ground about it on the slope
                int want = out == 0 ? o.getY() : ground + (int) Math.round((o.getY() - ground) * (1.0 - out / (double) (RAMP + 1)));
                // cut what natural stands above it
                for (int y = Math.max(want, ground) + 2; y >= want; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (!s.isAir() && natural(s)) world.breakBlock(p, false);
                }
                // fill up to it
                for (int y = want - 1; y >= want - (out == 0 ? 24 : 12); y--) { // down to the ground, however far: nothing stands on air
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    // leaves are no ground: earth heaped on a tree's crown is left hanging once the tree is felled and
                    // the leaves fall, and the building on it with it
                    if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty() && !s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                        if (y == want - 1 && natural(s) && s.isIn(net.minecraft.registry.tag.BlockTags.DIRT)) world.setBlockState(p, top.getDefaultState());
                        break;
                    }
                    if (out > 0 && !s.getFluidState().isEmpty()) break; // the slope does not run into water
                    world.setBlockState(p, (y == want - 1 ? top : under).getDefaultState());
                }
            }
        }
    }

    /** Fells the tree a log belongs to: every log joined to it (up to a big tree's worth), into the village's stores. */
    private static void fell(ServerWorld world, Village village, BlockPos start) {
        java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        open.add(start);
        seen.add(start);
        int n = 0;
        while (!open.isEmpty() && n < 160) {
            BlockPos p = open.poll();
            BlockState s = world.getBlockState(p);
            if (!s.isIn(net.minecraft.registry.tag.BlockTags.LOGS)) continue;
            world.breakBlock(p, false);
            if (village != null) Workforce.addToTreasury(village, new net.minecraft.item.ItemStack(s.getBlock().asItem()));
            n++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos q = p.add(dx, dy, dz);
                if (q.getY() < start.getY() - 1) continue; // not down into the roots (or a log house below)
                if (seen.add(q) && world.getBlockState(q).isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                        && (dx == 0 && dz == 0 && dy == 1 || wild(world, q))) open.add(q); // up the trunk, or out along a branch in the leaves
            }
        }
    }

    /** A log of a tree that grew there (natural leaves about it), not a beam somebody built in. */
    private static boolean wild(ServerWorld world, BlockPos p) {
        for (BlockPos q : BlockPos.iterate(p.add(-2, -1, -2), p.add(2, 2, 2))) {
            BlockState s = world.getBlockState(q);
            if (s.getBlock() instanceof net.minecraft.block.LeavesBlock && !s.get(net.minecraft.block.LeavesBlock.PERSISTENT)) return true;
        }
        return false;
    }

    private static boolean natural(BlockState s) {
        return s.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || s.isIn(net.minecraft.registry.tag.BlockTags.REPLACEABLE)
                || s.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD) || s.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                || s.isIn(net.minecraft.registry.tag.BlockTags.FLOWERS) || s.isOf(Blocks.GRAVEL) || s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)
                || s.isOf(Blocks.SNOW) || s.isOf(Blocks.CACTUS) || s.isOf(Blocks.DEAD_BUSH);
    }

    /** A beam laid along its axis (a log); in the desert's stone, the plain block. */
    private static BlockState axised(Block b, net.minecraft.util.math.Direction.Axis axis) {
        BlockState st = b.getDefaultState();
        return st.contains(PillarBlock.AXIS) ? st.with(PillarBlock.AXIS, axis) : st;
    }

    private static boolean startAt(Village village, String type, int w, int d, int floors, Layout.Slot slot) {
        if (village.getWorld() != null) {
            String clash = Layout.clash(village.getWorld(), village, slot.origin().getX(), slot.origin().getZ(), slot.fw(), slot.fd());
            if (clash != null) { // built inside another: never (the record says what stood in the way)
                net.mca.MCA.LOGGER.info("[Settlements] {}: {} at {} not begun: runs into {}", village.getName(), type, slot.origin().toShortString(), clash);
                return false;
            }
            levelSite(village.getWorld(), village, slot.origin(), slot.fw(), slot.fd());
        }
        village.buildType = type;
        village.buildOrigin = slot.origin();
        village.buildRot = slot.rot();
        village.buildW = w;
        village.buildD = d;
        village.buildF = floors;
        village.buildStep = 0;
        village.plots.add(new int[]{slot.origin().getX(), slot.origin().getZ(), slot.fw(), slot.fd()});
        announceProject = Buildings.base(type);
        village.markDirty();
        return true;
    }

    /** Free-standing projects without a ring slot, like the mine at the edge of the village. */
    private static boolean start(Village village, ServerWorld world, String type, int w, int d, int floors) {
        BlockPos site = findSite(world, village, w, d);
        if (site != null && Layout.clash(world, village, site.getX(), site.getZ(), Math.max(1, w), Math.max(1, d)) != null) site = null;
        if (site == null) return false;
        int[] sfp = Blueprints.footprint(type, w, d);
        levelSite(world, village, site, sfp[0], sfp[1]); // the ground made level, as for any other site
        village.buildType = type;
        village.buildOrigin = site;
        village.buildRot = 0;
        village.buildW = w;
        village.buildD = d;
        village.buildF = floors;
        village.buildStep = 0;
        village.plots.add(new int[]{site.getX(), site.getZ(), w, d});
        village.markDirty();
        announceProject = type;
        return true;
    }

    private static BlockPos findSite(ServerWorld world, Village village, int w, int d) {
        BlockPos c = new BlockPos(village.getCenter());
        var rand = world.random;
        for (int attempt = 0; attempt < 60; attempt++) {
            int x = c.getX() + rand.nextBetween(-40, 40);
            int z = c.getZ() + rand.nextBetween(-40, 40);
            if (Math.abs(x - c.getX()) < 8 && Math.abs(z - c.getZ()) < 8) continue;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            boolean ok = true;
            for (int dx = -2; dx <= w + 1 && ok; dx++) {
                for (int dz = -2; dz <= d + 1; dz++) {
                    int y = Layout.ground(world, x + dx, z + dz);
                    if (y == Integer.MIN_VALUE) {
                        ok = false;
                        break;
                    }
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
            if (!ok || maxY - minY > 1) continue;
            BlockPos origin = new BlockPos(x, maxY, z);
            boolean overlaps = false;
            for (Building b : village.getBuildings().values()) {
                for (int dx = -1; dx <= w && !overlaps; dx += w + 1) {
                    for (int dz = -1; dz <= d; dz += d + 1) {
                        if (b.containsPos(origin.add(dx, 1, dz))) {
                            overlaps = true;
                            break;
                        }
                    }
                }
            }
            for (House h : village.houses) {
                if (Math.abs(h.origin().getX() - x) < h.w() + w + 2 && Math.abs(h.origin().getZ() - z) < h.d() + d + 2) overlaps = true;
            }
            if (!overlaps) return origin;
        }
        return null;
    }

    // ---------- work ----------

    /** One builder second. Returns true when the villager actually worked (the caller drains energy). */
    public static boolean work(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world) || village == null) return false;
        // upkeep first: a damaged building, then the roads. The hall broken (blown up, burnt, its chests gone) cannot
        // wait for the house in hand: that is put aside and taken up again after (the village gave up on a blown-up hall)
        if (village.buildOrigin != null && !village.buildRepair && !PARKED.containsKey(village.getId()) && Repair.hallBroken(village)
                && !Buildings.base(village.buildType).equals("townhall")) {
            PARKED.put(village.getId(), new Object[]{village.buildType, village.buildOrigin, village.buildRot, village.buildW, village.buildD, village.buildF, village.buildStep});
            village.buildOrigin = null;
            village.markDirty();
        }
        if (village.buildOrigin == null && Repair.start(village)) Speech.say(v, "settlements.say.build.repair");
        // looking over the land for a site is heavy work: when nothing was found, again in half a minute
        boolean look = village.buildOrigin == null && world.getTime() >= NEXT_LOOK.getOrDefault(village.getId(), 0L);
        if (look) lastNoSite = null;
        if (village.buildOrigin == null && (!look || !plan(village, world))) {
            if (look && lastNoSite != null) NEXT_LOOK.put(village.getId(), world.getTime() + 600);
            if (look && lastNoSite != null && Tiers.isHome(lastNoSite)) Expansion.noRoom(village, world); // no plot for a home: the village is hemmed in
            // the house the homeless wait for is short of wood: he goes and gets it himself (picks up what lies about,
            // fells a tree), not paving the road while they sleep on the hall floor
            for (Plan.Status hs : Plan.pending(village)) {
                if (hs.goal().id().equals("housing") && hs.missPlanks() > 0) {
                    markShort(village, Needs.Need.PLANKS, true);
                    if (Scavenge.work(v, village, world, Needs.Need.PLANKS, false)) return true;
                }
            }
            // nothing to start on now (no site, short of material): the village's ground first (holes, craters, lumps
            // left standing), the roads meanwhile - buildings come first
            if (Ground.work(v, village, world)) return true;
            if (Repair.roadWork(v, village, world)) {
                Doing.set(v, "settlements.doing.road");
                return true;
            }
            Needs.Need blocker = Plan.firstBlocker(village);
            markShort(village, blocker, blocker == Needs.Need.PLANKS || blocker == Needs.Need.COBBLE);
            Life.askForHelp(v, village, world, blocker);
            if (blocker != Needs.Need.NONE && Scavenge.work(v, village, world, blocker, blocker == Needs.Need.COBBLE)) {
                if (Needs.get(v) != Needs.Need.FOOD) Needs.set(v, Needs.Need.NONE);
                Speech.say(v, "settlements.say.build.scavenge");
                return true;
            }
            if (Needs.get(v) != Needs.Need.FOOD) Needs.set(v, blocker);
            // says why nothing is going up: short of something, or no place found for what is wanted next
            if (blocker != Needs.Need.NONE) Doing.set(v, "settlements.doing.build.wait", Text.translatable("settlements.need.name." + blocker.name().toLowerCase(java.util.Locale.ROOT)));
            else if (lastNoSite != null) Doing.set(v, "settlements.doing.build.nosite", Text.translatable("settlements.building." + Buildings.base(lastNoSite)));
            else Doing.set(v, "settlements.doing.build.nothing");
            return false;
        }

        if (announceProject != null) {
            Speech.say(v, "settlements.say.build." + announceProject);
            announceProject = null;
        }
        if (Rebuild.active(village)) return Rebuild.work(v, village, world); // taking an old building down first
        List<Step> steps = plan(village);
        if (village.buildStep >= steps.size()) {
            finish(village);
            return false;
        }

        BlockPos origin = village.buildOrigin;
        if (!village.buildRepair) recheck(village, world, steps, origin);
        Doing.set(v, "settlements.doing.build", Text.translatable("settlements.building." + Buildings.base(village.buildType)),
                Math.min(100, village.buildStep * 100 / Math.max(1, steps.size())));
        if (Haul.fetch(v, village)) return true;
        // two builders do not crowd one spot: the first lays the blocks in order, the others take a stretch further on
        if (!village.buildRepair && !village.buildType.equals("mine") && !village.buildType.equals("wall") && !village.buildType.equals("palisade")
                && !lead(v, village, world)) {
            Doing.count("b.help");
            return helper(v, village, world, steps, origin);
        }
        boolean worked = false;
        int ops = 0, skips = 0;
        int checks = 0;
        // tired builders and struggling villages build slower; nobody watching, faster (the days the village is left
        // to itself must see a house up in a day or two, not ten)
        int budget = Prosperity.ops(v, village, opsPerSecond * (Bed.unseen(v, world) ? 2.0 : 1.0));
        if (budget == 0) { Doing.count("b.slow"); return true; } // working, just slowly
        // a man lays one block at a time: two going up in the same instant looked like a machine (what is left over is
        // kept for the next beat, up to a block or two)
        // two a beat at most where a player sees him (a player lays more); where nobody does, the pace of the days
        // nobody is about (it was one a second, and a house took ten days)
        int cap = Bed.unseen(v, world) ? 4 : 2;
        if (budget > cap) {
            v.settlementsPace = Math.min(2.0, v.settlementsPace + budget - cap);
            budget = cap;
        }
        while (ops < budget && skips < MAX_TRIVIAL_SKIPS && village.buildOrigin != null && village.buildStep < steps.size()) {
            if (ahead(village).remove(village.buildStep)) { // a helper laid this one already
                village.buildStep++;
                continue;
            }
            Step step = steps.get(village.buildStep);
            int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
            int[] rp = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], village.buildRot);
            BlockPos target = origin.add(rp[0], step.dy(), rp[1]);
            if (village.buildRepair && !Repair.missing(world, village, step, target)) { // repairs only put back what is gone
                village.buildStep++;
                if (++checks > 4000) break;
                continue;
            }

            // nothing to do at a spot that is already clear: no walk for it
            if ((step.kind() == Kind.CLEAR || step.kind() == Kind.DIG) && world.getBlockState(target).isAir()) {
                village.buildStep++;
                skips++;
                continue;
            }
            // the mine is dug from its entrance; everything else is built from within a man's reach of the block,
            // from scaffolding for the high parts
            boolean mine = village.buildType.equals("mine");
            boolean border = village.buildType.equals("palisade") || village.buildType.equals("wall");
            double bx = target.getX() + 0.5 - v.getX(), bz = target.getZ() + 0.5 - v.getZ();
            // within reach, and nothing in the way between his eyes and the spot (no building through walls)
            boolean far = mine ? v.squaredDistanceTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5) > 144 : !Scaffold.inReach(v, target)
                    || target.getY() > origin.getY() && target.getY() - origin.getY() < 3 && !Dig.sees(v, target); // (ceilings and roofs are laid from the scaffolding, over the edge; the ground and the floor at his feet he always gets at - a path cell walked round half a day)
            if (far && !mine && !border && target.getY() - origin.getY() >= 3) {
                Built site = new Built(village.buildType, origin, village.buildW, village.buildD, Math.max(1, village.buildF), village.buildRot);
                ON_SITE.put(v.getUuid(), world.getTime());
                Doing.count("b.climb");
                if (Scaffold.approach(v, village, world, target, site, planned(village, steps, origin))) return worked;
                far = bx * bx + bz * bz > Scaffold.REACH * Scaffold.REACH; // no way up: he does what he can from below
            }
            // stuck on one step for half a minute with the spot close by: the ground in the way is dug out, or else
            // the step is passed over - a building is never held up for days by one block
            long[] st = STEP_AT.computeIfAbsent(village.getId(), k -> new long[]{-1, 0});
            if (st[0] != village.buildStep) {
                st[0] = village.buildStep;
                st[1] = world.getTime();
            } else if (world.getTime() - st[1] > 600 && v.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) < 64) {
                // (it was two hours a step: a field whose last cell could not be reached stood four days unfinished)
                st[1] = world.getTime();
                // (the ground is not dug out where the step is the ground itself: a field's earth, a pond, a cut)
                boolean ground = step.kind() == Kind.FARMLAND || step.kind() == Kind.WATER || step.kind() == Kind.DIG || step.kind() == Kind.CLEAR;
                // (only something there to break: an empty spot is no obstacle, and breaking nothing held a house at one step for a day)
                if (!ground && !world.getBlockState(target).isAir() && (Layout.land(world.getBlockState(target)) || world.getBlockState(target).isReplaceable())) world.breakBlock(target, true, v);
                else LONG_ARM.put(village.getId(), village.buildStep); // laid from where he stands: never a hole left in the wall (steps passed over left houses gap-toothed)
                continue;
            } else if (world.getTime() - st[1] > 1200 && far) {
                // a minute and no way to it: he gets there somehow (a man climbs, squeezes through): set down beside it
                st[1] = world.getTime();
                LONG_ARM.put(village.getId(), village.buildStep); // laid from where he stands, not set down by it (a jump a player saw)
                continue;
            }
            if (LONG_ARM.getOrDefault(village.getId(), -1) == village.buildStep) far = false;
            // before walking off to it: what else of this course of the work is within his reach from here goes in
            // first, as a man lays the stretch of wall in front of him (he walked round the house for every block)
            if (far && !mine && !border && layNear(v, village, world, steps, origin, step.dy())) return true;
            if (far) {
                Move.to(v, (mine ? origin : target).getX() + 0.5, mine || border ? target.getY() : origin.getY(), (mine ? origin : target).getZ() + 0.5, 0.6);
                if (mine) Doing.count("b.walk.mine");
                else {
                    double hd = Math.sqrt(bx * bx + bz * bz), vd = target.getY() - v.getY();
                    Doing.count(hd > Scaffold.REACH ? (v.getNavigation().isIdle() ? "b.walk.far.nopath" : "b.walk.far")
                            : vd > 3.5 || vd < -3 ? "b.walk.height" : "b.walk.unseen." + step.kind().name().toLowerCase());
                }
                return worked;
            }

            if ((step.kind() == Kind.CLEAR || step.kind() == Kind.DIG) && !world.getBlockState(target).isReplaceable()) {
                Gear.toolFor(v, village, world.getBlockState(target)); // clearing the site with a spade or a pick, not by hand
            }
            // turned to the spot first, then the block goes in: not facing it, this beat he only turns (the head
            // leads, the body follows), and lays it on the next
            float want = (float) (net.minecraft.util.math.MathHelper.atan2(target.getZ() + 0.5 - v.getZ(), target.getX() + 0.5 - v.getX()) * (180 / Math.PI)) - 90f;
            // (one turn to it for each block: something kept turning him back, and he stood a day turning to one block)
            long turnKey = ((long) village.getId() << 32) | village.buildStep;
            if (Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(want - v.getBodyYaw())) > 120 && !FREE && TURNED.getOrDefault(v.getUuid(), -1L) != turnKey) { // (only a turn right round costs a beat: a half turn is made as the block goes in)
                TURNED.put(v.getUuid(), turnKey);
                v.getNavigation().stop();
                Gaze.at(v, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
                v.setBodyYaw(want);
                v.setHeadYaw(want);
                v.setYaw(want);
                Doing.count("b.turn");
                return true;
            }
            Gear.face(v, target);
            Result r = execute(world, village, step, target, village.buildRot);
            if (r == Result.WAIT) { // out of materials: go and get some instead of standing around
                Needs.Need need = needFor(step.kind());
                boolean any = switch (step.kind()) {
                    case FLOOR, ROOF, WALL, PILLAR, STONE, STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W, SEAT_W, SEAT_E -> true;
                    default -> false;
                };
                markShort(village, need, any);
                Life.askForHelp(v, village, world, need);
                Doing.set(v, "settlements.doing.build.short", Text.translatable("settlements.need.name." + need.name().toLowerCase()));
                // what he does about it is a plan of his own (see Planner): wait for the supplier and pave meanwhile,
                // get it himself, ask a friend of the village, tell the elder
                if (Planner.supply(v, village, world, need)) return true;
                Needs.set(v, need);
                return worked;
            }
            if (Needs.get(v) != Needs.Need.FOOD) Needs.set(v, Needs.Need.NONE);
            if (r == Result.ABORT) {
                village.buildOrigin = null;
                village.markDirty();
                return worked;
            }
            village.buildStep++;
            if (r == Result.PLACED) {
                SHORT.remove(village.getId());
                Doing.count("b.placed");
                v.settlementsCarry--;
                ops++;
                worked = true;
                // the block in hand, like a player laying it
                Item held = world.getBlockState(target).getBlock().asItem();
                if (held != Items.AIR) v.setStackInHand(v.getDominantHand(), new ItemStack(held));
                v.swingHand(v.getDominantHand());
                Anim.set(v, Anim.Act.BUILD, 30);
                BlockState placed = world.getBlockState(target);
                world.playSound(null, target, placed.getSoundGroup().getPlaceSound(), net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 0.9f);
            } else {
                skips++;
            }
        }
        if (village.buildOrigin != null && village.buildStep >= steps.size()) finish(village);
        village.markDirty();
        return worked;
    }

    /**
     * One block of the same course (the same height) further on in the plan, within his reach from where he stands,
     * laid now out of turn (and passed over when its turn comes, as a helper's are). True when one went in.
     */
    private static boolean layNear(VillagerEntityMCA v, Village village, ServerWorld world, List<Step> steps, BlockPos origin, int dy) {
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        Set<Integer> done = ahead(village);
        for (int j = village.buildStep + 1; j < Math.min(steps.size(), village.buildStep + 64); j++) {
            if (done.contains(j)) continue;
            Step sj = steps.get(j);
            if (sj.dy() != dy) {
                if (sj.dy() > dy) break; // the next course up waits for this one
                continue;
            }
            if (sj.kind() == Kind.CLEAR || sj.kind() == Kind.DIG) continue;
            int[] rp = Blueprints.rotate(sj.dx(), sj.dz(), fp[0], fp[1], village.buildRot);
            BlockPos t = origin.add(rp[0], sj.dy(), rp[1]);
            if (!Scaffold.inReach(v, t) || !Dig.sees(v, t)) continue;
            if (!world.getBlockState(t).isAir() && !world.getBlockState(t).isReplaceable()) continue;
            Gear.face(v, t);
            Result r = execute(world, village, sj, t, village.buildRot);
            if (r != Result.PLACED) continue;
            done.add(j);
            Doing.count("b.placed.near");
            Item held = world.getBlockState(t).getBlock().asItem();
            if (held != Items.AIR) v.setStackInHand(v.getDominantHand(), new ItemStack(held));
            v.swingHand(v.getDominantHand());
            Anim.set(v, Anim.Act.BUILD, 30);
            world.playSound(null, t, world.getBlockState(t).getSoundGroup().getPlaceSound(), net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 0.9f);
            village.markDirty();
            return true;
        }
        return false;
    }

    private record Lead(java.util.UUID id, long at) {
    }

    /**
     * A builder with people sleeping on the hall floor and their house in hand: no lingering at the well, no
     * gathering at the elder's, no rest day; from early morning to sundown at it. (A house took four days, the
     * builders at it a quarter of the day.)
     */
    public static boolean rush(VillagerEntityMCA v) {
        if (v.getProfession() != net.minecraft.village.VillagerProfession.CARTOGRAPHER) return false;
        Village village = v.getResidency().getHomeVillage().orElse(null);
        return village != null && village.buildOrigin != null && village.buildType != null && Tiers.isHome(village.buildType)
                && Housing.homeless(village) > 0;
    }

    private static final Map<Integer, Long> RECHECK = new HashMap<>();
    private static final Map<Integer, Integer> RECHECK_FIRST = new HashMap<>();

    /**
     * Once a minute, the work so far looked over: blocks laid that are gone again (blown up, broken, burnt) are laid
     * again before he goes on - he goes back to the first one missing, and passes over what still stands. (A house
     * blown to pieces stood at "90%" while he laid its roof on nothing.)
     */
    private static void recheck(Village village, ServerWorld world, List<Step> steps, BlockPos origin) {
        long now = world.getTime();
        if (now - RECHECK.getOrDefault(village.getId(), -10000L) < 1200) return;
        RECHECK.put(village.getId(), now);
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        int first = -1, gone = 0;
        java.util.List<Integer> standing = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(village.buildStep, steps.size()); i++) {
            Step st = steps.get(i);
            if (!structural(st.kind())) continue;
            int[] rp = Blueprints.rotate(st.dx(), st.dz(), fp[0], fp[1], village.buildRot);
            BlockPos t = origin.add(rp[0], st.dy(), rp[1]);
            if (!world.isChunkLoaded(t)) return;
            if (world.getBlockState(t).isAir() || !world.getFluidState(t).isEmpty()) {
                if (first < 0) first = i;
                gone++;
            } else if (first >= 0) standing.add(i);
        }
        if (first < 0 || gone < 8) return; // a few blocks: not worth going back over the whole house for (the repairs see to them later)
        if (RECHECK_FIRST.getOrDefault(village.getId(), -1) == first) return; // went back for it once already: that one will not stand (left as it is)
        RECHECK_FIRST.put(village.getId(), first);
        Set<Integer> done = ahead(village);
        done.addAll(standing);
        for (int i = first; i < village.buildStep; i++) if (!structural(steps.get(i).kind())) done.add(i); // the clearing and the frills: not again
        net.mca.MCA.LOGGER.info("[Settlements] {}: {} blocks of the {} gone, back to step {} of {}", village.getName(), gone, village.buildType, first, village.buildStep);
        village.buildStep = first;
        village.markDirty();
    }

    /** The bones of a building: what must stand for it to stand (not the clearing, nor the frills it can do without). */
    private static boolean structural(Kind k) {
        return switch (k) {
            case FLOOR, WALL, PILLAR, ROOF, DOOR, STONE, STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W, BEAM_X, BEAM_Z, ACCENT, THATCH, DAUB,
                 RSTAIR_S, RSTAIR_N, SLAB, LADDER, TPL -> true;
            default -> false;
        };
    }

    /** Builders up on the work (the last time): a step down off a wall onto the floor of the building hurts nobody. */
    private static final Map<java.util.UUID, Long> ON_SITE = new HashMap<>();

    public static boolean upOnWork(VillagerEntityMCA v) {
        return v.getWorld().getTime() - ON_SITE.getOrDefault(v.getUuid(), -1000L) < 200;
    }

    private static final Map<Integer, Object[]> PLANNED = new HashMap<>(); // village -> {origin, step from, positions}

    /** The spots the rest of the project still fills (not where to stand), looked up afresh every hundred steps. */
    private static Set<Long> planned(Village village, List<Step> steps, BlockPos origin) {
        Object[] c = PLANNED.get(village.getId());
        if (c != null && c[0].equals(origin) && village.buildStep >= (int) c[1] && village.buildStep - (int) c[1] < 100) {
            @SuppressWarnings("unchecked") Set<Long> s = (Set<Long>) c[2];
            return s;
        }
        Set<Long> s = new java.util.HashSet<>();
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        for (int i = village.buildStep; i < steps.size(); i++) {
            Step st = steps.get(i);
            if (st.kind() == Kind.CLEAR || st.kind() == Kind.DIG) continue;
            int[] rp = Blueprints.rotate(st.dx(), st.dz(), fp[0], fp[1], village.buildRot);
            s.add(origin.add(rp[0], st.dy(), rp[1]).asLong());
        }
        PLANNED.put(village.getId(), new Object[]{origin, village.buildStep, s});
        return s;
    }

    private static final Map<Integer, Lead> LEAD = new HashMap<>();
    private static final Map<Integer, Set<Integer>> AHEAD = new HashMap<>();
    private static final Map<Integer, String> AHEAD_SITE = new HashMap<>();

    /** Whether he is the one laying the blocks in order (the first to come, as long as he keeps at it). */
    private static boolean lead(VillagerEntityMCA v, Village village, ServerWorld world) {
        long now = world.getTime();
        Lead l = LEAD.get(village.getId());
        if (l == null || l.id().equals(v.getUuid()) || now - l.at() > 100) {
            LEAD.put(village.getId(), new Lead(v.getUuid(), now));
            return true;
        }
        return false;
    }

    /** The steps of this project the helpers have laid ahead of the leader. */
    private static Set<Integer> ahead(Village village) {
        String site = village.buildType + "@" + village.buildOrigin;
        if (!site.equals(AHEAD_SITE.get(village.getId()))) {
            AHEAD_SITE.put(village.getId(), site);
            AHEAD.put(village.getId(), new HashSet<>());
        }
        return AHEAD.computeIfAbsent(village.getId(), k -> new HashSet<>());
    }

    private static int group(Kind k) {
        return k == Kind.CLEAR || k == Kind.DIG ? 0 : fitting(k) ? 2 : 1;
    }

    /**
     * A helper's second: a block of the same kind of work as the leader's (the same course of the wall, the same
     * stage), a few paces away from him, within his own reach from the ground. Else he mends a road.
     */
    private static boolean helper(VillagerEntityMCA v, Village village, ServerWorld world, List<Step> steps, BlockPos origin) {
        int cur = village.buildStep;
        if (cur >= steps.size()) return false;
        Set<Integer> done = ahead(village);
        Step now = steps.get(cur);
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        int[] rc = Blueprints.rotate(now.dx(), now.dz(), fp[0], fp[1], village.buildRot);
        BlockPos leadAt = origin.add(rc[0], now.dy(), rc[1]);
        Lead l = LEAD.get(village.getId());
        net.minecraft.entity.Entity leader = l == null ? null : world.getEntity(l.id());
        boolean triedHigh = false;
        for (int i = cur + 4; i < Math.min(steps.size(), cur + 80); i++) {
            if (done.contains(i)) continue;
            Step s = steps.get(i);
            // (up the walls too, as the leader goes: kept to the ground, the second builder paved the road all
            // afternoon while the house went up a block at a time)
            if (s.kind() == Kind.DIG || group(s.kind()) != group(now.kind()) || s.dy() > now.dy() + 1) continue;
            int[] rp = Blueprints.rotate(s.dx(), s.dz(), fp[0], fp[1], village.buildRot);
            BlockPos t = origin.add(rp[0], s.dy(), rp[1]);
            int hx = t.getX() - leadAt.getX(), hz = t.getZ() - leadAt.getZ();
            if (hx * hx + hz * hz < 16) continue; // his elbow room
            if (leader != null && leader.squaredDistanceTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5) < 9) continue;
            if (s.kind() == Kind.CLEAR && world.getBlockState(t).isAir()) {
                done.add(i);
                continue;
            }
            double bx = t.getX() + 0.5 - v.getX(), bz = t.getZ() + 0.5 - v.getZ();
            if (!Scaffold.inReach(v, t) || t.getY() > origin.getY() && !Dig.sees(v, t)) {
                if (s.dy() >= 3) {
                    if (triedHigh) continue; // (one look for a way up a second: each costs a search for a path)
                    triedHigh = true;
                    Built site = new Built(village.buildType, origin, village.buildW, village.buildD, Math.max(1, village.buildF), village.buildRot);
                    ON_SITE.put(v.getUuid(), world.getTime());
                    if (Scaffold.approach(v, village, world, t, site, planned(village, steps, origin))) return true;
                    continue; // no way up to this one
                }
                if (bx * bx + bz * bz < 4) continue; // right by it and still cannot get at it: another one
                Move.to(v, t.getX() + 0.5, origin.getY(), t.getZ() + 0.5, 0.6);
                return true;
            }
            Result r = execute(world, village, s, t, village.buildRot);
            if (r == Result.WAIT || r == Result.ABORT) return false;
            done.add(i);
            if (r == Result.PLACED) {
                Doing.count("b.help.placed");
                Gear.face(v, t);
                Item held = world.getBlockState(t).getBlock().asItem();
                if (held != Items.AIR) v.setStackInHand(v.getDominantHand(), new ItemStack(held));
                v.swingHand(v.getDominantHand());
                Anim.set(v, Anim.Act.BUILD, 30);
                village.markDirty();
            }
            return true;
        }
        return Repair.roadWork(v, village, world);
    }

    /** About how much of this the current project still needs (one per block left to lay that takes it). */
    public static int stillNeeded(Village village, Needs.Need need) {
        if (village.buildOrigin == null) return 0;
        List<Step> steps = plan(village);
        int n = 0;
        for (int i = village.buildStep; i < steps.size(); i++) {
            Kind k = steps.get(i).kind();
            if (k == Kind.CLEAR || k == Kind.DIG) continue;
            if (needFor(k) == need) n++;
        }
        return n;
    }

    /** The name of the village's builder, for the order book. */
    public static String builderName(Village village, ServerWorld world) {
        for (VillagerEntityMCA r : village.getResidents(world)) if (r.getProfession() == net.minecraft.village.VillagerProfession.CARTOGRAPHER) return r.getName().getString();
        return "";
    }

    private static Needs.Need needFor(Kind kind) {
        return switch (kind) {
            case BED_FOOT -> Needs.Need.WOOL;
            case ANVIL, SMITHING -> Needs.Need.IRON;
            case FURNACE, STAIRS_N, STAIRS_S, STONE, SMOKER -> Needs.Need.COBBLE;
            default -> Needs.Need.PLANKS;
        };
    }

    /**
     * Every village starts with its town hall, where the elder keeps office: the founders raise it the moment the
     * village is first seen. A project already under way is put aside and resumed afterwards.
     */
    public static boolean foundHall(ServerWorld world, Village village) {
        if (village.hall != null) return false;
        String t = village.buildType;
        BlockPos o = village.buildOrigin;
        int st = village.buildStep, w = village.buildW, d = village.buildD, f = village.buildF, r = village.buildRot;
        // close by the bell first (the inner rings), then a site the founders level among the houses for the plain hall,
        // and only then further out: a big hall up the mountain forty blocks above the houses is no shelter at night
        NEAR_ONLY = true;
        boolean started;
        try {
            started = startRing(village, world, "townhall", 0, 0, 0, 0, 3);
        } finally {
            NEAR_ONLY = false;
        }
        if (!started) started = roughSite(village, world);
        if (!started) started = startRing(village, world, "townhall", 0, 0, 0, 0, 3);
        if (!started) {
            Layout.Slot hs = Layout.center(world, village, "townhall");
            started = hs != null && startAt(village, "townhall", 0, 0, 0, hs);
        }
        if (!started) started = start(village, world, "townhall", Blueprints.HALL_W, Blueprints.HALL_D, 0);
        if (!started) return false;
        announceProject = null;
        List<Step> steps = plan(village);
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        BlockPos origin = village.buildOrigin;
        int rot = village.buildRot;
        FREE = true;
        try {
            for (Step step : steps) {
                int[] rp = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], rot);
                execute(world, village, step, origin.add(rp[0], step.dy(), rp[1]), rot);
            }
        } finally {
            FREE = false;
        }
        village.buildStep = steps.size();
        finish(village);
        // the stocks stand on the square from the first day, as the village's own: not put up the moment somebody is caught
        try {
            Justice.pillory(world, village);
        } catch (RuntimeException ignored) {
        }
        // tell everyone around where the hall stands
        BlockPos hp = village.hallPos != null ? village.hallPos : origin;
        for (var pl : world.getPlayers()) {
            if (pl.squaredDistanceTo(hp.getX(), hp.getY(), hp.getZ()) < 256 * 256) {
                pl.sendMessage(Text.translatable("settlements.hall.founded", village.getName(), hp.getX(), hp.getY(), hp.getZ())
                        .formatted(net.minecraft.util.Formatting.GOLD), false);
            }
        }
        if (o != null && t != null && !Buildings.base(t).equals("townhall")) {
            village.buildType = t;
            village.buildOrigin = o;
            village.buildStep = st;
            village.buildW = w;
            village.buildD = d;
            village.buildF = f;
            village.buildRot = r;
        }
        village.markDirty();
        return true;
    }

    /**
     * Raises a whole building at once, as part of a settlement that comes with the world: materials come with it.
     * A project already under way is put aside and resumed afterwards. False if no site was found.
     */
    public static boolean raiseNow(ServerWorld world, Village village, String type, int w, int d, int f, int minRing, int maxRing) {
        String t = village.buildType;
        BlockPos o = village.buildOrigin;
        int st = village.buildStep, ow = village.buildW, od = village.buildD, of = village.buildF, orot = village.buildRot;
        boolean started;
        if (type.equals("well")) started = startCenter(village, world);
        else if (type.equals("hallup")) started = startHallUp(village);
        else started = startRing(village, world, type, w, d, f, minRing, maxRing);
        if (!started) return false;
        announceProject = null;
        List<Step> steps = plan(village);
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        BlockPos origin = village.buildOrigin;
        int rot = village.buildRot;
        FREE = true;
        try {
            for (Step step : steps) {
                int[] rp = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], rot);
                execute(world, village, step, origin.add(rp[0], step.dy(), rp[1]), rot);
            }
        } finally {
            FREE = false;
        }
        village.buildStep = steps.size();
        finish(village);
        if (o != null && t != null) {
            village.buildType = t;
            village.buildOrigin = o;
            village.buildStep = st;
            village.buildW = ow;
            village.buildD = od;
            village.buildF = of;
            village.buildRot = orot;
        }
        village.markDirty();
        return true;
    }

    /** All building types that can be placed for a look, with their default plan parameters {w, d, floors}. */
    public static final java.util.LinkedHashMap<String, int[]> SHOWCASE = new java.util.LinkedHashMap<>();

    static {
        String[] types = {"townhall", "cruck", "wealden", "rowhouse", "hut", "house", "longhouse", "porch", "twin", "farmhouse", "cottage", "tallhouse", "annex", "manor", "mansion", "storage", "granary", "sawmill", "charcoal", "mason", "mill",
                "smithy", "armory", "tavern", "barracks", "jail", "market", "chapel", "well", "board", "farm", "pen", "tower", "shop"};
        java.util.List<String> all = new java.util.ArrayList<>(java.util.List.of(types));
        for (String[] d : Buildings.designs()) for (String t : d) if (!all.contains(t)) all.add(t);
        for (String t : all) {
            SHOWCASE.put(t, switch (t) {
                case "hut" -> new int[]{5, 5, 1};
                case "house" -> new int[]{8, 8, 1};
                case "annex" -> new int[]{7, 7, 1};
                case "mansion" -> new int[]{11, 9, 2};
                case "manor" -> new int[]{9, 8, 2};
                case "tallhouse" -> new int[]{5, 6, 3};
                default -> new int[]{0, 0, 1};
            });
        }
    }

    /**
     * Places a building at once for a look (operators' test command): free materials, the land's palette, nothing
     * entered in any village's books. The site is cleared above the floor and filled below it.
     */
    public static void placeForLook(ServerWorld world, String type, BlockPos origin, int rot) {
        int[] dm = SHOWCASE.getOrDefault(type, new int[]{0, 0, 1});
        List<Step> steps = stepsFor(type, dm[0], dm[1], dm[2]);
        int[] fp = Blueprints.footprint(type, dm[0], dm[1]);
        int fw = (rot & 1) == 0 ? fp[0] : fp[1], fd = (rot & 1) == 0 ? fp[1] : fp[0];
        for (int x = origin.getX() - 2; x <= origin.getX() + fw + 1; x++) {
            for (int z = origin.getZ() - 2; z <= origin.getZ() + fd + 1; z++) {
                for (int y = origin.getY(); y <= origin.getY() + 16; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!world.getBlockState(p).isAir() && world.getBlockState(p).getHardness(world, p) >= 0) world.setBlockState(p, Blocks.AIR.getDefaultState());
                }
                for (int y = origin.getY() - 3; y < origin.getY(); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (world.getBlockState(p).isReplaceable()) world.setBlockState(p, y == origin.getY() - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState());
                }
            }
        }
        Village scratch = new Village(-1, world); // a throwaway village: bookkeeping goes nowhere
        scratch.buildType = type; // a template's blocks are looked up by the building under way (without it only the earth pad went down)
        String biome = world.getBiome(origin).getKey().map(k -> k.getValue().getPath()).orElse("plains");
        Palette.OVERRIDE = Palette.forBiome(biome);
        FREE = true;
        int t = Tiers.tierOf(type);
        MAT_TIER = t >= 0 ? t : Tiers.ORDINARY; // each design in the materials of its station
        try {
            for (Step step : steps) {
                int[] rp = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], rot);
                execute(world, scratch, step, origin.add(rp[0], step.dy(), rp[1]), rot);
            }
        } finally {
            FREE = false;
            MAT_TIER = -1;
            Palette.OVERRIDE = null;
        }
    }

    public static int[] footprintOf(String type, int rot) {
        int[] dm = SHOWCASE.getOrDefault(type, new int[]{0, 0, 1});
        int[] fp = Blueprints.footprint(type, dm[0], dm[1]);
        return (rot & 1) == 0 ? fp : new int[]{fp[1], fp[0]};
    }

    private static boolean startHallUp(Village village) {
        House h = village.hall;
        if (h == null || h.floors() >= 3) return false;
        village.buildType = "hallup";
        village.buildOrigin = h.origin();
        village.buildW = h.w();
        village.buildD = h.d();
        village.buildF = h.floors();
        village.buildRot = h.rot();
        village.buildStep = 0;
        return true;
    }

    private static boolean startCenter(Village village, ServerWorld world) {
        Layout.Slot slot = Layout.center(world, village, "well");
        if (slot == null) slot = Layout.find(world, village, "well", 0, 0, 0, 0);
        return slot != null && startAt(village, Tiers.design("well", Prosperity.tier(village), world.random, village), 0, 0, 0, slot);
    }

    /**
     * The nearest spot to the village center where the ground is no more than four blocks uneven and dry: the hall's
     * footprint is filled up with earth and cleared above, so the hall stands level.
     */
    private static boolean roughSite(Village village, ServerWorld world) {
        int[] fp = Blueprints.footprint("townhall", 0, 0);
        BlockPos c = new BlockPos(village.getCenter());
        for (int r = 0; r <= 40; r += 4) {
            for (int a = 0; a < Math.max(1, r * 2); a++) {
                double ang = a * Math.PI * 2 / Math.max(1, r * 2);
                int x = c.getX() + (int) (Math.cos(ang) * r) - fp[0] / 2, z = c.getZ() + (int) (Math.sin(ang) * r) - fp[1] / 2;
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                boolean ok = true;
                List<Integer> heights = new java.util.ArrayList<>();
                for (int dx = -1; dx <= fp[0] && ok; dx++) {
                    for (int dz = -1; dz <= fp[1]; dz++) {
                        if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) {
                            ok = false;
                            break;
                        }
                        int y = Layout.ground(world, x + dx, z + dz);
                        if (y == Integer.MIN_VALUE) {
                            ok = false;
                            break;
                        }
                        min = Math.min(min, y);
                        max = Math.max(max, y);
                        heights.add(y);
                    }
                }
                if (!ok || max - min > 4) continue;
                java.util.Collections.sort(heights);
                int oy = heights.get(heights.size() / 2);
                boolean taken = false;
                for (House h : village.houses) {
                    if (Math.abs(h.origin().getX() - x) < h.w() + fp[0] + 2 && Math.abs(h.origin().getZ() - z) < h.d() + fp[1] + 2) taken = true;
                }
                if (taken || Layout.clash(world, village, x - 1, z - 1, fp[0] + 2, fp[1] + 2) != null) continue; // (nor over a field, a yard or a house the village knows)
                // level it: earth up to the floor, nothing natural above it
                for (int dx = -1; dx <= fp[0]; dx++) {
                    for (int dz = -1; dz <= fp[1]; dz++) {
                        for (int y = min - 1; y < oy; y++) {
                            BlockPos q = new BlockPos(x + dx, y, z + dz);
                            if (world.getBlockState(q).isReplaceable() || world.getBlockState(q).isAir())
                                world.setBlockState(q, y == oy - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState());
                        }
                        for (int y = oy; y <= oy + 10; y++) {
                            BlockPos q = new BlockPos(x + dx, y, z + dz);
                            BlockState s = world.getBlockState(q);
                            if (!s.isAir() && s.getHardness(world, q) >= 0 && !(s.getBlock() instanceof BlockWithEntity)) world.setBlockState(q, Blocks.AIR.getDefaultState());
                        }
                    }
                }
                levelSite(world, village, new BlockPos(x, oy, z), fp[0], fp[1]); // the slope round it, the land's own earth
                village.buildType = "townhall";
                village.buildOrigin = new BlockPos(x, oy, z);
                village.buildRot = 0;
                village.buildW = 0;
                village.buildD = 0;
                village.buildF = 0;
                village.buildStep = 0;
                village.plots.add(new int[]{x, z, fp[0], fp[1]});
                return true;
            }
        }
        return false;
    }

    /** Places a whole plan at once, paying from the treasury. Used for the settlers' starter house. */
    public static boolean buildInstantly(ServerWorld world, Village payer, BlockPos origin, List<Step> steps) {
        if (planks(payer) < 120 || stockWool(payer) < 1) return false;
        for (Step step : steps) {
            Result r = execute(world, payer, step, origin.add(step.dx(), step.dy(), step.dz()), 0);
            if (r == Result.ABORT) return false;
        }
        return true;
    }

    private static Direction turn(Direction base, int rot) {
        return Direction.fromHorizontal(base.getHorizontal() + rot);
    }

    /** Sentinel: a template frill left out (see execute). */
    private static final BlockState SKIP = Blocks.STRUCTURE_VOID.getDefaultState();

    /**
     * What a template block costs the village, the way a player would have to come by it: timber from planks or
     * logs, stonework from cobble, iron work from iron, cloth from wool; earth and plants cost nothing. Glass, lamps
     * and frills a poor village does without (a lattice for glass, a torch for a lantern). Null when the village
     * cannot pay for a structural block yet; SKIP for a frill it cannot afford.
     */
    private static BlockState pay(Village village, BlockState s, int tier, boolean fit, Palette pal) {
        Block b = s.getBlock();
        Item item = b.asItem();
        if (s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND) || b == Blocks.GRAVEL || b == Blocks.DIRT_PATH || b instanceof PlantBlock
                || s.isIn(BlockTags.LEAVES) || b == Blocks.WATER || b == Blocks.SNOW || b == Blocks.SNOW_BLOCK) return s;
        if (b instanceof PaneBlock && b != Blocks.IRON_BARS || b instanceof AbstractGlassBlock) {
            if (tier != Tiers.POOR && (take(village, GLASS, 1) || take(village, x -> x.isOf(Items.SAND) || x.isOf(Items.RED_SAND), 1))) return s;
            if (b instanceof PaneBlock && takePlanks(village, 1)) return pal.fence().getDefaultState();
            return fit ? SKIP : null;
        }
        if (b instanceof LanternBlock) {
            if (tier > Tiers.POOR && (take(village, x -> x.isOf(Items.LANTERN), 1) || take(village, x -> x.isOf(Items.IRON_INGOT), 1) || take(village, x -> x.isOf(Items.IRON_NUGGET), 8))) return s;
            if (!s.get(LanternBlock.HANGING) && take(village, x -> x.isOf(Items.TORCH), 1)) return Blocks.TORCH.getDefaultState();
            return SKIP;
        }
        if (b instanceof TorchBlock) return take(village, x -> x.isOf(Items.TORCH), 1) ? s : SKIP;
        if (item != Items.AIR && take(village, x -> x.isOf(item), 1)) return s; // the very thing, from the stores
        if (s.isIn(BlockTags.LOGS)) return take(village, LOG, 1) || takePlanks(village, 4) ? s : null;
        if (s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.WOODEN_STAIRS) || s.isIn(BlockTags.WOODEN_SLABS) || s.isIn(BlockTags.WOODEN_FENCES)
                || s.isIn(BlockTags.FENCE_GATES) || s.isIn(BlockTags.WOODEN_TRAPDOORS) || s.isIn(BlockTags.WOODEN_PRESSURE_PLATES)
                || s.isIn(BlockTags.WOODEN_BUTTONS) || b instanceof LadderBlock || b instanceof AbstractSignBlock) return takePlanks(village, 1) ? s : fit ? SKIP : null;
        if (s.isIn(BlockTags.WOODEN_DOORS)) return takePlanks(village, 2) ? s : null;
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b == Blocks.CRAFTING_TABLE || b instanceof LecternBlock || b == Blocks.BOOKSHELF
                || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.FLETCHING_TABLE || b == Blocks.SMITHING_TABLE || b == Blocks.LOOM || b instanceof ComposterBlock)
            return takePlanks(village, b instanceof ChestBlock || b instanceof BarrelBlock ? 8 : 4) ? s : SKIP;
        // a mill's sails: cloth if there is wool, boards if not (bare spars stood in the air), whatever the village's station
        if (s.isIn(BlockTags.WOOL) && !fit) return take(village, WOOL, 1) ? s : takePlanks(village, 1) ? pal.planks().getDefaultState() : null;
        if (s.isIn(BlockTags.WOOL) || s.isIn(BlockTags.WOOL_CARPETS) || b instanceof BedBlock || s.isIn(BlockTags.BANNERS))
            return b instanceof BedBlock ? (takeBedding(village, 1) ? s : fit ? SKIP : null)
                    : tier > Tiers.POOR ? (take(village, WOOL, 1) ? s : fit ? SKIP : null) : SKIP;
        if (b == Blocks.IRON_BARS || b instanceof ChainBlock || b instanceof AbstractCauldronBlock || b instanceof AnvilBlock
                || b == Blocks.BLAST_FURNACE || b == Blocks.HOPPER) return take(village, x -> x.isOf(Items.IRON_INGOT), 1) ? s : SKIP;
        if (b instanceof AbstractFurnaceBlock || b instanceof StonecutterBlock || b instanceof GrindstoneBlock) return take(village, COBBLE, 4) ? s : SKIP;
        // the desert builds of sandstone, paid in sandstone or its sand (there is no cobble to be had there); adobe of earth
        String bid = Registries.BLOCK.getId(b).getPath();
        if (bid.contains("terracotta")) // the badlands' clay, in what they have: their terracotta, their red sand
            return take(village, x -> Registries.ITEM.getId(x.getItem()).getPath().contains("terracotta"), 1)
                    || take(village, x -> x.isOf(Items.RED_SAND) || x.isOf(Items.CLAY_BALL), 2) || take(village, COBBLE, 1) ? s : fit ? SKIP : null;
        if (bid.contains("sandstone"))
            return take(village, x -> x.isOf(Items.SANDSTONE) || x.isOf(Items.CUT_SANDSTONE) || x.isOf(Items.SMOOTH_SANDSTONE) || x.isOf(Items.RED_SANDSTONE), 1)
                    || take(village, x -> x.isOf(Items.SAND) || x.isOf(Items.RED_SAND), 2) || take(village, COBBLE, 1) ? s : fit ? SKIP : null;
        if (b == Blocks.MUD_BRICKS || b == Blocks.PACKED_MUD || b == Blocks.MUD_BRICK_STAIRS || b == Blocks.MUD_BRICK_SLAB)
            return take(village, x -> x.isOf(Items.MUD_BRICKS) || x.isOf(Items.PACKED_MUD) || x.isOf(Items.DIRT) || x.isOf(Items.MUD), 1)
                    || take(village, COBBLE, 1) ? s : fit ? SKIP : null;
        if (s.isIn(BlockTags.PICKAXE_MINEABLE) && !s.isIn(BlockTags.NEEDS_IRON_TOOL) && !s.isIn(BlockTags.NEEDS_DIAMOND_TOOL))
            return take(village, COBBLE, 1) ? s : fit ? SKIP : null; // stonework: dressed from cobble
        return fit ? SKIP : take(village, COBBLE, 1) ? s : null;
    }

    /** Takes items from the treasury for other systems (lamp posts on the roads). */
    public static boolean takeItem(Village v, Predicate<ItemStack> p, int amount) {
        return take(v, p, amount);
    }

    enum Result { PLACED, SKIPPED, WAIT, ABORT }

    /** The villager whose pockets catch the drops while he digs (the miner); null puts them in the stores. */
    static VillagerEntityMCA COLLECTOR = null;

    /** What the builders of a village are short of right now, so idle hands can go and gather it. */
    public record Shortage(Needs.Need need, boolean any, long since) {
    }

    private static final java.util.Map<Integer, Shortage> SHORT = new java.util.HashMap<>();

    public static Shortage shortage(Village village) {
        Shortage s = SHORT.get(village.getId());
        ServerWorld w = village.getWorld();
        if (s == null || w == null || w.getTime() - s.since() > 1200) return null;
        return s;
    }

    private static void markShort(Village village, Needs.Need need, boolean any) {
        ServerWorld w = village.getWorld();
        if (w != null && need != Needs.Need.NONE) SHORT.put(village.getId(), new Shortage(need, any, w.getTime()));
    }

    /**
     * The days nobody was about (see Ledger): up to {@code steps} of the work laid at once, paid from the stores as the
     * builder would have paid, and the next project begun when one is done. Returns how many steps were laid.
     */
    public static int catchUp(ServerWorld world, Village village, int steps) {
        int laid = 0;
        for (int projects = 0; laid < steps && projects < 6; projects++) {
            if (village.buildOrigin == null && !plan(village, world)) break;
            if (village.buildOrigin == null || Rebuild.active(village)) break;
            List<Step> st = plan(village);
            BlockPos origin = village.buildOrigin;
            int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
            while (laid < steps && village.buildStep < st.size()) {
                Step step = st.get(village.buildStep);
                int[] rp = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], village.buildRot);
                Result r = execute(world, village, step, origin.add(rp[0], step.dy(), rp[1]), village.buildRot);
                if (r == Result.WAIT || r == Result.ABORT) return laid; // out of material: the rest waits for the builders
                village.buildStep++;
                laid++;
            }
            if (village.buildStep < st.size()) break;
            finish(village);
        }
        village.markDirty();
        return laid;
    }

    private static final Map<Integer, Object[]> PARKED = new HashMap<>();
    private static final Map<java.util.UUID, Long> TURNED = new HashMap<>(); // builder -> the step he last turned to // village -> the project put aside for the hall's repair

    private static void finish(Village village) {
        BlockPos o = village.buildOrigin;
        if (o == null) return;
        if (village.getWorld() != null) {
            Scaffold.takeDown(village, village.getWorld()); // the building stands: down with the scaffolding
            // and every bit of scaffolding about the site, whoever put it up and when (the list of it is not kept over a
            // restart of the world, and a house stood with its scaffold round it for good)
            int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
            int w = (village.buildRot & 1) == 0 ? fp[0] : fp[1], d = (village.buildRot & 1) == 0 ? fp[1] : fp[0];
            Scaffold.clearAround(village, village.getWorld(), o, w, d);
        }
        String type = village.buildType;
        int rot = village.buildRot;
        if (village.buildRepair) { // a repair changes nothing in the books
            village.buildRepair = false;
            Life.news(village, "repaired", Text.translatable("settlements.building." + Buildings.base(type)));
            village.buildOrigin = null;
            village.buildStep = 0;
            Object[] parked = PARKED.remove(village.getId());
            if (parked != null) { // and back to the building that was put aside for it, from where it was left
                village.buildType = (String) parked[0];
                village.buildOrigin = (BlockPos) parked[1];
                village.buildRot = (int) parked[2];
                village.buildW = (int) parked[3];
                village.buildD = (int) parked[4];
                village.buildF = (int) parked[5];
                village.buildStep = (int) parked[6];
            }
            village.markDirty();
            return;
        }
        // the village's register of buildings, for use and repairs
        switch (type) {
            case "upgrade", "hallup" -> {
                for (int i = 0; i < village.built.size(); i++) {
                    Built b = village.built.get(i);
                    if (b.origin().equals(o)) village.built.set(i, b.withFloors(b.f() + 1));
                }
            }
            case "mine", "wall", "palisade" -> {
            }
            default -> village.built.add(new Built(type, o, village.buildW, village.buildD, Math.max(1, village.buildF), rot));
        }
        switch (Buildings.base(type)) {
            case "house" -> village.houses.add(new House(o, village.buildW, village.buildD, village.buildF, rot));
            case "manor" -> village.houses.add(new House(o, 9, 8, 2, rot));
            case "mansion" -> village.houses.add(new House(o, 11, 9, 2, rot));
            case "upgrade" -> {
                for (int i = 0; i < village.houses.size(); i++) {
                    House h = village.houses.get(i);
                    if (h.origin().equals(o)) village.houses.set(i, h.withFloors(h.floors() + 1));
                }
            }
            case "storage" -> village.storageBuilt = true;
            case "smithy" -> {
                village.smithyBuilt = true;
                registerSmithy(village);
            }
            case "armory" -> village.armoryBuilt = true;
            case "mine" -> {
                village.mineBuilt = true;
                village.mineOrigin = o;
                village.mineLevel = 1;
                village.mineStep = 0;
            }
            case "farm" -> village.farms++;
            case "tower" -> {
                village.towers++;
                int[][] tp = Tiers.towerPoints(type);
                int[] tfp = Blueprints.footprint(type, 0, 0);
                int[] tr = Blueprints.rotate(tp[0][0], tp[0][2], tfp[0], tfp[1], rot);
                village.watchPoints.add(o.add(tr[0], tp[0][1], tr[1]));
                int[] lr = Blueprints.rotate(tp[1][0], tp[1][2], tfp[0], tfp[1], rot);
                village.towerLadders.add(o.add(lr[0], tp[1][1], lr[1]));
            }
            case "tavern" -> registerTavern(village);
            case "townhall" -> {
                // the new designs are built whole: no storeys are added to them later
                int[] hfp = Blueprints.footprint(type, 0, 0);
                village.hall = type.equals("townhall") ? new House(o, Blueprints.HALL_W, Blueprints.HALL_D, 1, rot) : new House(o, hfp[0], hfp[1], 3, rot);
            }
            case "hallup" -> {
                if (village.hall != null) village.hall = village.hall.withFloors(village.hall.floors() + 1);
            }
            case "pen" -> {
                int[] pr = Blueprints.rotate(Blueprints.PEN / 2, Blueprints.PEN / 2, Blueprints.PEN, Blueprints.PEN, rot);
                village.penCenter = o.add(pr[0], 0, pr[1]);
            }
            case "wall" -> village.wallBuilt = true;
            case "palisade" -> village.palisadeR = village.buildW;
            default -> {
            }
        }
        ServerWorld world = village.getWorld();
        // the road to it is not laid by magic: the builders pave it a cell at a time between projects (Repair.roadWork)
        if (!type.equals("wall") || village.wallBuilt) {
            Life.news(village, "built", Text.translatable("settlements.building." + Buildings.base(type)));
            int[] mfp = Blueprints.footprint(type, village.buildW, village.buildD);
            int[] mid = Blueprints.rotate(mfp[0] / 2, mfp[1] / 2, mfp[0], mfp[1], rot);
            Mood.event(village, Mood.Kind.JOY, o.add(mid[0], 0, mid[1])); // they come and have a look at it
        }
        if (world != null) {
            int[] bfp = Blueprints.footprint(type, village.buildW, village.buildD);
            for (Step s : plan(village)) {
                boolean tplBed = s.kind() == Kind.TPL_FIT && village.buildType != null && Templates.of(village.buildType) != null
                        && Templates.of(village.buildType).palette()[s.face()].getBlock() instanceof BedBlock
                        && Templates.of(village.buildType).palette()[s.face()].get(BedBlock.PART) == net.minecraft.block.enums.BedPart.FOOT;
                if (s.kind() != Kind.BED_FOOT && !tplBed) continue;
                int[] r = Blueprints.rotate(s.dx(), s.dz(), bfp[0], bfp[1], rot);
                net.mca.server.world.data.VillageManager.get(world).reportBuilding(o.add(r[0], s.dy(), r[1]));
                break;
            }
            // the village's bounds take in the new building, wherever it stands (so the village grows outward with it)
            if (!java.util.Set.of("farm", "pen", "well", "wall", "palisade", "mine", "board").contains(Buildings.base(type))) {
                int[] mid = Blueprints.rotate(bfp[0] / 2, bfp[1] / 2, bfp[0], bfp[1], rot);
                net.mca.server.world.data.VillageManager.get(world).reportBuilding(o.add(mid[0], 1, mid[1]));
            }
        }
        village.buildOrigin = null;
        village.buildStep = 0;
        village.markDirty();
    }

    private static void registerSmithy(Village village) {
        BlockPos o = village.buildOrigin;
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        Templates.Template t = Templates.of(village.buildType);
        BlockPos table = null;
        for (Step step : plan(village)) {
            int[] r = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], village.buildRot);
            BlockPos p = o.add(r[0], step.dy(), r[1]);
            if (step.kind() == Kind.FURNACE) village.smithyFurnace = p;
            else if (step.kind() == Kind.ANVIL) village.smithyAnvil = p;
            else if (t != null && step.kind() == Kind.TPL_FIT) { // a MineColonies smithy: its forge and anvil from the template
                Block b = t.palette()[step.face()].getBlock();
                if (b instanceof AbstractFurnaceBlock && village.smithyFurnace == null) village.smithyFurnace = p;
                else if (b instanceof AnvilBlock) village.smithyAnvil = p;
                else if (b == Blocks.SMITHING_TABLE) table = p;
            }
        }
        if (village.smithyAnvil == null) village.smithyAnvil = table;
        if (village.smithyFurnace == null) village.smithyFurnace = village.smithyAnvil; // the forge is where he works
    }

    /** Reads the finished tavern's working blocks back out of its plan. */
    private static void registerTavern(Village village) {
        BlockPos o = village.buildOrigin;
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        BlockPos smoker = null, barrel = null, craft = null;
        List<BlockPos> seats = new java.util.ArrayList<>();
        for (Step step : plan(village)) {
            int[] r = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], village.buildRot);
            BlockPos p = o.add(r[0], step.dy(), r[1]);
            switch (step.kind()) {
                case SMOKER -> smoker = p;
                case BARREL -> barrel = p;
                case CRAFT -> craft = p;
                case SEAT_W, SEAT_E -> seats.add(p);
                case TPL_FIT, TPL -> { // a MineColonies tavern: its stove, barrel, table and benches from the template
                    Templates.Template t = Templates.of(village.buildType);
                    if (t == null) break;
                    BlockState bs = t.palette()[step.face()];
                    Block b = bs.getBlock();
                    if (b instanceof AbstractFurnaceBlock && smoker == null) smoker = p;
                    else if (b instanceof BarrelBlock && barrel == null) barrel = p;
                    else if (b == Blocks.CRAFTING_TABLE && craft == null) craft = p;
                    else if (b instanceof StairsBlock && step.dy() >= 0 && bs.get(StairsBlock.HALF) == net.minecraft.block.enums.BlockHalf.BOTTOM
                            && step.dy() < 3 && seats.size() < 12) seats.add(p);
                }
                default -> {
                }
            }
        }
        if (Templates.of(village.buildType) != null) { // what a template lacks is made up from what it has
            if (barrel == null) barrel = craft != null ? craft : smoker;
            if (craft == null) craft = barrel;
            if (smoker == null) smoker = craft;
        }
        if (smoker != null && barrel != null && craft != null) village.tavern = new Tavern(smoker, barrel, craft, seats);
    }

    static Result execute(ServerWorld world, Village village, Step step, BlockPos target, int rot) {
        BlockState old = world.getBlockState(target);
        if (!old.isAir() && old.getHardness(world, target) < 0) return Result.SKIPPED; // bedrock and friends

        if (step.kind() == Kind.DIG || step.kind() == Kind.CLEAR) {
            if (old.isAir()) return Result.SKIPPED;
            if (step.kind() == Kind.DIG) {
                for (Direction dir : Direction.values()) {
                    if (!world.getFluidState(target.offset(dir)).isEmpty()) return Result.ABORT; // water or lava, stop mining
                }
                if (old.getBlock() instanceof BlockWithEntity) return Result.SKIPPED;
                Move.clearFalling(world, target, drop -> {
                    if (COLLECTOR != null) {
                        ItemStack rest = COLLECTOR.getInventory().addStack(drop);
                        if (!rest.isEmpty()) Workforce.addToTreasury(village, rest);
                    } else Workforce.addToTreasury(village, drop);
                });
                for (ItemStack drop : Block.getDroppedStacks(old, world, target, world.getBlockEntity(target))) {
                    if (COLLECTOR != null) {
                        ItemStack rest = COLLECTOR.getInventory().addStack(drop);
                        if (!rest.isEmpty()) Workforce.addToTreasury(village, rest);
                    } else {
                        Workforce.addToTreasury(village, drop);
                    }
                }
            }
            world.setBlockState(target, Blocks.AIR.getDefaultState());
            return Result.PLACED;
        }

        boolean decoration = switch (step.kind()) {
            case SHUTTER, POT, HEDGE, PATH, STEP, BELL, SMOKE, LANTERN, CHIMNEY, FLOWER, SHELF, BANNER, FRAME, PROP -> true;
            default -> false;
        };
        if (decoration) {
            // decorations never push anything aside: a tree, a neighbour's wall or a chest stays where it is
            boolean free = old.isAir() || old.isReplaceable();
            if (step.kind() == Kind.CHIMNEY) free = free || old.getBlock() instanceof StairsBlock; // through the eaves
            if (step.kind() == Kind.FLOWER) free = old.isAir() && world.getBlockState(target.down()).isIn(net.minecraft.registry.tag.BlockTags.DIRT);
            if (step.kind() == Kind.PATH) free = (old.isOf(Blocks.GRASS_BLOCK) || old.isOf(Blocks.DIRT) || old.isOf(Blocks.COARSE_DIRT) || old.isOf(Blocks.PODZOL))
                    && world.getBlockState(target.up()).isReplaceable();
            // nothing hangs in the air: flowers, pots and hedges need ground under them
            if (free && (step.kind() == Kind.POT || step.kind() == Kind.HEDGE || step.kind() == Kind.FLOWER)
                    && !world.getBlockState(target.down()).isSideSolidFullSquare(world, target.down(), Direction.UP)) free = false;
            if (!free) return Result.SKIPPED;
        }
        BlockState state = stateFor(village, step, rot);
        if (state == null) {
            // glass, light and decoration are optional: without them the house is plainer, not unfinished
            return switch (step.kind()) {
                case GLASS, TORCH, CROP, CARPET, SHUTTER, POT, HEDGE, PATH, STEP, BELL, SMOKE, LANTERN, CHIMNEY,
                     STONECUTTER, HAY, COMPOSTER, PUMPKIN, CAULDRON, FLOWER, LOG_X, LOG_Z, WOOL, TARGET, CHAIN, CLOCK, SHELF, BANNER, FRAME, PROP, TPL_FIT -> Result.SKIPPED;
                // a home's smoker is a comfort, not a wall: no stone for it, the house goes up without (a hamlet with no
                // miner waited five days on it)
                case SMOKER, BARREL, BOOKSHELF, CRAFT -> village.buildType != null && Tiers.isHome(village.buildType) ? Result.SKIPPED : Result.WAIT;
                default -> Result.WAIT;
            };
        }
        if (state.isOf(Blocks.STRUCTURE_VOID)) return Result.SKIPPED; // a template's frill the village cannot afford
        if (step.kind() == Kind.LANTERN && !state.isOf(Blocks.LANTERN)) {
            // no lantern to be had (a poor village, or no iron): a torch on the nearest wall, or standing on the floor
            BlockState torch = null;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos n = target.offset(dir);
                if (world.getBlockState(n).isSideSolidFullSquare(world, n, dir.getOpposite())) {
                    torch = Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, dir.getOpposite());
                    break;
                }
            }
            if (torch == null && world.getBlockState(target.down()).isSideSolidFullSquare(world, target.down(), Direction.UP)) torch = Blocks.TORCH.getDefaultState();
            if (torch == null) return Result.SKIPPED;
            state = torch;
        } else if (step.kind() == Kind.LANTERN) {
            if (Block.sideCoversSmallSquare(world, target.down(), Direction.UP)) state = state.with(LanternBlock.HANGING, false);
            else if (Block.sideCoversSmallSquare(world, target.up(), Direction.DOWN)) state = state.with(LanternBlock.HANGING, true);
            else return Result.SKIPPED;
        }
        if (step.kind() == Kind.CLOCK) {
            // a clock in a frame on the wall behind this spot, facing out
            if (!world.getEntitiesByClass(net.minecraft.entity.decoration.ItemFrameEntity.class, new net.minecraft.util.math.Box(target), e -> true).isEmpty()) return Result.SKIPPED;
            var frame = new net.minecraft.entity.decoration.ItemFrameEntity(world, target, turn(Direction.SOUTH, rot));
            frame.setHeldItemStack(new ItemStack(Items.CLOCK), false);
            if (!frame.canStayAttached()) return Result.SKIPPED;
            world.spawnEntity(frame);
            return Result.PLACED;
        }
        if (step.kind() == Kind.FRAME) {
            // something of the house's in a frame on the wall: a frame needs leather, the thing in it comes from the stores
            if (!world.getEntitiesByClass(net.minecraft.entity.decoration.ItemFrameEntity.class, new net.minecraft.util.math.Box(target), e -> true).isEmpty()) return Result.SKIPPED;
            var frame = new net.minecraft.entity.decoration.ItemFrameEntity(world, target, face(step, rot).getOpposite());
            if (!frame.canStayAttached()) return Result.SKIPPED;
            Item[] keep = {Items.BOOK, Items.MAP, Items.COMPASS, Items.CLOCK, Items.BREAD, Items.WHEAT, Items.APPLE, Items.FEATHER, Items.SHEARS, Items.FISHING_ROD};
            ItemStack shown = ItemStack.EMPTY;
            int h = Math.floorMod(target.hashCode(), keep.length);
            for (int i = 0; i < keep.length && shown.isEmpty(); i++) {
                Item it = keep[(h + i) % keep.length];
                if (take(village, x -> x.isOf(it), 1)) shown = new ItemStack(it);
            }
            if (shown.isEmpty()) return Result.SKIPPED;
            frame.setHeldItemStack(shown, false);
            world.spawnEntity(frame);
            return Result.PLACED;
        }
        if (step.kind() == Kind.ARMOR_STAND) {
            var stand = net.minecraft.entity.EntityType.ARMOR_STAND.create(world);
            if (stand != null) {
                stand.refreshPositionAndAngles(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 180f, 0f);
                world.spawnEntity(stand);
            }
            return Result.PLACED;
        }
        if (step.kind() == Kind.TORCH) {
            // on a wall where there is one, like a player would hang it
            BlockState wall = null;
            // the wall the plan means it for first (in a mine, the timber post or the side wall, not the rock ahead
            // that is about to be dug away), then any other
            List<Direction> sides = new ArrayList<>();
            if (step.face() >= 0) sides.add(face(step, rot));
            for (Direction dir : Direction.Type.HORIZONTAL) if (!sides.contains(dir)) sides.add(dir);
            for (Direction dir : sides) {
                BlockPos n = target.offset(dir);
                if (world.getBlockState(n).isSideSolidFullSquare(world, n, dir.getOpposite())) {
                    wall = Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, dir.getOpposite());
                    break;
                }
            }
            if (wall != null) state = wall;
            else if (!world.getBlockState(target.down()).isSideSolidFullSquare(world, target.down(), Direction.UP)) return Result.SKIPPED;
        }
        // panes, fences, bars and stairs join up with what is around them, like when a player places them
        switch (step.kind()) {
            // (never a door: shaped against its neighbours before its other half is there, it turns to air - which is how
            // every building from a template lost its doors and let the night in)
            case GLASS, FENCE, BARS, GATE, STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W, RSTAIR_S, RSTAIR_N, PEW, SEAT_W, SEAT_E, STEP, CHAIN, LOG_X, LOG_Z, TPL, TPL_FIT -> {
                // (nor a chest: shaped before its other half stood there, each half came out a single chest)
                if (!(state.getBlock() instanceof DoorBlock) && !(state.getBlock() instanceof ChestBlock)) state = Block.postProcessState(state, world, target);
            }
            default -> {
            }
        }
        if (state.getBlock() instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER) state = pairDoor(world, target, state);
        world.setBlockState(target, state);
        if (step.kind() == Kind.CHEST || step.kind() == Kind.HALL_CHEST
                || state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) == net.minecraft.block.enums.ChestType.SINGLE) pairChest(world, target, state);
        // the floor rests on the ground: under it a stone footing goes down to solid earth
        if (step.dy() == 0 && needsFooting(step.kind())) foundation(world, village, target.down());
        if (step.kind() == Kind.CHEST) village.lootChests.add(target.toImmutable());
        if (step.kind() == Kind.TPL_FIT && state.getBlock() instanceof ChestBlock) {
            if (Buildings.base(village.buildType).equals("townhall")) village.hallChests.add(target.toImmutable());
            else village.lootChests.add(target.toImmutable());
        }
        if (step.kind() == Kind.TPL_FIT && state.getBlock() instanceof LecternBlock && Buildings.base(village.buildType).equals("townhall")) village.hallPos = target.toImmutable();
        if (step.kind() == Kind.HALL_CHEST) village.hallChests.add(target.toImmutable());
        if (step.kind() == Kind.LECTERN) village.hallPos = target.toImmutable();
        if (step.kind() == Kind.SIGN) {
            village.boardSigns.add(target.toImmutable());
            Board.refresh(world, village);
        }
        if (step.kind() == Kind.DOOR || state.getBlock() instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
            world.setBlockState(target.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        }
        world.playSound(null, target, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 1.0f);
        return Result.PLACED;
    }

    /** Everything standing on the ground plane gets a footing; small things on top of others do not. */
    private static boolean needsFooting(Kind k) {
        return switch (k) {
            case CARPET, TORCH, CROP, FARMLAND, WATER, PLATE, SIGN, LANTERN, SMOKE, POT, FLOWER, HEDGE, PATH, DIG, CLEAR, SHELF, BANNER, FRAME, TPL_FIT -> false;
            default -> true;
        };
    }

    /** Two chests side by side, facing the same way, make one double chest. */
    /**
     * A door going in beside another facing the same way: hung as the other leaf of a double door, its hinge on the
     * outer side (as the game hangs a player's second door). Both leaves were hung the same way, one handle at the
     * middle and one at the frame.
     */
    static BlockState pairDoor(ServerWorld world, BlockPos p, BlockState state) {
        Direction f = state.get(DoorBlock.FACING);
        for (int side = 0; side < 2; side++) {
            Direction toOther = side == 0 ? f.rotateYCounterclockwise() : f.rotateYClockwise();
            BlockPos q = p.offset(toOther);
            BlockState o = world.getBlockState(q);
            if (!(o.getBlock() instanceof DoorBlock) || o.get(DoorBlock.HALF) != DoubleBlockHalf.LOWER || o.get(DoorBlock.FACING) != f) continue;
            // the other leaf on his left (counter-clockwise of the facing): this one hinges right, that one left
            net.minecraft.block.enums.DoorHinge mine = side == 0 ? net.minecraft.block.enums.DoorHinge.RIGHT : net.minecraft.block.enums.DoorHinge.LEFT;
            net.minecraft.block.enums.DoorHinge theirs = side == 0 ? net.minecraft.block.enums.DoorHinge.LEFT : net.minecraft.block.enums.DoorHinge.RIGHT;
            if (o.get(DoorBlock.HINGE) != theirs) {
                world.setBlockState(q, o.with(DoorBlock.HINGE, theirs), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                BlockState up = world.getBlockState(q.up());
                if (up.getBlock() instanceof DoorBlock) world.setBlockState(q.up(), up.with(DoorBlock.HINGE, theirs), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
            return state.with(DoorBlock.HINGE, mine);
        }
        return state;
    }

    private static void pairChest(ServerWorld world, BlockPos p, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock)) return;
        Direction f = state.get(ChestBlock.FACING);
        for (int side = 0; side < 2; side++) {
            Direction toOther = side == 0 ? f.rotateYClockwise() : f.rotateYCounterclockwise();
            BlockPos q = p.offset(toOther);
            BlockState o = world.getBlockState(q);
            if (!o.isOf(state.getBlock()) || o.get(ChestBlock.FACING) != f || o.get(ChestBlock.CHEST_TYPE) != net.minecraft.block.enums.ChestType.SINGLE) continue;
            // the half whose partner lies clockwise of its facing is the left one
            net.minecraft.block.enums.ChestType mine = side == 0 ? net.minecraft.block.enums.ChestType.LEFT : net.minecraft.block.enums.ChestType.RIGHT;
            net.minecraft.block.enums.ChestType theirs = side == 0 ? net.minecraft.block.enums.ChestType.RIGHT : net.minecraft.block.enums.ChestType.LEFT;
            world.setBlockState(p, state.with(ChestBlock.CHEST_TYPE, mine), Block.NOTIFY_LISTENERS);
            world.setBlockState(q, o.with(ChestBlock.CHEST_TYPE, theirs), Block.NOTIFY_LISTENERS);
            return;
        }
    }

    /** Fills the gap under a floor block down to the ground (at most six blocks) with the village's stone. */
    private static void foundation(ServerWorld world, Village village, BlockPos below) {
        Block stone = Palette.of(village).stone();
        for (int i = 0; i < 12; i++) {
            BlockPos p = below.down(i);
            BlockState s = world.getBlockState(p);
            if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty()) return; // solid ground reached
            if (!FREE && !take(village, COBBLE, 1)) {
                // no stone left: earth from around will do
                boolean paid = false;
                for (ItemStack fs : village.storageBuffer) {
                    if (Scavenge.isFiller(fs)) {
                        fs.decrement(1);
                        paid = true;
                        break;
                    }
                }
                village.storageBuffer.removeIf(ItemStack::isEmpty);
                // unpaid, earth will do where it is hidden in the ground; where it shows (a footing on a slope) it
                // is laid in stone all the same, so that no house stands on a stack of dirt
                boolean hidden = true;
                for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                    BlockState n = world.getBlockState(p.offset(d));
                    if (n.isAir() || n.isReplaceable()) hidden = false;
                }
                world.setBlockState(p, paid || !hidden ? stone.getDefaultState() : Blocks.DIRT.getDefaultState());
                continue;
            }
            world.setBlockState(p, stone.getDefaultState());
        }
    }

    /** Pays for the block and returns what to place, or null if the treasury cannot pay. */
    private static Direction face(Step step, int rot) {
        return turn(Direction.fromHorizontal(Math.max(0, step.face())), rot);
    }

    private static BlockState stateFor(Village village, Step step, int rot) {
        Kind kind = step.kind();
        Palette pal = Palette.of(village);
        int tier = MAT_TIER >= 0 ? MAT_TIER : Prosperity.tier(village);
        if (tier == Tiers.POOR) {
            // a poor village does without: no glass, no shutters, flowers or rugs, timber instead of dressed stone,
            // beaten earth for a floor
            switch (kind) {
                case SHUTTER, POT, HEDGE, FLOWER, CARPET, BELL -> {
                    return null;
                }
                case GLASS -> {
                    if (!takePlanks(village, 1)) return null;
                    return pal.fence().getDefaultState();
                }
                case ACCENT -> {
                    BlockState b = betterOr(village, kind, "log", LOG, pal.log().getDefaultState());
                    if (b != null) return b == WANT ? null : b;
                    return plank(village, pal.planks());
                }
                case FLOOR -> {
                    if (step.dy() == 0) return Blocks.COARSE_DIRT.getDefaultState();
                }
                default -> {
                }
            }
        } else if (tier >= Tiers.WEALTHY) {
            boolean city = tier >= Tiers.CITY;
            switch (kind) {
                case STONE, CHIMNEY -> { // a wealthy village builds in dressed stone
                    BlockState b = betterOr(village, kind, "dressed", COBBLE, pal.accent().getDefaultState());
                    if (b != null) return b == WANT ? null : b;
                }
                case WALL -> { // a city: a stone ground floor, plastered storeys above
                    BlockState b = city ? betterOr(village, kind, "city", COBBLE, (step.dy() <= 3 ? pal.accent() : pal.plaster()).getDefaultState()) : null;
                    if (b != null) return b == WANT ? null : b;
                }
                case ROOF -> { // and tiled roofs
                    BlockState b = city ? betterOr(village, kind, "city", COBBLE, pal.tiles().getDefaultState()) : null;
                    if (b != null) return b == WANT ? null : b;
                }
                case STAIRS_E, STAIRS_W -> {
                    BlockState b = city && step.dy() >= Blueprints.LEVEL_HEIGHT ? betterOr(village, kind, "city", COBBLE,
                            pal.tileStairs().getDefaultState().with(StairsBlock.FACING, turn(kind == Kind.STAIRS_E ? Direction.EAST : Direction.WEST, rot))) : null;
                    if (b != null) return b == WANT ? null : b;
                }
                case PATH -> { // paved paths
                    BlockState b = city ? betterOr(village, kind, "city", COBBLE, pal.accent().getDefaultState()) : null;
                    if (b != null) return b == WANT ? null : b;
                }
                default -> {
                }
            }
        }
        // the desert builds in sandstone: wood is scarce there and dear, kept for doors, beams and shutters
        // (paid in what the desert has: its sandstone, its sand, its clay - the cobble asked for here before was never
        // there, and the walls went up in planks every time)
        if (pal.stone() == Blocks.SANDSTONE || pal.stone() == Blocks.RED_SANDSTONE) {
            switch (kind) {
                case WALL -> {
                    BlockState b = betterOr(village, kind, "sand", SANDISH, (step.dy() == 0 ? pal.stone() : pal.daub()).getDefaultState());
                    if (b != null) return b == WANT ? null : b;
                }
                case FLOOR -> {
                    BlockState b = step.dy() > 0 ? betterOr(village, kind, "sand", SANDISH, pal.daub().getDefaultState()) : null;
                    if (b != null) return b == WANT ? null : b;
                }
                case ROOF -> {
                    BlockState b = betterOr(village, kind, "sand", SANDISH, pal.roofPlanks().getDefaultState());
                    if (b != null) return b == WANT ? null : b;
                }
                case PILLAR, BEAM_X, BEAM_Z -> {
                    BlockState b = betterOr(village, kind, "sand", SANDISH, pal.log().getDefaultState());
                    if (b != null) return b == WANT ? null : b;
                }
                default -> {
                }
            }
        }
        // the north: the first course of the walls in stone, against the snow and the wet
        if (pal.wood().equals("spruce") && kind == Kind.WALL && step.dy() == 0) {
            BlockState b = betterOr(village, kind, "north", COBBLE, pal.stone().getDefaultState());
            if (b != null) return b == WANT ? null : b;
        }
        switch (kind) {
            case FLOOR:
                return plank(village, pal.planks());
            case ROOF:
                return plank(village, pal.roofPlanks());
            case WALL:
                return plank(village, pal.planks());
            case PILLAR: {
                BlockState b = betterOr(village, kind, "log", LOG, pal.log().getDefaultState());
                if (b != null) return b == WANT ? null : b;
                return plank(village, pal.planks());
            }
            case BEAM_X, BEAM_Z: {
                Direction.Axis axis = (kind == Kind.BEAM_X) == ((rot & 1) == 0) ? Direction.Axis.X : Direction.Axis.Z;
                BlockState b = betterOr(village, kind, "log", LOG, axised(pal.strippedLog(), axis));
                if (b != null) return b == WANT ? null : b;
                return plank(village, pal.planks());
            }
            case ACCENT: {
                BlockState b = betterOr(village, kind, "stone", COBBLE, pal.accent().getDefaultState());
                if (b != null) return b == WANT ? null : b;
                return plank(village, pal.log());
            }
            case CHIMNEY: {
                if (take(village, COBBLE, 1)) return pal.stone().getDefaultState();
                for (ItemStack fs : village.storageBuffer) {
                    if (Scavenge.isFiller(fs)) {
                        fs.decrement(1);
                        village.storageBuffer.removeIf(ItemStack::isEmpty);
                        return pal.stone().getDefaultState();
                    }
                }
                return null;
            }
            case SMOKE: { // the chimney's top: a stone wall post, as on the old villages' chimneys (no fire burning up there)
                Block st = pal.stone();
                Block wall = st == Blocks.SANDSTONE || st == Blocks.CUT_SANDSTONE || st == Blocks.SMOOTH_SANDSTONE ? Blocks.SANDSTONE_WALL
                        : st == Blocks.RED_SANDSTONE || st == Blocks.CUT_RED_SANDSTONE ? Blocks.RED_SANDSTONE_WALL
                        : st == Blocks.MOSSY_COBBLESTONE ? Blocks.MOSSY_COBBLESTONE_WALL
                        : st == Blocks.STONE_BRICKS ? Blocks.STONE_BRICK_WALL
                        : st == Blocks.MUD_BRICKS || st == Blocks.PACKED_MUD ? Blocks.MUD_BRICK_WALL
                        : Blocks.COBBLESTONE_WALL;
                if (!FREE && !take(village, COBBLE, 1)) return null;
                return wall.getDefaultState();
            }
            case STEP: {
                if (!take(village, COBBLE, 1) && !takePlanks(village, 1)) return null;
                return pal.stoneStairs().getDefaultState().with(StairsBlock.FACING, face(step, rot));
            }
            case SHUTTER: {
                if (!takePlanks(village, 1)) return null;
                return pal.trapdoor().getDefaultState().with(TrapdoorBlock.FACING, face(step, rot)).with(TrapdoorBlock.OPEN, true);
            }
            case POT:
                return pal.flower().getDefaultState();
            case HEDGE:
                return pal.leaves().getDefaultState().with(LeavesBlock.PERSISTENT, true);
            case PATH:
                return Blocks.DIRT_PATH.getDefaultState();
            case BELL:
                if (!FREE && !take(village, s -> s.isOf(Items.GOLD_INGOT), 1)) return null;
                return Blocks.BELL.getDefaultState().with(BellBlock.FACING, turn(Direction.SOUTH, rot));
            case LANTERN: {
                // a lantern is a torch in an iron cage: without iron (and in a poor village) it stays a torch
                if (!take(village, s -> s.isOf(Items.TORCH), 1)) return null;
                if (tier > Tiers.POOR && (FREE || take(village, s -> s.isOf(Items.IRON_INGOT), 1) || take(village, s -> s.isOf(Items.IRON_NUGGET), 8)))
                    return Blocks.LANTERN.getDefaultState();
                return Blocks.TORCH.getDefaultState();
            }
            case STONECUTTER:
                if (!take(village, COBBLE, 3)) return null;
                return Blocks.STONECUTTER.getDefaultState().with(StonecutterBlock.FACING, turn(Direction.SOUTH, rot));
            case HAY:
                if (!take(village, s -> s.isOf(Items.WHEAT), 9) && !take(village, s -> s.isOf(Items.HAY_BLOCK), 1)) return null;
                return Blocks.HAY_BLOCK.getDefaultState();
            case COMPOSTER:
                if (!takePlanks(village, 3)) return null;
                return Blocks.COMPOSTER.getDefaultState();
            case BARS:
                if (take(village, s -> s.isOf(Items.IRON_INGOT), 1)) return Blocks.IRON_BARS.getDefaultState();
                if (!takePlanks(village, 1)) return null;
                return pal.fence().getDefaultState(); // no iron: wooden bars
            case WOOL: {
                Block[] colors = {Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.GREEN_WOOL};
                if (take(village, WOOL, 1)) return (step.face() < 0 ? Blocks.WHITE_WOOL : colors[step.face() % colors.length]).getDefaultState();
                if (!takePlanks(village, 1)) return null;
                return pal.roofSlab().getDefaultState(); // a wooden awning instead of cloth
            }
            case SLAB:
                if (!takePlanks(village, 1)) return null;
                return pal.roofSlab().getDefaultState();
            case PUMPKIN:
                return Blocks.CARVED_PUMPKIN.getDefaultState().with(CarvedPumpkinBlock.FACING, face(step, rot));
            case GATE:
                if (!takePlanks(village, 2)) return null;
                return pal.fenceGate().getDefaultState().with(FenceGateBlock.FACING, face(step, rot));
            case CAULDRON:
                if (!take(village, s -> s.isOf(Items.IRON_INGOT), 7)) return null;
                return Blocks.CAULDRON.getDefaultState();
            case FLOWER:
                return pal.plainFlower().getDefaultState();
            case MOUND:
                return Blocks.COARSE_DIRT.getDefaultState();
            case PEW:
                if (!takePlanks(village, 1)) return null;
                // the plans give the way the sitter looks; a stair's facing is the side of its back
                return pal.stairs().getDefaultState().with(StairsBlock.FACING, face(step, rot).getOpposite());
            case LOG_X, LOG_Z: {
                Direction.Axis axis = (kind == Kind.LOG_X) == ((rot & 1) == 0) ? Direction.Axis.X : Direction.Axis.Z;
                if (!take(village, LOG, 1)) return null;
                return axised(pal.log(), axis);
            }
            case GLASS: {
                if (take(village, GLASS, 1)) return Blocks.GLASS_PANE.getDefaultState();
                // no glass (that is the glazier's work, or the merchant's): a wooden lattice keeps the window
                if (!takePlanks(village, 1)) return null;
                return pal.fence().getDefaultState();
            }
            case DOOR:
                if (!takePlanks(village, 6)) return null;
                return pal.door().getDefaultState().with(DoorBlock.FACING, turn(Direction.SOUTH, rot)).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            case CHEST:
                if (!takePlanks(village, 8)) return null;
                return Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, turn(Direction.SOUTH, rot));
            case BED_FOOT: {
                if (planks(village) < 3 || stockWool(village) < 1) return null;
                takeBedding(village, 1);
                takePlanks(village, 3);
                return Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, turn(Direction.SOUTH, rot)).with(BedBlock.PART, BedPart.FOOT);
            }
            case BED_HEAD:
                return Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, turn(Direction.SOUTH, rot)).with(BedBlock.PART, BedPart.HEAD);
            case TARGET:
                if (!take(village, s -> s.isOf(Items.HAY_BLOCK) || s.isOf(Items.WHEAT), 1)) return null;
                return Blocks.TARGET.getDefaultState();
            case CHAIN:
                if (!take(village, s -> s.isOf(Items.IRON_NUGGET) || s.isOf(Items.IRON_INGOT), 1)) return null;
                return Blocks.CHAIN.getDefaultState();
            case TORCH: {
                if (!take(village, s -> s.isOf(Items.TORCH), 1)) return null;
                return Blocks.TORCH.getDefaultState();
            }
            case ANVIL: {
                Block pyroAnvil = Compat.block("pyrotech", "anvil_granite");
                if (pyroAnvil != null) {
                    if (!take(village, COBBLE, 10)) return null;
                    return pyroAnvil.getDefaultState();
                }
                if (!take(village, s -> s.isOf(Items.IRON_INGOT), 31)) return null;
                return Blocks.ANVIL.getDefaultState();
            }
            case FURNACE: {
                if (!take(village, COBBLE, 8)) return null;
                Block pyroForge = Compat.block("pyrotech", "bloomery");
                if (pyroForge != null) return pyroForge.getDefaultState();
                return Blocks.FURNACE.getDefaultState().with(AbstractFurnaceBlock.FACING, turn(Direction.SOUTH, rot));
            }
            case GRINDSTONE: {
                if (count(village, COBBLE) < 3 || planks(village) < 2) return null;
                take(village, COBBLE, 3);
                takePlanks(village, 2);
                return Blocks.GRINDSTONE.getDefaultState();
            }
            case SMITHING: {
                if (count(village, s -> s.isOf(Items.IRON_INGOT)) < 2 || planks(village) < 4) return null;
                take(village, s -> s.isOf(Items.IRON_INGOT), 2);
                takePlanks(village, 4);
                return Blocks.SMITHING_TABLE.getDefaultState();
            }
            case CLOCK:
                if (!FREE && !take(village, s -> s.isOf(Items.GOLD_INGOT), 4)) return null;
                return Blocks.AIR.getDefaultState(); // marker, the frame is hung in execute()
            case THATCH: {
                // the land's roofing: straw bales, sod in the north (earth), bamboo in the jungle; boards if there is not
                // enough for the whole roof - decided once for the roof, not block by block (half a roof of straw and half
                // of boards); and the wheat is the village's bread, not roofing
                Block th = pal.thatch();
                if (FREE) return th.getDefaultState();
                Predicate<ItemStack> stock = th == Blocks.HAY_BLOCK ? s -> s.isOf(Items.HAY_BLOCK)
                        : th == Blocks.MOSS_BLOCK ? s -> s.isOf(Items.DIRT) || s.isOf(Items.MOSS_BLOCK) || s.isOf(Items.GRASS_BLOCK)
                        : s -> s.isOf(Items.BAMBOO_BLOCK);
                BlockState made = betterOr(village, Kind.THATCH, "thatch", stock, th.getDefaultState());
                if (made != null) return made;
                return plank(village, pal.roofPlanks());
            }
            case DAUB: {
                // the land's walling: daub or mud brick from earth, adobe from sand, clay in the savanna, logs in the north
                Block db = pal.daub();
                if (FREE) return db.getDefaultState();
                if (db.getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
                    if (take(village, LOG, 1)) return db.getDefaultState();
                    return plank(village, pal.planks());
                }
                for (ItemStack fs : village.storageBuffer) {
                    if (fs.isOf(Items.DIRT) || fs.isOf(Items.COARSE_DIRT) || fs.isOf(Items.MUD) || fs.isOf(Items.PACKED_MUD) || fs.isOf(Items.CLAY_BALL)
                            || fs.isOf(Items.SAND) || fs.isOf(Items.RED_SAND) || fs.isOf(Items.SANDSTONE) || fs.isOf(Items.TERRACOTTA)) {
                        fs.decrement(1);
                        village.storageBuffer.removeIf(ItemStack::isEmpty);
                        return db.getDefaultState();
                    }
                }
                return plank(village, pal.planks());
            }
            case RSTAIR_S, RSTAIR_N: {
                Direction facing = turn(kind == Kind.RSTAIR_S ? Direction.SOUTH : Direction.NORTH, rot);
                if (tier >= Tiers.CITY && take(village, COBBLE, 1)) return pal.tileStairs().getDefaultState().with(StairsBlock.FACING, facing);
                if (takePlanks(village, 1)) return pal.roofStairs().getDefaultState().with(StairsBlock.FACING, facing);
                return null; // a wooden roof waits for its boards: no stone steps patched in among the wooden ones
            }
            case ARMOR_STAND: {
                if (!takePlanks(village, 4)) return null;
                return Blocks.AIR.getDefaultState(); // marker, the stand itself is spawned in execute()
            }
            case STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W: {
                Direction base = switch (kind) {
                    case STAIRS_N -> Direction.NORTH;
                    case STAIRS_S -> Direction.SOUTH;
                    case STAIRS_E -> Direction.EAST;
                    default -> Direction.WEST;
                };
                Direction facing = turn(base, rot);
                if (kind == Kind.STAIRS_N) { // the mine: stone steps
                    if (take(village, COBBLE, 1)) return pal.stoneStairs().getDefaultState().with(StairsBlock.FACING, facing);
                    if (!takePlanks(village, 1)) return null;
                    return pal.stairs().getDefaultState().with(StairsBlock.FACING, facing);
                }
                // roofs and indoor stairs: wood, the roof in its darker wood
                Block wood = kind == Kind.STAIRS_S ? pal.stairs() : pal.roofStairs();
                if (takePlanks(village, 1)) return wood.getDefaultState().with(StairsBlock.FACING, facing);
                return null; // waits for boards (stone steps in a wooden roof, one or two among the rest, looked a botch)
            }
            case BOOKSHELF:
                if (!takePlanks(village, 6)) return null;
                return Blocks.BOOKSHELF.getDefaultState();
            case CARPET: {
                if (!take(village, WOOL, 1)) return null;
                Block[] rugs = {Blocks.RED_CARPET, Blocks.BLUE_CARPET, Blocks.GREEN_CARPET, Blocks.BROWN_CARPET, Blocks.CYAN_CARPET, Blocks.YELLOW_CARPET};
                return rugs[Math.floorMod(step.face(), rugs.length)].getDefaultState();
            }
            case LECTERN:
                if (!takePlanks(village, 6)) return null;
                return Blocks.LECTERN.getDefaultState().with(LecternBlock.FACING, turn(Direction.SOUTH, rot));
            case HALL_CHEST:
                if (!takePlanks(village, 8)) return null;
                return Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, turn(Direction.SOUTH, rot));
            case SMOKER: {
                if (count(village, COBBLE) < 8 || planks(village) < 4) return null;
                take(village, COBBLE, 8);
                takePlanks(village, 4);
                return Blocks.SMOKER.getDefaultState().with(AbstractFurnaceBlock.FACING, turn(Direction.SOUTH, rot));
            }
            case BARREL: {
                if (!takePlanks(village, 8)) return null;
                return Blocks.BARREL.getDefaultState().with(BarrelBlock.FACING, Direction.UP);
            }
            case CRAFT: {
                if (!takePlanks(village, 4)) return null;
                return Blocks.CRAFTING_TABLE.getDefaultState();
            }
            case PLATE: {
                if (!takePlanks(village, 2)) return null;
                return pal.plate().getDefaultState();
            }
            case SEAT_W, SEAT_E: {
                Direction facing = turn(kind == Kind.SEAT_W ? Direction.WEST : Direction.EAST, rot);
                if (!takePlanks(village, 1)) return null;
                return pal.stairs().getDefaultState().with(StairsBlock.FACING, facing);
            }
            case STONE: {
                // stone if there is stone enough for all of it, else timber throughout (a cobble patch here and there in a plank wall)
                boolean dressed = once(village, "STONE:mason", Buildings.has(village, "mason"));
                BlockState b = betterOr(village, kind, "stone", COBBLE, (dressed ? pal.accent() : pal.stone()).getDefaultState());
                if (b != null) return b == WANT ? null : b;
                return plank(village, pal.planks());
            }
            case SIGN:
                if (!takePlanks(village, 2)) return null;
                return Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, turn(Direction.SOUTH, rot));
            case FENCE:
                if (!takePlanks(village, 1)) return null;
                return pal.fence().getDefaultState();
            case LADDER:
                if (!takePlanks(village, 1)) return null;
                return Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, turn(Direction.SOUTH, rot));
            case FARMLAND:
                return Blocks.FARMLAND.getDefaultState();
            case WATER:
                return Blocks.WATER.getDefaultState();
            case TPL, TPL_FIT: {
                BlockState ts = Templates.state(village, step, rot);
                return ts == null ? null : pay(village, ts, tier, step.kind() == Kind.TPL_FIT, pal);
            }
            case SHELF:
                // a board on the wall at head height: a trapdoor laid shut in the top half
                if (!takePlanks(village, 2)) return null;
                return pal.trapdoor().getDefaultState().with(TrapdoorBlock.HALF, net.minecraft.block.enums.BlockHalf.TOP)
                        .with(TrapdoorBlock.OPEN, false).with(TrapdoorBlock.FACING, face(step, rot).getOpposite());
            case BANNER: {
                if (tier == Tiers.POOR || !take(village, WOOL, 2)) return null;
                Block[] banners = {Blocks.RED_WALL_BANNER, Blocks.BLUE_WALL_BANNER, Blocks.GREEN_WALL_BANNER, Blocks.YELLOW_WALL_BANNER, Blocks.WHITE_WALL_BANNER, Blocks.CYAN_WALL_BANNER};
                Block b = banners[Math.floorMod(step.dx() * 7 + step.dz() * 3 + step.dy(), banners.length)];
                return b.getDefaultState().with(WallBannerBlock.FACING, face(step, rot).getOpposite());
            }
            case FRAME:
                return tier == Tiers.POOR ? null : Blocks.AIR.getDefaultState(); // the frame itself is an entity (see execute)
            case PROP:
                switch (step.face()) {
                    case Decor.CRATE -> {
                        if (!takePlanks(village, 8)) return null;
                        return Blocks.BARREL.getDefaultState().with(BarrelBlock.FACING, turn(Direction.EAST, rot)); // on its side
                    }
                    case Decor.HAY -> {
                        if (!take(village, x -> x.isOf(Items.HAY_BLOCK), 1) && !take(village, x -> x.isOf(Items.WHEAT), 9)) return null;
                        return Blocks.HAY_BLOCK.getDefaultState();
                    }
                    case Decor.BOOKS -> {
                        if (!take(village, x -> x.isOf(Items.BOOKSHELF), 1) && !takePlanks(village, 6)) return null;
                        return Blocks.BOOKSHELF.getDefaultState();
                    }
                    case Decor.POT -> {
                        if (tier == Tiers.POOR) return null;
                        return pal.flower().getDefaultState();
                    }
                    default -> {
                        if (!takePlanks(village, 8)) return null;
                        return Blocks.BARREL.getDefaultState().with(BarrelBlock.FACING, Direction.UP);
                    }
                }
            case CROP:
                if (!take(village, x -> x.isOf(Items.WHEAT_SEEDS), 1)) return null;
                return Blocks.WHEAT.getDefaultState();
            default:
                return null;
        }
    }

    /** One plank's worth of building block: placed in the village's own wood, or stone and earth when wood is out. */
    /** Per project: which material each kind of part is made of, decided once (see better). */
    private static final Map<String, Map<String, Boolean>> CHOICE = new HashMap<>();
    /** Not to be had now: the step waits for it (the choice holds; nothing else goes in its place). */
    private static final BlockState WANT = Blocks.BARRIER.getDefaultState();

    /**
     * Whether this kind of part of the building is made of the better material: decided once for the project, the
     * first time it comes up - the better one if the stores hold enough of it for all of that part still to build,
     * else the plain one throughout. (Each block took whatever was at hand: a wall of planks, cobble and earth, a log
     * pillar beside a plank one.)
     */
    private static boolean better(Village village, Kind kind, String what, Predicate<ItemStack> stock, int perBlock) {
        if (FREE || village.buildOrigin == null) return count(village, stock) >= perBlock;
        Map<String, Boolean> m = CHOICE.computeIfAbsent(village.buildType + "@" + village.buildOrigin, k -> new HashMap<>());
        String key = kind.name() + ":" + what;
        Boolean b = m.get(key);
        if (b == null) {
            int need = 0;
            List<Step> steps = plan(village);
            for (int i = village.buildStep; i < steps.size(); i++) if (steps.get(i).kind() == kind) need++;
            b = count(village, stock) >= need * perBlock;
            m.put(key, b);
        }
        return b;
    }

    /** A choice for the project made the first time it is asked, and held to (what the stone is dressed as). */
    private static boolean once(Village village, String key, boolean now) {
        if (village.buildOrigin == null) return now;
        return CHOICE.computeIfAbsent(village.buildType + "@" + village.buildOrigin, k -> new HashMap<>()).computeIfAbsent(key, k -> now);
    }

    /** The better material, if this part is made of it: the block, or WANT while it is not in the stores; null if the plain one. */
    private static BlockState betterOr(Village village, Kind kind, String what, Predicate<ItemStack> stock, BlockState made) {
        if (!better(village, kind, what, stock, 1)) return null;
        return take(village, stock, 1) ? made : WANT;
    }

    private static BlockState plank(Village village, Block preferred) {
        return takePlank(village) != null ? preferred.getDefaultState() : null;
    }

    private static BlockState plank(Village village) {
        Item p = takePlank(village);
        if (p == null) return null; // no wood: he waits for it (a wall patched with earth and cobble was no wall)
        Block b = Block.getBlockFromItem(p);
        return b == Blocks.AIR ? Blocks.OAK_PLANKS.getDefaultState() : b.getDefaultState();
    }
}
