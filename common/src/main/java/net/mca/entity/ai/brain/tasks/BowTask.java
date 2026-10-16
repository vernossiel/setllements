package net.mca.entity.ai.brain.tasks;

import com.google.common.collect.ImmutableMap;
import net.minecraft.entity.CrossbowUser;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.LookTargetUtil;
import net.minecraft.entity.ai.brain.task.MultiTickTask;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;

public class BowTask<E extends MobEntity & CrossbowUser> extends MultiTickTask<E> {
    private int lastShot;
    private final int fireInterval;
    private final int squaredRange;

    public BowTask(int fireInterval, int range) {
        super(ImmutableMap.of(MemoryModuleType.LOOK_TARGET, MemoryModuleState.REGISTERED,
                MemoryModuleType.ATTACK_TARGET, MemoryModuleState.VALUE_PRESENT));
        this.fireInterval = fireInterval;
        this.squaredRange = range * range;
    }

    @Override
    protected boolean shouldRun(ServerWorld serverWorld, E entity) {
        LivingEntity livingEntity = getAttackTarget(entity);
        return livingEntity != null && entity.isHolding(Items.BOW)
                && LookTargetUtil.isVisibleInMemory(entity, livingEntity)
                && LookTargetUtil.isTargetWithinAttackRange(entity, livingEntity, 0);
    }

    @Override
    protected void keepRunning(ServerWorld world, E entity, long time) {
        super.keepRunning(world, entity, time);

        LivingEntity target = getAttackTarget(entity);
        double d = entity.squaredDistanceTo(target);

        //keep distance
        float backward = 0.0f;
        if (d > this.squaredRange * 1.25F) {
            backward = 0.5f;
        } else if (d < this.squaredRange * 0.75F) {
            backward = -0.5f;
        }

        //strafe
        float strafe = (float)(Math.cos(time / 20.0f) * 0.5);
        entity.getMoveControl().strafeTo(backward, strafe);
        entity.lookAtEntity(target, 30.0F, 30.0F);

        entity.getLookControl().lookAt(target, 30.0F, 30.0F);

        // draw, aim, loose: like a skeleton, and only with the target in front of him and in sight
        net.minecraft.util.Hand hand = net.minecraft.entity.projectile.ProjectileUtil.getHandPossiblyHolding(entity, Items.BOW);
        boolean sees = entity.getVisibilityCache().canSee(target);
        if (!entity.isUsingItem()) {
            if (sees && entity.age - lastShot > fireInterval) entity.setCurrentHand(hand);
        } else if (!sees) {
            entity.clearActiveItem(); // lost sight of him: the arrow stays on the string
        } else if (entity.getItemUseTime() >= 20 && facing(entity, target)) {
            entity.clearActiveItem();
            entity.attack(target, net.minecraft.item.BowItem.getPullProgress(20));
            lastShot = entity.age;
        }
    }

    /** Turned towards the target, near enough. */
    private static boolean facing(LivingEntity e, LivingEntity target) {
        double dx = target.getX() - e.getX(), dz = target.getZ() - e.getZ();
        float want = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        return Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(want - e.getHeadYaw())) < 20
                && Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(want - e.getBodyYaw())) < 45;
    }

    @Override
    protected boolean shouldKeepRunning(ServerWorld world, E entity, long time) {
        return shouldRun(world, entity);
    }

    @Override
    protected void run(ServerWorld world, E entity, long time) {
        entity.setAttacking(true);
    }

    private static LivingEntity getAttackTarget(LivingEntity entity) {
        return entity.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
    }

    @Override
    protected void finishRunning(ServerWorld world, E entity, long time) {
        super.finishRunning(world, entity, time);
        entity.setAttacking(false);
        if (entity.isUsingItem()) entity.clearActiveItem();
    }
}
