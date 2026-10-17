package net.mca.mixin;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Settlements: a villager's own float on the water. The game's float belongs to a player's rod and does away with itself
 * at once without one; this one stays as long as the fisher keeps his line out, and lies where his cast put it (see
 * FishingTask, which moves it on a bite and reels it in).
 */
@Mixin(FishingBobberEntity.class)
public abstract class MixinFishingBobberEntity {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void settlements$villagersFloat(CallbackInfo ci) {
        FishingBobberEntity self = (FishingBobberEntity) (Object) this;
        if (!(self.getOwner() instanceof VillagerEntityMCA v)) return;
        if (!self.getWorld().isClient && (!v.isAlive() || v.squaredDistanceTo(self) > 24 * 24)) self.discard();
        ci.cancel();
    }

    /** On the client: not thrown away for want of a player at the other end of the line. */
    @Redirect(method = "onSpawnPacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/projectile/FishingBobberEntity;kill()V"))
    private void settlements$keepVillagersFloat(FishingBobberEntity self) {
        if (!(self.getOwner() instanceof VillagerEntityMCA)) self.kill();
    }
}
