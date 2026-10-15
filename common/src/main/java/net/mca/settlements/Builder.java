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
        return CACHE.computeIfAbsent(key, k -> ordered(type.equals("mine") ? null : type, rawSteps(type, w, d, f)));
    }

    /** Fittings go in once the shell stands: furniture, light, decoration. */
    private static boolean fitting(Kind k) {
        return switch (k) {
            case BED_FOOT, BED_HEAD, CHEST, HALL_CHEST, CRAFT, CARPET, TORCH, LANTERN, BARREL, BOOKSHELF, LECTERN, SMOKER, FURNACE, ANVIL,
                 GRINDSTONE, SMITHING, STONECUTTER, CAULDRON, COMPOSTER, ARMOR_STAND, PLATE, SEAT_W, SEAT_E, PEW, POT, FLOWER, HEDGE,
                 HAY, PUMPKIN, TARGET, CHAIN, BELL, SIGN, SMOKE, CLOCK, SHUTTER, WOOL, LADDER -> true;
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
        for (Step st : last.values()) {
            var map = st.kind() == Kind.CLEAR || st.kind() == Kind.DIG ? clears : fitting(st.kind()) ? fit : shell;
            map.computeIfAbsent(st.dy(), y -> new ArrayList<>()).add(st);
        }
        List<Step> out = new ArrayList<>(raw.size());
        for (List<Step> layer : clears.values()) out.addAll(layer);
        int[] at = {0, 0};
        for (var group : List.of(shell, fit)) {
            for (List<Step> layer : group.values()) {
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
            default -> Buildings.plan(type, w, d, f);
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

    private static final Predicate<ItemStack> COBBLE = s -> s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE);
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

    public static int stockWool(Village v) {
        return count(v, WOOL);
    }

    /** A place for the mine's entrance: used by the miner, who opens the mine himself. */
    public static BlockPos chooseMineSite(ServerWorld world, Village village) {
        return findSite(world, village, 4, 4);
    }

    // ---------- planner ----------

    /** The project the planner started last, so the builder can say what he is about to do. */
    static String announceProject = null;

    private static boolean plan(Village village, ServerWorld world) {
        int planks = planks(village);
        var rand = world.random;
        for (Plan.Status st : Plan.pending(village)) {
            if (!st.ready()) continue;
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
                        if (h.floors() < 3 && h.d() >= 7 && planks >= 130) {
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
                    String kind = tier == Tiers.ORDINARY && lv == Plan.Level.HAMLET ? new String[]{"house", "longhouse", "twin", "porch"}[rand.nextInt(4)]
                            : Tiers.design("home", tier, rand, null);
                    int need = switch (kind) {
                        case "mansion", "wealden" -> 340;
                        case "rowhouse" -> 220;
                        case "cruck" -> 0;
                        case "manor", "farmhouse" -> 260;
                        case "annex", "tallhouse", "twin" -> 180;
                        case "hut" -> 0;
                        default -> 110;
                    };
                    if (planks >= need && roll < 0.8) {
                        int[] dm = SHOWCASE.get(kind);
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
                    if (startRing(village, world, "farm", 0, 0, 0, 2, 5)) return true;
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
                    if (startRing(village, world, "tower", 0, 0, 0, 4, 5)) return true;
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

    private static boolean startRing(Village village, ServerWorld world, String type, int w, int d, int floors, int minRing, int maxRing) {
        type = Tiers.design(type, Prosperity.tier(village), world.random, village); // the design that suits the village's station
        Layout.Slot slot = Layout.find(world, village, type, w, d, minRing, maxRing);
        if (slot == null) return false;
        return startAt(village, type, w, d, floors, slot);
    }

    /**
     * The site made ready: the ground under the building and a step round it filled up to the floor's level (earth,
     * grass on top), and the odd bump above it cut away, so nothing stands in the air on a slope.
     */
    static void levelSite(ServerWorld world, BlockPos o, int fw, int fd) {
        for (int x = o.getX() - 1; x <= o.getX() + fw; x++) {
            for (int z = o.getZ() - 1; z <= o.getZ() + fd; z++) {
                // branches and leaves hanging over the site are cut back
                for (int y = o.getY() + 14; y > o.getY() + 2; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (world.getBlockState(p).isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) world.breakBlock(p, true);
                }
                for (int y = o.getY() + 2; y >= o.getY(); y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    boolean natural = s.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || s.isIn(net.minecraft.registry.tag.BlockTags.REPLACEABLE)
                            || s.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD) || s.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                            || s.isIn(net.minecraft.registry.tag.BlockTags.FLOWERS) || s.isOf(Blocks.GRAVEL) || s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES);
                    if (!s.isAir() && natural) world.breakBlock(p, false);
                }
                for (int y = o.getY() - 1; y >= o.getY() - 5; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty()) break;
                    world.setBlockState(p, (y == o.getY() - 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).getDefaultState());
                }
            }
        }
    }

    private static boolean startAt(Village village, String type, int w, int d, int floors, Layout.Slot slot) {
        if (village.getWorld() != null) levelSite(village.getWorld(), slot.origin(), slot.fw(), slot.fd());
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
        if (site == null) return false;
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
                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                    BlockPos g = new BlockPos(x + dx, y - 1, z + dz);
                    BlockState ground = world.getBlockState(g);
                    if (!ground.isSolidBlock(world, g) || !ground.getFluidState().isEmpty()) {
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
        // upkeep first: a damaged building, then the roads
        if (village.buildOrigin == null && Repair.start(village)) Speech.say(v, "settlements.say.build.repair");
        if (village.buildOrigin == null && Repair.roadWork(v, village, world)) return true;
        if (village.buildOrigin == null && !plan(village, world)) {
            Needs.Need blocker = Plan.firstBlocker(village);
            markShort(village, blocker, blocker == Needs.Need.PLANKS || blocker == Needs.Need.COBBLE);
            Life.askForHelp(v, village, world, blocker);
            if (blocker != Needs.Need.NONE && Scavenge.work(v, village, world, blocker, blocker == Needs.Need.PLANKS || blocker == Needs.Need.COBBLE)) {
                if (Needs.get(v) != Needs.Need.FOOD) Needs.set(v, Needs.Need.NONE);
                Speech.say(v, "settlements.say.build.scavenge");
                return true;
            }
            if (Needs.get(v) != Needs.Need.FOOD) Needs.set(v, blocker);
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
        if (Haul.fetch(v, village)) return true;
        boolean worked = false;
        int ops = 0, skips = 0;
        int checks = 0;
        int budget = Prosperity.ops(v, village, opsPerSecond); // tired builders and struggling villages build slower
        if (budget == 0) return true; // working, just slowly
        while (ops < budget && skips < MAX_TRIVIAL_SKIPS && village.buildOrigin != null && village.buildStep < steps.size()) {
            Step step = steps.get(village.buildStep);
            int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
            int[] rp = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], village.buildRot);
            BlockPos target = origin.add(rp[0], step.dy(), rp[1]);
            if (village.buildRepair && !Repair.missing(world, step, target)) { // repairs only put back what is gone
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
            boolean far = mine ? v.squaredDistanceTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5) > 144 : !Scaffold.inReach(v, target);
            if (far && !mine && !border && target.getY() - origin.getY() >= 3) {
                Built site = new Built(village.buildType, origin, village.buildW, village.buildD, Math.max(1, village.buildF), village.buildRot);
                if (Scaffold.approach(v, village, world, target, site)) return worked;
                far = bx * bx + bz * bz > Scaffold.REACH * Scaffold.REACH; // no way up: he does what he can from below
            }
            if (far) {
                Move.to(v, (mine ? origin : target).getX() + 0.5, mine || border ? target.getY() : origin.getY(), (mine ? origin : target).getZ() + 0.5, 0.6);
                return worked;
            }

            Result r = execute(world, village, step, target, village.buildRot);
            if (r == Result.WAIT) { // out of materials: go and get some instead of standing around
                Needs.Need need = needFor(step.kind());
                boolean any = switch (step.kind()) {
                    case FLOOR, ROOF, WALL, PILLAR, STONE, STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W, SEAT_W, SEAT_E -> true;
                    default -> false;
                };
                markShort(village, need, any);
                Life.askForHelp(v, village, world, need);
                if (Scavenge.work(v, village, world, need, any)) {
                    if (Needs.get(v) != Needs.Need.FOOD) Needs.set(v, Needs.Need.NONE);
                    Speech.say(v, "settlements.say.build.scavenge");
                    return true;
                }
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
                v.settlementsCarry--;
                ops++;
                worked = true;
                // the block in hand, facing the spot, like a player laying it
                Gear.face(v, target);
                Item held = world.getBlockState(target).getBlock().asItem();
                if (held != Items.AIR) v.setStackInHand(v.getDominantHand(), new ItemStack(held));
                v.swingHand(v.getDominantHand());
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
        boolean started = startRing(village, world, "townhall", 0, 0, 0, 0, 3);
        if (!started) {
            Layout.Slot hs = Layout.center(world, village, "townhall");
            started = hs != null && startAt(village, "townhall", 0, 0, 0, hs);
        }
        if (!started) started = start(village, world, "townhall", Blueprints.HALL_W, Blueprints.HALL_D, 0);
        if (!started) started = roughSite(village, world); // hills and forest: the founders level a site
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
        String[] types = {"townhall", "cruck", "wealden", "rowhouse", "hut", "house", "longhouse", "porch", "twin", "farmhouse", "tallhouse", "annex", "manor", "mansion", "storage", "granary", "sawmill", "charcoal", "mason", "mill",
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
                        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                        BlockPos g = new BlockPos(x + dx, y - 1, z + dz);
                        if (!world.getFluidState(g).isEmpty()) {
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
                if (taken) continue;
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
        if (planks(payer) < 120 || count(payer, WOOL) < 3) return false;
        for (Step step : steps) {
            Result r = execute(world, payer, step, origin.add(step.dx(), step.dy(), step.dz()), 0);
            if (r == Result.ABORT) return false;
        }
        return true;
    }

    private static Direction turn(Direction base, int rot) {
        return Direction.fromHorizontal(base.getHorizontal() + rot);
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

    private static void finish(Village village) {
        BlockPos o = village.buildOrigin;
        if (o == null) return;
        if (village.getWorld() != null) Scaffold.takeDown(village, village.getWorld()); // the building stands: down with the scaffolding
        String type = village.buildType;
        int rot = village.buildRot;
        if (village.buildRepair) { // a repair changes nothing in the books
            village.buildRepair = false;
            Life.news(village, "repaired", Text.translatable("settlements.building." + Buildings.base(type)));
            village.buildOrigin = null;
            village.buildStep = 0;
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
        if (world != null && !type.equals("upgrade") && !type.equals("hallup") && !type.equals("mine") && !type.equals("wall") && !type.equals("palisade")) {
            if (Buildings.base(type).equals("well")) {
                Roads.plaza(world, village);
            } else {
                int[] fp = Blueprints.footprint(type, village.buildW, village.buildD);
                int[] front = Blueprints.rotate(fp[0] / 2, fp[1], fp[0], fp[1], rot);
                BlockPos fr = o.add(front[0], 0, front[1]);
                Roads.connect(world, village, fr);
            }
        }
        if (!type.equals("wall") || village.wallBuilt) Life.news(village, "built", Text.translatable("settlements.building." + Buildings.base(type)));
        if (world != null) {
            int[] bfp = Blueprints.footprint(type, village.buildW, village.buildD);
            for (Step s : plan(village)) {
                if (s.kind() != Kind.BED_FOOT) continue;
                int[] r = Blueprints.rotate(s.dx(), s.dz(), bfp[0], bfp[1], rot);
                net.mca.server.world.data.VillageManager.get(world).reportBuilding(o.add(r[0], s.dy(), r[1]));
                break;
            }
        }
        village.buildOrigin = null;
        village.buildStep = 0;
        village.markDirty();
    }

    private static void registerSmithy(Village village) {
        BlockPos o = village.buildOrigin;
        int[] fp = Blueprints.footprint(village.buildType, 0, 0);
        for (Step step : plan(village)) {
            int[] r = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], village.buildRot);
            BlockPos p = o.add(r[0], step.dy(), r[1]);
            if (step.kind() == Kind.FURNACE) village.smithyFurnace = p;
            else if (step.kind() == Kind.ANVIL) village.smithyAnvil = p;
        }
    }

    /** Reads the finished tavern's working blocks back out of its plan. */
    private static void registerTavern(Village village) {
        BlockPos o = village.buildOrigin;
        int[] fp = Blueprints.footprint(village.buildType, 0, 0);
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
                default -> {
                }
            }
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
            case SHUTTER, POT, HEDGE, PATH, STEP, BELL, SMOKE, LANTERN, CHIMNEY, FLOWER -> true;
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
                     STONECUTTER, HAY, COMPOSTER, PUMPKIN, CAULDRON, FLOWER, LOG_X, LOG_Z, WOOL, TARGET, CHAIN, CLOCK -> Result.SKIPPED;
                default -> Result.WAIT;
            };
        }
        if (step.kind() == Kind.LANTERN) {
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
            case GLASS, FENCE, BARS, GATE, STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W, RSTAIR_S, RSTAIR_N, PEW, SEAT_W, SEAT_E, STEP, CHAIN, LOG_X, LOG_Z -> state = Block.postProcessState(state, world, target);
            default -> {
            }
        }
        world.setBlockState(target, state);
        if (step.kind() == Kind.CHEST || step.kind() == Kind.HALL_CHEST) pairChest(world, target, state);
        // the floor rests on the ground: under it a stone footing goes down to solid earth
        if (step.dy() == 0 && needsFooting(step.kind())) foundation(world, village, target.down());
        if (step.kind() == Kind.CHEST) village.lootChests.add(target.toImmutable());
        if (step.kind() == Kind.HALL_CHEST) village.hallChests.add(target.toImmutable());
        if (step.kind() == Kind.LECTERN) village.hallPos = target.toImmutable();
        if (step.kind() == Kind.SIGN) {
            village.boardSigns.add(target.toImmutable());
            Board.refresh(world, village);
        }
        if (step.kind() == Kind.DOOR) {
            world.setBlockState(target.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        world.playSound(null, target, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 1.0f);
        return Result.PLACED;
    }

    /** Everything standing on the ground plane gets a footing; small things on top of others do not. */
    private static boolean needsFooting(Kind k) {
        return switch (k) {
            case CARPET, TORCH, CROP, FARMLAND, WATER, PLATE, SIGN, LANTERN, SMOKE, POT, FLOWER, HEDGE, PATH, DIG, CLEAR -> false;
            default -> true;
        };
    }

    /** Two chests side by side, facing the same way, make one double chest. */
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
        for (int i = 0; i < 6; i++) {
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
                world.setBlockState(p, paid ? stone.getDefaultState() : Blocks.DIRT.getDefaultState());
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
                    if (take(village, LOG, 1)) return pal.log().getDefaultState();
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
                    if (take(village, COBBLE, 1)) return pal.accent().getDefaultState();
                }
                case WALL -> { // a city: a stone ground floor, plastered storeys above
                    if (city && take(village, COBBLE, 1)) return (step.dy() <= 3 ? pal.accent() : pal.plaster()).getDefaultState();
                }
                case ROOF -> { // and tiled roofs
                    if (city && take(village, COBBLE, 1)) return pal.tiles().getDefaultState();
                }
                case STAIRS_E, STAIRS_W -> {
                    if (city && step.dy() >= Blueprints.LEVEL_HEIGHT && take(village, COBBLE, 1))
                        return pal.tileStairs().getDefaultState().with(StairsBlock.FACING, turn(kind == Kind.STAIRS_E ? Direction.EAST : Direction.WEST, rot));
                }
                case PATH -> { // paved paths
                    if (city && take(village, COBBLE, 1)) return pal.accent().getDefaultState();
                }
                default -> {
                }
            }
        }
        switch (kind) {
            case FLOOR:
                return plank(village, pal.planks());
            case ROOF:
                return plank(village, pal.roofPlanks());
            case WALL:
                return plank(village, pal.planks());
            case PILLAR: {
                if (take(village, LOG, 1)) return pal.log().getDefaultState();
                return plank(village, pal.planks());
            }
            case BEAM_X, BEAM_Z: {
                Direction.Axis axis = (kind == Kind.BEAM_X) == ((rot & 1) == 0) ? Direction.Axis.X : Direction.Axis.Z;
                if (take(village, LOG, 1)) return pal.strippedLog().getDefaultState().with(PillarBlock.AXIS, axis);
                return plank(village, pal.planks());
            }
            case ACCENT: {
                if (take(village, COBBLE, 1)) return pal.accent().getDefaultState();
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
            case SMOKE:
                return Blocks.CAMPFIRE.getDefaultState().with(CampfireBlock.SIGNAL_FIRE, false);
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
                if (!take(village, s -> s.isOf(Items.TORCH), 1)) return null;
                return Blocks.LANTERN.getDefaultState();
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
                return pal.stairs().getDefaultState().with(StairsBlock.FACING, face(step, rot));
            case LOG_X, LOG_Z: {
                Direction.Axis axis = (kind == Kind.LOG_X) == ((rot & 1) == 0) ? Direction.Axis.X : Direction.Axis.Z;
                if (!take(village, LOG, 1)) return null;
                return pal.log().getDefaultState().with(PillarBlock.AXIS, axis);
            }
            case GLASS: {
                if (take(village, GLASS, 1)) return Blocks.GLASS_PANE.getDefaultState();
                // no glass yet: sand from the stores is melted into a pane, otherwise a wooden lattice keeps the window
                if (take(village, s -> s.isOf(Items.SAND) || s.isOf(Items.RED_SAND), 1)) return Blocks.GLASS_PANE.getDefaultState();
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
                if (planks(village) < 3 || count(village, WOOL) < 3) return null;
                take(village, WOOL, 3);
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
                // the land's roofing: straw (wheat or a bale), sod in the north (earth), bamboo in the jungle; boards if none
                Block th = pal.thatch();
                if (th == Blocks.HAY_BLOCK && (take(village, s -> s.isOf(Items.HAY_BLOCK), 1) || take(village, s -> s.isOf(Items.WHEAT), 2))) return th.getDefaultState();
                if (th == Blocks.MOSS_BLOCK && (FREE || take(village, s -> s.isOf(Items.DIRT) || s.isOf(Items.MOSS_BLOCK) || s.isOf(Items.GRASS_BLOCK), 1))) return th.getDefaultState();
                if (th == Blocks.BAMBOO_BLOCK && (FREE || take(village, s -> s.isOf(Items.BAMBOO), 4) || take(village, s -> s.isOf(Items.BAMBOO_BLOCK), 1))) return th.getDefaultState();
                if (FREE) return th.getDefaultState();
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
                if (!take(village, COBBLE, 1)) return null;
                return pal.stoneStairs().getDefaultState().with(StairsBlock.FACING, facing);
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
                if (!take(village, COBBLE, 1)) return null;
                return pal.stoneStairs().getDefaultState().with(StairsBlock.FACING, facing);
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
                if (take(village, COBBLE, 1)) return (Buildings.has(village, "mason") ? pal.accent() : pal.stone()).getDefaultState();
                return plank(village, pal.planks()); // stone or earth from around, or wood
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
            case CROP:
                if (!take(village, x -> x.isOf(Items.WHEAT_SEEDS), 1)) return null;
                return Blocks.WHEAT.getDefaultState();
            default:
                return null;
        }
    }

    /** One plank's worth of building block: placed in the village's own wood, or stone and earth when wood is out. */
    private static BlockState plank(Village village, Block preferred) {
        Item p = takePlank(village);
        if (p != null) return preferred.getDefaultState();
        return plank(village);
    }

    private static BlockState plank(Village village) {
        Item p = takePlank(village);
        if (p == null) {
            // no wood left: build from whatever stone and earth the villagers have gathered around
            for (ItemStack s : village.storageBuffer) {
                if (!Scavenge.isFiller(s)) continue;
                Block fb = Block.getBlockFromItem(s.getItem());
                s.decrement(1);
                village.storageBuffer.removeIf(ItemStack::isEmpty);
                village.markDirty();
                return fb.getDefaultState();
            }
            return null;
        }
        Block b = Block.getBlockFromItem(p);
        return b == Blocks.AIR ? Blocks.OAK_PLANKS.getDefaultState() : b.getDefaultState();
    }
}
