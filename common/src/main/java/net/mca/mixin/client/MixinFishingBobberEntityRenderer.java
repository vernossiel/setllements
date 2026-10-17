package net.mca.mixin.client;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Settlements: a villager's float drawn as a player's is, the line running up to the rod in his hand. */
@Mixin(FishingBobberEntityRenderer.class)
public abstract class MixinFishingBobberEntityRenderer {
    @Shadow @Final private static RenderLayer LAYER;

    @Shadow
    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Matrix3f normalMatrix, int light, float x, int y, int u, int v) {
    }

    @Shadow
    private static void renderFishingLine(float x, float y, float z, VertexConsumer buffer, MatrixStack.Entry matrices, float segmentStart, float segmentEnd) {
    }

    @Shadow
    private static float percentage(int value, int max) {
        return 0;
    }

    @Inject(method = "render(Lnet/minecraft/entity/projectile/FishingBobberEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), cancellable = true)
    private void settlements$villagersFloat(FishingBobberEntity bobber, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light, CallbackInfo ci) {
        if (!(bobber.getOwner() instanceof VillagerEntityMCA fisher)) return;
        ci.cancel();
        matrices.push();
        matrices.push();
        matrices.scale(0.5f, 0.5f, 0.5f);
        matrices.multiply(MinecraftClient.getInstance().getEntityRenderDispatcher().getRotation());
        MatrixStack.Entry entry = matrices.peek();
        VertexConsumer vc = vcp.getBuffer(LAYER);
        vertex(vc, entry.getPositionMatrix(), entry.getNormalMatrix(), light, 0.0f, 0, 0, 1);
        vertex(vc, entry.getPositionMatrix(), entry.getNormalMatrix(), light, 1.0f, 0, 1, 1);
        vertex(vc, entry.getPositionMatrix(), entry.getNormalMatrix(), light, 1.0f, 1, 1, 0);
        vertex(vc, entry.getPositionMatrix(), entry.getNormalMatrix(), light, 0.0f, 1, 0, 0);
        matrices.pop();
        // the rod's tip, in his right hand, held out before him
        float body = MathHelper.lerp(tickDelta, fisher.prevBodyYaw, fisher.bodyYaw) * ((float) Math.PI / 180);
        double sin = MathHelper.sin(body), cos = MathHelper.cos(body), side = 0.35;
        double hx = MathHelper.lerp(tickDelta, fisher.prevX, fisher.getX()) - cos * side - sin * 0.8;
        double hy = fisher.prevY + fisher.getStandingEyeHeight() + (fisher.getY() - fisher.prevY) * tickDelta - 0.45;
        double hz = MathHelper.lerp(tickDelta, fisher.prevZ, fisher.getZ()) - sin * side + cos * 0.8;
        double bx = MathHelper.lerp(tickDelta, bobber.prevX, bobber.getX());
        double by = MathHelper.lerp(tickDelta, bobber.prevY, bobber.getY()) + 0.25;
        double bz = MathHelper.lerp(tickDelta, bobber.prevZ, bobber.getZ());
        float dx = (float) (hx - bx), dy = (float) (hy - by), dz = (float) (hz - bz);
        VertexConsumer line = vcp.getBuffer(RenderLayer.getLineStrip());
        MatrixStack.Entry e2 = matrices.peek();
        for (int i = 0; i <= 16; i++) renderFishingLine(dx, dy, dz, line, e2, percentage(i, 16), percentage(i + 1, 16));
        matrices.pop();
    }
}
