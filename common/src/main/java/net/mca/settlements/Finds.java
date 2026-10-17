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
 * Nothing useful lies about long: a villager who sees money, food, coal and ore, wood or stone on the ground in the
 * village goes and picks it up for the stores (a thrown purse, a dead thief's pockets, the coal a miner dug out and
 * left, meat from a kill).
 */
public final class Finds {
    private static final double RANGE = 10;
    private static final Map<UUID, UUID> CLAIMED = new HashMap<>(); // item -> the villager going for it
    private static final Map<UUID, long[]> TRYING = new HashMap<>(); // villager -> {item entity id, since}
    private static final java.util.Set<UUID> GIVEN_UP = new java.util.HashSet<>();

    private Finds() {
    }

    /** Worth stooping for: money and gems, and whatever the village uses - food, coal and ore, wood, stone, wool, seed. */
    static boolean valuable(ItemStack s) {
        if (Money.is(s) || Reputation.value(new ItemStack(s.getItem())) >= 3 || s.isFood()) return true;
        var item = s.getItem();
        return s.isIn(net.minecraft.registry.tag.ItemTags.LOGS) || s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS) || s.isIn(net.minecraft.registry.tag.ItemTags.WOOL)
                || s.isIn(net.minecraft.registry.tag.ItemTags.SAPLINGS) || s.isIn(net.minecraft.registry.tag.ItemTags.COALS)
                || item == net.minecraft.item.Items.COBBLESTONE || item == net.minecraft.item.Items.COBBLED_DEEPSLATE || item == net.minecraft.item.Items.RAW_IRON
                || item == net.minecraft.item.Items.RAW_COPPER || item == net.minecraft.item.Items.RAW_GOLD || item == net.minecraft.item.Items.LEATHER
                || item == net.minecraft.item.Items.WHEAT || item == net.minecraft.item.Items.WHEAT_SEEDS || item == net.minecraft.item.Items.FEATHER
                || item == net.minecraft.item.Items.STRING || item == net.minecraft.item.Items.FLINT || item == net.minecraft.item.Items.IRON_INGOT;
    }

    /** Once a second. True while he is going for something he saw lying there. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (v.isBaby() || v.isSleeping() || v.hasVehicle() || Talk.talking(v) || v.getVillagerBrain().isPanicking()
                || v.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent() || !village.isWithinBorder(v)) return false;
        if (CLAIMED.size() > 200) CLAIMED.clear();
        ItemEntity best = null;
        double bd = RANGE * RANGE;
        for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, v.getBoundingBox().expand(RANGE, 4, RANGE),
                e -> e.isAlive() && e.age > 40 && valuable(e.getStack()) && world.getClosestPlayer(e, 3) == null && !Move.unreachable(world, e.getBlockPos())
                        && !GIVEN_UP.contains(e.getUuid()))) { // (no way to it: a builder stood stuck reaching for a nugget) // (not what a player stands over: his own, likely)
            UUID by = CLAIMED.get(e.getUuid());
            if (by != null && !by.equals(v.getUuid()) && world.getEntity(by) instanceof VillagerEntityMCA o && o.isAlive() && o.squaredDistanceTo(e) < RANGE * RANGE * 2) continue;
            double d = v.squaredDistanceTo(e);
            if (d < bd && (by != null || v.canSee(e))) {
                bd = d;
                best = e;
            }
        }
        if (best == null) return false;
        if (CLAIMED.put(best.getUuid(), v.getUuid()) == null && world.random.nextInt(4) == 0) Speech.say(v, "settlements.say.finds");
        Doing.set(v, "settlements.doing.finds");
        // a quarter of a minute after the one thing and not got it (down a hole, on a roof): it is left lying (the miner
        // spent his days going after one stone)
        long now = world.getTime();
        long[] t = TRYING.computeIfAbsent(v.getUuid(), k -> new long[]{-1, 0});
        if (t[0] != best.getId()) {
            t[0] = best.getId();
            t[1] = now;
        } else if (now - t[1] > 300) {
            GIVEN_UP.add(best.getUuid());
            if (GIVEN_UP.size() > 500) GIVEN_UP.clear();
            CLAIMED.remove(best.getUuid());
            return false;
        }
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
