package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Nobody jumps out of bed and runs to the woods with an axe. A morning goes: up, a bite of breakfast, out of the
 * house, a stretch and a wash at the well, a word with whoever is about - and only then off to work, where the tools
 * are taken in hand. The early riser is quick about it, the idler dawdles, the sociable stand talking longer.
 */
public final class Morning {
    /** Mornings end by this hour at the latest (ten in the morning). */
    private static final long LATEST = 4000;

    private Morning() {
    }

    /** True while his morning is still going on (he is not to be put to work). */
    public static boolean active(VillagerEntityMCA v, long time) {
        long t = time % 24000L;
        return v.settlementsMorningDay != time / 24000L && t < LATEST && !v.isSleeping() && !v.isGuard() && !v.isBaby();
    }

    /** Once a second. True while the morning keeps him busy. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, long time) {
        if (!active(v, time)) {
            if (v.settlementsMorningDay != time / 24000L && time % 24000L >= LATEST) v.settlementsMorningDay = time / 24000L;
            v.settlementsMorningSec = 0;
            return false;
        }
        Gear.pocket(v); // the tools stay put away till he is at work
        int sec = v.settlementsMorningSec++;
        Persona.Traits tr = Persona.of(v);
        if (sec == 0) Speech.sayAny(v, "settlements.say.morning.wake", 5);
        Doing.set(v, "settlements.doing.morning");
        // breakfast first, if there is anything to eat
        if (sec == 2 && Workforce.hasMeal(v, village) && Workforce.startEating(v, village, 0)) return true;
        // out to the well (or the fire), a stretch, a wash, a chat
        long linger = Builder.rush(v) ? 6 : 25 + (long) ((1.2 - tr.diligence()) * 40) + (long) (tr.sociability() * 30);
        if (sec < 4 + linger) {
            BlockPos spot = village == null ? null : wellOrFire(village, world);
            if (spot != null && v.squaredDistanceTo(Vec3d.ofCenter(spot)) > 16) {
                if (sec < 4 + linger - 5) Move.to(v, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.45); // no hurry
            } else {
                v.getNavigation().stop();
                if (sec % 9 == 0) v.swingHand(v.getDominantHand()); // splashing water, stretching
                if (sec % 23 == 5) Speech.sayAny(v, "settlements.say.morning.wash", 5);
            }
            return true;
        }
        v.settlementsMorningDay = time / 24000L;
        v.settlementsMorningSec = 0;
        Speech.sayAny(v, "settlements.say.morning.off", 5);
        return false;
    }

    private static BlockPos wellOrFire(Village village, ServerWorld world) {
        for (Built b : village.built) if (Buildings.base(b.type()).equals("well")) return b.point(2, 0, 5); // in front of it
        BlockPos fire = Camp.hearth(village, world);
        if (fire != null) return fire;
        // the middle of the village on the ground (the village's centre can be up in a tree or deep in a hill)
        return world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(village.getCenter()));
    }
}
