package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import java.util.List;

/**
 * A villager's craft: work is not a machine running at one speed. A man gets better at his trade with every day of
 * it, a beginner is slow and fumbles now and then, a master works quickly. Nobody works without a pause: after a
 * stretch he straightens up, looks round, eyes his work, wipes his brow, says a word, and goes on. Two men on the
 * same job talk about it.
 */
public final class Trade {
    /** Experience for one level of skill; five levels from novice to master. */
    private static final int PER_LEVEL = 1800;
    private static final int MAX_LEVEL = 5;

    private Trade() {
    }

    public static int level(VillagerEntityMCA v) {
        return Math.min(MAX_LEVEL, v.settlementsSkill / PER_LEVEL);
    }

    /** How fast he works for his skill and character: a novice at three quarters, a master half again as fast. */
    public static double pace(VillagerEntityMCA v) {
        double skill = 0.75 + 0.15 * level(v);
        return skill * Math.sqrt(Persona.of(v).diligence());
    }

    /** A second of real work done: experience, and a word when he has got noticeably better. */
    public static void practise(VillagerEntityMCA v) {
        int before = level(v);
        v.settlementsSkill++;
        int after = level(v);
        if (after > before) Speech.say(v, "settlements.say.skill." + after);
    }

    /**
     * Once a second at work, before working: true while he takes one of his small pauses. Pauses come after a
     * stretch of work of his own length (shorter for the idle), last a few seconds, and are spent looking round,
     * looking over the work, or with a word; a novice now and then has to redo something he got wrong.
     */
    public static boolean pausing(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (v.settlementsPauseLeft > 0) {
            v.settlementsPauseLeft--;
            v.getNavigation().stop();
            if (v.getRandom().nextInt(3) == 0) {
                // looking round, at the sky, at the ground, at the work
                float yaw = v.getYaw() + (v.getRandom().nextFloat() - 0.5f) * 160f;
                double dx = -Math.sin(Math.toRadians(yaw)) * 4, dz = Math.cos(Math.toRadians(yaw)) * 4;
                v.getLookControl().lookAt(v.getX() + dx, v.getEyeY() + (v.getRandom().nextFloat() - 0.6f) * 3, v.getZ() + dz);
            }
            return true;
        }
        if (v.settlementsStretch < 0) v.settlementsStretch = stretch(v);
        if (v.settlementsStretch-- > 0) {
            if (v.getRandom().nextInt(260) == 0) talkShop(v, village, world);
            return false;
        }
        v.settlementsStretch = stretch(v);
        v.settlementsPauseLeft = 3 + v.getRandom().nextInt(6);
        int lvl = level(v);
        if (v.getRandom().nextInt(4 + lvl * 3) == 0) {
            Speech.sayAny(v, "settlements.say.oops", 4); // got it wrong: undo, again
            v.settlementsPauseLeft += 4;
            v.swingHand(v.getDominantHand());
        } else if (v.getRandom().nextInt(3) == 0) {
            Speech.sayAny(v, "settlements.say.pause", 8);
        }
        return true;
    }

    /** Seconds of work before the next pause: the diligent keep at it longer. */
    private static int stretch(VillagerEntityMCA v) {
        return (int) (12 + 30 * Persona.of(v).diligence()) + v.getRandom().nextInt(20);
    }

    /** Two men of the same trade working side by side say a word about the job. */
    private static void talkShop(VillagerEntityMCA v, Village village, ServerWorld world) {
        VillagerProfession p = v.getProfession();
        List<VillagerEntityMCA> mates = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(8),
                o -> o != v && o.isAlive() && o.getProfession() == p && !o.isSleeping());
        if (mates.isEmpty()) return;
        Life.exchange(v, mates.get(world.random.nextInt(mates.size())), village, world);
    }
}
