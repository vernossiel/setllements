package net.mca.network.c2s;

import net.mca.cobalt.network.Message;
import net.mca.entity.VillagerLike;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Serial;
import java.util.UUID;

public class InteractionVillagerMessage implements Message {
    @Serial
    private static final long serialVersionUID = 2563941495766992462L;

    private final String command;
    private final UUID villagerUUID;

    public InteractionVillagerMessage(String command, UUID villagerUUID) {
        this.command = command.replace("gui.button.", "");
        this.villagerUUID = villagerUUID;
    }

    @Override
    public void receive(ServerPlayerEntity player) {
        Entity v = player.getServerWorld().getEntity(villagerUUID);
        if (v instanceof VillagerLike<?> villager) {
            if (villager.getInteractions().handle(player, command)) {
                villager.getInteractions().stopInteracting();
            }
        }
    }
}
