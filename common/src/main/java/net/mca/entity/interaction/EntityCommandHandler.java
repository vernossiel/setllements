package net.mca.entity.interaction;

import net.mca.cobalt.network.NetworkHandler;
import net.mca.entity.VillagerLike;
import net.mca.network.s2c.OpenGuiRequest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public abstract class EntityCommandHandler<T extends Entity & VillagerLike<?>> {
    @Nullable
    protected PlayerEntity interactingPlayer;

    protected final T entity;

    public EntityCommandHandler(T entity) {
        this.entity = entity;
    }

    public Optional<PlayerEntity> getInteractingPlayer() {
        return Optional.ofNullable(interactingPlayer).filter(player -> player.currentScreenHandler != null);
    }

    public void stopInteracting() {
        if (!entity.getWorld().isClient) {
            if (interactingPlayer instanceof ServerPlayerEntity serverPlayer) {
                serverPlayer.closeHandledScreen();
            }
        }
        interactingPlayer = null;
    }

    public ActionResult interactAt(PlayerEntity player, Vec3d pos, @NotNull Hand hand) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            NetworkHandler.sendToPlayer(new OpenGuiRequest(OpenGuiRequest.Type.INTERACT, entity), serverPlayer);
        }
        interactingPlayer = player;
        return ActionResult.SUCCESS;
    }

    /**
     * Called on the server to respond to button events.
     */
    public boolean handle(ServerPlayerEntity player, String command) {
        return false;
    }
}
