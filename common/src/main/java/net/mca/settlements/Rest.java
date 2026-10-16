package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * A break the villager's own way: some go to the fire, some find a bench or a step to sit on, some sit down in the
 * shade where they are, some seek company for a chat. Each keeps his habit, so breaks are not one crowd at the fire.
 */
public final class Rest {
    private Rest() {
    }

    private static int habit(VillagerEntityMCA v) {
        return Math.floorMod(v.getUuid().hashCode() >> 3, 4);
    }

    /** One second of a break. True while the villager rests in his place (sitting). */
    public static boolean tick(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        if (Seat.seated(v)) {
            if (v.age % 200 == 0) Life.restingChat(v, world);
            return true;
        }
        switch (habit(v)) {
            case 0 -> { // by the fire
                return Camp.goAndSit(v, village);
            }
            case 1 -> { // a bench or a step nearby
                if (v.settlementsRestSpot == null) v.settlementsRestSpot = Seat.findNear(v, 14);
                BlockPos s = v.settlementsRestSpot;
                if (s == null) return sitDown(v, world);
                if (v.squaredDistanceTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5) > 2.5) {
                    Move.to(v, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0.5);
                    return false;
                }
                Seat.sit(v, s.getX() + 0.5, Seat.seatHeight(world, s), s.getZ() + 0.5, v.getYaw());
                return true;
            }
            case 2 -> { // in the shade, under a tree or a roof
                if (v.settlementsRestSpot == null) {
                    for (int i = 0; i < 16 && v.settlementsRestSpot == null; i++) {
                        BlockPos q = v.getBlockPos().add(world.random.nextBetween(-10, 10), 0, world.random.nextBetween(-10, 10));
                        q = new BlockPos(q.getX(), world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, q.getX(), q.getZ()), q.getZ());
                        if (!world.isSkyVisible(q) && Move.passable(world, q) && Move.passable(world, q.up())) v.settlementsRestSpot = q;
                    }
                    if (v.settlementsRestSpot == null) v.settlementsRestSpot = v.getBlockPos();
                }
                BlockPos s = v.settlementsRestSpot;
                if (v.squaredDistanceTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5) > 2.5) {
                    Move.to(v, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0.5);
                    return false;
                }
                return sitDown(v, world);
            }
            default -> { // company: next to somebody else who rests, for a chat
                var mate = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(24),
                        o -> o != v && (o.settlementsBreak || Seat.seated(o))).stream().findFirst().orElse(null);
                if (mate == null) return Camp.goAndSit(v, village);
                if (v.squaredDistanceTo(mate) > 6) {
                    Move.to(v, mate, 0.5);
                    return false;
                }
                Gaze.at(v, mate, 30f, 30f);
                return sitDown(v, world);
            }
        }
    }

    private static boolean sitDown(VillagerEntityMCA v, ServerWorld world) {
        Seat.sitHere(v, v.getYaw());
        return true;
    }

    /** Back to work: up from the seat, the spot forgotten. */
    public static void end(VillagerEntityMCA v) {
        Seat.stand(v);
        v.settlementsRestSpot = null;
    }
}
