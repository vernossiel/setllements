package net.mca.item;

import net.mca.Config;
import net.mca.cobalt.network.NetworkHandler;
import net.mca.network.s2c.OpenGuiRequest;
import net.mca.server.world.data.VillagerTrackerManager;
import net.mca.util.NbtHelper;
import net.mca.util.localization.FlowingText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CompassItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Vanishable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public class VillagerTrackerItem extends Item implements Vanishable {
    public VillagerTrackerItem(Item.Settings settings) {
        super(settings);
    }

    @Override
    public final TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);

        if (player instanceof ServerPlayerEntity serverPlayer) {
            NetworkHandler.sendToPlayer(new OpenGuiRequest(OpenGuiRequest.Type.VILLAGER_TRACKER), serverPlayer);
        }

        return TypedActionResult.success(stack);
    }

    public static GlobalPos getTargetPos(ItemStack stack) {
        NbtCompound position = stack.getSubNbt("position");
        return position != null ? NbtHelper.decodeGlobalPos(position) : null;
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return CompassItem.hasLodestone(stack) || super.hasGlint(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (world instanceof ServerWorld serverWorld) {
            if (world.getTime() % Config.getInstance().trackVillagerPositionEveryNTicks == 0 && stack.getOrCreateNbt().contains("targetUUID")) {
                UUID uuid = stack.getOrCreateNbt().getUuid("targetUUID");
                GlobalPos pos = VillagerTrackerManager.get(serverWorld).get(uuid);
                if (pos != null) {
                    stack.getOrCreateNbt().put("position", NbtHelper.encodeGlobalPosition(pos));
                }
            }
        }
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        if (stack.getOrCreateNbt().contains("targetName")) {
            //noinspection ConstantConditions
            tooltip.add(Text.translatable(this.getTranslationKey(stack) + ".active", stack.getOrCreateNbt().get("targetName").asString()).formatted(Formatting.GREEN));

            GlobalPos pos = getTargetPos(stack);
            if (pos != null && world != null && pos.getDimension() == world.getRegistryKey()) {
                ClientPlayerEntity player = MinecraftClient.getInstance().player;
                if (player != null) {
                    int precision = 5;
                    int distance = ((int)Math.sqrt(pos.getPos().getSquaredDistance(player.getPos()))) / precision * precision;
                    tooltip.add(Text.translatable(this.getTranslationKey(stack) + ".distance", distance).formatted(Formatting.ITALIC));
                }
            }
        }
        tooltip.addAll(FlowingText.wrap(Text.translatable(getTranslationKey(stack) + ".tooltip").formatted(Formatting.GRAY), 160));
    }
}

