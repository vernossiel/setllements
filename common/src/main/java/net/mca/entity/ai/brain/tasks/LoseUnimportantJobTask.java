package net.mca.entity.ai.brain.tasks;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.Task;
import net.minecraft.entity.ai.brain.task.TaskTriggerer;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;

public class LoseUnimportantJobTask {
    protected static boolean shouldRun(ServerWorld world, VillagerEntity entity) {
        return !((VillagerEntityMCA) entity).isProfessionImportant();
    }

    public static Task<VillagerEntity> create() {
        return TaskTriggerer.task((context) -> {
            return context.group(context.queryMemoryAbsent(MemoryModuleType.JOB_SITE)).apply(context, (jobSite) -> {
                return (world, entity, time) -> {
                    VillagerData villagerData = entity.getVillagerData();
                    if (shouldRun(world, entity) && villagerData.getProfession() != VillagerProfession.NONE && villagerData.getProfession() != VillagerProfession.NITWIT && entity.getExperience() == 0 && villagerData.getLevel() <= 1) {
                        entity.setVillagerData(entity.getVillagerData().withProfession(VillagerProfession.NONE));
                        entity.reinitializeBrain(world);
                        return true;
                    } else {
                        return false;
                    }
                };
            });
        });
    }
}