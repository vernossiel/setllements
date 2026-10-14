package net.mca.mixin;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.JumpingMount;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Saddleable;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.inventory.InventoryChangedListener;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractHorseEntity.class)
abstract class MixinHorseBaseEntity extends AnimalEntity implements InventoryChangedListener, JumpingMount, Saddleable {
    @Shadow @Nullable public abstract LivingEntity getControllingPassenger();

    MixinHorseBaseEntity() { super(null, null); }

    @Inject(method = "isImmobile()Z", at = @At("HEAD"), cancellable = true)
    private void onIsImmobile(CallbackInfoReturnable<Boolean> info) {
        if (getControllingPassenger() instanceof VillagerEntityMCA) {
            info.setReturnValue(false); // Fixes villagers not being able to move when riding a horse
        }
    }
}
