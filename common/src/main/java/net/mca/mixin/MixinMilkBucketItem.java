package net.mca.mixin;

import net.mca.client.model.CommonVillagerModel;
import net.mca.entity.VillagerLike;
import net.mca.entity.ai.Traits;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MilkBucketItem;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MilkBucketItem.class)
public class MixinMilkBucketItem {
    @Inject(method = "finishUsing", at = @At("RETURN"))
    public void onFinishedUsing(ItemStack stack, World world, LivingEntity user, CallbackInfoReturnable<ItemStack> cir) {
        VillagerLike<?> villagerLike = world.isClient ? CommonVillagerModel.getVillager(user) : VillagerLike.toVillager(user);
        if (villagerLike != null) {
            if (villagerLike.getTraits().hasTrait(Traits.LACTOSE_INTOLERANCE)) {
                user.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100, 0));
            }
        }
    }
}
