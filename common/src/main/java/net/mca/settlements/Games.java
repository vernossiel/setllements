package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Children at play, together, the way children play: tag (one is "it" and chases the rest; whoever is caught is "it"
 * and the others run) and hide-and-seek (one counts with his face to a wall, the others hide where he cannot see them
 * and he goes looking). A game lasts a while and then they wander off or start another. At dusk a parent calls them
 * home and they go.
 */
public final class Games {
    enum Kind { TAG, HIDE }

    private static final class Game {
        final Kind kind;
        final Set<UUID> kids = new HashSet<>();
        UUID it; // the chaser, or the seeker
        final Map<UUID, BlockPos> hides = new HashMap<>();
        final Set<UUID> found = new HashSet<>();
        long start, counted;

        Game(Kind kind) {
            this.kind = kind;
        }
    }

    private static final Map<UUID, Game> PLAYING = new HashMap<>();
    private static final Map<UUID, Long> RESTING = new HashMap<>();
    private static final Map<UUID, Long> CALLED = new HashMap<>();

    private Games() {
    }

    /** Once a second for a child; true while the game (or going home) takes him up. */
    public static boolean tick(VillagerEntityMCA v, ServerWorld world) {
        if (v.isSleeping() || !v.isBaby()) return false;
        long t = world.getTimeOfDay() % 24000L, now = world.getTime();
        // dusk: home, called by mother or father
        if (t >= 11500 && t < 13500) {
            PLAYING.remove(v.getUuid());
            return home(v, world);
        }
        if (t < 1000 || world.isRaining()) return false;
        Game g = PLAYING.get(v.getUuid());
        if (g == null) {
            if (now < RESTING.getOrDefault(v.getUuid(), 0L) || world.random.nextInt(6) != 0) return false;
            g = start(v, world);
            if (g == null) return false;
        }
        if (now - g.start > 20 * 75 || g.kids.size() < 2) { // the game is over
            end(g, now, world);
            return false;
        }
        return g.kind == Kind.TAG ? tag(v, world, g) : hide(v, world, g);
    }

