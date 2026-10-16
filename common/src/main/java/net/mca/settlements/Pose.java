package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.EntityPose;

/**
 * The body at work and at rest: bent down to sow or reap or shear, bent over a grave, a child squatting in a hiding
 * place; a jump for joy. Only the model bends (see Anim): the villager's own pose (and with it his size) stays as it is,
 * so that he never sinks into the ground.
 */
public final class Pose {
    private Pose() {
    }

    /** Down for a moment ({@code ticks}), unless his hands are already busy with something else. */
    public static void crouch(VillagerEntityMCA v, int ticks) {
        if (v.isSleeping() || v.hasVehicle()) return;
        if (v.getSettlementsAction() == 0 || v.getSettlementsAction() == Anim.Act.BEND.ordinal()) Anim.set(v, Anim.Act.BEND, ticks);
    }

    /** A jump, for joy or in play. */
    public static void jump(VillagerEntityMCA v) {
        if (v.isOnGround() && !v.isSleeping()) v.getJumpControl().setActive();
    }

    /** Once a second: one left crouching by an older version stands up. */
    public static void tick(VillagerEntityMCA v) {
        if (v.getPose() == EntityPose.CROUCHING) v.setPose(EntityPose.STANDING);
    }
}
