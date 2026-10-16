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
