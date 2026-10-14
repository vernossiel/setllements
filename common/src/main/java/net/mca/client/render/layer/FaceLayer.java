package net.mca.client.render.layer;

import net.mca.MCA;
import net.mca.client.model.CommonVillagerModel;
import net.mca.entity.ai.Genetics;
import net.mca.entity.ai.Traits;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;

public class FaceLayer<T extends LivingEntity, M extends BipedEntityModel<T>> extends VillagerLayer<T, M> {
    private static final int FACE_COUNT = 22;

    private final String variant;

    public FaceLayer(FeatureRendererContext<T, M> renderer, M model, String variant) {
        super(renderer, model);
        this.variant = variant;
    }

    @Override
    public void render(MatrixStack transform, VertexConsumerProvider provider, int light, T villager, float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        model.setVisible(false);
        model.head.visible = true;

        super.render(transform, provider, light, villager, limbAngle, limbDistance, tickDelta, animationProgress, headYaw, headPitch);
    }

    @Override
    protected boolean isTranslucent() {
        return true;
    }

    @Override
    public Identifier getSkin(T villager) {
        int index = (int) Math.min(FACE_COUNT - 1, Math.max(0, CommonVillagerModel.getVillager(villager).getGenetics().getGene(Genetics.FACE) * FACE_COUNT));
        int time = villager.age / 2 + (int) (CommonVillagerModel.getVillager(villager).getGenetics().getGene(Genetics.HEMOGLOBIN) * 65536);
        boolean blink = time % 50 == 1 || time % 57 == 1 || villager.isSleeping() || villager.isDead();
        boolean hasHeterochromia = variant.equals("normal") && CommonVillagerModel.getVillager(villager).getTraits().hasTrait(Traits.HETEROCHROMIA);
        String gender = CommonVillagerModel.getVillager(villager).getGenetics().getGender().getDataName();
        String blinkTexture = blink ? "_blink" : (hasHeterochromia ? "_hetero" : "");

        return cached("skins/face/" + variant + "/" + gender + "/" + index + blinkTexture + ".png", MCA::locate);
    }
}
