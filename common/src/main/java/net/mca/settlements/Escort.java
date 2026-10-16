package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Nobody goes far from the village alone if a guard can be spared: a lumberjack off to a distant wood (or anyone
 * sent far) asks, and the nearest free guard goes along, keeping a few paces behind and fighting off whatever comes.
 */
public final class Escort {
    private record Ask(UUID who, long until) {
    }

    private static final Map<Integer, Ask> ASKS = new HashMap<>();
    private static final Map<Integer, UUID> GUARD = new HashMap<>();

    private Escort() {
    }

    public static void ask(VillagerEntityMCA v, Village village, long ticks) {
        Ask a = ASKS.get(village.getId());
        if (a != null && a.who().equals(v.getUuid()) && a.until() > v.getWorld().getTime()) return;
        ASKS.put(village.getId(), new Ask(v.getUuid(), v.getWorld().getTime() + ticks));
        GUARD.remove(village.getId());
    }

    /** Once a second for a guard. True while he is escorting (instead of his round). */
    public static boolean guard(VillagerEntityMCA g, Village village, ServerWorld world) {
        if (village == null || g.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent()) return false;
        Ask a = ASKS.get(village.getId());
        if (a == null) return false;
        long now = world.getTime();
        Entity e = world.getEntity(a.who());
        boolean home = e != null && e.getBlockPos().isWithinDistance(village.getCenter(), 40);
        if (now > a.until() || !(e instanceof VillagerEntityMCA who) || !who.isAlive() || home && now > a.until() - 20 * 120) {
            ASKS.remove(village.getId());
            GUARD.remove(village.getId());
            return false;
        }
        UUID mine = GUARD.get(village.getId());
        if (mine == null) {
            // the nearest guard who is free takes it
            VillagerEntityMCA best = null;
            double bd = 96 * 96;
            for (VillagerEntityMCA o : village.getResidents(world)) {
                if (!o.isGuard() || !o.isAlive() || o.isSleeping() || o.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent()) continue;
                double d = o.squaredDistanceTo(who);
                if (d < bd) {
                    bd = d;
                    best = o;
                }
            }
            if (best == null) return false;
            GUARD.put(village.getId(), best.getUuid());
            Speech.sayNow(best, "settlements.say.escort.go", who.getName());
            mine = best.getUuid();
        }
        if (!mine.equals(g.getUuid())) return false;
        Doing.set(g, "settlements.doing.escort", who.getName());
        if (g.squaredDistanceTo(who) > 36) Move.to(g, who.getX(), who.getY(), who.getZ(), 0.6);
        else {
            g.getNavigation().stop();
            Gaze.at(g, who, 20f, 20f);
        }
        return true;
    }
}
