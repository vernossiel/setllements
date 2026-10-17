package net.mca.mixin;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BlockState;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Settlements: villagers walk their fields as farmers do, along the rows, and do not trample the beds back to earth. */
@Mixin(FarmlandBlock.class)
public class MixinFarmlandBlock {
    @Inject(method = "onLandedUpon", at = @At("HEAD"), cancellable = true)
    private void settlements$noTrample(World world, BlockState state, BlockPos pos, Entity entity, float fallDistance, CallbackInfo ci) {
        if (entity instanceof VillagerEntityMCA) {
            entity.handleFallDamage(fallDistance, 1.0f, world.getDamageSources().fall());
            ci.cancel();
        }
    }
}
