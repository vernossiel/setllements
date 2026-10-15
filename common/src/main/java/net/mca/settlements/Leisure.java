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
        if (!Persona.free(v, time) || !(v.getWorld() instanceof net.minecraft.server.world.ServerWorld world)) return;

        // supper at the fire
        boolean hungry = v.settlementsHunger >= 250;
        if (village != null && Chapel.visit(v, village)) return; // remembering the dead at the churchyard
        if (hungry && village != null && Camp.goAndSit(v, village)) return; // supper at the fire
        Persona.pastime(v, village, world); // otherwise as he pleases: friends, the tavern, a stroll, home
    }

    private static void chat(VillagerEntityMCA v) {
        if (v.age % 100 != 0) return;
        List<VillagerEntityMCA> near = v.getWorld().getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(4), o -> o != v && !o.isSleeping());
        if (near.isEmpty()) return;
        VillagerEntityMCA other = near.get(0);
        v.getLookControl().lookAt(other, 30f, 30f);
    }
}
