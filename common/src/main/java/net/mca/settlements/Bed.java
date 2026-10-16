package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BedBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bedtime. At night a villager goes to his own bed in his own house and lies down in it, rather than leaving it to the
 * vanilla routine (which loses its way, or finds the bed taken, and leaves him out in the dark all night). One whose
 * bed cannot be reached spends the night in the town hall.
 */
public final class Bed {
    private static final Map<UUID, Long> SINCE = new HashMap<>();
    private static final Map<UUID, Long> NO_WAY = new HashMap<>(); // villager -> the night his bed could not be reached

    private Bed() {
    }

    /** Where the homeless spend the night: the town hall, or before there is one, by the fire at the heart of the village. */
    private static BlockPos refuge(net.mca.server.world.data.Village village, ServerWorld world) {
        if (village == null) return null;
        if (village.hallPos != null) return village.hallPos;
        BlockPos c = new BlockPos(village.getCenter());
        return new BlockPos(c.getX(), world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ());
    }

    /** Nobody is watching him: no player close by, and none who has him in sight. */
    public static boolean unseen(VillagerEntityMCA v, ServerWorld world) {
        for (net.minecraft.server.network.ServerPlayerEntity p : world.getPlayers()) {
            if (p.isSpectator()) continue;
            double d = p.squaredDistanceTo(v);
            if (d < 16 * 16 || d < 96 * 96 && p.canSee(v)) return false;
        }
        return true;
    }

    /** Off-screen, he gets to safety the long way round: in his bed if it is free, else the town hall. True if moved. */
    public static boolean shelter(VillagerEntityMCA v, net.mca.server.world.data.Village village, ServerWorld world) {
        GlobalPos home = v.getResidency().getHome().orElse(null);
        BlockPos bed = home != null && home.getDimension() == world.getRegistryKey() && world.getBlockState(home.getPos()).getBlock() instanceof BedBlock ? home.getPos() : null;
        v.getNavigation().stop();
        if (v.hasVehicle()) v.stopRiding();
        if (bed != null && world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(bed), o -> o != v && o.isSleeping()).isEmpty()) {
            v.requestTeleport(bed.getX() + 0.5, bed.getY() + 0.6, bed.getZ() + 0.5);
            long t = world.getTimeOfDay() % 24000L;
            if (t >= 12800 || t < 500) v.sleep(bed);
            return true;
        }
        BlockPos hall = refuge(village, world);
        if (hall == null) return false;
        v.requestTeleport(hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5);
        return true;
    }

    /** Once a second at bedtime; true while he is on his way to bed or in it. */
    public static boolean tick(VillagerEntityMCA v, net.mca.server.world.data.Village village, ServerWorld world) {
        if (v.isSleeping()) {
            SINCE.remove(v.getUuid());
            return true;
        }
        if (v.hasVehicle()) v.stopRiding();
        GlobalPos home = v.getResidency().getHome().orElse(null);
        BlockPos bed = home != null && home.getDimension() == world.getRegistryKey() && world.getBlockState(home.getPos()).getBlock() instanceof BedBlock ? home.getPos() : null;
        long now = world.getTime();
        long since = SINCE.computeIfAbsent(v.getUuid(), k -> now);
        Doing.set(v, "settlements.doing.bed");
        // a bed there is no way to (a shut-in house, a door that is not there): after a few tries, the hall tonight
        if (bed != null && village != null && !Housing.allowed(village, bed)) bed = null; // a bed off in the valley is no bed tonight
        long night = world.getTimeOfDay() / 24000L;
        if (bed != null && NO_WAY.getOrDefault(v.getUuid(), -1L) == night) bed = null;
        if (bed != null && v.squaredDistanceTo(Vec3d.ofCenter(bed)) > 2.2 * 2.2 && now - since >= 20 * 8) {
            var p = v.getNavigation().getCurrentPath();
            if (p == null || !p.reachesTarget() && p.getTarget().getSquaredDistance(bed) > 4) {
                NO_WAY.put(v.getUuid(), night);
                bed = null;
            }
        }
        // a minute and still not home - fallen down a slope, lost his way in the woods, no path to be found - and nobody
        // about to see: he gets home all the same, the long way round, as a man does in the dark
        if (now - since == 20 * 30 || now - since == 20 * 120) { // for the record (and the tests): who cannot get to bed, and why
            var path = v.getNavigation().getCurrentPath();
            net.mca.MCA.LOGGER.info("[Settlements] {} not in bed after {}s at {} (bed {}, path {}, seen {})", v.getName().getString(), (now - since) / 20,
                    v.getBlockPos().toShortString(), bed == null ? "none" : bed.toShortString(),
                    path == null ? "none" : (path.reachesTarget() ? "reaches " : "ends short at ") + path.getTarget().toShortString(), !unseen(v, world));
        }
        if (now - since > 20 * 30 && unseen(v, world) && shelter(v, village, world)) {
            SINCE.remove(v.getUuid());
            return true;
        }
        if (bed != null && now - since < 20 * 90) {
            if (v.squaredDistanceTo(Vec3d.ofCenter(bed)) > 2.2 * 2.2) {
                Move.to(v, bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, 0.55);
                return true;
            }
            if (Home.evening(v, village, world, bed)) return true; // first he sees to his house: his savings, something new for it
            if (world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(bed), o -> o != v && o.isSleeping()).isEmpty()) {
                v.sleep(bed);
                SINCE.remove(v.getUuid());
                return true;
            }
        }
        // no bed to be had or reached: the night under the town hall's roof
        BlockPos hall = refuge(village, world);
        if (hall == null) return false;
        if (NO_HALL.getOrDefault(v.getUuid(), -1L) == night) hall = ROOF.getOrDefault(v.getUuid(), hall); // no way up to the hall: the roof he found
        if (v.squaredDistanceTo(Vec3d.ofCenter(hall)) > 3 * 3) {
            Move.to(v, hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5, 0.55);
            var p = v.getNavigation().getCurrentPath();
            if (now - since >= 20 * 16 && NO_HALL.getOrDefault(v.getUuid(), -1L) != night && (p == null || !p.reachesTarget())) {
                BlockPos roof = roof(v, world); // the hall cannot be reached either: under the nearest roof there is
                if (roof != null) {
                    NO_HALL.put(v.getUuid(), night);
                    ROOF.put(v.getUuid(), roof);
                }
            }
        } else v.getNavigation().stop();
        return true;
    }

    private static final Map<UUID, Long> NO_HALL = new HashMap<>();
    private static final Map<UUID, BlockPos> ROOF = new HashMap<>();

    /** The nearest spot under a roof he can stand in, within sixteen blocks. */
    private static BlockPos roof(VillagerEntityMCA v, ServerWorld world) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.iterateOutwards(v.getBlockPos(), 16, 4, 16)) {
            if (world.isSkyVisible(q) || !Move.passable(world, q) || !Move.passable(world, q.up()) || !world.getBlockState(q.down()).isSolidBlock(world, q.down())) continue;
            double d = q.getSquaredDistance(v.getBlockPos());
            if (d < bd) {
                bd = d;
                best = q.toImmutable();
                if (d < 9) break;
            }
        }
        return best;
    }
}
