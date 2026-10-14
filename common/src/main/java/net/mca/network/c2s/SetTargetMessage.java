
package net.mca.network.c2s;

import net.mca.cobalt.network.Message;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.io.Serial;
import java.util.Arrays;
import java.util.UUID;


public class SetTargetMessage implements Message {
    @Serial
    private static final long serialVersionUID = 7257172480717481644L;

    private final String itemIdentifier;
    private final String targetName;
    private final String targetUUID;

    public SetTargetMessage(Identifier identifier, String targetName, UUID targetUUID) {
        this.itemIdentifier = identifier.toString();
        this.targetName = targetName;
        this.targetUUID = targetUUID.toString();
    }

    @Override
    public void receive(ServerPlayerEntity player) {
        Arrays.stream(Hand.values()).forEach(hand -> {
            ItemStack stack = player.getStackInHand(hand);
            if (Registries.ITEM.getId(stack.getItem()).toString().equals(itemIdentifier)) {
                stack.getOrCreateNbt().putString("targetName", targetName);
                stack.getOrCreateNbt().putUuid("targetUUID", UUID.fromString(targetUUID));
            }
        });
    }
}
