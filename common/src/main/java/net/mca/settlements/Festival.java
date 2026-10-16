package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The days that are not like the others. A wedding: when two villagers marry, the village leaves what it is doing and
 * gathers on the square in a ring about the couple, who stand face to face in the middle; there are cheers and good
 * wishes, and then the couple go home together. A fair: every sixth day the morning is given over to it - people
 * stand about the square in knots, talk, look at what is to be had - and work starts only after noon.
 */
public final class Festival {
    private record Wedding(UUID a, UUID b, BlockPos at, long until) {
    }

    private static final Map<Integer, Wedding> WEDDINGS = new HashMap<>();
    private static final Map<Integer, BlockPos> SQUARE = new HashMap<>();
    private static final Map<Integer, Long> SQUARE_AT = new HashMap<>();

    /** The open ground on the square (looked for once in a while, not by everybody every second). */
    private static BlockPos square(ServerWorld world, Village village) {
        Long at = SQUARE_AT.get(village.getId());
        if (at == null || world.getTime() - at > 6000) {
            SQUARE.put(village.getId(), Justice.stocks(world, village));
            SQUARE_AT.put(village.getId(), world.getTime());
        }
        return SQUARE.get(village.getId());
    }

    private Festival() {
    }

    /** Two of the village have married (see VillageMarriageManager). */
    public static void wedding(Village village, ServerWorld world, VillagerEntityMCA a, VillagerEntityMCA b) {
        BlockPos at = square(world, village); // the open ground on the square
        WEDDINGS.put(village.getId(), new Wedding(a.getUuid(), b.getUuid(), at, world.getTime() + 20 * 150));
        Life.news(village, "wedding", a.getName().getString() + ", " + b.getName().getString());
    }

    /** A fair morning: every sixth day, from the morning till noon. */
    public static boolean fair(long timeOfDay) {
        long day = timeOfDay / 24000L, t = timeOfDay % 24000L;
        return day % 6 == 2 && t >= 1500 && t < 6000;
    }

    /** Once a second for a grown villager; true while the day's festivity takes him up. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, long timeOfDay) {
        if (village == null || v.isBaby() || v.isSleeping()) return false;
        if (v.isGuard() && GuardAI.onDuty(v, timeOfDay)) return false; // the watch keeps watch
        Wedding w = WEDDINGS.get(village.getId());
        if (w != null && world.getTime() >= w.until()) {
            WEDDINGS.remove(village.getId());
            w = null;
        }
        if (w != null) return wedding(v, world, w);
        if (service(timeOfDay) && Buildings.has(village, "chapel")) return church(v, village, world);
        if (fair(timeOfDay) && !Workforce.isRestDay(timeOfDay)) return fairDay(v, village, world);
        return false;
    }

    /** The rest day's morning: the service at the chapel. */
    public static boolean service(long timeOfDay) {
        long t = timeOfDay % 24000L;
        return Workforce.isRestDay(timeOfDay) && t >= 1500 && t < 4000;
    }

    /** The priest of the village, if it has one. */
    static VillagerEntityMCA priest(ServerWorld world, Village village) {
        for (VillagerEntityMCA r : village.getResidents(world)) if (r.isAlive() && r.getProfession() == net.mca.ProfessionsMCA.PRIEST.get()) return r;
        return null;
    }

    /**
     * The service: the priest before the altar at the far end, facing them; the village on the benches before him,
     * listening, heads bowed for the prayers, a hymn sung together. Without a priest the elder says a few words.
     */
    private static boolean church(VillagerEntityMCA v, Village village, ServerWorld world) {
        Built chapel = Buildings.find(village, "chapel");
        VillagerEntityMCA priest = priest(world, village);
        VillagerEntityMCA leader = priest != null ? priest : Plan.elder(world, village);
        int[] fp = chapel.footprint();
        BlockPos altar = Crafts.spot(world, chapel); // the middle of the nave; the priest a few steps further in
        BlockPos front = chapel.point(fp[0] / 2, 0, Math.max(1, fp[1] - 3));
        long t = world.getTimeOfDay() % 24000L;
        if (v == leader) {
            Doing.set(v, "settlements.doing.fest.preach");
            if (v.squaredDistanceTo(Vec3d.ofBottomCenter(front)) > 2) {
                Move.to(v, front.getX() + 0.5, front.getY(), front.getZ() + 0.5, 0.5);
                return true;
            }
            v.getNavigation().stop();
            Gaze.at(v, altar.getX() + 0.5, altar.getY() + 1.4, altar.getZ() + 0.5);
            Anim.set(v, Anim.Act.TALK, 40);
            if (world.random.nextInt(14) == 0) Speech.sayAny(v, priest != null ? "settlements.say.fest.sermon" : "settlements.say.fest.elderword", 5);
            return true;
        }
        Doing.set(v, "settlements.doing.fest.service");
        // a place on the benches: spread over the nave by who he is
        int seat = Math.floorMod(v.getUuid().hashCode(), 9);
        BlockPos at = altar.add(seat % 3 - 1, 0, seat / 3 - 1);
        if (v.squaredDistanceTo(Vec3d.ofBottomCenter(at)) > 2.5 * 2.5) {
            Move.to(v, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.5);
            return true;
        }
        v.getNavigation().stop();
        if (leader != null) Gaze.at(v, leader, 30f, 30f);
        // the prayers: heads bowed; and a hymn, all together, towards the end
        if ((t / 400) % 3 == 1) Pose.crouch(v, 40);
        if (t > 3300 && world.random.nextInt(25) == 0) Speech.sayAny(v, "settlements.say.fest.hymn", 3);
        return true;
    }

