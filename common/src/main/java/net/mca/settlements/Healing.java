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
    private Healing() {
    }

    public static boolean work(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        VillagerEntityMCA patient = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(48, 12, 48),
                        o -> o != v && o.isAlive() && o.getHealth() < o.getMaxHealth() * 0.7f)
                .stream().min(Comparator.comparingDouble(o -> o.getHealth())).orElse(null);
        if (patient == null) return false;
        if (v.squaredDistanceTo(patient) > 6) {
            v.getNavigation().startMovingTo(patient, 0.6);
            return false;
        }
        if (!takeFood(village)) return false;
        patient.heal(6f);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, patient.getX(), patient.getBodyY(0.7), patient.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
        v.getLookControl().lookAt(patient, 30f, 30f);
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
