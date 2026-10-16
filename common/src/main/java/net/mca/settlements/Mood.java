package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How the whole village feels after what has happened, and how it shows. A building finished: whoever is about and not
 * busy goes to have a look at it and says so. A death: the village grieves for a day - people walk slower, those with
 * a moment gather where the dead was laid, there is little joking. An attack: for a day and a half the village is
 * afraid - in their own time people keep to their houses and go in earlier, and they look about them more.
 */
public final class Mood {
    public enum Kind { JOY, GRIEF, FEAR }

    private record State(Kind kind, long until, BlockPos at, long since) {
    }

    private static final Map<Integer, State> STATES = new HashMap<>();
    private static final Map<Integer, Set<UUID>> CAME = new HashMap<>();

    private Mood() {
    }

    /** Something has happened in the village. Grief and fear outweigh joy; a fresh one replaces an older of its kind. */
    public static void event(Village village, Kind kind, BlockPos at) {
        if (village == null || village.getWorld() == null) return;
        long now = village.getWorld().getTime();
        State old = STATES.get(village.getId());
        if (old != null && now < old.until() && kind == Kind.JOY && old.kind() != Kind.JOY) return; // no celebrating in mourning
        long length = switch (kind) {
            case JOY -> 20 * 90;
            case GRIEF -> 24000;
            case FEAR -> 36000;
        };
        STATES.put(village.getId(), new State(kind, now + length, at, now));
        CAME.remove(village.getId());
    }

    public static Kind now(Village village) {
        if (village == null || village.getWorld() == null) return null;
        State s = STATES.get(village.getId());
        return s == null || village.getWorld().getTime() >= s.until() ? null : s.kind();
    }

    /** How the mood weighs on a man's step. */
    public static double pace(Village village) {
        Kind k = now(village);
        return k == Kind.GRIEF ? 0.85 : k == Kind.FEAR ? 1.05 : 1.0;
    }

    /** In a frightened village people stay in and look about them. */
    public static boolean afraid(Village village) {
        return now(village) == Kind.FEAR;
    }

    /** Once a second for a grown villager with a moment to spare (off work, or between jobs). True while the mood takes him up. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, boolean free) {
        Kind k = now(village);
        if (k == null || v.isBaby()) return false;
        State s = STATES.get(village.getId());
        long now = world.getTime();
        Set<UUID> came = CAME.computeIfAbsent(village.getId(), x -> new HashSet<>());
        switch (k) {
            case JOY -> {
                // the new building: a look at it, a word, and back to what he was doing
                if (s.at() == null || came.contains(v.getUuid()) || !free && world.random.nextInt(3) != 0) return false;
                if (v.squaredDistanceTo(Vec3d.ofCenter(s.at())) > 48 * 48) return false;
                Doing.set(v, "settlements.doing.mood.look");
                if (v.squaredDistanceTo(Vec3d.ofCenter(s.at())) > 8 * 8) {
                    Move.to(v, s.at().getX() + 0.5, s.at().getY(), s.at().getZ() + 0.5, 0.5);
                    return true;
                }
                v.getNavigation().stop();
                v.getLookControl().lookAt(s.at().getX() + 0.5, s.at().getY() + 2, s.at().getZ() + 0.5);
                if (world.random.nextInt(4) == 0) {
                    Speech.sayAny(v, "settlements.say.mood.built", 4);
                    came.add(v.getUuid());
                }
                return true;
            }
            case GRIEF -> {
                if (!free) return false;
                // the first hours: those with a moment stand a while where the dead was laid
                if (now - s.since() < 20 * 300 && s.at() != null && !came.contains(v.getUuid())) {
                    Doing.set(v, "settlements.doing.mood.mourn");
                    if (v.squaredDistanceTo(Vec3d.ofCenter(s.at())) > 5 * 5) {
                        Move.to(v, s.at().getX() + 0.5, s.at().getY(), s.at().getZ() + 0.5, 0.4);
                        return true;
                    }
                    v.getNavigation().stop();
                    v.getLookControl().lookAt(s.at().getX() + 0.5, s.at().getY(), s.at().getZ() + 0.5);
                    if (world.random.nextInt(8) == 0) Pose.crouch(v, 60); // bent over the grave a while
                    if (world.random.nextInt(30) == 0) {
                        Speech.sayAny(v, "settlements.say.mood.mourn", 4);
                        came.add(v.getUuid()); // said his piece: he may go
                    }
                    return true;
                }
                return false;
            }
            case FEAR -> {
                if (!free) return false;
                // in their own time they keep to the house
                GlobalPos h = v.getResidency().getHome().orElse(null);
                if (h == null || h.getDimension() != world.getRegistryKey()) return false;
                Doing.set(v, "settlements.doing.mood.afraid");
                BlockPos p = h.getPos();
                if (v.squaredDistanceTo(Vec3d.ofBottomCenter(p)) > 6 * 6) {
                    Move.to(v, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.55);
                    return true;
                }
                if (world.random.nextInt(40) == 0) Speech.sayAny(v, "settlements.say.mood.afraid", 4);
                return false; // at home: the house's own pastimes go on
            }
        }
        return false;
    }
}
