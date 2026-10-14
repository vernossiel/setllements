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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

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

    private static List<Step> plan(Village v) {
        String key = v.buildType + "|" + v.buildW + "|" + v.buildD + "|" + v.buildF
                + (v.buildType.equals("wall") && v.buildOrigin != null ? "|" + v.buildOrigin.asLong() : "");
        return CACHE.computeIfAbsent(key, k -> switch (v.buildType) {
            case "house" -> Blueprints.house(v.buildW, v.buildD, v.buildF);
            case "upgrade" -> Blueprints.addFloor(v.buildW, v.buildD, v.buildF);
            case "storage" -> Blueprints.storage();
            case "smithy" -> Blueprints.smithy();
            case "armory" -> Blueprints.armory();
            case "tavern" -> Blueprints.tavern();
            case "townhall" -> Blueprints.townhall();
            case "hallup" -> Blueprints.hallUpgrade(v.buildW, v.buildD, v.buildF);
            case "pen" -> Blueprints.pen();
            case "mine" -> Blueprints.mine();
            case "wall" -> wallPlan(v);
            default -> List.of();
        });
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

    private static int count(Village v, Predicate<ItemStack> p) {
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
                v.storageBuffer.add(0, new ItemStack(planks, 3));
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
        return planks(v);
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
                    if (slot != null) return startAt(village, "well", 0, 0, 0, slot);
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
                    if (startRing(village, world, "townhall", 0, 0, 0, 0, 1)) return true;
                }
                case "townhall" -> village.hall = new House(o, Blueprints.HALL_W, Blueprints.HALL_D, 1, rot);
            case "hallup" -> {
                if (village.hall != null) village.hall = village.hall.withFloors(village.hall.floors() + 1);
            }
            case "pen" -> {
                    if (startRing(village, world, "pen", 0, 0, 0, 2, 4)) return true;
                }
                case "tavern" -> {
                    if (startRing(village, world, "tavern", 0, 0, 0, 0, 3)) return true;
                }
                case "wall" -> {
                    BlockPos c = new BlockPos(village.getCenter());
                    int half = 46;
                    int cy = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
                    village.buildType = "wall";
                    village.buildOrigin = new BlockPos(c.getX() - half, cy, c.getZ() - half);
                    village.buildRot = 0;
                    village.buildW = half * 2 + 1;
                    village.buildD = half * 2 + 1;
                    village.buildF = 0;
                    village.buildStep = 0;
                    village.markDirty();
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static boolean startRing(Village village, ServerWorld world, String type, int w, int d, int floors, int minRing, int maxRing) {
        Layout.Slot slot = Layout.find(world, village, type, w, d, minRing, maxRing);
        if (slot == null) return false;
        return startAt(village, type, w, d, floors, slot);
    }

    private static boolean startAt(Village village, String type, int w, int d, int floors, Layout.Slot slot) {
        village.buildType = type;
        village.buildOrigin = slot.origin();
        village.buildRot = slot.rot();
        village.buildW = w;
        village.buildD = d;
        village.buildF = floors;
        village.buildStep = 0;
        village.plots.add(new int[]{slot.origin().getX(), slot.origin().getZ(), slot.fw(), slot.fd()});
        announceProject = type;
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
        if (village.buildOrigin == null && !plan(village, world)) {
            Needs.Need blocker = Plan.firstBlocker(village);
            if (Needs.get(v) != Needs.Need.FOOD) Needs.set(v, blocker);
            return false;
        }

        if (announceProject != null) {
            Speech.say(v, "settlements.say.build." + announceProject);
            announceProject = null;
        }
        List<Step> steps = plan(village);
        if (village.buildStep >= steps.size()) {
            finish(village);
            return false;
        }

        BlockPos origin = village.buildOrigin;
        boolean worked = false;
        int ops = 0, skips = 0;
        while (ops < opsPerSecond && skips < MAX_TRIVIAL_SKIPS && village.buildOrigin != null && village.buildStep < steps.size()) {
            Step step = steps.get(village.buildStep);
            int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
            int[] rp = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], village.buildRot);
            BlockPos target = origin.add(rp[0], step.dy(), rp[1]);

            // the mine is dug from its entrance, everything else is built standing next to the block
            double limit = village.buildType.equals("mine") ? 12 * 12 : 6 * 6;
            BlockPos stand = village.buildType.equals("mine") ? origin : target;
            if (v.squaredDistanceTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > limit) {
                v.getNavigation().startMovingTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.6);
                return worked;
            }

            Result r = execute(world, village, step, target, village.buildRot);
            if (r == Result.WAIT) { // waiting for materials: tell the player what would help
                Needs.set(v, needFor(step.kind()));
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
                ops++;
                worked = true;
                v.swingHand(v.getDominantHand());
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

    private static void finish(Village village) {
        BlockPos o = village.buildOrigin;
        if (o == null) return;
        String type = village.buildType;
        int rot = village.buildRot;
        switch (type) {
            case "house" -> village.houses.add(new House(o, village.buildW, village.buildD, village.buildF, rot));
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
                int[] tr = Blueprints.rotate(2, 2, Blueprints.TOWER, Blueprints.TOWER, rot);
                village.watchPoints.add(o.add(tr[0], 11, tr[1]));
                int[] lr = Blueprints.rotate(2, 1, Blueprints.TOWER, Blueprints.TOWER, rot);
                village.towerLadders.add(o.add(lr[0], 1, lr[1]));
            }
            case "tavern" -> registerTavern(village);
            case "pen" -> {
                int[] pr = Blueprints.rotate(Blueprints.PEN / 2, Blueprints.PEN / 2, Blueprints.PEN, Blueprints.PEN, rot);
                village.penCenter = o.add(pr[0], 0, pr[1]);
            }
            case "wall" -> village.wallBuilt = true;
            default -> {
            }
        }
        ServerWorld world = village.getWorld();
        if (world != null && !type.equals("upgrade") && !type.equals("hallup") && !type.equals("mine") && !type.equals("wall")) {
            if (type.equals("well")) {
                Roads.plaza(world, village);
            } else {
                int[] fp = Blueprints.footprint(type, village.buildW, village.buildD);
                int[] front = Blueprints.rotate(fp[0] / 2, fp[1], fp[0], fp[1], rot);
                BlockPos fr = o.add(front[0], 0, front[1]);
                Roads.connect(world, village, fr);
            }
        }
        village.buildOrigin = null;
        village.buildStep = 0;
        village.markDirty();
    }

    private static void registerSmithy(Village village) {
        BlockPos o = village.buildOrigin;
        int[] fp = Blueprints.footprint("smithy", 0, 0);
        for (Step step : Blueprints.smithy()) {
            int[] r = Blueprints.rotate(step.dx(), step.dz(), fp[0], fp[1], village.buildRot);
            BlockPos p = o.add(r[0], step.dy(), r[1]);
            if (step.kind() == Kind.FURNACE) village.smithyFurnace = p;
            else if (step.kind() == Kind.ANVIL) village.smithyAnvil = p;
        }
    }

    /** Reads the finished tavern's working blocks back out of its plan. */
    private static void registerTavern(Village village) {
        BlockPos o = village.buildOrigin;
        int[] fp = Blueprints.footprint("tavern", 0, 0);
        BlockPos smoker = null, barrel = null, craft = null;
        List<BlockPos> seats = new java.util.ArrayList<>();
        for (Step step : Blueprints.tavern()) {
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
                for (ItemStack drop : Block.getDroppedStacks(old, world, target, world.getBlockEntity(target))) {
                    Workforce.addToTreasury(village, drop);
                }
            }
            world.setBlockState(target, Blocks.AIR.getDefaultState());
            return Result.PLACED;
        }

        BlockState state = stateFor(village, step.kind(), rot);
        if (state == null) {
            // glass is optional: no glass in stock means an open window
            return step.kind() == Kind.GLASS || step.kind() == Kind.TORCH || step.kind() == Kind.CROP || step.kind() == Kind.CARPET ? Result.SKIPPED : Result.WAIT;
        }
        if (step.kind() == Kind.ARMOR_STAND) {
            var stand = net.minecraft.entity.EntityType.ARMOR_STAND.create(world);
            if (stand != null) {
                stand.refreshPositionAndAngles(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 180f, 0f);
                world.spawnEntity(stand);
            }
            return Result.PLACED;
        }
        world.setBlockState(target, state);
        if (step.kind() == Kind.CHEST) village.lootChests.add(target.toImmutable());
        if (step.kind() == Kind.HALL_CHEST) village.hallChests.add(target.toImmutable());
        if (step.kind() == Kind.LECTERN) village.hallPos = target.toImmutable();
        if (step.kind() == Kind.DOOR) {
            world.setBlockState(target.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        world.playSound(null, target, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 1.0f);
        return Result.PLACED;
    }

    /** Pays for the block and returns what to place, or null if the treasury cannot pay. */
    private static BlockState stateFor(Village village, Kind kind, int rot) {
        switch (kind) {
            case FLOOR, ROOF:
                return plank(village);
            case WALL: {
                if (take(village, COBBLE, 1)) return Blocks.COBBLESTONE.getDefaultState();
                return plank(village);
            }
            case PILLAR: {
                if (take(village, LOG, 1)) return Blocks.OAK_LOG.getDefaultState();
                return plank(village);
            }
            case GLASS: {
                if (!take(village, GLASS, 1)) return null;
                return Blocks.GLASS_PANE.getDefaultState();
            }
            case DOOR:
                if (!takePlanks(village, 6)) return null;
                return Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, turn(Direction.SOUTH, rot)).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
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
                if (take(village, COBBLE, 1)) return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, facing);
                if (!takePlanks(village, 1)) return null;
                return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, facing);
            }
            case BOOKSHELF:
                if (!takePlanks(village, 6)) return null;
                return Blocks.BOOKSHELF.getDefaultState();
            case CARPET: {
                if (!take(village, WOOL, 1)) return null;
                return Blocks.RED_CARPET.getDefaultState();
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
                return Blocks.OAK_PRESSURE_PLATE.getDefaultState();
            }
            case SEAT_W, SEAT_E: {
                Direction facing = turn(kind == Kind.SEAT_W ? Direction.WEST : Direction.EAST, rot);
                if (take(village, COBBLE, 1)) return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, facing);
                if (!takePlanks(village, 1)) return null;
                return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, facing);
            }
            case STONE: {
                if (!take(village, COBBLE, 1)) return null;
                return Blocks.COBBLESTONE.getDefaultState();
            }
            case FENCE:
                if (!takePlanks(village, 1)) return null;
                return Blocks.OAK_FENCE.getDefaultState();
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

    private static BlockState plank(Village village) {
        Item p = takePlank(village);
        if (p == null) return null;
        Block b = Block.getBlockFromItem(p);
        return b == Blocks.AIR ? Blocks.OAK_PLANKS.getDefaultState() : b.getDefaultState();
    }
}