    private static boolean wedding(VillagerEntityMCA v, ServerWorld world, Wedding w) {
        boolean couple = v.getUuid().equals(w.a()) || v.getUuid().equals(w.b());
        Vec3d c = Vec3d.ofBottomCenter(w.at());
        if (couple) {
            Doing.set(v, "settlements.doing.fest.wed");
            // the two in the middle, a step apart, face to face
            double side = v.getUuid().equals(w.a()) ? -0.8 : 0.8;
            Vec3d spot = c.add(side, 0, 0);
            if (v.squaredDistanceTo(spot) > 1.2) {
                Move.to(v, spot.x, spot.y, spot.z, 0.5);
                return true;
            }
            v.getNavigation().stop();
            if (world.getEntity(v.getUuid().equals(w.a()) ? w.b() : w.a()) instanceof VillagerEntityMCA other) Gaze.at(v, other, 30f, 30f);
            if (world.random.nextInt(25) == 0) Speech.sayAny(v, "settlements.say.fest.vow", 3);
            return true;
        }
        if (v.getProfession() == net.mca.ProfessionsMCA.PRIEST.get()) {
            // the priest stands before the couple and marries them
            Doing.set(v, "settlements.doing.fest.marry");
            Vec3d spot = c.add(0, 0, 1.8);
            if (v.squaredDistanceTo(spot) > 1.2) {
                Move.to(v, spot.x, spot.y, spot.z, 0.5);
                return true;
            }
            v.getNavigation().stop();
            Gaze.at(v, c.x, c.y + 1.4, c.z);
            Anim.set(v, Anim.Act.TALK, 40);
            if (world.random.nextInt(20) == 0) Speech.sayAny(v, "settlements.say.fest.marry", 3);
            return true;
        }
        Doing.set(v, "settlements.doing.fest.guest");
        // the guests in a ring about them
        double angle = (v.getUuid().hashCode() & 0xffff) / 65536.0 * Math.PI * 2;
        Vec3d spot = c.add(Math.cos(angle) * 4.5, 0, Math.sin(angle) * 4.5);
        if (v.squaredDistanceTo(spot) > 2.5) {
            Move.to(v, spot.x, spot.y, spot.z, 0.55);
            return true;
        }
        v.getNavigation().stop();
        Gaze.at(v, c.x, c.y + 1.4, c.z);
        if (world.random.nextInt(4) == 0) Anim.set(v, Anim.Act.CLAP, 60); // clapping
        if (world.random.nextInt(14) == 0) Pose.jump(v); // and a jump for joy
        if (world.random.nextInt(30) == 0) Speech.sayAny(v, "settlements.say.fest.cheer", 4);
        return true;
    }

    private static boolean fairDay(VillagerEntityMCA v, Village village, ServerWorld world) {
        Doing.set(v, "settlements.doing.fest.fair");
        BlockPos at = square(world, village);
        Vec3d c = Vec3d.ofBottomCenter(at);
        // each to a knot of people about the square, a few steps from the middle
        double angle = ((v.getUuid().hashCode() >>> 4) % 5) * Math.PI * 2 / 5 + (world.getTime() / 2400) * 0.7;
        Vec3d spot = c.add(Math.cos(angle) * 6, 0, Math.sin(angle) * 6);
        if (v.squaredDistanceTo(spot) > 9) {
            Move.to(v, spot.x, spot.y, spot.z, 0.5);
            return true;
        }
        v.getNavigation().stop();
        if (world.random.nextInt(40) == 0) Speech.sayAny(v, "settlements.say.fest.fair", 4);
        return true; // there: standing about, talking (the conversations go on: a fair is a moment to spare), looking round
    }
}
