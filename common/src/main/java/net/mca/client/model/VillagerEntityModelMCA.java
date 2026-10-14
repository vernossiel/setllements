package net.mca.client.model;

import com.google.common.collect.ImmutableList;
import net.mca.entity.VillagerLike;
import net.minecraft.client.model.*;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelPartNames;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;

public class VillagerEntityModelMCA<T extends LivingEntity & VillagerLike<T>> extends VillagerEntityBaseModelMCA<T> {
    protected static final String BREASTPLATE = "breastplate";

    public final ModelPart breastsWear;
    public final ModelPart leftArmwear;
    public final ModelPart rightArmwear;
    public final ModelPart leftLegwear;
    public final ModelPart rightLegwear;
    public final ModelPart bodyWear;

    private boolean wearsHidden;

    public VillagerEntityModelMCA(ModelPart tree) {
        super(tree);
        bodyWear = tree.getChild(EntityModelPartNames.JACKET);
        leftArmwear = tree.getChild("left_sleeve");
        rightArmwear = tree.getChild("right_sleeve");
        leftLegwear = tree.getChild("left_pants");
        rightLegwear = tree.getChild("right_pants");

        breastsWear = tree.getChild(BREASTPLATE);
    }

    //
    // body - 0 (body.body 0.0)
    // face - 0 (body.head 0.01)
    //  clothing - 1 (clothing.body 0.075)
    //   hair - 2 (hair.body 0.1) + (hair.hat 0.1 + 0.3 = 0.4)
    //    hood - 3 (clothing.hat 0.075 + 0.5 = 0.575)

    public static ModelData hairData(Dilation dilation) {
        ModelData modelData = bodyData(dilation);
        ModelPartData root = modelData.getRoot();
        root.addChild(EntityModelPartNames.HAT, ModelPartBuilder.create().uv(32, 0).cuboid(-4, -8, -4, 8, 8, 8, dilation.add(0.3F)), ModelTransform.NONE);
        return modelData;
    }

    public static ModelData bodyData(Dilation dilation) {
        return bodyData(dilation, false);
    }

    public static ModelData bodyData(Dilation dilation, boolean slim) {
        ModelData modelData = PlayerEntityModel.getTexturedModelData(dilation, slim);
        ModelPartData root = modelData.getRoot();
        root.addChild(BREASTS, newBreasts(dilation, 0), ModelTransform.NONE);
        root.addChild(BREASTPLATE, newBreasts(dilation.add(0.1F), 16), ModelTransform.NONE);
        return modelData;
    }

    public static ModelData armorData(Dilation dilation) {
        ModelData modelData = BipedEntityModel.getModelData(dilation, 0.0f);
        ModelPartData root = modelData.getRoot();
        root.addChild(BREASTS, newBreasts(dilation, 0), ModelTransform.NONE);
        return modelData;
    }

    @Override
    protected Iterable<ModelPart> getBodyParts() {
        return ImmutableList.of(body, rightArm, leftArm, rightLeg, leftLeg, bodyWear, leftLegwear, rightLegwear, leftArmwear, rightArmwear);
    }

    @Override
    public Iterable<ModelPart> getBreastParts() {
        return ImmutableList.of(breasts, breastsWear);
    }

    @Override
    public void setAngles(T villager, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        super.setAngles(villager, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
        leftLegwear.copyTransform(leftLeg);
        rightLegwear.copyTransform(rightLeg);
        leftArmwear.copyTransform(leftArm);
        rightArmwear.copyTransform(rightArm);
        bodyWear.copyTransform(body);
        breastsWear.copyTransform(breasts);
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);

        leftArmwear.visible = !wearsHidden && visible;
        rightArmwear.visible = !wearsHidden && visible;
        leftLegwear.visible = !wearsHidden && visible;
        rightLegwear.visible = !wearsHidden && visible;
        bodyWear.visible = !wearsHidden && visible;
    }

    public VillagerEntityModelMCA<T> hideWears() {
        wearsHidden = true;
        breastsWear.visible = false;
        leftArmwear.visible = false;
        rightArmwear.visible = false;
        leftLegwear.visible = false;
        rightLegwear.visible = false;
        bodyWear.visible = false;
        return this;
    }

    @Override
    public void copyBipedStateTo(BipedEntityModel<T> target) {
        super.copyBipedStateTo(target);
        if (target instanceof VillagerEntityModelMCA) {
            copyAttributes((VillagerEntityModelMCA<T>)target);
        }
    }

    private void copyAttributes(VillagerEntityModelMCA<T> target) {
        target.leftLegwear.copyTransform(leftLegwear);
        target.rightLegwear.copyTransform(rightLegwear);
        target.leftArmwear.copyTransform(leftArmwear);
        target.rightArmwear.copyTransform(rightArmwear);
        target.bodyWear.copyTransform(bodyWear);
        target.breastsWear.copyTransform(breastsWear);
    }

    public <M extends BipedEntityModel<T>> void copyVisibility(M model) {
        head.visible = model.head.visible;
        hat.visible = model.head.visible;
        body.visible = model.body.visible;
        bodyWear.visible = model.body.visible;
        breasts.visible = model.body.visible;
        breastsWear.visible = model.body.visible;
        leftArm.visible = model.leftArm.visible;
        leftArmwear.visible = model.leftArm.visible;
        rightArm.visible = model.rightArm.visible;
        rightArmwear.visible = model.rightArm.visible;
        leftLeg.visible = model.leftLeg.visible;
        leftLegwear.visible = model.leftLeg.visible;
        rightLeg.visible = model.rightLeg.visible;
        rightLegwear.visible = model.rightLeg.visible;
    }
}
