package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.MoveState;
import net.mca.server.world.data.Village;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Free time: in the evening and on rest days villagers gather at the campfire, chat, stroll around the village
 * and have supper. Sleeping at night is left to the normal villager schedule.
 */
public final class Leisure {
    private Leisure() {
    }

    /** Evening hours before bedtime, plus the whole day on a rest day. */
    public static boolean isLeisureTime(long worldTime) {
        long t = worldTime % 24000L;
        if (Workforce.isRestDay(worldTime)) return t >= 1000 && t < 13000;
        return t >= 11500 && t < 13000;
    }

    /** Called once per second for a villager with nothing to work on. */
    public static void tick(VillagerEntityMCA v, Village village, long time) {
        if (v.isSleeping() || v.getVillagerBrain().isPanicking() || v.getVillagerBrain().getMoveState() != MoveState.MOVE) return;
        if (!isLeisureTime(time)) return;

        // supper at the fire
        boolean hungry = v.settlementsHunger >= 250;
        if (village != null && Chapel.visit(v, village)) return; // remembering the dead at the churchyard
        boolean seated = Camp.goAndSit(v, village);
        long phase = (v.age / 20 + v.getUuid().hashCode()) % 60; // each villager has their own rhythm
        if (seated || (hungry && phase < 40)) {
            if (seated) {
                v.settlementsEnergy = Math.min(Workforce.MAX, v.settlementsEnergy + 28);
                chat(v);
            }
            return;
        }
        if (phase < 25) return; // stand around for a while
        // stroll
        Vec3d target = FuzzyTargeting.find(v, 12, 4);
        if (target != null) {
            v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.45f, 1));
        }
    }

    private static void chat(VillagerEntityMCA v) {
        if (v.age % 100 != 0) return;
        List<VillagerEntityMCA> near = v.getWorld().getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(4), o -> o != v && !o.isSleeping());
        if (near.isEmpty()) return;
        VillagerEntityMCA other = near.get(0);
        v.getLookControl().lookAt(other, 30f, 30f);
        v.getWorld().playSoundFromEntity(null, v, SoundEvents.ENTITY_VILLAGER_AMBIENT, SoundCategory.NEUTRAL, 0.6f, 1.0f);
    }
}
