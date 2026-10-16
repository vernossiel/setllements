package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Villagers take notice of what goes on about them. A player putting up something by the village draws the idle ones
 * over to look and say what they think of it; a cat or a dog about the place gets a pat from whoever has a moment.
 */
public final class Notice {
    private record Placed(BlockPos pos, long at) {
    }

    private record Watch(BlockPos at, Vec3d stand, long until, long giveUp, boolean said) {
    }

    private static final Map<UUID, Deque<Placed>> PLACED = new HashMap<>();
    private static final Map<UUID, Watch> WATCHING = new HashMap<>();
    private static final Map<UUID, Long> PETTED = new HashMap<>();
    private static final Map<UUID, Long> LOOKED = new HashMap<>(); // a villager comes to look once in ten minutes
    private static final Map<UUID, Long> DRAWN = new HashMap<>(); // a player's building draws a crowd once in three

    private Notice() {
    }

    /** A player has put a block down. */
    public static void placed(PlayerEntity player, BlockPos pos) {
        if (!(player.getWorld() instanceof ServerWorld world)) return;
        Village village = Village.findNearest(player).orElse(null);
        if (village == null || !village.isWithinBorder(player)) return;
        long now = world.getTime();
        Deque<Placed> q = PLACED.computeIfAbsent(player.getUuid(), k -> new ArrayDeque<>());
        q.addLast(new Placed(pos.toImmutable(), now));
        while (!q.isEmpty() && now - q.peekFirst().at() > 20 * 40) q.removeFirst();
        if (q.size() < 8 || now < DRAWN.getOrDefault(player.getUuid(), 0L)) return;
        // a building going up: its middle, and one or two of those with a moment to spare come to look
        int x = 0, y = 0, z = 0;
        for (Placed p : q) {
            x += p.pos().getX();
            y += p.pos().getY();
            z += p.pos().getZ();
        }
        BlockPos mid = new BlockPos(x / q.size(), y / q.size(), z / q.size());
        q.clear();
        DRAWN.put(player.getUuid(), now + 20 * 180);
        long tod = world.getTimeOfDay();
        int drawn = 0;
        for (VillagerEntityMCA v : world.getEntitiesByClass(VillagerEntityMCA.class, player.getBoundingBox().expand(24, 6, 24),
                v -> v.isAlive() && !v.isSleeping() && !v.isBaby() && !v.isGuard() && !Dig.busy(v) && !v.settlementsHauling && !WATCHING.containsKey(v.getUuid()))) {
            if (drawn >= 2 || now < LOOKED.getOrDefault(v.getUuid(), 0L)) continue;
            if (Mind.onDuty(v, village, tod) && v.settlementsIdleWork < 30) continue; // at work: he looks up, no more
            if (world.random.nextInt(2) == 0) continue;
            // a place to stand on his side of it, a few steps off, on open ground
            Vec3d c = Vec3d.ofCenter(mid), dir = v.getPos().subtract(c).multiply(1, 0, 1);
            if (dir.lengthSquared() < 0.01) dir = new Vec3d(1, 0, 0);
            Vec3d at = c.add(dir.normalize().multiply(7));
            int gy = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(at.x), (int) Math.floor(at.z));
            LOOKED.put(v.getUuid(), now + 20 * 600);
            WATCHING.put(v.getUuid(), new Watch(mid, new Vec3d(at.x, gy, at.z), now + 20 * (24 + world.random.nextInt(10)), now + 20 * 15, false)); // some fifteen seconds to get there, ten to look
            drawn++;
        }
    }

    /** Once a second; true while he stands looking at what the player is building. */
    public static boolean tick(VillagerEntityMCA v, ServerWorld world) {
        Watch w = WATCHING.get(v.getUuid());
        if (w == null) return false;
        long now = world.getTime();
        Vec3d c = Vec3d.ofCenter(w.at());
        boolean there = v.squaredDistanceTo(w.stand()) < 2.5 * 2.5 || v.squaredDistanceTo(c) < 9 * 9;
        if (!there && now >= w.giveUp()) { // could not get near: let it be
            WATCHING.remove(v.getUuid());
            return false;
        }
        if (there && now >= w.until()) {
            WATCHING.remove(v.getUuid());
            return false;
        }
        Doing.set(v, "settlements.doing.notice.build");
        if (!there) {
            Move.to(v, w.stand().x, w.stand().y, w.stand().z, 0.5);
            return true;
        }
        v.getNavigation().stop();
        v.getLookControl().lookAt(c.x, c.y + 1, c.z);
        if (!w.said() && world.random.nextInt(4) == 0) {
            Speech.sayAny(v, "settlements.say.notice.build", 5);
            WATCHING.put(v.getUuid(), new Watch(w.at(), w.stand(), w.until(), w.giveUp(), true));
        }
        return true;
    }

    /** In his own time: a pat for a cat or a dog about the place. True while he is at it. */
    public static boolean pet(VillagerEntityMCA v, ServerWorld world) {
        long now = world.getTime();
        if (now < PETTED.getOrDefault(v.getUuid(), 0L)) return false;
        TameableEntity pet = world.getEntitiesByClass(TameableEntity.class, v.getBoundingBox().expand(10, 3, 10),
                p -> p.isAlive() && (p instanceof CatEntity || p instanceof WolfEntity) && !p.isInSittingPose())
                .stream().min(java.util.Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
        if (pet == null) {
            PETTED.put(v.getUuid(), now + 20 * 30);
            return false;
        }
        Doing.set(v, "settlements.doing.notice.pet");
        if (v.squaredDistanceTo(pet) > 3) {
            Move.to(v, pet, 0.45);
            pet.getNavigation().stop();
            return true;
        }
        v.getNavigation().stop();
        v.getLookControl().lookAt(pet, 30f, 60f);
        pet.getLookControl().lookAt(v, 30f, 30f);
        v.swingHand(v.getDominantHand());
        world.spawnParticles(net.minecraft.particle.ParticleTypes.HEART, pet.getX(), pet.getY() + 0.8, pet.getZ(), 1, 0.2, 0.2, 0.2, 0);
        Speech.sayAny(v, pet instanceof CatEntity ? "settlements.say.notice.cat" : "settlements.say.notice.dog", 3);
        PETTED.put(v.getUuid(), now + 20 * (240 + world.random.nextInt(300)));
        return true;
    }
}
