package net.mca.client.model;

import com.google.common.collect.ImmutableList;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.relationship.VillagerDimensions;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;

import static net.mca.client.model.VillagerEntityBaseModelMCA.BREASTS;
import static net.mca.client.model.VillagerEntityModelMCA.BREASTPLATE;

public class PlayerEntityExtendedModel<T extends LivingEntity> extends PlayerEntityModel<T> implements CommonVillagerModel<T> {
    public final ModelPart breasts;
    public final ModelPart breastsWear;

    final VillagerDimensions.Mutable dimensions = new VillagerDimensions.Mutable(AgeState.ADULT);
    float breastSize;

    public PlayerEntityExtendedModel(ModelPart root) {
        super(root, false);
        this.breasts = root.getChild(BREASTS);
        this.breastsWear = root.getChild(BREASTPLATE);
    }

    @Override
    public void copyBipedStateTo(BipedEntityModel<T> target) {
        super.copyBipedStateTo(target);

        if (target instanceof PlayerEntityExtendedModel<T> playerTarget) {
            copyAttributes(playerTarget);
        }
        if (target instanceof PlayerArmorExtendedModel<T> armorTarget) {
            copyAttributes(armorTarget);
        }
    }

    private void copyAttributes(PlayerEntityExtendedModel<T> target) {
        target.leftPants.copyTransform(leftPants);
        target.rightPants.copyTransform(rightPants);
        target.leftSleeve.copyTransform(leftSleeve);
        target.rightSleeve.copyTransform(rightSleeve);
        target.jacket.copyTransform(jacket);
        target.breastsWear.copyTransform(breastsWear);

        copyCommonAttributes(target);

        target.breasts.visible = breasts.visible;
        target.breasts.copyTransform(breasts);
    }

    private void copyAttributes(PlayerArmorExtendedModel<T> target) {
        copyCommonAttributes(target);

        target.breasts.visible = breasts.visible;
        target.breasts.copyTransform(breasts);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, float red, float green, float blue, float alpha) {
        // Idk anymore
        breastsWear.visible = jacket.visible;

        renderCommon(matrices, vertices, light, overlay, red, green, blue, alpha);
    }

    @Override
    public ModelPart getBreastPart() {
        return breasts;
    }

    @Override
    public ModelPart getBodyPart() {
        return body;
    }

    @Override
    public Iterable<ModelPart> getCommonHeadParts() {
        return getHeadParts();
    }

    @Override
    public Iterable<ModelPart> getCommonBodyParts() {
        return getBodyParts();
    }

    @Override
    public Iterable<ModelPart> getBreastParts() {
        return ImmutableList.of(breasts, breastsWear);
    }

    @Override
    public VillagerDimensions.Mutable getDimensions() {
        return dimensions;
    }

    @Override
    public float getBreastSize() {
        return breastSize;
    }

    @Override
    public void setBreastSize(float breastSize) {
        this.breastSize = breastSize;
    }

    @Override
    public void setAngles(T villager, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        if (CommonVillagerModel.getVillager(villager).getAgeState() == AgeState.BABY && !villager.hasVehicle()) {
            limbDistance = (float)Math.sin(villager.age / 12F);
            limbAngle = (float)Math.cos(villager.age / 9F) * 3;
            headYaw += (float)Math.sin(villager.age / 2F);
        }

        super.setAngles(villager, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
        applyVillagerDimensions(CommonVillagerModel.getVillager(villager), villager.isInSneakingPose());
    }

    public <M extends BipedEntityModel<T>> void copyVisibility(M model) {
        head.visible = model.head.visible;
        hat.visible = model.head.visible;
        body.visible = model.body.visible;
        jacket.visible = model.body.visible;
        breasts.visible = model.body.visible;
        breastsWear.visible = model.body.visible;
        leftArm.visible = model.leftArm.visible;
        leftSleeve.visible = model.leftArm.visible;
        rightArm.visible = model.rightArm.visible;
        rightSleeve.visible = model.rightArm.visible;
        leftLeg.visible = model.leftLeg.visible;
        leftPants.visible = model.leftLeg.visible;
        rightLeg.visible = model.rightLeg.visible;
        rightPants.visible = model.rightLeg.visible;
    }
}
