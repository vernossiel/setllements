package net.mca;

import net.mca.network.ClientInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Workaround for Forge's BS
 */
public class ClientProxy {

    private static Impl INSTANCE = new Impl();

    @Nullable
    public static PlayerEntity getClientPlayer() {
        return INSTANCE.getClientPlayer();
    }

    public static ClientInteractionManager getNetworkHandler() {
        return INSTANCE.getNetworkHandler();
    }

    public static class Impl {
        protected Impl() {
            INSTANCE = this;
        }

        public PlayerEntity getClientPlayer() {
            return null;
        }

        public ClientInteractionManager getNetworkHandler() {
            return null;
        }
    }
}
