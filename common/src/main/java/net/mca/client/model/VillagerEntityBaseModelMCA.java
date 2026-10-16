package net.mca.client.model;

import com.google.common.collect.ImmutableList;
import net.mca.Config;
import net.mca.entity.VillagerLike;
import net.mca.entity.ai.relationship.AgeState;
import net.mca.entity.ai.relationship.VillagerDimensions;
import net.minecraft.client.model.*;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;

public class VillagerEntityBaseModelMCA<T extends LivingEntity & VillagerLike<T>> extends BipedEntityModel<T> implements CommonVillagerModel<T> {
    protected static final String BREASTS = "breasts";

    public final ModelPart breasts;

    final VillagerDimensions.Mutable dimensions = new VillagerDimensions.Mutable(AgeState.ADULT);
    float breastSize;

    public VillagerEntityBaseModelMCA(ModelPart root) {
        super(root);
        this.breasts = root.getChild(BREASTS);
    }

    public static ModelData getModelData(Dilation dilation) {
        ModelData modelData = BipedEntityModel.getModelData(dilation, 0.0f);
        ModelPartData data = modelData.getRoot();

        data.addChild(BREASTS, newBreasts(dilation, 0), ModelTransform.NONE);

        return modelData;
    }

    protected static ModelPartBuilder newBreasts(Dilation dilation, int oy) {
        ModelPartBuilder builder = ModelPartBuilder.create();
        if (Config.getInstance().enableBoobs) {
            builder.uv(18, 21 + oy).cuboid(-3.25F, -1.25F, -1.5F, 6, 3, 3, dilation);
        }
        return builder;
    }

    @Override
    protected Iterable<ModelPart> getHeadParts() {
        return ImmutableList.of(head, hat);
    }

    @Override
    protected Iterable<ModelPart> getBodyParts() {
        return ImmutableList.of(body, rightArm, leftArm, rightLeg, leftLeg);
    }

    @Override
    public void animateModel(T entity, float limbAngle, float limbDistance, float tickDelta) {
        super.animateModel(entity, limbDistance, limbAngle, tickDelta);
        riding |= entity.getAgeState() == AgeState.BABY;
    }

    @Override
    public void setAngles(T villager, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        if (villager.getAgeState() == AgeState.BABY && !villager.hasVehicle()) {
            limbDistance = (float)Math.sin(villager.age / 12F);
            limbAngle = (float)Math.cos(villager.age / 9F) * 3;
            headYaw += (float)Math.sin(villager.age / 2F);
        }

        //remove the boost for babies
        if (villager.isBaby()) {
            limbAngle /= 3.0f;
        }

        //and add our own
        limbAngle /= (0.2f + villager.getRawScaleFactor());

        super.setAngles(villager, limbAngle, limbDistance, animationProgress, headYaw, headPitch);

        if (villager.getVillagerBrain().isPanicking()) {
            float toRadians = (float)Math.PI / 180;

            float armRaise = (((float)Math.sin(animationProgress / 5) * 30 - 180)
                    + ((float)Math.sin(animationProgress / 3) * 3))
                    * toRadians;
            float waveSideways = ((float)Math.sin(animationProgress / 2) * 12 - 17) * toRadians;

            this.leftArm.pitch = armRaise;
            this.leftArm.roll = -waveSideways;
            this.rightArm.pitch = -armRaise;
            this.rightArm.roll = waveSideways;
        }

        if (villager instanceof net.mca.entity.VillagerEntityMCA v && !villager.isSleeping() && !villager.getVillagerBrain().isPanicking()) {
            posePart(v, animationProgress, limbDistance);
        }

        applyVillagerDimensions(villager, villager.isInSneakingPose());
    }