    private static Game start(VillagerEntityMCA v, ServerWorld world) {
        List<VillagerEntityMCA> near = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(16, 4, 16),
                o -> o.isAlive() && o.isBaby() && !o.isSleeping() && !PLAYING.containsKey(o.getUuid()) && world.getTime() >= RESTING.getOrDefault(o.getUuid(), 0L));
        if (near.size() < 2) return null; // himself and at least one more
        Game g = new Game(world.random.nextBoolean() ? Kind.TAG : Kind.HIDE);
        for (VillagerEntityMCA k : near) {
            if (g.kids.size() >= 5) break;
            g.kids.add(k.getUuid());
            PLAYING.put(k.getUuid(), g);
        }
        g.it = v.getUuid();
        g.start = world.getTime();
        Speech.sayNow(v, g.kind == Kind.TAG ? "settlements.kid.game.tag" : "settlements.kid.game.hide");
        return g;
    }

    private static void end(Game g, long now, ServerWorld world) {
        for (UUID k : g.kids) {
            PLAYING.remove(k);
            RESTING.put(k, now + 20 * (40 + world.random.nextInt(60)));
        }
        g.kids.clear();
    }

    private static boolean tag(VillagerEntityMCA v, ServerWorld world, Game g) {
        Doing.set(v, "settlements.doing.kid.tag");
        VillagerEntityMCA it = world.getEntity(g.it) instanceof VillagerEntityMCA i ? i : null;
        if (it == null || !it.isAlive()) {
            g.it = v.getUuid();
            return true;
        }
        if (v == it) {
            // chase the nearest of the others
            VillagerEntityMCA prey = null;
            double bd = Double.MAX_VALUE;
            for (UUID k : g.kids) {
                if (k.equals(v.getUuid()) || !(world.getEntity(k) instanceof VillagerEntityMCA o)) continue;
                double d = o.squaredDistanceTo(v);
                if (d < bd) {
                    bd = d;
                    prey = o;
                }
            }
            if (prey == null) return true;
            if (bd < 2.2) { // caught: now he is it, and the catcher runs
                g.it = prey.getUuid();
                v.swingHand(v.getDominantHand());
                Pose.jump(v);
                Speech.sayNow(v, "settlements.kid.tag." + world.random.nextInt(3));
                run(v, prey, world);
                return true;
            }
            v.getNavigation().startMovingTo(prey, 0.75);
            return true;
        }
        // run from "it" when he comes close, else hang about within reach of the game
        if (v.squaredDistanceTo(it) < 7 * 7) run(v, it, world);
        else if (v.getNavigation().isIdle() && world.random.nextInt(3) == 0) {
            Vec3d go = FuzzyTargeting.find(v, 6, 2);
            if (go != null) v.getNavigation().startMovingTo(go.x, go.y, go.z, 0.5);
        }
        return true;
    }

    private static void run(VillagerEntityMCA v, VillagerEntityMCA from, ServerWorld world) {
        Vec3d away = NoPenaltyTargeting.findFrom(v, 10, 3, from.getPos());
        if (away != null) v.getNavigation().startMovingTo(away.x, away.y, away.z, 0.75);
    }

    private static boolean hide(VillagerEntityMCA v, ServerWorld world, Game g) {
        long now = world.getTime();
        VillagerEntityMCA seeker = world.getEntity(g.it) instanceof VillagerEntityMCA s ? s : null;
        if (seeker == null) {
            end(g, now, world);
            return false;
        }
        boolean counting = now - g.start < 20 * 12;
        if (v == seeker) {
            if (counting) {
                Doing.set(v, "settlements.doing.kid.count");
                v.getNavigation().stop();
                v.getLookControl().lookAt(v.getX() + 0.0, v.getY() - 2, v.getZ() + 0.1); // eyes shut, face down
                if ((now - g.start) % 60 == 0) Speech.sayNow(v, "settlements.kid.count." + Math.min(3, (now - g.start) / 60));
                return true;
            }
            Doing.set(v, "settlements.doing.kid.seek");
            if (g.counted == 0) {
                g.counted = now;
                Speech.sayNow(v, "settlements.kid.ready");
            }
            // to the nearest one not found yet; found when he can see him close by
            VillagerEntityMCA next = null;
            double bd = Double.MAX_VALUE;
            for (UUID k : g.kids) {
                if (k.equals(v.getUuid()) || g.found.contains(k) || !(world.getEntity(k) instanceof VillagerEntityMCA o)) continue;
                double d = o.squaredDistanceTo(v);
                if (d < bd) {
                    bd = d;
                    next = o;
                }
            }
            if (next == null) { // all found: the first found counts next time
                Speech.sayNow(v, "settlements.kid.allfound");
                end(g, now, world);
                return true;
            }
            if (bd < 4 * 4 && v.canSee(next)) {
                g.found.add(next.getUuid());
                Speech.sayNow(v, "settlements.kid.found", next.getName());
                next.swingHand(next.getDominantHand());
                return true;
            }
            v.getNavigation().startMovingTo(next, 0.55);
            return true;
        }
        // a hider
        if (g.found.contains(v.getUuid())) {
            Doing.set(v, "settlements.doing.kid.found");
            if (v.squaredDistanceTo(seeker) > 16) v.getNavigation().startMovingTo(seeker, 0.5); // tags along with the seeker
            return true;
        }
        Doing.set(v, "settlements.doing.kid.hide");
        BlockPos spot = g.hides.get(v.getUuid());
        if (spot == null && counting) {
            // somewhere the seeker cannot see from where he counts
            for (int i = 0; i < 12 && spot == null; i++) {
                Vec3d p = FuzzyTargeting.find(v, 14, 3);
                if (p == null) continue;
                BlockPos b = BlockPos.ofFloored(p);
                net.minecraft.util.hit.BlockHitResult hit = world.raycast(new net.minecraft.world.RaycastContext(seeker.getEyePos(), Vec3d.ofCenter(b.up()),
                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, seeker));
                if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS && b.getSquaredDistance(seeker.getBlockPos()) > 36) spot = b;
            }
            if (spot != null) g.hides.put(v.getUuid(), spot);
        }
        if (spot != null && v.squaredDistanceTo(Vec3d.ofBottomCenter(spot)) > 2) v.getNavigation().startMovingTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.65);
        else {
            v.getNavigation().stop();
            Pose.crouch(v, 40); // squatting down out of sight
            if (seeker.squaredDistanceTo(v) < 36 && world.random.nextInt(4) == 0) v.getLookControl().lookAt(seeker, 30f, 30f); // peeking
        }
        return true;
    }

    /** Dusk: home, and a parent calls out for him once. */
    private static boolean home(VillagerEntityMCA v, ServerWorld world) {
        GlobalPos h = v.getResidency().getHome().orElse(null);
        if (h == null || h.getDimension() != world.getRegistryKey()) return false;
        BlockPos p = h.getPos();
        if (v.squaredDistanceTo(Vec3d.ofBottomCenter(p)) < 9) return false;
        if (world.getTime() - CALLED.getOrDefault(v.getUuid(), -100000L) > 20 * 600) {
            CALLED.put(v.getUuid(), world.getTime());
            net.mca.server.world.data.FamilyTreeNode me = v.getRelationships().getFamilyEntry();
            for (UUID pid : new UUID[]{me.mother(), me.father()}) {
                if (pid != null && world.getEntity(pid) instanceof VillagerEntityMCA parent && parent.isAlive() && !parent.isSleeping()) {
                    Speech.sayNow(parent, "settlements.kid.callhome", v.getName());
                    break;
                }
            }
        }
        Doing.set(v, "settlements.doing.kid.home");
        v.getNavigation().startMovingTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.55);
        return true;
    }

    static List<UUID> players() {
        return new ArrayList<>(PLAYING.keySet());
    }
}
