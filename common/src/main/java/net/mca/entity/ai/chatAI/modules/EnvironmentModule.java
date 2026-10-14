package net.mca.entity.ai.chatAI.modules;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

public class EnvironmentModule {
    public static void apply(List<String> input, VillagerEntityMCA villager, ServerPlayerEntity player) {
        if (player.getWorld().isRaining()) {
            input.add("It is raining. ");
        }
        if (player.getWorld().isThundering()) {
            input.add("It is thundering. ");
        }
        if (player.getWorld().isNight()) {
            input.add("It is night. ");
        }
    }
}
