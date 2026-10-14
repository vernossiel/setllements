package net.mca.mixin;

import net.mca.advancement.criterion.CriterionMCA;
import net.mca.item.ItemsMCA;
import net.mca.util.WorldUtils;
import net.minecraft.entity.*;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.GoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GoatEntity.class)
public abstract class MixinGoatEntity extends AnimalEntity {
    protected MixinGoatEntity(EntityType<? extends AnimalEntity> entityType, World world) {
        super(entityType, world);
    }

    @Inject(method = "getMilkingSound()Lnet/minecraft/sound/SoundEvent;", at = @At("HEAD"))
    protected void getMilkingSound(CallbackInfoReturnable<SoundEvent> cir) {
        if (!this.getWorld().isClient && this.getWorld().isRaining()) {
            long time = this.getWorld().getTimeOfDay() % 24000;
            BlockPos pos = getBlockPos();
            if (time > 16000 && time < 20000 && this.getWorld().getBiome(pos).value().isCold(pos) && SpawnHelper.canSpawn(SpawnRestriction.Location.ON_GROUND, getWorld(), pos, EntityType.WITHER_SKELETON)) {
                WitherSkeletonEntity ancientCultist = EntityType.WITHER_SKELETON.create(getWorld());
                if (ancientCultist != null) {
                    //place the ancient boi
                    ancientCultist.setPosition(pos.getX(), pos.getY(), pos.getZ());
                    WorldUtils.spawnEntity(getWorld(), ancientCultist, SpawnReason.EVENT);

                    //drip
                    ancientCultist.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                    ancientCultist.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
                    ancientCultist.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.GOLDEN_LEGGINGS));
                    ancientCultist.equipStack(EquipmentSlot.FEET, new ItemStack(Items.GOLDEN_BOOTS));
                    ancientCultist.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
                    ancientCultist.equipStack(EquipmentSlot.OFFHAND, new ItemStack(ItemsMCA.BOOK_CULT_ANCIENT.get()));
                    ancientCultist.setEquipmentDropChance(EquipmentSlot.OFFHAND, 1.0f);

                    ancientCultist.setCustomName(Text.translatable("entity.mca.ancient_cultist"));

                    //advancement
                    ((ServerWorld)this.getWorld()).getPlayers().stream().filter(p -> p.distanceTo(this) < 30).forEach(p -> {
                        CriterionMCA.GENERIC_EVENT_CRITERION.trigger(p, "ancient_cultists");
                    });

                    //remove the goat
                    kill();

                    //extra spiciness
                    getWorld().setLightningTicksLeft(10);
                    LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(getWorld());
                    if (bolt != null) {
                        bolt.setCosmetic(true);
                        bolt.updatePosition(pos.getX() + 0.5F, pos.getY(), pos.getZ() + 0.5F);
                        getWorld().spawnEntity(bolt);
                    }
                }
            }
        }
    }
}
