package net.mca.mixin.client;

import net.mca.settlements.Seat;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Settlements: whoever is locked in the stocks - a player or a villager - is drawn kneeling, bent forward at the
 * waist, neck and both wrists through the board in front of him (head up, arms straight out), instead of sitting.
 */
@Mixin(BipedEntityModel.class)
abstract class MixinBipedEntityModelStocks<T extends LivingEntity> {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart hat;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void settlements$stocks(T entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch, CallbackInfo ci) {
        if (!Seat.inStocks(entity)) {
            // the model is shared by everyone drawn with it, and the game itself never moves the head back from the
            // board: after the stocks the head (his, and the next one's) stayed thrust forward
            head.pivotZ = 0;
            hat.pivotZ = 0;
            return;
        }
        // bent over: the trunk tipped forward from the hips
        body.pitch = 0.75f;
        body.yaw = 0;
        body.pivotY = 0;
        body.pivotZ = 0;
        // the neck through the board: the head well forward and down, the face lifted a little to look about
        head.pivotY = 3.5f;
        head.pivotZ = -5.5f;
        head.pitch = -0.35f;
        head.yaw = Math.max(-0.35f, Math.min(0.35f, head.yaw)); // the board allows only a little turning
        head.roll = 0;
        // both wrists through it either side of the head: arms straight out in front, a little inwards
        float sway = (float) Math.sin(animationProgress * 0.08f) * 0.03f;
        rightArm.pivotY = 4.5f;
        rightArm.pivotZ = -3.0f;
        rightArm.pitch = -1.25f + sway;
        rightArm.yaw = -0.25f;
        rightArm.roll = 0;
        leftArm.pivotY = 4.5f;
        leftArm.pivotZ = -3.0f;
        leftArm.pitch = -1.25f - sway;
        leftArm.yaw = 0.25f;
        leftArm.roll = 0;
        // on his knees: the thighs down and back, under him
        rightLeg.pivotY = 11.0f;
        rightLeg.pivotZ = 4.0f;
        rightLeg.pitch = 1.2f;
        rightLeg.yaw = 0.05f;
        rightLeg.roll = 0;
        leftLeg.pivotY = 11.0f;
        leftLeg.pivotZ = 4.0f;
        leftLeg.pitch = 1.2f;
        leftLeg.yaw = -0.05f;
        leftLeg.roll = 0;
        hat.copyTransform(head);
    }
}
