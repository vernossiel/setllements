package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a villager wants most right now, weighed afresh every second instead of read off a timetable: hunger, weariness,
 * the wish for company, and the pull of his work - how dutiful he is by nature, and how much the village is waiting
 * on him (an order he has taken, the builders short of what he brings, the stores empty). The strongest wins, with
 * a little stickiness so he does not flit between them. A hard worker eats late and stays on when the building
 * waits for his wood; an idler stops early and chats; a lonely, sociable one goes looking for someone to talk to.
 */
public final class Mind {
    public enum Drive { EAT, REST, TALK, WORK, FREE }

    private static final Map<UUID, Drive> LAST = new HashMap<>();
    private static final Map<UUID, Long> TALKED = new HashMap<>();
    private static final double STICK = 0.08;

    private Mind() {
    }

    /** He has just had a conversation. */
    public static void talked(VillagerEntityMCA v) {
        TALKED.put(v.getUuid(), v.getWorld().getTime());
    }

    private static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    /** How strongly the village is waiting on his work (0 nothing in particular .. 1 the work stands still without him). */
    static double urgency(VillagerEntityMCA v, Village village) {
        if (village == null) return 0;
        double u = 0;
        if (Orders.held(v, village) != null) u += 0.6; // he has taken an order on
        Builder.Shortage s = Builder.shortage(village);
        if (s != null && v.getProfession() == net.minecraft.village.VillagerProfession.CARTOGRAPHER) u += 0.3;
        if (Forage.hungry(village) && v.getProfession() == net.minecraft.village.VillagerProfession.FARMER) u += 0.4;
        return clamp(u);
    }

    /** Within his hours, or staying on past them because the work cannot wait (and he is the kind who stays). */
    public static boolean onDuty(VillagerEntityMCA v, Village village, long time) {
        if (Persona.working(v, time)) return true;
        if (Workforce.isRestDay(time)) return false;
        long t = time % 24000L, end = Persona.workEnd(v);
        Persona.Traits tr = Persona.of(v);
        long over = (long) (urgency(v, village) * tr.diligence() * 1500); // up to an hour and a half
        return t >= end && t < end + over && t < 12800;
    }

    public static EnumMap<Drive, Double> weigh(VillagerEntityMCA v, Village village, long time) {
        Persona.Traits tr = Persona.of(v);
        double hunger = clamp(v.settlementsHunger / 900.0);
        double tired = clamp(1 - v.settlementsEnergy / (double) Workforce.MAX);
        long since = v.getWorld().getTime() - TALKED.getOrDefault(v.getUuid(), v.getWorld().getTime() - 3000);
        double lonely = clamp(since / 6000.0) * tr.sociability();
        boolean duty = onDuty(v, village, time);
        double work = duty ? clamp(0.45 * tr.diligence() + 0.45 * urgency(v, village)) : 0;
        work *= (1 - 0.5 * tired) * (1 - 0.6 * hunger);
        EnumMap<Drive, Double> w = new EnumMap<>(Drive.class);
        w.put(Drive.EAT, Workforce.hasMeal(v, village) ? Math.pow(hunger, 1.6) * 1.3 : 0);
        w.put(Drive.REST, Math.pow(tired, 2) * Persona.breakFactor(v) * 1.1);
        w.put(Drive.TALK, lonely * (duty ? 0.35 : 0.8));
        w.put(Drive.WORK, work);
        w.put(Drive.FREE, duty ? 0.05 : 0.3);
        return w;
    }

    /** What he wants most this second. */
    public static Drive top(VillagerEntityMCA v, Village village, long time) {
        EnumMap<Drive, Double> w = weigh(v, village, time);
        Drive last = LAST.get(v.getUuid());
        Drive best = Drive.FREE;
        double bs = -1;
        for (Map.Entry<Drive, Double> e : w.entrySet()) {
            double s = e.getValue() + (e.getKey() == last ? STICK : 0);
            if (s > bs) {
                bs = s;
                best = e.getKey();
            }
        }
        LAST.put(v.getUuid(), best);
        return best;
    }

    /** Whether this drive beats his work right now. */
    public static boolean before(VillagerEntityMCA v, Village village, long time, Drive d) {
        EnumMap<Drive, Double> w = weigh(v, village, time);
        return w.get(d) > w.get(Drive.WORK);
    }
}
