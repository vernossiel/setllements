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
    private static final int OPS_PER_SECOND = 2;
    private static final int MAX_TRIVIAL_SKIPS = 60;
    private static final Map<String, List<Step>> CACHE = new HashMap<>();

    private Builder() {
    }

    // ---------- plans ----------

    private static List<Step> plan(Village v) {
        String key = v.buildType + "|" + v.buildW + "|" + v.buildD + "|" + v.buildF;
        return CACHE.computeIfAbsent(key, k -> switch (v.buildType) {
            case "house" -> Blueprints.house(v.buildW, v.buildD, v.buildF);
            case "upgrade" -> Blueprints.addFloor(v.buildW, v.buildD, v.buildF);
            case "storage" -> Blueprints.storage();
            case "smithy" -> Blueprints.smithy();
            case "armory" -> Blueprints.armory();
            case "mine" -> Blueprints.mine();
            default -> List.of();
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

    // ---------- planner ----------

    private static boolean plan(Village village, ServerWorld world) {
        int planks = planks(village);
        boolean full = village.getPopulation() >= village.getMaxPopulation();
        var rand = world.random;

        if (!village.storageBuilt && village.getPopulation() >= 3 && planks >= 150) {
            return start(village, world, "storage", 11, 7, 0);
        }
        if (full) {
            for (House h : village.houses) {
                if (h.floors() < 3 && h.d() >= 7 && planks >= 90) {
                    village.buildType = "upgrade";
                    village.buildOrigin = h.origin();
                    village.buildW = h.w();
                    village.buildD = h.d();
                    village.buildF = h.floors();
                    village.buildStep = 0;
                    village.markDirty();
                    return true;
                }
            }
            if (planks >= 300) return start(village, world, "house", 6 + rand.nextInt(3), 7 + rand.nextInt(2), 3);
            if (planks >= 160) return start(village, world, "house", 6 + rand.nextInt(3), 7 + rand.nextInt(2), 2);
            if (planks >= 70) return start(village, world, "house", 5, 5, 1);
        }
        if (!village.mineBuilt && planks >= 40 && count(village, COBBLE) < 128) {
            return start(village, world, "mine", 4, 4, 0);
        }
        int iron = count(village, s -> s.isOf(Items.IRON_INGOT));
        if (!village.smithyBuilt && village.storageBuilt && iron >= 40 && count(village, COBBLE) >= 12 && planks >= 100) {
            return start(village, world, "smithy", 10, 6, 0);
        }
        if (!village.armoryBuilt && village.smithyBuilt && village.getPopulation() >= 6 && planks >= 150) {
            return start(village, world, "armory", 10, 7, 0);
        }
        return false;
    }

    private static boolean start(Village village, ServerWorld world, String type, int w, int d, int floors) {
        int fw = w, fd = d;
        BlockPos site = findSite(world, village, fw, fd);
        if (site == null) return false;
        village.buildType = type;
        village.buildOrigin = site;
        village.buildW = w;
        village.buildD = d;
        village.buildF = floors;
        village.buildStep = 0;
        village.markDirty();
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
        if (village.buildOrigin == null && !plan(village, world)) return false;

        List<Step> steps = plan(village);
        if (village.buildStep >= steps.size()) {
            finish(village);
            return false;
        }

        BlockPos origin = village.buildOrigin;
        boolean worked = false;
        int ops = 0, skips = 0;
        while (ops < OPS_PER_SECOND && skips < MAX_TRIVIAL_SKIPS && village.buildOrigin != null && village.buildStep < steps.size()) {
            Step step = steps.get(village.buildStep);
            BlockPos target = origin.add(step.dx(), step.dy(), step.dz());

            // the mine is dug from its entrance, everything else is built standing next to the block
            double limit = village.buildType.equals("mine") ? 12 * 12 : 6 * 6;
            BlockPos stand = village.buildType.equals("mine") ? origin : target;
            if (v.squaredDistanceTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > limit) {
                v.getNavigation().startMovingTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.6);
                return worked;
            }

            Result r = execute(world, village, step, target);
            if (r == Result.WAIT) return worked; // waiting for materials
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

    private enum Result { PLACED, SKIPPED, WAIT, ABORT }

    private static void finish(Village village) {
        BlockPos o = village.buildOrigin;
        if (o == null) return;
        switch (village.buildType) {
            case "house" -> village.houses.add(new House(o, village.buildW, village.buildD, village.buildF));
            case "upgrade" -> {
                for (int i = 0; i < village.houses.size(); i++) {
                    House h = village.houses.get(i);
                    if (h.origin().equals(o)) village.houses.set(i, new House(o, h.w(), h.d(), h.floors() + 1));
                }
            }
            case "storage" -> village.storageBuilt = true;
            case "smithy" -> village.smithyBuilt = true;
            case "armory" -> village.armoryBuilt = true;
            case "mine" -> village.mineBuilt = true;
            default -> {
            }
        }
        village.buildOrigin = null;
        village.buildStep = 0;
        village.markDirty();
    }

    private static Result execute(ServerWorld world, Village village, Step step, BlockPos target) {
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

        BlockState state = stateFor(village, step.kind());
        if (state == null) {
            // glass is optional: no glass in stock means an open window
            return step.kind() == Kind.GLASS || step.kind() == Kind.TORCH ? Result.SKIPPED : Result.WAIT;
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
        if (step.kind() == Kind.DOOR) {
            world.setBlockState(target.up(), state.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        world.playSound(null, target, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 1.0f);
        return Result.PLACED;
    }

    /** Pays for the block and returns what to place, or null if the treasury cannot pay. */
    private static BlockState stateFor(Village village, Kind kind) {
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
                return Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            case CHEST:
                if (!takePlanks(village, 8)) return null;
                return Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH);
            case BED_FOOT: {
                if (planks(village) < 3 || count(village, WOOL) < 3) return null;
                take(village, WOOL, 3);
                takePlanks(village, 3);
                return Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.SOUTH).with(BedBlock.PART, BedPart.FOOT);
            }
            case BED_HEAD:
                return Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.SOUTH).with(BedBlock.PART, BedPart.HEAD);
            case TORCH: {
                if (!take(village, s -> s.isOf(Items.TORCH), 1)) return null;
                return Blocks.TORCH.getDefaultState();
            }
            case ANVIL: {
                if (!take(village, s -> s.isOf(Items.IRON_INGOT), 31)) return null;
                return Blocks.ANVIL.getDefaultState();
            }
            case FURNACE: {
                if (!take(village, COBBLE, 8)) return null;
                return Blocks.FURNACE.getDefaultState().with(AbstractFurnaceBlock.FACING, Direction.SOUTH);
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
            case STAIRS_N, STAIRS_S: {
                Direction facing = kind == Kind.STAIRS_N ? Direction.NORTH : Direction.SOUTH;
                if (take(village, COBBLE, 1)) return Blocks.COBBLESTONE_STAIRS.getDefaultState().with(StairsBlock.FACING, facing);
                if (!takePlanks(village, 1)) return null;
                return Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, facing);
            }
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
