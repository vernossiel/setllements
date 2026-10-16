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

    private record Watch(BlockPos at, long until, boolean said) {
    }

    private static final Map<UUID, Deque<Placed>> PLACED = new HashMap<>();
    private static final Map<UUID, Watch> WATCHING = new HashMap<>();
    private static final Map<UUID, Long> PETTED = new HashMap<>();

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
        if (q.size() < 8) return;
        // a building going up: its middle, and whoever is idle about comes to look
        int x = 0, y = 0, z = 0;
        for (Placed p : q) {
            x += p.pos().getX();
            y += p.pos().getY();
            z += p.pos().getZ();
        }
        BlockPos mid = new BlockPos(x / q.size(), y / q.size(), z / q.size());
        q.clear();
        for (VillagerEntityMCA v : world.getEntitiesByClass(VillagerEntityMCA.class, player.getBoundingBox().expand(24, 6, 24),
                v -> v.isAlive() && !v.isSleeping() && !v.isBaby() && !Dig.busy(v) && !v.settlementsHauling && !WATCHING.containsKey(v.getUuid()))) {
            if (world.random.nextInt(3) == 0) continue; // not everybody
            WATCHING.put(v.getUuid(), new Watch(mid, now + 20 * (20 + world.random.nextInt(20)), false));
        }
    }

    /** Once a second; true while he stands looking at what the player is building. */
    public static boolean tick(VillagerEntityMCA v, ServerWorld world) {
        Watch w = WATCHING.get(v.getUuid());
        if (w == null) return false;
        if (world.getTime() >= w.until()) {
            WATCHING.remove(v.getUuid());
            return false;
        }
        Doing.set(v, "settlements.doing.notice.build");
        Vec3d c = Vec3d.ofCenter(w.at());
        if (v.squaredDistanceTo(c) > 7 * 7) {
            Move.to(v, c.x, c.y, c.z, 0.5);
            return true;
        }
        v.getNavigation().stop();
        v.getLookControl().lookAt(c.x, c.y + 1, c.z);
        if (!w.said() && world.random.nextInt(5) == 0) {
            Speech.sayAny(v, "settlements.say.notice.build", 5);
            WATCHING.put(v.getUuid(), new Watch(w.at(), w.until(), true));
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
