package net.mca.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.ZombieSiegeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Settlements: no vanilla zombie siege in the middle of a village. The siege drops a score of zombies right among the
 * houses at midnight, past every guard and wall - out of nowhere, as it looked; they killed the builder at his door.
 * The night's monsters come in from the dark outside instead (see MixinSpawnHelper).
 */
@Mixin(ZombieSiegeManager.class)
public abstract class MixinZombieSiegeManager {
    @Shadow
    private int startX;
    @Shadow
    private int startY;
    @Shadow
    private int startZ;

    @Inject(method = "trySpawnZombie(Lnet/minecraft/server/world/ServerWorld;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void settlements$noSiegeInVillages(ServerWorld world, CallbackInfo ci) {
        if (net.mca.settlements.Safety.inVillage(world, new BlockPos(startX, startY, startZ))) ci.cancel();
    }
}
