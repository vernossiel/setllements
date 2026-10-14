package net.mca.item;

import net.mca.Config;
import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.PlayerSaveData;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;

public class WeddingRingItem extends RelationshipItem {
    public WeddingRingItem(Item.Settings properties) {
        super(properties);
    }

     @Override
    protected int getHeartsRequired() {
        return Config.getInstance().marriageHeartsRequirement;
    }

    @Override
    public boolean handle(ServerPlayerEntity player, VillagerEntityMCA villager) {
        PlayerSaveData playerData = PlayerSaveData.get(player);
        String response;

        if (super.handle(player, villager)) {
            return false;
        } else {
            response = "interaction.marry.success";
            playerData.marry(villager);
            villager.getRelationships().marry(player);
            villager.getVillagerBrain().modifyMoodValue(15);
        }

        villager.sendChatMessage(player, response);
        return true;
    }
}
