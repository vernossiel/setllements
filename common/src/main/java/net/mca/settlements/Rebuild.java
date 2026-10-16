package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Building;
import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The village grows out of its old buildings: one at a time, the builder takes down a house that came with the world
 * (everything salvaged goes to the stores, chests are emptied first), levels the ground and raises one of the village's
 * own buildings in its place. Homes become huts, houses, family houses or two-storey houses as the village can afford;
 * an old smithy, mason's, storehouse, inn or prison becomes the village's own.
 */
public final class Rebuild {
    private static final Map<Integer, List<BlockPos>> PLANS = new HashMap<>();
    private static final Map<Integer, int[]> OLD_BOX = new HashMap<>(); // x0, y0, z0, x1, y1, z1 of the old building
    private static final Set<String> SKIPPED = new HashSet<>();
    private static final String PREFIX = "demolish:";

    private Rebuild() {
    }

    public static boolean active(Village village) {
        return village.buildType != null && village.buildType.startsWith(PREFIX);
    }

    /** What an old building becomes; null if it is left alone. */
    private static String replacement(Village village, Building b) {
        String t = b.getType();
        switch (t) {
            case "town_center", "graveyard", "blocked" -> {
                return null;
            }
            case "armorer", "armory", "blacksmith", "weaponsmith", "toolsmith" -> {
                if (!village.smithyBuilt) return "smithy";
            }
            case "mason" -> {
                if (!Buildings.has(village, "mason")) return "mason";
            }
            case "fletcher" -> {
                if (!Buildings.has(village, "sawmill")) return "sawmill";
            }
            case "storage" -> {
                if (!village.storageBuilt) return "storage";
            }
            case "inn" -> {
                if (village.tavern == null) return "tavern";
            }
            case "prison" -> {
                if (!Buildings.has(village, "jail")) return "jail";
            }
            default -> {
            }
        }
        if (b.getBuildingType().grouped()) return null;
        Plan.Level lv = Plan.level(village);
        int w = b.getPos1().getX() - b.getPos0().getX(), d = b.getPos1().getZ() - b.getPos0().getZ();
        int area = Math.max(1, w) * Math.max(1, d);
        int pick = Math.floorMod(b.getId() * 7 + area, 3);
        if ((lv == Plan.Level.TOWN || lv == Plan.Level.FORTRESS) && area >= 40) return new String[]{"manor", "tallhouse", "porch"}[pick];
        if (lv != Plan.Level.HAMLET && area >= 60) return new String[]{"annex", "farmhouse", "cottage"}[pick];
        if (area <= 30) return lv == Plan.Level.HAMLET ? new String[]{"hut", "hut", "twin"}[pick] : new String[]{"house", "porch", "twin"}[pick];
        return new String[]{"house", "longhouse", "porch"}[pick];
    }

    private static boolean ours(Village village, BlockPos p) {
        for (Built b : village.built) if (b.contains(p)) return true;
        return false;
    }

    /** The next old building worth replacing, or null. */
    public static Building candidate(Village village) {
        Building best = null;
        int bestSize = Integer.MAX_VALUE;
        for (Building b : village.getBuildings().values()) {
            if (SKIPPED.contains(village.getId() + ":" + b.getId())) continue;
            if (ours(village, b.getCenter())) continue;
            if (replacement(village, b) == null) continue;
            int size = b.getSize();
            if (size > 0 && size < bestSize) { // the smallest first: the cheapest to replace
                bestSize = size;
                best = b;
            }
        }
        return best;
    }

    private static int[] dims(String type) {
        Templates.Template t = Templates.of(type);
        if (t != null) return new int[]{t.w(), t.d(), 1};
        return Builder.SHOWCASE.getOrDefault(type, new int[]{0, 0, 0});
    }

    /** Takes on the next old building: from now on the builder's project is to take it down. */
    public static boolean start(Village village, ServerWorld world) {
        Building old = candidate(village);
        if (old == null) return false;
        String type = Tiers.design(replacement(village, old), Prosperity.tier(village), world.random, village);
        int[] dm = dims(type);
        int[] fp = Blueprints.footprint(type, dm[0], dm[1]);
        BlockPos c = old.getCenter();
        BlockPos vc = new BlockPos(village.getCenter());
        double dx = vc.getX() - c.getX(), dz = vc.getZ() - c.getZ();
        int rot = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? 1 : 3) : (dz > 0 ? 0 : 2);
        int fw = (rot & 1) == 0 ? fp[0] : fp[1], fd = (rot & 1) == 0 ? fp[1] : fp[0];
        int x0 = c.getX() - fw / 2, z0 = c.getZ() - fd / 2;

