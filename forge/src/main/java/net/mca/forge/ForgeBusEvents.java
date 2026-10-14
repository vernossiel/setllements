package net.mca.forge;

import net.mca.MCA;
import net.mca.MCAClient;
import net.mca.server.ServerInteractionManager;
import net.mca.server.command.AdminCommand;
import net.mca.server.command.Command;
import net.mca.server.world.data.VillageManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

/**
 * Events that listen on the forge event bus.
 *
 * @see {@link MinecraftForge#EVENT_BUS}
 */
@Mod.EventBusSubscriber(modid = MCA.MOD_ID)
public class ForgeBusEvents {
    @SubscribeEvent
    public static void onCommandRegister(RegisterCommandsEvent event) {
        AdminCommand.register(event.getDispatcher());
        Command.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.LevelTickEvent event) {
        if (!event.level.isClient && event.side == LogicalSide.SERVER && event.phase == TickEvent.Phase.END) {
            VillageManager.get((ServerWorld)event.level).tick();
            if (event.level.getTime() % 20 == 0) net.mca.settlements.Reputation.tick((ServerWorld)event.level);
            if (event.level.getTime() % 24000 == 6000) net.mca.settlements.Diplomacy.daily((ServerWorld)event.level);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.side == LogicalSide.SERVER && event.phase == TickEvent.Phase.END) {
            ServerInteractionManager.getInstance().tick();
        }
        MCA.setServer(event.getServer());
    }

    @SubscribeEvent
    public static void OnEntityJoinWorldEvent(EntityJoinLevelEvent event) {
        if (event.getEntity().getWorld().isClient) {
            if (MinecraftClient.getInstance().player == null || event.getEntity().getUuid().equals(MinecraftClient.getInstance().player.getUuid())) {
                MCAClient.onLogin();
            }
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity player) {
            net.mca.settlements.Protection.onBreak(player, event.getPos(), event.getState());
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayerEntity player) {
            net.mca.settlements.Reputation.onOpen(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayerEntity player) {
            net.mca.settlements.Reputation.onClose(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
        ServerInteractionManager.getInstance().onPlayerJoin((ServerPlayerEntity)event.getEntity());
    }

    @SubscribeEvent
    public static void onParticleFactoryRegistration(TickEvent.ClientTickEvent event) {
        MCAClient.tickClient(MinecraftClient.getInstance());
    }
}
