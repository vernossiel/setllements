package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How the player carries himself in the village, and how the village takes it. A drawn weapon in the street with no
 * danger about makes people stare and step back, and a guard comes over and tells him to put it away (and remembers if
 * he does not). Badly hurt - they ask after him and send him to the healer. Running full tilt between the houses - a
 * look and a word. In somebody's house at night -
 * the household wakes and shouts, and the village hears of it.
 */
public final class Bearing {
    private static final Map<UUID, Long> ARMED_SINCE = new HashMap<>();
    private static final Map<UUID, Long> WARNED = new HashMap<>();
    private static final Map<UUID, Long> REMARKED = new HashMap<>();

    private Bearing() {
    }

    private static boolean weapon(Item i) {
        return i instanceof SwordItem || i instanceof AxeItem || i instanceof BowItem || i instanceof CrossbowItem || i instanceof TridentItem;
    }

    /** Once a second per world. */
    public static void tick(ServerWorld world) {
        long now = world.getTime();
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.isSpectator() || p.isCreative()) continue;
            Village village = VillageManager.get(world).findNearestVillage(p).orElse(null);
            if (village == null || !village.isWithinBorder(p)) {
                ARMED_SINCE.remove(p.getUuid());
                continue;
            }
            boolean danger = !world.getEntitiesByClass(HostileEntity.class, p.getBoundingBox().expand(20, 8, 20), HostileEntity::isAlive).isEmpty();
            List<VillagerEntityMCA> near = world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(10, 4, 10),
                    v -> v.isAlive() && !v.isSleeping() && !v.isBaby() && v.canSee(p));

            // a weapon out in the street
            if (weapon(p.getMainHandStack().getItem()) && !danger && !Justice.wanted(p)) {
                long since = ARMED_SINCE.computeIfAbsent(p.getUuid(), k -> now);
                if (now - since > 20 * 4) {
                    for (VillagerEntityMCA v : near) {
                        if (v.isGuard()) continue;
                        Gaze.at(v, p, 30f, 30f);
                        if (world.random.nextInt(40) == 0) Speech.sayAny(v, "settlements.say.bearing.armed", 4);
                    }
                    VillagerEntityMCA guard = world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(28, 8, 28),
                            v -> v.isGuard() && v.isAlive() && !v.isSleeping()).stream().findFirst().orElse(null);
                    Long warned = WARNED.get(p.getUuid());
                    if (guard != null && (warned == null || now - warned > 20 * 180)) {
                        WARNED.put(p.getUuid(), now);
                        guard.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET, new net.minecraft.entity.ai.brain.WalkTarget(p, 0.6f, 3));
                        Speech.sayNow(guard, "settlements.say.bearing.guard", p.getName());
                    } else if (guard != null && warned != null && now - warned > 20 * 45 && now - warned < 20 * 46) {
                        Speech.sayNow(guard, "settlements.say.bearing.guard2", p.getName()); // told once already
                        village.pushHearts(p, -2);
                    }
                }
            } else {
                ARMED_SINCE.remove(p.getUuid());
            }

            if (near.isEmpty() || now < REMARKED.getOrDefault(p.getUuid(), 0L)) continue;
            VillagerEntityMCA v = near.get(world.random.nextInt(near.size()));
            String key = null;
            if (p.getHealth() < p.getMaxHealth() * 0.4f) key = "settlements.say.bearing.hurt";
            else if (p.isSprinting() && world.random.nextInt(4) == 0) key = "settlements.say.bearing.running";
            else if (night(world) && inHouse(village, p) && v.getResidency().getHome().map(h -> h.getPos().isWithinDistance(p.getBlockPos(), 8)).orElse(false)) {
                Speech.sayNow(v, "settlements.say.bearing.intruder", p.getName());
                village.pushHearts(p, -3);
                Fame.deed(world, village, p, Fame.Deed.INTRUDE, v, 1);
                Life.news(village, "intruder", p.getName().getString());
                REMARKED.put(p.getUuid(), now + 20 * 60);
                continue;
            }
            if (key == null && world.random.nextInt(3) == 0) {
                // how he is known, in how they take him: a wave, a turned back, a wary step aside
                int opinion = Fame.opinion(v, p);
                if (Fame.get(village, p, Fame.Axis.PEACE) <= -30 || opinion <= -40) {
                    key = "settlements.say.greet.afraid";
                    Move.stepBack(v, p);
                } else if (Fame.get(village, p, Fame.Axis.HONEST) <= -30) key = "settlements.say.greet.thief";
                else if (opinion <= -15) {
                    REMARKED.put(p.getUuid(), now + 20 * 40);
                    Gaze.at(v, v.getX() * 2 - p.getX(), v.getEyeY(), v.getZ() * 2 - p.getZ()); // turns his back
                    Speech.sayAny(v, "settlements.say.greet.cold", 3, p.getName());
                    continue;
                } else if (opinion >= 15) {
                    key = "settlements.say.greet.friend";
                    Anim.set(v, Anim.Act.WAVE, 30);
                }
            }
            if (key == null) continue;
            REMARKED.put(p.getUuid(), now + 20 * 40);
            Gaze.at(v, p, 30f, 30f);
            Speech.sayAny(v, key, 3, p.getName());
        }
    }

    private static boolean night(ServerWorld world) {
        long t = world.getTimeOfDay() % 24000L;
        return t >= 13000 && t <= 23000;
    }

    private static boolean inHouse(Village village, ServerPlayerEntity p) {
        for (var b : village.getBuildings().values()) if (b.containsPos(p.getBlockPos()) && !b.getBuildingType().grouped()) return true;
        return false;
    }
}