        // the new building must not run into anything else of the village
        for (int x = x0 - 1; x <= x0 + fw; x++) {
            for (int z = z0 - 1; z <= z0 + fd; z++) {
                BlockPos p = new BlockPos(x, c.getY(), z);
                boolean clash = ours(village, p);
                for (Building other : village.getBuildings().values()) if (other != old && !other.getBuildingType().grouped() && other.containsPos(p)) clash = true;
                if (clash) {
                    SKIPPED.add(village.getId() + ":" + old.getId());
                    return false;
                }
            }
        }
        // the ground level: where the land around the old building lies
        List<Integer> ys = new ArrayList<>();
        for (int x = x0 - 2; x <= x0 + fw + 1; x++) {
            for (int z : new int[]{z0 - 2, z0 + fd + 1}) ys.add(world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z));
        }
        java.util.Collections.sort(ys);
        int y = ys.get(ys.size() / 2);

        village.buildType = PREFIX + type;
        village.buildOrigin = new BlockPos(x0, y, z0);
        village.buildW = dm[0];
        village.buildD = dm[1];
        village.buildF = dm[2];
        village.buildRot = rot;
        village.buildStep = 0;
        village.plots.add(new int[]{x0, z0, fw, fd});
        BlockPos a = old.getPos0(), b = old.getPos1();
        OLD_BOX.put(village.getId(), new int[]{a.getX() - 2, a.getY() - 1, a.getZ() - 2, b.getX() + 2, b.getY() + 5, b.getZ() + 2});
        PLANS.remove(village.getId());
        village.removeBuilding(old.getId()); // its residents look for a new home; they get one here soon
        village.markDirty();
        Builder.announceProject = "rebuild";
        return true;
    }

    // ---------- renovation: the village's own buildings, rebuilt better as it climbs ----------

    private static String renoKey(Village village, Built b) {
        return village.getId() + ":r:" + b.origin().asLong();
    }

    /** The building most behind the village's station, or null. */
    public static Built renovation(Village village) {
        // first what a household has paid for
        for (Long origin : village.commissions.keySet()) {
            for (Built b : village.built) if (b.origin().asLong() == origin && !SKIPPED.contains(renoKey(village, b))) return b;
        }
        int tier = Prosperity.tier(village);
        Built best = null;
        int bestGap = 0;
        for (Built b : village.built) {
            if (SKIPPED.contains(renoKey(village, b)) || !Tiers.outdated(b.type(), tier)) continue;
            int gap = tier - Tiers.tierOf(b.type()) + (Tiers.isHome(b.type()) ? 0 : 1); // the public buildings first
            if (gap > bestGap) {
                bestGap = gap;
                best = b;
            }
        }
        return best;
    }

    /** A commission that cannot be carried out: the money goes back to whoever paid it. */
    static void refund(Village village, ServerWorld world, java.util.UUID payer) {
        if (payer == null) return;
        if (world.getEntity(payer) instanceof VillagerEntityMCA v) Money.give(v.getInventory(), Money.take(village, Home.COMMISSION));
    }

    /** The station a commissioned house is built to: one above the village's own. */
    static int commissionTier(Village village) {
        return Math.min(Tiers.CITY, Prosperity.tier(village) + 1);
    }

    /** Whether a house can be commissioned better: it is the village's own, and below what a commission would build. */
    static boolean canCommission(Village village, Built b) {
        return Tiers.isHome(b.type()) && Tiers.tierOf(b.type()) >= 0 && Tiers.tierOf(b.type()) < commissionTier(village)
                && !SKIPPED.contains(renoKey(village, b)) && !village.commissions.containsKey(b.origin().asLong());
    }

    /** Takes on the next outdated building: down it comes, and a better one goes up in its place. */
    public static boolean renovate(Village village, ServerWorld world) {
        Built old = renovation(village);
        if (old == null) return false;
        java.util.UUID payer = village.commissions.remove(old.origin().asLong());
        int tier = payer != null ? commissionTier(village) : Prosperity.tier(village); // a house paid for is built a station better than the village's
        boolean home = Tiers.isHome(old.type());
        String type = Tiers.design(home ? "home" : Buildings.base(old.type()), tier, world.random, village);
        if (Tiers.tierOf(type) <= Tiers.tierOf(old.type())) {
            SKIPPED.add(renoKey(village, old));
            refund(village, world, payer);
            return false;
        }
        int[] dm = type.equals("house") ? new int[]{9, 8, 2} : dims(type);
        int[] fp = Blueprints.footprint(type, dm[0], dm[1]);
        int rot = old.rot();
        int fw = (rot & 1) == 0 ? fp[0] : fp[1], fd = (rot & 1) == 0 ? fp[1] : fp[0];
        int cx = old.origin().getX() + old.fw() / 2, cz = old.origin().getZ() + old.fd() / 2;
        int x0 = cx - fw / 2, z0 = cz - fd / 2;
        int y = old.origin().getY();
        // the new building must not run into anything else of the village
        for (int x = x0 - 1; x <= x0 + fw; x++) {
            for (int z = z0 - 1; z <= z0 + fd; z++) {
                BlockPos p = new BlockPos(x, y, z);
                boolean clash = false;
                for (Built b : village.built) if (b != old && b.contains(p)) clash = true;
                for (Building other : village.getBuildings().values()) {
                    if (!other.getBuildingType().grouped() && other.containsPos(p) && !old.contains(other.getCenter())) clash = true;
                }
                if (clash) {
                    SKIPPED.add(renoKey(village, old));
                    if (payer != null) village.commissionElsewhere = payer; // no room here: a new house on a new site, and they move
                    payer = null;
                    return false;
                }
            }
        }
        // the old one leaves the books
        village.built.remove(old);
        village.houses.removeIf(h -> h.origin().equals(old.origin()));
        for (Building b : new ArrayList<>(village.getBuildings().values())) {
            if (!b.getBuildingType().grouped() && old.contains(b.getCenter())) village.removeBuilding(b.getId());
        }
        switch (Buildings.base(old.type())) {
            case "tavern" -> village.tavern = null;
            case "townhall" -> village.hallChests.clear(); // emptied into the stores with the old hall; the new hall's chests take over
            case "tower" -> {
                village.towers = Math.max(0, village.towers - 1);
                for (int i = village.watchPoints.size() - 1; i >= 0; i--) {
                    BlockPos w = village.watchPoints.get(i);
                    if (w.getX() >= old.origin().getX() && w.getX() < old.origin().getX() + old.fw() && w.getZ() >= old.origin().getZ() && w.getZ() < old.origin().getZ() + old.fd()) {
                        village.watchPoints.remove(i);
                        if (i < village.towerLadders.size()) village.towerLadders.remove(i);
                    }
                }
            }
            default -> {
            }
        }
        village.buildType = PREFIX + type;
        village.buildOrigin = new BlockPos(x0, y, z0);
        village.buildW = dm[0];
        village.buildD = dm[1];
        village.buildF = dm[2];
        village.buildRot = rot;
        village.buildStep = 0;
        village.plots.add(new int[]{x0, z0, fw, fd});
        if (payer != null) village.reserved.put(new BlockPos(x0 + fw / 2, y + 1, z0 + fd / 2).asLong(), payer);
        int top = y + Math.max(1, old.f()) * Blueprints.LEVEL_HEIGHT + 12;
        OLD_BOX.put(village.getId(), new int[]{old.origin().getX() - 2, y - 1, old.origin().getZ() - 2, old.origin().getX() + old.fw() + 1, top, old.origin().getZ() + old.fd() + 1});
        PLANS.remove(village.getId());
        village.markDirty();
        Builder.announceProject = "renovate";
        return true;
    }

    private static boolean natural(BlockState s) {
        return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.BASE_STONE_OVERWORLD) || s.isIn(BlockTags.SAND) || s.isOf(Blocks.GRAVEL)
                || s.isOf(Blocks.SNOW) || s.isOf(Blocks.SNOW_BLOCK) || s.isOf(Blocks.CLAY);
    }

    /** Everything to take down, beds and work blocks first (so nobody claims them again), then from the top down. */
    private static List<BlockPos> plan(Village village, ServerWorld world) {
        String type = village.buildType.substring(PREFIX.length());
        int[] fp = Blueprints.footprint(type, village.buildW, village.buildD);
        int fw = (village.buildRot & 1) == 0 ? fp[0] : fp[1], fd = (village.buildRot & 1) == 0 ? fp[1] : fp[0];
        BlockPos o = village.buildOrigin;
        int[] box = OLD_BOX.getOrDefault(village.getId(), new int[]{o.getX() - 2, o.getY() - 1, o.getZ() - 2, o.getX() + fw + 1, o.getY() + 9, o.getZ() + fd + 1});
        int x0 = Math.min(box[0], o.getX() - 1), z0 = Math.min(box[2], o.getZ() - 1), x1 = Math.max(box[3], o.getX() + fw), z1 = Math.max(box[5], o.getZ() + fd);
        int y0 = Math.min(box[1], o.getY() - 1), y1 = Math.max(box[4], o.getY() + 12);
        List<BlockPos> first = new ArrayList<>(), rest = new ArrayList<>();
        for (int y = y1; y >= y0; y--) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (s.isAir() || s.getHardness(world, p) < 0 || !s.getFluidState().isEmpty()) continue;
                    boolean inside = x >= o.getX() && x < o.getX() + fw && z >= o.getZ() && z < o.getZ() + fd;
                    if (natural(s) && (y < o.getY() || !inside)) continue; // the ground stays; natural blocks only go where the new house stands
                    if (s.isIn(BlockTags.LOGS) && !inside) continue; // trees around stay
                    if (ours(village, p)) continue;
                    if (s.isIn(BlockTags.BEDS) || s.getBlock() instanceof net.minecraft.block.BlockWithEntity) first.add(p);
                    else rest.add(p);
                }
            }
        }
        first.addAll(rest);
        return first;
    }

    /** One builder second of taking the old building down. True when he worked. */
    public static boolean work(VillagerEntityMCA v, Village village, ServerWorld world) {
        List<BlockPos> plan = PLANS.computeIfAbsent(village.getId(), k -> plan(village, world));
        int budget = Math.max(1, Prosperity.ops(v, village, Builder.opsPerSecond));
        int done = 0, checked = 0;
        while (village.buildStep < plan.size() && done < budget && checked < 400) {
            BlockPos p = plan.get(village.buildStep);
            checked++;
            BlockState s = world.getBlockState(p);
            if (s.isAir() || !s.getFluidState().isEmpty()) {
                village.buildStep++;
                continue;
            }
            double hx = p.getX() + 0.5 - v.getX(), hz = p.getZ() + 0.5 - v.getZ();
            if (hx * hx + hz * hz > 36 || Math.abs(p.getY() - v.getY()) > 12) {
                Move.to(v, p.getX() + 0.5, village.buildOrigin.getY(), p.getZ() + 0.5, 0.6);
                return done > 0;
            }
            // chests are emptied into the stores, the rest is salvaged
            BlockEntity be = world.getBlockEntity(p);
            if (be instanceof Inventory inv) {
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack st = inv.getStack(i);
                    if (!st.isEmpty()) Workforce.addToTreasury(village, st.copy());
                    inv.setStack(i, ItemStack.EMPTY);
                }
            }
            for (ItemStack drop : Block.getDroppedStacks(s, world, p, be)) Workforce.addToTreasury(village, drop);
            world.breakBlock(p, false, v);
            Gaze.at(v, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            v.swingHand(v.getDominantHand());
            village.buildStep++;
            done++;
        }
        Speech.say(v, "settlements.say.build.demolish");
        if (village.buildStep >= plan.size()) {
            level(village, world);
            String type = village.buildType.substring(PREFIX.length());
            village.buildType = type; // now the new building goes up in the cleared place
            village.buildStep = 0;
            PLANS.remove(village.getId());
            OLD_BOX.remove(village.getId());
            Builder.announceProject = Buildings.base(type);
            Life.news(village, "rebuilt", net.minecraft.text.Text.translatable("settlements.building." + Buildings.base(type)));
        }
        village.markDirty();
        return true;
    }

    /** Fills the dug-out floor so the new building stands on level ground. */
    private static void level(Village village, ServerWorld world) {
        String type = village.buildType.substring(PREFIX.length());
        int[] fp = Blueprints.footprint(type, village.buildW, village.buildD);
        int fw = (village.buildRot & 1) == 0 ? fp[0] : fp[1], fd = (village.buildRot & 1) == 0 ? fp[1] : fp[0];
        BlockPos o = village.buildOrigin;
        for (int x = o.getX() - 1; x <= o.getX() + fw; x++) {
            for (int z = o.getZ() - 1; z <= o.getZ() + fd; z++) {
                for (int y = o.getY() - 4; y < o.getY(); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (world.getBlockState(p).isAir() || world.getBlockState(p).isReplaceable())
                        world.setBlockState(p, y == o.getY() - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState());
                }
            }
        }
    }
}
