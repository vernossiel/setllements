package net.mca.entity.ai.chatAI;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

public class TriggerCommandInfo {
    public String command;
    public String description;
    public BiPredicate<ServerPlayerEntity, VillagerEntityMCA> isActive;
    public BiConsumer<ServerPlayerEntity, VillagerEntityMCA> call;

    public TriggerCommandInfo(String command, String description, BiConsumer<ServerPlayerEntity, VillagerEntityMCA> call, BiPredicate<ServerPlayerEntity, VillagerEntityMCA> isActive) {
        this.command = command;
        this.description = description;
        this.call = call;
        this.isActive = isActive;
    }

    public TriggerCommandInfo(String command, String description, BiConsumer<ServerPlayerEntity, VillagerEntityMCA> call) {
        this(command, description, call, (p, v) -> true);
    }
}
