package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.mca.settlements.Blueprints.Kind;
import net.mca.settlements.Blueprints.Step;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Upkeep. Every half minute one building of each village is looked over: missing walls, roof, doors, beds and
 * furniture make it a repair job for the builder, who walks round and puts back what is missing (and pays for it).
 * The roads from the buildings to the plaza are checked too: dug-up or grown-over cells are mended by hand.
 */
public final class Repair {
    private static final Map<Integer, Integer> CURSOR = new HashMap<>();
    private static final Map<Integer, Built> PENDING = new HashMap<>();
    private static final Map<Integer, Deque<int[]>> ROADS = new HashMap<>();

    private Repair() {
    }

    /** Blocks that make a building whole; decoration and optional pieces never call for a repair. */
    static boolean structural(Kind k) {
        return switch (k) {
            case FLOOR, WALL, PILLAR, ROOF, STAIRS_N, STAIRS_S, STAIRS_E, STAIRS_W, STONE, ACCENT, BEAM_X, BEAM_Z, DOOR, FENCE,
                 SLAB, GATE, BARS, LADDER, CHEST, HALL_CHEST, BED_FOOT, BED_HEAD, LECTERN, SEAT_W, SEAT_E, PEW, THATCH, DAUB, RSTAIR_S, RSTAIR_N -> true;
            default -> false;
        };
    }

    /** The fittings a house may have gone up without (no stone, no boards to spare): put in once the stores can pay. */
    static boolean fitting(Kind k) {
        return k == Kind.SMOKER || k == Kind.CRAFT || k == Kind.BARREL || k == Kind.BOOKSHELF;
    }

    static boolean affordable(Village village, Kind k) {
        int planks = Builder.stockPlanks(village);
        return k == Kind.SMOKER ? Builder.stockCobble(village) >= 8 && planks >= 4 : planks >= 8;
    }

    /** True when the block this step puts there is gone (air, plants, water). */
    public static boolean missing(ServerWorld world, Step step, BlockPos target) {
        return missing(world, null, step, target);
    }

    /** As above; with the village, also a fitting left out before that the stores can pay for now. */
    public static boolean missing(ServerWorld world, Village village, Step step, BlockPos target) {
        if (!structural(step.kind()) && !(village != null && fitting(step.kind()) && affordable(village, step.kind()))) return false;
        if (!world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) return false;
        BlockState s = world.getBlockState(target);
        return s.isAir() || s.isReplaceable() || !s.getFluidState().isEmpty();
    }

    /** Older villages: their houses and hall go into the register so they can be kept up too. */
    private static void migrate(Village village) {
        if (!village.built.isEmpty()) return;
        for (House h : village.houses) village.built.add(new Built("house", h.origin(), h.w(), h.d(), h.floors(), h.rot()));
        if (village.hall != null) village.built.add(new Built("townhall", village.hall.origin(), 0, 0, village.hall.floors(), village.hall.rot()));
        if (!village.built.isEmpty()) village.markDirty();
    }

    /** Every thirty seconds per world. */
    public static void tick(ServerWorld world) {
        for (Village village : VillageManager.get(world)) {
            migrate(village);
            if (village.built.isEmpty() || PENDING.containsKey(village.getId())) continue;
            int i = CURSOR.merge(village.getId(), 1, Integer::sum) % village.built.size();
            Built b = village.built.get(i);
            if (!world.isChunkLoaded(b.origin().getX() >> 4, b.origin().getZ() >> 4)) continue;
            List<Step> steps = b.steps();
            int missing = 0;
            for (Step s : steps) {
                if (missing(world, village, s, b.point(s.dx(), s.dy(), s.dz()))) {
                    missing += fitting(s.kind()) ? 3 : 1; // one fitting to put in is reason enough to come round
                    if (missing >= 3) break;
                }
            }
            if (missing >= (Buildings.base(b.type()).equals("townhall") ? 1 : 3)) PENDING.put(village.getId(), b);

            // its road to the plaza
            int[] fp = b.footprint();
            BlockPos front = b.point(fp[0] / 2, 0, fp[1]);
            Deque<int[]> roads = ROADS.computeIfAbsent(village.getId(), k -> new ArrayDeque<>());
            java.util.List<int[]> cells = new java.util.ArrayList<>(Roads.route(village, front));
            if (village.built.stream().anyMatch(x -> Buildings.base(x.type()).equals("well"))) cells.addAll(Roads.plazaCells(village));
            for (int[] cell : cells) {
                if (roads.size() >= 48) break;
                if (Roads.needs(world, village, cell) && roads.stream().noneMatch(c -> c[0] == cell[0] && c[1] == cell[1] && c.length == cell.length)) roads.add(cell);
            }
        }
    }

    /** Turns a pending repair into the builder's project. */
    public static boolean start(Village village) {
        Built b = PENDING.remove(village.getId());
        if (b == null) return false;
        village.buildType = b.type();
        village.buildOrigin = b.origin();
        village.buildW = b.w();
        village.buildD = b.d();
        village.buildF = b.type().equals("townhall") ? 0 : b.f();
        village.buildRot = b.rot();
        village.buildStep = 0;
        village.buildRepair = true;
        village.markDirty();
        Builder.announceProject = "repair";
        return true;
    }

    /** The builder mends the next broken road cell. True while he is at it. */
    public static boolean roadWork(VillagerEntityMCA v, Village village, ServerWorld world) {
        Deque<int[]> roads = ROADS.get(village.getId());
        if (roads == null || roads.isEmpty()) return false;
        int[] cell = roads.peek();
        if (!Roads.needs(world, village, cell)) {
            roads.poll();
            return true;
        }
        double dx = cell[0] + 0.5 - v.getX(), dz = cell[1] + 0.5 - v.getZ();
        if (dx * dx + dz * dz > 9) {
            int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cell[0], cell[1]);
            Move.to(v, cell[0] + 0.5, y, cell[1] + 0.5, 0.6);
            return true;
        }
        Roads.work(world, village, cell);
        v.swingHand(v.getDominantHand());
        Speech.say(v, "settlements.say.build.road");
        roads.poll();
        return true;
    }
}
