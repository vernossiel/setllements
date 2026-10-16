package net.mca.client.render;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.mca.Config;
import net.mca.client.gui.VillagerEditorScreen;
import net.mca.client.model.VillagerEntityBaseModelMCA;
import net.mca.client.model.VillagerEntityModelMCA;
import net.mca.entity.Infectable;
import net.mca.entity.VillagerLike;
import net.mca.entity.ai.relationship.AgeState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class VillagerLikeEntityMCARenderer<T extends MobEntity & VillagerLike<T>> extends BipedEntityRenderer<T, VillagerEntityModelMCA<T>> {
    public VillagerLikeEntityMCARenderer(EntityRendererFactory.Context ctx, VillagerEntityModelMCA<T> model) {
        super(ctx, model, 0.5F);
        addFeature(new ArmorFeatureRenderer<>(this, createArmorModel(0.3f), createArmorModel(0.55f), ctx.getModelManager()));
    }

    private VillagerEntityBaseModelMCA<T> createArmorModel(float modelSize) {
        return new VillagerEntityBaseModelMCA<>(
                TexturedModelData.of(
                                VillagerEntityBaseModelMCA.getModelData(new Dilation(modelSize)), 64, 32)
                        .createModel()
        );
    }

    @Override
    public void render(T villager, float yaw, float tickDelta, MatrixStack matrices, net.minecraft.client.render.VertexConsumerProvider vertices, int light) {
        // a bow being drawn is held as a player holds it
        boolean drawing = villager.isUsingItem() && villager.getActiveItem().getItem() instanceof net.minecraft.item.BowItem;
        boolean rightHanded = villager.getMainArm() == net.minecraft.util.Arm.RIGHT;
        boolean main = villager.getActiveHand() == net.minecraft.util.Hand.MAIN_HAND;
        net.minecraft.client.render.entity.model.BipedEntityModel.ArmPose bow = net.minecraft.client.render.entity.model.BipedEntityModel.ArmPose.BOW_AND_ARROW;
        net.minecraft.client.render.entity.model.BipedEntityModel.ArmPose none = net.minecraft.client.render.entity.model.BipedEntityModel.ArmPose.EMPTY;
        getModel().rightArmPose = drawing && rightHanded == main ? bow : none;
        getModel().leftArmPose = drawing && rightHanded != main ? bow : none;
        super.render(villager, yaw, tickDelta, matrices, vertices, light);
        // what he says, over his head (above the name tag)
        net.mca.client.Bubbles.render(villager, matrices, vertices, light, villager.getHeight() + (hasLabel(villager) ? 0.75f : 0.45f));
    }

    @Override
    protected void scale(T villager, MatrixStack matrices, float tickDelta) {
        float height = villager.getRawScaleFactor();
        float width = villager.getHorizontalScaleFactor();
        matrices.scale(width, height, width);
        if (villager.getAgeState() == AgeState.BABY && !villager.hasVehicle()) {
            matrices.translate(0, 0.6F, 0);
        }
    }

    @Nullable
    @Override
    protected RenderLayer getRenderLayer(T entity, boolean showBody, boolean translucent, boolean showOutlines) {
        if (entity.hasCustomSkin()) {
            //custom skin
            MinecraftClient minecraftClient = MinecraftClient.getInstance();
            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = minecraftClient.getSkinProvider().getTextures(entity.getGameProfile());
            return map.containsKey(MinecraftProfileTexture.Type.SKIN) ?
                    RenderLayer.getEntityTranslucent(
                            minecraftClient.getSkinProvider().loadSkin(
                                    map.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN
                            )) :
                    RenderLayer.getEntityCutoutNoCull(
                            DefaultSkinHelper.getTexture(
                                    Uuids.getUuidFromProfile(entity.getGameProfile())
                            )
                    );
        }

        //setting the type to null prevents it from rendering
        //we need a skin layer anyway because of the color
        return null;
    }

    @Override
    protected boolean hasLabel(T villager) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        return villager.getCustomName() != null
                && !(MinecraftClient.getInstance().currentScreen instanceof VillagerEditorScreen)
                && player != null
                && Config.getInstance().showNameTags
                && player.squaredDistanceTo(villager) < Math.pow(Config.getInstance().nameTagDistance, 2.0f)
                && !villager.isInvisibleTo(player);
    }

    private static final Identifier TEXTURE = new Identifier("textures/entity/steve.png");

    @Override
    public Identifier getTexture(T mobEntity) {
        return TEXTURE;
    }

    @Override
    protected boolean isShaking(T entity) {
        return entity.getInfectionProgress() > Infectable.FEVER_THRESHOLD;
    }
}
