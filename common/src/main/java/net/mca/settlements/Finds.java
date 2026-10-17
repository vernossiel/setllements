package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Money on the ground does not lie there long: a villager who sees coins, gems or ingots dropped in the village goes
 * and picks them up, and they go into the village's coffers (a thrown purse, a dead thief's pockets).
 */
public final class Finds {
    private static final double RANGE = 10;
    private static final Map<UUID, UUID> CLAIMED = new HashMap<>(); // item -> the villager going for it

    private Finds() {
    }

    static boolean valuable(ItemStack s) {
        return Money.is(s) || Reputation.value(new ItemStack(s.getItem())) >= 3;
    }

    /** Once a second. True while he is going for something he saw lying there. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (v.isBaby() || v.isSleeping() || v.hasVehicle() || Talk.talking(v) || v.getVillagerBrain().isPanicking()
                || v.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent() || !village.isWithinBorder(v)) return false;
        if (CLAIMED.size() > 200) CLAIMED.clear();
        ItemEntity best = null;
        double bd = RANGE * RANGE;
        for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, v.getBoundingBox().expand(RANGE, 4, RANGE),
                e -> e.isAlive() && e.age > 40 && valuable(e.getStack()))) {
            UUID by = CLAIMED.get(e.getUuid());
            if (by != null && !by.equals(v.getUuid()) && world.getEntity(by) instanceof VillagerEntityMCA o && o.isAlive() && o.squaredDistanceTo(e) < RANGE * RANGE * 2) continue;
            double d = v.squaredDistanceTo(e);
            if (d < bd && (by != null || v.canSee(e))) {
                bd = d;
                best = e;
            }
        }
        if (best == null) return false;
        if (CLAIMED.put(best.getUuid(), v.getUuid()) == null) Speech.say(v, "settlements.say.finds");
        Doing.set(v, "settlements.doing.finds");
        if (bd > 1.6 * 1.6) {
            Gaze.at(v, best.getX(), best.getY(), best.getZ());
            Move.to(v, best.getX(), best.getY(), best.getZ(), 0.6);
            return true;
        }
        v.getNavigation().stop();
        v.swingHand(v.getDominantHand());
        Workforce.addToTreasury(village, best.getStack().copy());
        village.markDirty();
        CLAIMED.remove(best.getUuid());
        best.discard();
        CLAIMED.values().removeIf(u -> u.equals(v.getUuid()));
        return true;
    }
}
