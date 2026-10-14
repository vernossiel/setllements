package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;

import java.util.Comparator;
import java.util.List;

/**
 * Threat assessment. Hostile creatures near a village are rated by kind (data-driven by name for the big ones,
 * by health for the rest). Guards engage minor threats; a major one, a dragon for instance, sends civilians hiding
 * and calls out the village so players can react.
 */
public final class Defense {
    private static final int RADIUS = 56;
    private static final double MAJOR = 20;

    private Defense() {
    }

    public static double rating(LivingEntity e) {
        String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        if (id.contains("dragon")) return 100;
        if (id.contains("wither")) return 60;
        if (id.contains("warden")) return 40;
        if (id.contains("ravager")) return 8;
        if (id.contains("evoker") || id.contains("vindicator")) return 4;
        if (id.contains("creeper") || id.contains("witch")) return 3;
        return Math.max(1, e.getMaxHealth() / 10.0);
    }

    /** Every five seconds per world. */
    public static void tick(ServerWorld world) {
        for (Village v : VillageManager.get(world)) {
            var c = v.getCenter();
            Box box = new Box(c.getX() - RADIUS, c.getY() - 30, c.getZ() - RADIUS, c.getX() + RADIUS, c.getY() + 60, c.getZ() + RADIUS);
            List<LivingEntity> threats = world.getEntitiesByClass(LivingEntity.class, box, e -> e instanceof Monster && e.isAlive());
            if (threats.isEmpty()) continue;
            double total = 0;
            for (LivingEntity t : threats) total += rating(t);
            if (total < 3) continue; // a lone zombie is the guards' problem anyway

            List<VillagerEntityMCA> residents = world.getEntitiesByClass(VillagerEntityMCA.class, box, VillagerEntityMCA::isAlive);
            List<VillagerEntityMCA> guards = residents.stream().filter(VillagerEntityMCA::isGuard).toList();
            for (VillagerEntityMCA g : guards) {
                LivingEntity nearest = threats.stream().min(Comparator.comparingDouble(g::squaredDistanceTo)).orElse(null);
                if (nearest != null) g.getBrain().remember(MemoryModuleType.ATTACK_TARGET, nearest, 400);
            }
            // monsters are the guards' business: only a village without guards runs for cover, and nobody makes a fuss
            if (total >= MAJOR && guards.isEmpty() && world.getTime() % 200 < 100) {
                for (VillagerEntityMCA r : residents) {
                    if (r.isGuard() || r.getProfession() == VillagerProfession.NONE && r.isBaby()) continue;
                    r.getBrain().doExclusively(net.minecraft.entity.ai.brain.Activity.PANIC);
                }
            }
        }
    }
}
