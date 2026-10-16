package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.relationship.Personality;
import net.mca.server.world.data.Village;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/**
 * Who a villager is, beyond his trade. Every villager has a character drawn from his MCA personality and a little
 * of his own: how hard he works, how much he seeks company, how brave, curious and quick-tempered he is, and whether
 * he is up with the sun or likes a late start. It sets his own working hours, whether he turns up at the morning
 * gathering, where he eats, how often he stops for a break or a word with a neighbour, who his friends are and how
 * he spends his free time. So no two villagers keep quite the same day.
 */
public final class Persona {
    public record Traits(double diligence, double sociability, double bravery, double curiosity, double temper, int shift) {
    }

    private Persona() {
    }

    public static Traits of(VillagerEntityMCA v) {
        Personality p = v.getVillagerBrain().getPersonality();
        double d = 1, s = 0.6, b = 0.6, c = 0.5, temper = 0.3;
        switch (p) {
            case LAZY -> d = 0.6;
            case PEPPY -> {
                d = 1.25;
                s = 0.9;
            }
            case ATHLETIC -> {
                d = 1.2;
                b = 0.8;
            }
            case CONFIDENT -> b = 1.0;
            case FRIENDLY -> s = 1.0;
            case FLIRTY -> s = 0.95;
            case WITTY -> s = 0.85;
            case SHY -> {
                s = 0.2;
                b = 0.3;
            }
            case GLOOMY -> {
                s = 0.4;
                temper = 0.5;
            }
            case SENSITIVE -> {
                b = 0.3;
                temper = 0.5;
            }
            case GREEDY -> {
                d = 1.15;
                s = 0.4;
            }
            case ODD -> c = 1.0;
            case GRUMPY -> {
                s = 0.25;
                temper = 1.0;
            }
            default -> {
            }
        }
        long h = seed(v.getUuid());
        return new Traits(clamp(d + noise(h, 0) * 0.15, 0.4, 1.5), clamp(s + noise(h, 1) * 0.15, 0, 1), clamp(b + noise(h, 2) * 0.15, 0, 1.2),
                clamp(c + noise(h, 3) * 0.2, 0, 1), clamp(temper + noise(h, 4) * 0.15, 0, 1), (int) (noise(h, 5) * 700));
    }

    private static long seed(UUID id) {
        return id.getMostSignificantBits() ^ id.getLeastSignificantBits();
    }

