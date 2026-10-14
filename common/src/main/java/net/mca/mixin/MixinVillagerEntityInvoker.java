package net.mca.mixin;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VillagerEntity.class)
public interface MixinVillagerEntityInvoker {
    @Invoker("beginTradeWith")
    void invokeBeginTradeWith(PlayerEntity player);
}
