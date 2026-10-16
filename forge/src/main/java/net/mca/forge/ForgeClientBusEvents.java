package net.mca.forge;

import net.mca.MCA;
import net.mca.client.gui.DialogueHud;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only events on the forge bus: the face-to-face conversation over the game, and its controls. */
@Mod.EventBusSubscriber(modid = MCA.MOD_ID, value = Dist.CLIENT)
public class ForgeClientBusEvents {
    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        DialogueHud.render(event.getGuiGraphics());
    }

    /** In the stocks there is no getting down: the game's 'press shift to dismount' line is kept off the screen. */
    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null && net.mca.settlements.Seat.inStocks(mc.player) && mc.player.age % 5 == 0) {
            mc.inGameHud.setOverlayMessage(net.minecraft.text.Text.empty(), false);
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (DialogueHud.scroll(event.getScrollDelta())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isUseItem() && DialogueHud.use()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }
}
