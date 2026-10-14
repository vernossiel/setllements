package net.mca.item;

import net.mca.cobalt.network.NetworkHandler;
import net.mca.network.s2c.OpenGuiRequest;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public class WhistleItem extends TooltippedItem {
    public WhistleItem(Settings properties) {
        super(properties);
    }

    @Override
    public final TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);

        if (player instanceof ServerPlayerEntity serverPlayer) {
            NetworkHandler.sendToPlayer(new OpenGuiRequest(OpenGuiRequest.Type.WHISTLE), serverPlayer);
        }

        return TypedActionResult.success(stack);
    }
}