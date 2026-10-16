package net.mca.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * For testing only (/settlements warp on): the server runs its ticks back to back instead of twenty a second, as fast
 * as the machine can, so that days of village life go by in minutes. Nothing changes in the world but the pace.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerWarp {
    @Shadow
    protected long timeReference;
    @Shadow
    private long nextTickTimestamp;

    @Inject(method = "runTasksTillTickEnd", at = @At("HEAD"))
    private void settlements$warp(CallbackInfo ci) {
        if (net.mca.settlements.SettlementsCommand.WARP) {
            long now = Util.getMeasuringTimeMs();
            timeReference = now;
            nextTickTimestamp = now;
        }
    }
}
