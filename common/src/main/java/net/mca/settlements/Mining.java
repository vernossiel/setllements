package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.settlements.Blueprints.Step;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Miners really dig the village mine, level after level: they walk to the mine, go down the stairs and break stone and
 * ore block by block; everything they break goes to the treasury. If the path down fails they are taken to the
 * working face after a while, so they never get stuck for good.
 */
public final class Mining {
    private static final int MAX_LEVEL = 8;
    private static final int STONE_QUOTA = 1200;
    private static final Map<Integer, List<Step>> LEVELS = new HashMap<>();

    private Mining() {
    }

    public static boolean work(VillagerEntityMCA v, Village village) {
        if (!village.mineBuilt || village.mineOrigin == null || !(v.getWorld() instanceof ServerWorld world)) return false;
        if (village.mineLevel > MAX_LEVEL || Builder.stockCobble(village) >= STONE_QUOTA) return false;

        List<Step> steps = LEVELS.computeIfAbsent(village.mineLevel, Blueprints::mineExtension);
        if (village.mineStep >= steps.size()) {
            village.mineLevel++;
            village.mineStep = 0;
            village.markDirty();
            return false;
        }
        BlockPos o = village.mineOrigin;
        BlockPos stand = standFor(o, steps, village);
        if (v.squaredDistanceTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > 25) {
            // walk down; if the stairs do not work out, go straight to the face
            if (v.settlementsStuck++ > 25 && world.isChunkLoaded(stand.getX() >> 4, stand.getZ() >> 4)) {
                v.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, v.getYaw(), 0);
                v.settlementsStuck = 0;
            } else {
                v.getNavigation().startMovingTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.6);
            }
            return false;
        }
        v.settlementsStuck = 0;
        Speech.say(v, "settlements.say.mine.dig");

        boolean worked = false;
        int ops = 0, skips = 0;
        while (ops < Builder.opsPerSecond && skips < 60 && village.mineStep < steps.size()) {
            Step step = steps.get(village.mineStep);
            BlockPos target = o.add(step.dx(), step.dy(), step.dz());
            if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 49) break;
            Builder.Result r = Builder.execute(world, village, step, target, 0);
            if (r == Builder.Result.ABORT) { // water or lava ahead: this level is done
                village.mineLevel++;
                village.mineStep = 0;
                break;
            }
            if (r == Builder.Result.WAIT) break;
            village.mineStep++;
            if (r == Builder.Result.PLACED) {
                ops++;
                worked = true;
                v.swingHand(v.getDominantHand());
            } else {
                skips++;
            }
        }
        village.markDirty();
        return worked;
    }

    /** An already dug cell next to the next block to dig. */
    private static BlockPos standFor(BlockPos o, List<Step> steps, Village village) {
        if (village.mineStep > 0) {
            Step prev = steps.get(Math.min(village.mineStep, steps.size()) - 1);
            return o.add(prev.dx(), prev.dy(), prev.dz());
        }
        int depth = 28, bottom = -(depth - 1);
        return o.add(0, bottom, depth - 1 + 5 * (village.mineLevel - 1));
    }
}
