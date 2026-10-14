package net.mca.server.world.data.villageComponents;

import net.mca.Config;
import net.mca.ProfessionsMCA;
import net.mca.entity.EquipmentSet;
import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.village.VillagerProfession;

import java.util.LinkedList;
import java.util.List;

public class VillageGuardsManager {
    private final Village village;

    public VillageGuardsManager(Village village) {
        this.village= village;
    }

    public void spawnGuards(ServerWorld world) {
        int guardCapacity = (int)Math.ceil(village.getPopulation() * Config.getInstance().guardSpawnFraction);

        // Count up the guards
        int guards = 0;
        int citizen = 0;
        List<VillagerEntityMCA> villagers = village.getResidents(world);
        List<VillagerEntityMCA> nonGuards = new LinkedList<>();
        for (VillagerEntityMCA villager : villagers) {
            if (villager.isGuard()) {
                guards++;
            } else {
                if (!villager.isBaby() && !villager.isProfessionImportant() && villager.getExperience() == 0 && villager.getVillagerData().getLevel() <= 1) {
                    nonGuards.add(villager);
                }
                citizen++;
            }
        }

        // Count all unloaded villagers against the guard limit
        // This is statistical and may not be accurate, but it's better than nothing
        guards += Math.ceil((village.getPopulation() - guards - citizen) * Config.getInstance().guardSpawnFraction);

        // Spawn a new guard if we don't have enough
        if (nonGuards.size() > 0 && guards < guardCapacity) {
            VillagerEntityMCA villager = nonGuards.get(world.random.nextInt(nonGuards.size()));
            villager.setProfession(guards % 2 == 0 ? ProfessionsMCA.GUARD.get() : ProfessionsMCA.ARCHER.get());
        }
    }


    /** Settlements: no free iron armor. Better gear is crafted by the village into the guard's pack (see Gear). */
    public EquipmentSet getGuardEquipment(VillagerProfession profession, Hand dominantHand) {
        if (profession == ProfessionsMCA.ARCHER.get()) {
            return getEquipmentFor(dominantHand, EquipmentSet.ARCHER_BARE, EquipmentSet.ARCHER_BARE_LEFT);
        }
        return getEquipmentFor(dominantHand, EquipmentSet.GUARD_BARE, EquipmentSet.GUARD_BARE_LEFT);
    }

    public static EquipmentSet getEquipmentFor(Hand dominantHand, EquipmentSet rightSet, EquipmentSet leftSet) {
        return dominantHand == Hand.OFF_HAND && leftSet != null ? leftSet : rightSet;
    }
}