    /** A steady pseudo-random number in [-1, 1] for this seed and slot. */
    private static double noise(long h, long i) {
        long x = h * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L;
        x ^= x >>> 31;
        x *= 0x94D049BB133111EBL;
        x ^= x >>> 29;
        return ((x >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    // ---------- his own day ----------

    /** When he starts work: the early riser before the others, the idler well after them. */
    public static long workStart(VillagerEntityMCA v) {
        Traits t = of(v);
        return Math.max(300, 1000 + t.shift() + (long) ((1 - t.diligence()) * 1200));
    }

    public static long workEnd(VillagerEntityMCA v) {
        Traits t = of(v);
        return Math.min(12400, 11500 + t.shift() - (long) ((1 - t.diligence()) * 1200));
    }

    /** His working hours today (nobody works on the rest day). */
    public static boolean working(VillagerEntityMCA v, long time) {
        if (Workforce.isRestDay(time)) return false;
        long t = time % 24000L;
        return t >= workStart(v) && t < workEnd(v);
    }

    /** Up and about with nothing to do for the village: the morning before work, the evening after it, the rest day. */
    public static boolean free(VillagerEntityMCA v, long time) {
        long t = time % 24000L;
        if (t >= 12800 + of(v).shift() / 3 || t < 500) return false; // bedtime
        return !working(v, time);
    }

    /** Whether he turns up at the elder's morning gathering today: the dutiful and the sociable mostly do. */
    public static boolean attends(VillagerEntityMCA v, long day) {
        if (v.isElder()) return true;
        Traits t = of(v);
        double chance = clamp(0.15 + 0.45 * t.diligence() + 0.3 * t.sociability(), 0.1, 0.95);
        return (noise(seed(v.getUuid()) ^ day * 0x632BE59BD9B4E019L, 7) + 1) / 2 < chance;
    }

    /** Lunch a little earlier or later than the next man. */
    public static long lunchShift(VillagerEntityMCA v) {
        return of(v).shift() / 2;
    }

    /** Company at lunch: the unsociable eat where they are. */
    public static boolean eatsTogether(VillagerEntityMCA v) {
        return of(v).sociability() >= 0.35;
    }

    /** How much more (or less) often than the average man he stops for a short break. */
    public static double breakFactor(VillagerEntityMCA v) {
        return 2 - of(v).diligence();
    }

    // ---------- friends ----------

    /** How well two villagers get on: chance, kindred spirits, the same trade; a grump gets on with few. */
    public static double affinity(VillagerEntityMCA a, VillagerEntityMCA b) {
        Traits ta = of(a), tb = of(b);
        double x = noise(seed(a.getUuid()) ^ seed(b.getUuid()), 9) * 0.6;
        x += 0.4 - Math.abs(ta.sociability() - tb.sociability());
        if (a.getProfession() == b.getProfession()) x += 0.2;
        x -= (ta.temper() + tb.temper()) * 0.25;
        x += Math.min(0.6, a.settlementsBonds.getInt(b.getUuidAsString()) * 0.03); // the talks they have had
        return x;
    }

    /** One more talk between them: the bond grows on both sides (kept with the villagers). */
    public static void befriend(VillagerEntityMCA a, VillagerEntityMCA b) {
        a.settlementsBonds.putInt(b.getUuidAsString(), Math.min(50, a.settlementsBonds.getInt(b.getUuidAsString()) + 1));
        b.settlementsBonds.putInt(a.getUuidAsString(), Math.min(50, b.settlementsBonds.getInt(a.getUuidAsString()) + 1));
    }

    /** His best friend in the village who is up and about, or null. */
    public static VillagerEntityMCA friend(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null) return null;
        VillagerEntityMCA best = null;
        double bd = 0.1; // somebody he really likes, not just anybody
        for (VillagerEntityMCA o : village.getResidents(world)) {
            if (o == v || !o.isAlive() || o.isBaby() || o.isSleeping() || o.squaredDistanceTo(v) > 80 * 80) continue;
            double a = affinity(v, o);
            if (a > bd) {
                bd = a;
                best = o;
            }
        }
        return best;
    }

    // ---------- a word on the way ----------

    /**
     * Once a second at work: a sociable soul now and then stops a neighbour he meets for a few words, and the two
     * stand and talk a little before going on. True while he is talking.
     */
    public static boolean chatting(VillagerEntityMCA v, Village village, ServerWorld world) {
        long now = world.getTime();
        if (now < v.settlementsChatUntil) {
            Entity o = v.settlementsChatWith == null ? null : world.getEntity(v.settlementsChatWith);
            if (o instanceof VillagerEntityMCA other && other.isAlive() && other.squaredDistanceTo(v) < 36) {
                v.getNavigation().stop();
                Gaze.at(v, other, 30f, 30f);
                if (now % 120 < 20) Life.exchange(v, other, village, world);
                return true;
            }
            v.settlementsChatUntil = 0;
            return false;
        }
        if (v.isGuard() || v.isBaby() || v.getVillagerBrain().isPanicking()) return false;
        Traits t = of(v);
        if (v.getRandom().nextDouble() > t.sociability() * (1.6 - t.diligence()) / 150) return false;
        List<VillagerEntityMCA> near = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(4),
                o -> o != v && o.isAlive() && !o.isBaby() && !o.isSleeping() && !o.isGuard() && now >= o.settlementsChatUntil && !o.settlementsBreak);
        if (near.isEmpty()) return false;
        VillagerEntityMCA other = near.get(world.random.nextInt(near.size()));
        if (affinity(v, other) < -0.3) return false; // not with him
        long until = now + 160 + world.random.nextInt(200);
        v.settlementsChatUntil = until;
        v.settlementsChatWith = other.getUuid();
        other.settlementsChatUntil = until;
        other.settlementsChatWith = v.getUuid();
        other.getNavigation().stop();
        Life.exchange(v, other, village, world);
        return true;
    }

    // ---------- free time ----------

    private static final int FIRE = 0, VISIT = 1, TAVERN = 2, STROLL = 3, HOME = 4;

    /** Once a second of free time: carries on with what he chose to do, or picks something new. */
    public static void pastime(VillagerEntityMCA v, Village village, ServerWorld world) {
        long now = world.getTime();
        if (v.settlementsPastime < 0 || now >= v.settlementsPastimeUntil) choose(v, village, world);
        Entity with = v.settlementsPastimeWith == null ? null : world.getEntity(v.settlementsPastimeWith);
        switch (v.settlementsPastime) {
            case FIRE -> Doing.set(v, "settlements.doing.pastime.fire");
            case VISIT -> Doing.set(v, "settlements.doing.pastime.visit", with == null ? "" : with.getName());
            case TAVERN -> Doing.set(v, "settlements.doing.pastime.tavern");
            case HOME -> Doing.set(v, "settlements.doing.pastime.home");
            default -> Doing.set(v, "settlements.doing.pastime.stroll");
        }
        switch (v.settlementsPastime) {
            case FIRE -> {
                if (Camp.goAndSit(v, village)) {
                    v.settlementsEnergy = Math.min(Workforce.MAX, v.settlementsEnergy + 20);
                    Life.restingChat(v, world);
                }
            }
            case VISIT -> {
                Entity e = v.settlementsPastimeWith == null ? null : world.getEntity(v.settlementsPastimeWith);
                if (!(e instanceof VillagerEntityMCA friend) || !friend.isAlive() || friend.isSleeping()) {
                    v.settlementsPastimeUntil = 0;
                    return;
                }
                if (v.squaredDistanceTo(friend) > 9) {
                    v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(friend, 0.45f, 2));
                } else {
                    Gaze.at(v, friend, 30f, 30f);
                    Gaze.at(friend, v, 30f, 30f);
                    // the friend, if he has nothing better to do, stays to talk
                    if (friend.settlementsPastime != VISIT && free(friend, world.getTimeOfDay())) {
                        friend.settlementsPastime = VISIT;
                        friend.settlementsPastimeWith = v.getUuid();
                        friend.settlementsPastimeUntil = v.settlementsPastimeUntil;
                        friend.getNavigation().stop();
                    }
                    if (now % 100 < 20) Life.exchange(v, friend, village, world);
                }
            }
            case TAVERN -> {
                if (village == null || village.tavern == null) {
                    v.settlementsPastimeUntil = 0;
                    return;
                }
                List<BlockPos> seats = village.tavern.seats();
                BlockPos spot = seats.isEmpty() ? village.tavern.smoker() : seats.get(Math.floorMod(v.getUuid().hashCode(), seats.size()));
                if (v.squaredDistanceTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 9) {
                    v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, 0.45f, 1));
                } else {
                    Life.restingChat(v, world);
                }
            }
            case HOME -> {
                GlobalPos home = v.getResidency().getHome().orElse(null);
                if (home == null || home.getDimension() != world.getRegistryKey()) {
                    v.settlementsPastime = STROLL;
                    return;
                }
                BlockPos h = home.getPos();
                if (v.squaredDistanceTo(h.getX() + 0.5, h.getY(), h.getZ() + 0.5) > 16) {
                    v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(h, 0.4f, 2));
                } else if (v.getRandom().nextInt(20) == 0) {
                    // pottering about the house
                    Vec3d go = FuzzyTargeting.find(v, 4, 2);
                    if (go != null) v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(go, 0.35f, 0));
                }
            }
            default -> { // a stroll: wander, stop, look about
                if (Seat.seated(v)) Rest.end(v);
                long phase = (now / 20 + v.getUuid().hashCode()) % 30;
                if (phase < 12) return;
                if (v.getNavigation().isIdle()) {
                    Vec3d go = FuzzyTargeting.find(v, 12, 4);
                    if (go != null) v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(go, 0.4f, 1));
                }
            }
        }
    }

    private static void choose(VillagerEntityMCA v, Village village, ServerWorld world) {
        Traits t = of(v);
        VillagerEntityMCA friend = friend(v, village, world);
        // a friend laid up ill is looked in on
        if (friend != null && Sickness.sick(friend) && world.random.nextBoolean()) {
            v.settlementsPastime = VISIT;
            v.settlementsPastimeWith = friend.getUuid();
            v.settlementsPastimeUntil = world.getTime() + 600;
            return;
        }
        // husband and wife spend the evening together more often than not
        if (v.getRelationships().getPartnerUUID().isPresent() && world.getEntity(v.getRelationships().getPartnerUUID().get()) instanceof VillagerEntityMCA spouse
                && spouse.isAlive() && !spouse.isSleeping() && free(spouse, world.getTimeOfDay()) && spouse.squaredDistanceTo(v) < 64 * 64 && world.random.nextInt(3) > 0) {
            friend = spouse;
            if (v.settlementsPastime == FIRE && Seat.seated(v)) Rest.end(v);
            v.settlementsPastime = VISIT;
            v.settlementsPastimeWith = spouse.getUuid();
            v.settlementsPastimeUntil = world.getTime() + 900 + world.random.nextInt(1200);
            return;
        }
        boolean tired = v.settlementsEnergy < 500;
        double[] w = {
                0.3 + t.sociability() * 0.8 + (tired ? 0.6 : 0),                       // the fire
                friend == null ? 0 : t.sociability() * 1.2,                             // a friend
                village == null || village.tavern == null ? 0 : t.sociability() * 0.9, // the tavern
                0.2 + t.curiosity() * 0.8,                                              // a stroll
                0.3 + (1 - t.sociability()) * 1.0 + (tired ? 0.3 : 0)                   // at home
        };
        double sum = 0;
        for (double x : w) sum += x;
        double r = world.random.nextDouble() * sum;
        int pick = 0;
        while (pick < w.length - 1 && (r -= w[pick]) > 0) pick++;
        if (v.settlementsPastime == FIRE && pick != FIRE && Seat.seated(v)) Rest.end(v);
        v.settlementsPastime = pick;
        v.settlementsPastimeWith = pick == VISIT && friend != null ? friend.getUuid() : null;
        v.settlementsPastimeUntil = world.getTime() + 600 + world.random.nextInt(1000);
    }
}
