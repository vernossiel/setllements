package net.mca.client.model;

import net.mca.entity.VillagerLike;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.CrossbowPosing;
import net.minecraft.entity.LivingEntity;

public class ZombieVillagerEntityModelMCA<T extends LivingEntity & VillagerLike<T>> extends VillagerEntityModelMCA<T> {
    public ZombieVillagerEntityModelMCA(ModelPart tree) {
        super(tree);
    }

    @Override
    public void setAngles(T villager, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        super.setAngles(villager, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
        CrossbowPosing.meleeAttack(leftArm, rightArm, false, handSwingProgress, animationProgress);
        leftArmwear.copyTransform(leftArm);
        rightArmwear.copyTransform(rightArm);
    }
}
