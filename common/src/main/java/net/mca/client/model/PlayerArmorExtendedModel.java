package net.mca.client.model;

import com.google.common.collect.ImmutableList;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.relationship.VillagerDimensions;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;

import static net.mca.client.model.VillagerEntityBaseModelMCA.BREASTS;

public class PlayerArmorExtendedModel<T extends LivingEntity> extends BipedEntityModel<T> implements CommonVillagerModel<T> {
    public final ModelPart breasts;

    final VillagerDimensions.Mutable dimensions = new VillagerDimensions.Mutable(AgeState.ADULT);
    float breastSize;

    public PlayerArmorExtendedModel(ModelPart root) {
        super(root);
        this.breasts = root.getChild(BREASTS);
    }

    @Override
    public void copyBipedStateTo(BipedEntityModel<T> target) {
        super.copyBipedStateTo(target);

        if (target instanceof PlayerEntityExtendedModel<T> playerTarget) {
            copyAttributes(playerTarget);
        }
    }

    private void copyAttributes(PlayerEntityExtendedModel<T> target) {
        copyCommonAttributes(target);

        target.breasts.visible = breasts.visible;
        target.breasts.copyTransform(breasts);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, float red, float green, float blue, float alpha) {
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
        return ImmutableList.of(breasts);
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
}
