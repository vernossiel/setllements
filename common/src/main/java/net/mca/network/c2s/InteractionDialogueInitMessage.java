package net.mca.network.c2s;

import net.mca.cobalt.network.Message;
import net.mca.cobalt.network.NetworkHandler;
import net.mca.entity.VillagerEntityMCA;
import net.mca.network.s2c.InteractionDialogueResponse;
import net.mca.resources.Dialogues;
import net.mca.resources.data.dialogue.Question;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Serial;
import java.util.UUID;

public class InteractionDialogueInitMessage implements Message {
    @Serial
    private static final long serialVersionUID = -8007274573058750406L;

    private final UUID villagerUUID;

    public InteractionDialogueInitMessage(UUID uuid) {
        villagerUUID = uuid;
    }

    @Override
    public void receive(ServerPlayerEntity player) {
        Entity v = player.getServerWorld().getEntity(villagerUUID);
        if (v instanceof VillagerEntityMCA villager) {
            Question question = Dialogues.getInstance().getQuestion("root");
            if (question.isAuto()) {
                Dialogues.getInstance().selectAnswer(villager, player, question.getName(), question.getRandomAnswer().getName());
            } else {
                NetworkHandler.sendToPlayer(new InteractionDialogueResponse(question, player, villager), player);
            }
        }
    }
}
