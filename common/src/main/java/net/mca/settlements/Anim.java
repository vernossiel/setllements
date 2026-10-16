package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a villager's hands are busy with, so that the model can show it: an axe swung over the head at a trunk, a pick
 * brought down on stone, a spade pushed into the ground, a hand scattering seed, both hands full of a load, a hammer at
 * a wall, hands that talk, hands that clap. The server says it (a tracked number on the entity) and it lasts a moment;
 * the client model (VillagerEntityBaseModelMCA) poses the arms by it.
 */
public final class Anim {
    public enum Act { NONE, CHOP, MINE, DIG, SOW, REAP, CARRY, BUILD, TALK, CLAP, WAVE, EAT, SHEAR, FEED, BEHIND, BEND }

    private static final Map<UUID, Long> UNTIL = new HashMap<>();

    private Anim() {
    }

    /** Busy with {@code act} for the next {@code ticks}. A short one does not cut a running one of the same kind. */
    public static void set(VillagerEntityMCA v, Act act, int ticks) {
        if (v.isSleeping()) return;
        long until = v.getWorld().getTime() + ticks;
        if (v.getSettlementsAction() == act.ordinal()) {
            UNTIL.merge(v.getUuid(), until, Math::max);
            return;
        }
        // clapping, waving and talking are done with empty hands: the tool goes back in the bag
        if (act == Act.CLAP || act == Act.WAVE || act == Act.TALK) Gear.pocket(v);
        v.setSettlementsAction(act.ordinal());
        UNTIL.put(v.getUuid(), until);
    }

    /** Hands free again. */
    public static void clear(VillagerEntityMCA v) {
        UNTIL.remove(v.getUuid());
        v.setSettlementsAction(0);
    }

    /** Once a second: the moment is over, or he has gone to sleep. */
    public static void tick(VillagerEntityMCA v) {
        if (v.getSettlementsAction() == 0) return;
        Long until = UNTIL.get(v.getUuid());
        if (until == null || v.isSleeping() || v.getWorld().getTime() >= until) clear(v);
    }

    /** The acts done bent over: drawn crouching, as a player crouches. */
    public static boolean bent(Act a) {
        return a == Act.BEND || a == Act.REAP || a == Act.SOW || a == Act.SHEAR || a == Act.FEED;
    }

    public static Act of(int ordinal) {
        Act[] all = Act.values();
        return ordinal > 0 && ordinal < all.length ? all[ordinal] : Act.NONE;
    }
}