    /** The arms at work, by what the server says his hands are busy with (see settlements.Anim). */
    private void posePart(net.mca.entity.VillagerEntityMCA v, float t, float walking) {
        net.mca.settlements.Anim.Act act = net.mca.settlements.Anim.of(v.getSettlementsAction());
        float s = (float)Math.sin(t * 0.45f), c = (float)Math.cos(t * 0.45f);
        // a strike: up slowly, down fast
        float phase = (t * 0.11f) % 1f;
        float strike = phase < 0.7f ? phase / 0.7f : 1f - (phase - 0.7f) / 0.3f;
        if (act == net.mca.settlements.Anim.Act.NONE && v.isUsingItem() && v.getActiveItem().isFood()) {
            // a bite: the hand to the mouth, a little shake
            rightArm.pitch = -1.7f + (float)Math.sin(t * 1.2f) * 0.12f;
            rightArm.yaw = -0.45f;
            return;
        }
        if (act == net.mca.settlements.Anim.Act.NONE && v.isElder() && walking < 0.1f) {
            // the elder stands with his hands behind his back
            rightArm.pitch = 0.45f;
            leftArm.pitch = 0.45f;
            rightArm.roll = 0.25f;
            leftArm.roll = -0.25f;
            rightArm.yaw = -0.3f;
            leftArm.yaw = 0.3f;
            return;
        }
        switch (act) {
            case CHOP -> { // both hands on the haft, from over the shoulder into the trunk
                float a = -2.6f + strike * 2.0f;
                rightArm.pitch = a;
                leftArm.pitch = a;
                rightArm.yaw = -0.35f;
                leftArm.yaw = 0.35f;
                body.yaw = 0.15f - strike * 0.3f;
            }
            case MINE -> { // the pick brought down hard, one hand leading
                float fast = (t * 0.16f) % 1f;
                float st = fast < 0.6f ? fast / 0.6f : 1f - (fast - 0.6f) / 0.4f;
                rightArm.pitch = -2.8f + st * 2.2f;
                leftArm.pitch = -2.4f + st * 1.9f;
                rightArm.yaw = -0.2f;
                leftArm.yaw = 0.45f;
                body.pitch = st * 0.2f;
            }
            case DIG -> { // the spade pushed down into the ground and levered up
                rightArm.pitch = -0.9f + s * 0.35f;
                leftArm.pitch = -0.7f + s * 0.35f;
                rightArm.yaw = -0.15f;
                leftArm.yaw = 0.3f;
                body.pitch = 0.25f + s * 0.1f;
            }
            case SOW -> { // a hand low, scattering
                rightArm.pitch = -0.7f + s * 0.4f;
                rightArm.yaw = c * 0.5f;
                leftArm.pitch = -0.3f;
            }
            case REAP -> { // both hands low, sweeping across
                rightArm.pitch = -0.9f;
                leftArm.pitch = -0.9f;
                rightArm.yaw = s * 0.6f;
                leftArm.yaw = s * 0.6f;
                body.yaw = s * 0.25f;
            }
            case CARRY -> { // both arms round the load in front of him
                rightArm.pitch = -1.05f;
                leftArm.pitch = -1.05f;
                rightArm.yaw = -0.3f;
                leftArm.yaw = 0.3f;
                rightArm.roll = 0f;
                leftArm.roll = 0f;
            }
            case BUILD -> { // the hammer at the wall, the other hand holding
                rightArm.pitch = -1.9f + strike * 1.0f;
                rightArm.yaw = -0.1f;
                leftArm.pitch = -1.2f;
                leftArm.yaw = 0.25f;
            }
            case TALK -> { // hands that talk
                rightArm.pitch = -0.55f + (float)Math.sin(t * 0.21f) * 0.3f;
                rightArm.yaw = (float)Math.sin(t * 0.13f) * 0.35f;
                leftArm.pitch = -0.3f + (float)Math.sin(t * 0.17f + 1f) * 0.25f;
                leftArm.roll = -0.15f;
            }
            case CLAP -> { // hands together in front, clapping
                float clap = Math.abs((float)Math.sin(t * 0.9f));
                rightArm.pitch = -1.3f;
                leftArm.pitch = -1.3f;
                rightArm.yaw = -0.15f - clap * 0.45f;
                leftArm.yaw = 0.15f + clap * 0.45f;
            }
            case WAVE -> { // a hand raised, waving
                rightArm.pitch = -2.9f;
                rightArm.roll = 0.25f + s * 0.35f;
                rightArm.yaw = 0f;
            }
            case EAT -> {
                rightArm.pitch = -1.7f + (float)Math.sin(t * 1.2f) * 0.12f;
                rightArm.yaw = -0.45f;
            }
            case SHEAR -> { // down at the fleece, both hands, snipping
                rightArm.pitch = -1.0f + (float)Math.sin(t * 1.1f) * 0.12f;
                leftArm.pitch = -1.1f;
                rightArm.yaw = -0.2f;
                leftArm.yaw = 0.3f;
                body.pitch = 0.35f;
            }
            case FEED -> { // a hand held out, low, with something in it
                rightArm.pitch = -1.15f + s * 0.1f;
                rightArm.yaw = -0.1f;
                body.pitch = 0.2f;
            }
            case BEHIND -> {
                rightArm.pitch = 0.45f;
                leftArm.pitch = 0.45f;
                rightArm.roll = 0.25f;
                leftArm.roll = -0.25f;
            }
            default -> {
            }
        }
    }

    @Override
    public void copyBipedStateTo(BipedEntityModel<T> target) {
        super.copyBipedStateTo(target);

        if (target instanceof VillagerEntityBaseModelMCA<T> m) {
            copyCommonAttributes(m);

            m.breasts.visible = breasts.visible;
            m.breasts.copyTransform(breasts);
        }
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
}
