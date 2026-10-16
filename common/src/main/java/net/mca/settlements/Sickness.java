package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Now and then somebody falls ill: he keeps to his house for a day, does no work, coughs; the neighbours look in on
 * him (see Persona's visits). The old and the worn out take ill more easily.
 */
public final class Sickness {
    private static final Map<UUID, Long> SICK = new HashMap<>();
    private static final Map<UUID, Long> CHECKED = new HashMap<>();

    private Sickness() {
    }

    public static boolean sick(VillagerEntityMCA v) {
        Long until = SICK.get(v.getUuid());
        return until != null && v.getWorld().getTime() < until;
    }

    /** Once a second for a grown villager; true while illness keeps him in. */
    public static boolean tick(VillagerEntityMCA v, ServerWorld world) {
        long now = world.getTime(), day = world.getTimeOfDay() / 24000L;
        // once a day, at random, the chance of falling ill
        if (CHECKED.getOrDefault(v.getUuid(), -1L) != day) {
            CHECKED.put(v.getUuid(), day);
            double chance = 0.02 + (v.settlementsEnergy < 300 ? 0.03 : 0) + (v.settlementsHunger > 800 ? 0.03 : 0);
            if (!v.isGuard() && world.random.nextDouble() < chance) {
                SICK.put(v.getUuid(), now + 20000 + world.random.nextInt(6000));
                Speech.sayAny(v, "settlements.say.sick.start", 3);
                Life.news(net.mca.server.world.data.Village.findNearest(v).orElse(null), "sick", v.getName().getString());
            }
        }
        if (!sick(v)) {
            SICK.remove(v.getUuid());
            return false;
        }
        Doing.set(v, "settlements.doing.sick");
        GlobalPos h = v.getResidency().getHome().orElse(null);
        if (h != null && h.getDimension() == world.getRegistryKey()) {
            BlockPos p = h.getPos();
            if (v.squaredDistanceTo(Vec3d.ofBottomCenter(p)) > 9) {
                Move.to(v, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.35); // slowly, unwell
                return true;
            }
        }
        v.getNavigation().stop();
        v.settlementsEnergy = Math.min(Workforce.MAX, v.settlementsEnergy + 2);
        if (world.random.nextInt(45) == 0) Speech.sayAny(v, "settlements.say.sick.cough", 3);
        return true;
    }
}
