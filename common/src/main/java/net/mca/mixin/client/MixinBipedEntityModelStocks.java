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
 * Settlements: whoever is locked in the stocks - a player or a villager - is drawn standing bent forward at the waist,
 * neck and both wrists through the board in front of him (head up, arms straight out), instead of sitting.
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
        // standing on the platform, bent at the waist the way the game's own crouch bends a man (that pose looks
        // right; a kneel cannot, the legs have no knees), neck and both wrists through the board before him
        body.pitch = 0.5f;
        body.yaw = 0;
        body.roll = 0;
        body.pivotY = 3.2f;
        body.pivotZ = 0;
        // the head held in the board: forward with the shoulders, the face up to look about, a little turning allowed
        head.pivotY = 4.2f;
        head.pivotZ = -1.5f;
        head.pitch = Math.max(-0.7f, Math.min(0.1f, head.pitch - 0.2f));
        head.yaw = Math.max(-0.35f, Math.min(0.35f, head.yaw));
        head.roll = 0;
        // the arms straight out before him into the board, either side of the head, hanging there
        float sway = (float) Math.sin(animationProgress * 0.08f) * 0.03f;
        rightArm.pivotY = 5.2f;
        rightArm.pivotZ = -1.0f;
        rightArm.pitch = -1.45f + sway;
        rightArm.yaw = -0.3f;
        rightArm.roll = 0;
        leftArm.pivotY = 5.2f;
        leftArm.pivotZ = -1.0f;
        leftArm.pitch = -1.45f - sway;
        leftArm.yaw = 0.3f;
        leftArm.roll = 0;
        // on his feet, the legs straight under the hips
        rightLeg.pivotY = 12.2f;
        rightLeg.pivotZ = 4.0f;
        rightLeg.pitch = 0;
        rightLeg.yaw = 0;
        rightLeg.roll = 0;
        leftLeg.pivotY = 12.2f;
        leftLeg.pivotZ = 4.0f;
        leftLeg.pitch = 0;
        leftLeg.yaw = 0;
        leftLeg.roll = 0;
        hat.copyTransform(head);
    }
}
