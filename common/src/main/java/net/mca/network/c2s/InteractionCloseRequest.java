package net.mca.network.c2s;

import net.mca.cobalt.network.Message;
import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Serial;
import java.util.UUID;

public class InteractionCloseRequest implements Message {
    @Serial
    private static final long serialVersionUID = 5410526074172819931L;

    private final UUID villagerUUID;

    public InteractionCloseRequest(UUID uuid) {
        villagerUUID = uuid;
    }

    @Override
    public void receive(ServerPlayerEntity player) {
        Entity v = player.getServerWorld().getEntity(villagerUUID);
        if (v instanceof VillagerEntityMCA villager) {
            villager.getInteractions().stopInteracting();
        }
    }
}
