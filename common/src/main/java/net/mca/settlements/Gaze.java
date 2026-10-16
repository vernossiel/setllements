package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where a villager looks when his work does not hold his eyes: at what is going on about him, as people do. A player
 * coming up, somebody walking past or calling out, a man at work nearby (a block cracking under a pick), a lamb, an
 * apple rolling on the ground. He holds it a moment and goes back to his own business; walking, he only glances.
 * The curious look about more, the shy look at people less and at the ground more.
 */
public final class Gaze {
    private record Look(UUID at, long until) {
    }

    private static final Map<UUID, Look> LOOKS = new HashMap<>();

    /** What his eyes are on right now: an entity, or a spot; kept for a moment and turned to smoothly every tick. */
    private record Hold(Entity at, net.minecraft.util.math.Vec3d spot, long until) {
    }

    private static final Map<UUID, Hold> HOLD = new HashMap<>();
    /** How far the head turns in a tick, in degrees: a man turns his head, he does not snap it round. */
    private static final float TURN = 9f;

    /** Eyes on {@code e} (the speeds are the old look-control ones and are not used: the head turns at its own pace). */
    public static void at(VillagerEntityMCA v, Entity e, float yawSpeed, float pitchSpeed) {
        if (e == null) return;
        HOLD.put(v.getUuid(), new Hold(e, null, v.getWorld().getTime() + 30));
    }

    /** Eyes on a spot. */
    public static void at(VillagerEntityMCA v, double x, double y, double z) {
        HOLD.put(v.getUuid(), new Hold(null, new net.minecraft.util.math.Vec3d(x, y, z), v.getWorld().getTime() + 30));
    }

    /**
     * Every tick: the head turns towards what he is looking at, a little each tick, and stays on it; the brain's own
     * idle looking about does not pull it away meanwhile. With nothing to look at, the head follows the body.
     */
    public static void head(VillagerEntityMCA v) {
        Hold h = HOLD.get(v.getUuid());
        if (h == null) {
            // the brain's own idle glances: none up into the sky (at a bat, a bird in a crown), nobody stares at the clouds
            var lt = v.getBrain().getOptionalMemory(MemoryModuleType.LOOK_TARGET).orElse(null);
            if (lt != null && lt.getPos().y - v.getEyeY() > 2.5) v.getBrain().forget(MemoryModuleType.LOOK_TARGET);
            return;
        }
        if (v.settlementsDigPos != null) return; // at the block he is breaking (see Dig)
        if (v.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent()) { // in a fight, eyes on the foe (the fight looks after that)
            HOLD.remove(v.getUuid());
            return;
        }
        if (v.getWorld().getTime() > h.until() || v.isSleeping() || h.at() != null && (!h.at().isAlive() || h.at().getWorld() != v.getWorld())) {
            HOLD.remove(v.getUuid());
            return;
        }
        v.getBrain().forget(MemoryModuleType.LOOK_TARGET);
        if (h.at() != null) v.getLookControl().lookAt(h.at(), TURN, TURN);
        else v.getLookControl().lookAt(h.spot().x, h.spot().y, h.spot().z, TURN, TURN);
    }

    private Gaze() {
    }

    /** Once a second. */
    public static void tick(VillagerEntityMCA v, ServerWorld world) {
        if (v.isSleeping() || v.isBaby() && world.random.nextBoolean() || Dig.busy(v) || Talk.talking(v)
                || v.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent() || busy(v)) return;
        long now = world.getTime();
        Look l = LOOKS.get(v.getUuid());
        if (l != null && now < l.until()) {
            Entity e = world.getEntity(l.at());
            if (e != null && e.isAlive() && e.squaredDistanceTo(v) < 16 * 16) {
                at(v, e, 20f, 20f);
                return;
            }
        }
        LOOKS.remove(v.getUuid());
        boolean walking = v.getNavigation().isFollowingPath();
        Persona.Traits tr = Persona.of(v);
        // how often something catches his eye: more standing about, less on the way somewhere
        double chance = (walking ? 0.12 : 0.35) * (0.6 + 0.6 * tr.sociability());
        var home = v.getResidency().getHomeVillage().orElse(null);
        if (home != null && Mood.afraid(home)) chance *= 1.8; // a frightened village looks about it
        if (world.random.nextDouble() > chance) return;
        Entity best = null;
        double bs = 0;
        for (Entity e : world.getOtherEntities(v, v.getBoundingBox().expand(12, 4, 12), e -> e.isAlive() && !e.isSpectator())) {
            double d = Math.sqrt(e.squaredDistanceTo(v));
            if (d < 1.2) continue;
            // only what he can see, before him: nobody looks through a wall, or twists his neck round to his back
            double off = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(
                    Math.toDegrees(Math.atan2(e.getZ() - v.getZ(), e.getX() - v.getX())) - 90 - v.getBodyYaw()));
            if (off > (walking ? 60 : 100) || e.getEyeY() - v.getEyeY() > 2.5 || !v.canSee(e)) continue;
            double s = 0;
            if (e instanceof PlayerEntity p) {
                s = 3.0 + (p.isSprinting() ? 1 : 0) + (p.getVelocity().horizontalLengthSquared() > 0.003 ? 0.5 : 0);
                if (p.getRotationVec(1f).dotProduct(v.getPos().subtract(p.getEyePos()).normalize()) > 0.95) s += 2; // he is looking at me
            } else if (e instanceof VillagerEntityMCA o) {
                s = 1.2 + (Dig.busy(o) ? 1.2 : 0) + (o.getVelocity().horizontalLengthSquared() > 0.002 ? 0.6 : 0) + Persona.affinity(v, o);
                if (o.isBaby()) s += 0.5;
            } else if (e instanceof AnimalEntity a) {
                s = a.isBaby() ? 1.6 : 0.7;
            } else if (e instanceof ItemEntity) {
                s = 0.9;
            } else if (e instanceof LivingEntity) {
                s = 0.4;
            }
            s = s * (0.6 + 0.8 * world.random.nextDouble()) / (1 + d * 0.25);
            if (e instanceof PlayerEntity || e instanceof VillagerEntityMCA) s *= 0.5 + tr.sociability();
            if (s > bs) {
                bs = s;
                best = e;
            }
        }
        if (best == null) return;
        long hold = walking ? 20 + world.random.nextInt(20) : 40 + world.random.nextInt(60);
        LOOKS.put(v.getUuid(), new Look(best.getUuid(), now + hold));
        Gaze.at(v, best, 20f, 20f);
    }

    /** At a piece of work with his hands: his eyes stay on it, nothing about him draws them away. */
    private static boolean busy(VillagerEntityMCA v) {
        Anim.Act a = Anim.of(v.getSettlementsAction());
        return a != Anim.Act.NONE && a != Anim.Act.CARRY && a != Anim.Act.WAVE && a != Anim.Act.CLAP && a != Anim.Act.BEHIND;
    }

    /** How quickly he walks: slower worn out or hungry, brisker fresh and keen. */
    public static double pace(VillagerEntityMCA v) {
        double energy = v.settlementsEnergy / (double) Workforce.MAX, hunger = v.settlementsHunger / 1000.0;
        double f = 0.82 + 0.25 * energy - 0.12 * Math.max(0, hunger - 0.5);
        f *= 0.9 + 0.2 * Math.min(1.2, Persona.of(v).diligence());
        return Math.max(0.7, Math.min(1.15, f));
    }
}
