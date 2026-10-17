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
    private static final Map<Integer, BlockPos> SHUT_IN = new HashMap<>(); // village -> the most enclosed spot inside its hall
    private static final Map<Integer, Long> SHUT_IN_AT = new HashMap<>();

    /**
     * The most shut-in place inside the town hall to spend the night: under its roof, as little open sky about as can
     * be found (a template's lectern may stand in an open gallery the zombies walk into). Looked for once a day.
     */
    private static BlockPos inside(net.mca.server.world.data.Village village, ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        if (SHUT_IN_AT.getOrDefault(village.getId(), -1L) == day) {
            BlockPos at = SHUT_IN.get(village.getId());
            // still free to stand on (a bed put down in the hall since took the very spot, and the homeless stood all
            // night just outside, two steps from a place that could no longer be had)
            if (at == null || Move.passable(world, at) && Move.passable(world, at.up())) return at;
        }
        SHUT_IN_AT.put(village.getId(), day);
        Built hall = null;
        for (Built b : village.built) if (Buildings.base(b.type()).equals("townhall")) hall = b;
        BlockPos best = null;
        int bestSky = 16;
        double bestD = Double.MAX_VALUE;
        if (hall != null) {
            int[] fp = hall.footprint();
            for (int dx = 0; dx < fp[0]; dx++) {
                for (int dz = 0; dz < fp[1]; dz++) {
                    for (int dy = 0; dy < 12; dy++) {
                        BlockPos q = hall.point(dx, dy, dz);
                        if (!Move.passable(world, q) || !Move.passable(world, q.up()) || !world.getBlockState(q.down()).isSolidBlock(world, q.down())) continue;
                        if (world.isSkyVisible(q)) continue;
                        int sky = world.getLightLevel(net.minecraft.world.LightType.SKY, q) + (nearDoor(world, q) ? 8 : 0); // (by the door counts as open)
                        double d = village.hallPos != null ? q.getSquaredDistance(village.hallPos) : 0;
                        if (sky < bestSky || sky == bestSky && d < bestD) {
                            bestSky = sky;
                            bestD = d;
                            best = q.toImmutable();
                        }
                    }
                }
            }
        }
        if (bestSky > 7) best = null; // open to the night all the same (a hall still going up, its roof not on yet): no shelter there
        if (best != null && !Housing.safe(world, best)) best = null; // a gap in its walls the zombies walk through
        if (best == null) SHUT_IN.remove(village.getId());
        else SHUT_IN.put(village.getId(), best);
        return best;
    }

    private static BlockPos refuge(VillagerEntityMCA v, net.mca.server.world.data.Village village, ServerWorld world) {
        if (village == null) return null;
        // the hall once it stands finished (a hall going up is a frame open to the sky: a woman sat the night out in
        // one and the zombies had her); till then the nearest house with a door, then the old meeting place
        BlockPos in = village.hall != null ? inside(village, world) : null;
        if (in != null) return in;
        BlockPos h = house(v, village, world);
        if (h != null) return h;
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
        if (bed != null && !Housing.safe(world, bed)) bed = null; // not set down in a room the night walks into
        v.getNavigation().stop();
        if (v.hasVehicle()) v.stopRiding();
        if (bed != null && world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(bed), o -> o != v && o.isSleeping()).isEmpty()) {
            v.requestTeleport(bed.getX() + 0.5, bed.getY() + 0.6, bed.getZ() + 0.5);
            long t = world.getTimeOfDay() % 24000L;
            if (t >= 12800 || t < 500) v.sleep(bed);
            return true;
        }
        BlockPos hall = refuge(v, village, world);
        if (hall == null) return false;
        v.requestTeleport(hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5);
        return true;
    }

    /** Once a second at bedtime; true while he is on his way to bed or in it. */
    public static boolean tick(VillagerEntityMCA v, net.mca.server.world.data.Village village, ServerWorld world) {
        if (v.isSleeping()) {
            // asleep in a bed he took for himself off in the valley: up, and to a proper place for the night
            BlockPos in = v.getSleepingPosition().orElse(null);
            if (in != null && village != null && (!Housing.allowed(village, in) || !Housing.safe(world, in))) { // (or one in a room the night walks into)
                v.wakeUp();
                v.getBrain().forget(net.minecraft.entity.ai.brain.MemoryModuleType.HOME);
                return true;
            }
            SINCE.remove(v.getUuid());
            // the house shut for the night: a door somebody left open by his bed is shut (a zombie walked in through
            // one onto a sleeper two blocks from it)
            if (world.getTime() / 20 % 5 == 0) Move.shutDoorsAbout(v, 4);
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
        if (bed != null && !Housing.safe(world, bed)) bed = null; // nor one in a room with a wall gone: the hall tonight
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
        BlockPos hall = refuge(v, village, world);
        if (hall == null) return false;
        if (NO_HALL.getOrDefault(v.getUuid(), -1L) == night) hall = ROOF.getOrDefault(v.getUuid(), hall); // no way up to the hall: the roof he found
        if (v.squaredDistanceTo(Vec3d.ofCenter(hall)) > 3 * 3) {
            Move.to(v, hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5, 0.55);
            var p = v.getNavigation().getCurrentPath();
            if (now - since >= 20 * 16 && NO_HALL.getOrDefault(v.getUuid(), -1L) != night && (p == null || !p.reachesTarget())) {
                BlockPos roof = house(v, village, world); // the hall cannot be reached either: the nearest house of the village with a door
                if (roof == null) roof = roof(v, world); // or at least the nearest roof
                if (roof != null) {
                    NO_HALL.put(v.getUuid(), night);
                    ROOF.put(v.getUuid(), roof);
                }
            }
        } else {
            v.getNavigation().stop();
            // in under the roof for the night with no bed of his own: down on the floor by the wall, dozing sitting up
            long t = world.getTimeOfDay() % 24000L;
            if ((t >= 12800 || t < 500) && !Seat.seated(v) && !world.isSkyVisible(v.getBlockPos().up())) Seat.sitHere(v, v.getBodyYaw());
            if (world.getTime() / 20 % 5 == 0) Move.shutDoorsAbout(v, 5); // the door of the place he shelters in, shut for the night
            Doing.set(v, "settlements.doing.bed.floor");
        }
        return true;
    }

    private static final Map<UUID, Long> NO_HALL = new HashMap<>();
    private static final Map<UUID, BlockPos> ROOF = new HashMap<>();

    /** The nearest house of the village (on its level, near the hall) that he can walk into: the inside of it. */
    private static BlockPos house(VillagerEntityMCA v, net.mca.server.world.data.Village village, ServerWorld world) {
        if (village == null) return null;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (net.mca.server.world.data.Building b : village.getBuildings().values()) {
            if (b.getBuildingType().grouped()) continue;
            BlockPos c = b.getCenter();
            if (!Housing.allowed(village, c)) continue;
            // a spot inside to stand on: floor under it, under the roof
            // the most sheltered: away from the door (one sat the night just inside it and was killed through it), least sky
            BlockPos in = null;
            double inScore = Double.MAX_VALUE;
            for (BlockPos q : BlockPos.iterateOutwards(c, 4, 3, 4)) {
                if (b.containsPos(q) && !world.isSkyVisible(q) && Move.passable(world, q) && Move.passable(world, q.up())
                        && world.getBlockState(q.down()).isSolidBlock(world, q.down())) {
                    double sc = world.getLightLevel(net.minecraft.world.LightType.SKY, q) * 10 + (nearDoor(world, q) ? 200 : 0) + Math.sqrt(q.getSquaredDistance(c));
                    if (sc < inScore) {
                        inScore = sc;
                        in = q.toImmutable();
                    }
                }
            }
            if (in == null || !Housing.safe(world, in)) continue; // a room the night can walk into is no shelter (one woken from an unsafe bed sat the night on the floor of the same room, and died there)
            double d = in.getSquaredDistance(v.getBlockPos());
            if (d < bd) {
                bd = d;
                best = in;
            }
        }
        return best;
    }

    /** A door or a gap to the outside within two blocks: not a place to sit out the night. */
    private static boolean nearDoor(ServerWorld world, BlockPos q) {
        for (BlockPos o : BlockPos.iterate(q.add(-2, 0, -2), q.add(2, 1, 2))) {
            if (world.getBlockState(o).getBlock() instanceof net.minecraft.block.DoorBlock) return true;
            if (Move.passable(world, o) && world.isSkyVisible(o)) return true;
        }
        return false;
    }

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
