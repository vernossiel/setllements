package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;

/**
 * A villager who has said something to a player in the dialogue line stands still and faces him while the words are
 * up - he does not walk off mid-sentence - till the player answers, walks away or the time is out.
 */
public final class Dialogue {
    private static final Map<Integer, Long> HELD = new HashMap<>(); // entity id -> until (millis; the request is built off the tick)

    private Dialogue() {
    }

    public static void hold(int entity, int seconds) {
        if (entity >= 0) HELD.put(entity, System.currentTimeMillis() + seconds * 1000L);
    }

    public static void release(int entity) {
        HELD.remove(entity);
    }

    /** Once a second for a villager: true while he is in a conversation with a player (and he stood and faced him). */
    public static boolean tick(VillagerEntityMCA v, ServerWorld world) {
        Long until = HELD.get(v.getId());
        if (until == null) return false;
        PlayerEntity p = world.getClosestPlayer(v, 10);
        if (System.currentTimeMillis() > until || p == null || v.isSleeping()) {
            HELD.remove(v.getId());
            return false;
        }
        v.getNavigation().stop();
        Gaze.at(v, p, 30f, 30f);
        return true;
    }
}
