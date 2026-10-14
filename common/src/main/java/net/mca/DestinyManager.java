package net.mca;

import net.mca.client.gui.DestinyScreen;
import net.minecraft.client.MinecraftClient;

public class DestinyManager {
    private boolean openDestiny;
    private boolean allowTeleportation;

    public void tick(MinecraftClient client) {
        if (openDestiny && client.currentScreen == null) {
            assert client.player != null;
            client.setScreen(new DestinyScreen(client.player.getUuid(), allowTeleportation));
        }
    }

    public void requestOpen(boolean allowTeleportation) {
        this.openDestiny = true;
        this.allowTeleportation = allowTeleportation;
    }

    public void allowClosing() {
        this.openDestiny = false;
    }
}
