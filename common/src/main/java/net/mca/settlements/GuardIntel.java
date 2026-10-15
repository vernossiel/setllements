package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the guards know. They do not hunt monsters in caves and dark corners that nobody has seen. A threat is known
 * when a villager reported it, when it is going for a villager or a player, or when it stands in the open inside the
 * village and someone can see it. Villagers who see a monster, or a player doing damage, run the news to the guards.
 */
public final class GuardIntel {
    private static final long REPORT_TTL = 20 * 40;
    private static final double SIGHT = 16;
    private static final Map<UUID, Long> REPORTED = new HashMap<>();

    private GuardIntel() {
    }

    public static void reportThreat(ServerWorld world, LivingEntity threat) {
        REPORTED.put(threat.getUuid(), world.getTime() + REPORT_TTL);
    }

    public static boolean isKnown(ServerWorld world, LivingEntity guard, LivingEntity enemy) {
        if (enemy instanceof MobEntity m && (m.getTarget() instanceof VillagerEntity || m.getTarget() instanceof PlayerEntity)) return true;
        Long until = REPORTED.get(enemy.getUuid());
        if (until != null) {
            if (until >= world.getTime()) return true;
            REPORTED.remove(enemy.getUuid());
        }
        // otherwise only what the guard sees with his own eyes, close by
        return guard.squaredDistanceTo(enemy) < 14 * 14 && guard.canSee(enemy);
    }

    /** Called every second for civilians: a monster in plain view is reported to the nearest guards. */
    public static void civilianWatch(VillagerEntityMCA v, ServerWorld world) {
        if (v.isBaby() || v.isGuard() || v.isSleeping()) return;
        LivingEntity seen = world.getEntitiesByClass(LivingEntity.class, v.getBoundingBox().expand(SIGHT),
                        e -> e instanceof Monster && e.isAlive() && v.canSee(e))
                .stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
        if (seen == null) return;
        reportThreat(world, seen);
        // a monster coming close: a miner or a farmer is no soldier - he runs, shouting for the guards
        boolean coming = seen instanceof net.minecraft.entity.mob.MobEntity m0 && m0.getTarget() == v;
        if ((coming || v.squaredDistanceTo(seen) < 10 * 10) && world.getTime() >= v.settlementsFleeUntil - 100) {
            Life.fleeFrom(v, seen, world);
            Speech.sayNow(v, "settlements.say.report.mob");
            alertNearestGuard(world, v, seen);
            return;
        }
        // a word only when it is really going for someone; otherwise the guard is told quietly
        if (seen instanceof net.minecraft.entity.mob.MobEntity m && m.getTarget() != null) Speech.say(v, "settlements.say.report.mob");
        alertNearestGuard(world, v, seen);
    }

    /** A guard within reach is told, turns up, and says why. */
    public static void alertNearestGuard(ServerWorld world, VillagerEntityMCA reporter, LivingEntity subject) {
        VillagerEntityMCA guard = world.getEntitiesByClass(VillagerEntityMCA.class, reporter.getBoundingBox().expand(40),
                        g -> g.isGuard() && g.isAlive() && !g.isSleeping())
                .stream().min(Comparator.comparingDouble(reporter::squaredDistanceTo)).orElse(null);
        if (guard == null) return;
        if (subject instanceof Monster) {
            // told about it, the guard goes to have a look; he fights once he sees it himself (or it is known to attack)
            if (guard.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent()) return;
            guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(subject.getBlockPos(), 0.6f, 3));
            Speech.say(guard, "settlements.say.guard.scout");
        } else {
            guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(subject, 0.6f, 2));
        }
        if (!(subject instanceof Monster)) Speech.say(guard, "settlements.say.guard.reported");
    }
}
