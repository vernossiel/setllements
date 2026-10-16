package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.EntityPose;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The body at work and at rest: crouching to sow or reap or shear, bent over a grave, a child squatting in a hiding
 * place; a jump for joy. A crouch lasts a moment and he straightens up again by himself.
 */
public final class Pose {
    private static final Map<UUID, Long> CROUCHED = new HashMap<>();

    private Pose() {
    }

    /** Down on his haunches for a moment ({@code ticks}). */
    public static void crouch(VillagerEntityMCA v, int ticks) {
        if (v.isSleeping() || v.hasVehicle()) return;
        v.setPose(EntityPose.CROUCHING);
        CROUCHED.put(v.getUuid(), v.getWorld().getTime() + ticks);
    }

    /** A jump, for joy or in play. */
    public static void jump(VillagerEntityMCA v) {
        if (v.isOnGround() && !v.isSleeping()) v.getJumpControl().setActive();
    }

    /** Once a second: up again when the moment is over. */
    public static void tick(VillagerEntityMCA v) {
        Long until = CROUCHED.get(v.getUuid());
        if (until == null) return;
        if (v.getWorld().getTime() >= until || v.getNavigation().isFollowingPath()) {
            CROUCHED.remove(v.getUuid());
            if (v.getPose() == EntityPose.CROUCHING) v.setPose(EntityPose.STANDING);
        } else if (v.getPose() != EntityPose.CROUCHING) {
            v.setPose(EntityPose.CROUCHING);
        }
    }
}
