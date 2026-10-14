package net.mca.item;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Traits;
import net.mca.entity.ai.relationship.Gender;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public class SirbenBabyItem extends BabyItem {
    public SirbenBabyItem(Gender gender, Settings properties) {
        super(gender, properties);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    protected VillagerEntityMCA birthChild(ItemStack stack, ServerWorld world, ServerPlayerEntity player) {
        VillagerEntityMCA child = super.birthChild(stack, world, player);
        child.getTraits().addTrait(Traits.SIRBEN);
        return child;
    }
}
