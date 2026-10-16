package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;

import java.util.Comparator;
import java.util.Iterator;

/** The healer walks to the most hurt villager nearby and tends to them, using food from the treasury. */
public final class Healing {
    private static final java.util.Map<java.util.UUID, Long> TREATED = new java.util.HashMap<>();

    private Healing() {
    }

    public static boolean work(VillagerEntityMCA v, Village village) {
        Doing.set(v, "settlements.doing.heal");
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        // the sick first: a visit, a draught, and they are up sooner
        VillagerEntityMCA ill = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(64, 12, 64),
                        o -> o != v && o.isAlive() && Sickness.sick(o) && world.getTime() >= TREATED.getOrDefault(o.getUuid(), 0L))
                .stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
        if (ill != null) {
            Doing.set(v, "settlements.doing.heal.visit", ill.getName());
            if (v.squaredDistanceTo(ill) > 6) {
                Move.to(v, ill, 0.6);
                return true;
            }
            Sickness.treat(ill);
            TREATED.put(ill.getUuid(), world.getTime() + 6000);
            Gaze.at(v, ill, 30f, 30f);
            Anim.set(v, Anim.Act.FEED, 40);
            Speech.sayNow(v, "settlements.say.heal.sick", ill.getName());
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, ill.getX(), ill.getBodyY(0.7), ill.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
            return true;
        }
        // a hurt player the village thinks well of: bandaged on the spot
        net.minecraft.entity.player.PlayerEntity hurt = world.getClosestPlayer(v, 8);
        if (hurt != null && hurt.getHealth() < hurt.getMaxHealth() * 0.7f && village != null && Reputation.tier(village.getReputation(hurt)) >= 3
                && world.getTime() >= TREATED.getOrDefault(hurt.getUuid(), 0L)) {
            if (v.squaredDistanceTo(hurt) > 6) {
                Move.to(v, hurt, 0.6);
                return true;
            }
            hurt.heal(6f);
            TREATED.put(hurt.getUuid(), world.getTime() + 20 * 60);
            Gaze.at(v, hurt, 30f, 30f);
            Anim.set(v, Anim.Act.FEED, 40);
            Speech.sayNow(v, "settlements.say.heal.player", hurt.getName());
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, hurt.getX(), hurt.getBodyY(0.7), hurt.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
            return true;
        }
        VillagerEntityMCA patient = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(48, 12, 48),
                        o -> o != v && o.isAlive() && o.getHealth() < o.getMaxHealth() * 0.7f)
                .stream().min(Comparator.comparingDouble(o -> o.getHealth())).orElse(null);
        if (patient == null) return false;
        if (v.squaredDistanceTo(patient) > 6) {
            Move.to(v, patient, 0.6);
            return false;
        }
        if (!takeFood(village)) return false;
        patient.heal(6f);
        Speech.say(v, "settlements.say.heal");
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, patient.getX(), patient.getBodyY(0.7), patient.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
        Gaze.at(v, patient, 30f, 30f);
        v.swingHand(v.getDominantHand());
        return true;
    }

    private static boolean takeFood(Village village) {
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (Workforce.eatable(s)) {
                s.decrement(1);
                if (s.isEmpty()) it.remove();
                village.markDirty();
                return true;
            }
        }
        return false;
    }
}
