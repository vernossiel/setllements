package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The village day. In the morning everybody gathers at the hearth and the elder hands out the day's work, name by
 * name. At noon work stops for lunch by the fire. Breaks and the evening are spent there too (see {@link Camp}).
 */
public final class Routine {
    private static final long ASSEMBLY_FROM = 1000, ASSEMBLY_TO = 1700;
    private static final long LUNCH_FROM = 6000, LUNCH_TO = 7000;
    private static final int LUNCH_SECONDS = 45;
    private static final Map<Integer, Long> ASSEMBLED = new HashMap<>();

    private Routine() {
    }

    /** Once per second during the day. True while the villager is at the morning gathering or at lunch. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, long time) {
        if (village == null || v.isGuard() || Workforce.isRestDay(time)) return false;
        long day = time / 24000L, t = time % 24000L;

        if (t >= ASSEMBLY_FROM && t < ASSEMBLY_TO && ASSEMBLED.getOrDefault(village.getId(), -1L) != day) {
            if (Camp.hearth(village, world) == null && Camp.ensureHearth(village, world) == null) return false;
            boolean there = Camp.goAndSit(v, village);
            if (there && v.isElder()) {
                BlockPos fire = Camp.hearth(village, world);
                List<VillagerEntityMCA> people = village.getResidents(world).stream().filter(r -> r.isAlive() && !r.isBaby() && !r.isGuard()).toList();
                long gathered = fire == null ? 0 : people.stream().filter(r -> r.getBlockPos().isWithinDistance(fire, 8)).count();
                if (gathered >= Math.max(2, people.size() * 2 / 3) || t >= ASSEMBLY_TO - 200) {
                    announce(v, village, world, people);
                    ASSEMBLED.put(village.getId(), day);
                }
            }
            if (t >= ASSEMBLY_TO - 100) ASSEMBLED.put(village.getId(), day); // no elder: off to work anyway
            return true;
        }

        if (t >= LUNCH_FROM && t < LUNCH_TO && v.settlementsLunchDay != day) {
            boolean there = Camp.goAndSit(v, village);
            if (there) {
                v.settlementsLunchSec++;
                v.settlementsEnergy = Math.min(Workforce.MAX, v.settlementsEnergy + 20);
                if (v.settlementsLunchSec == 3) {
                    if (!Workforce.lunch(v, village)) Speech.sayAny(v, "settlements.say.lunch.none", 3);
                    else Speech.sayAny(v, "settlements.say.lunch", 4);
                }
                if (v.settlementsLunchSec >= LUNCH_SECONDS) {
                    v.settlementsLunchDay = day;
                    v.settlementsLunchSec = 0;
                    v.settlementsWorkSeconds = 0;
                }
            }
            if (t >= LUNCH_TO - 100) v.settlementsLunchDay = day;
            return true;
        }
        return false;
    }

    /** The elder's morning words: a greeting, everybody's job for the day, and off you go. */
    private static void announce(VillagerEntityMCA elder, Village village, ServerWorld world, List<VillagerEntityMCA> people) {
        elder.settlementsQueue.add(Text.translatable("settlements.assembly.hello"));
        int told = 0;
        for (VillagerEntityMCA r : people) {
            if (r == elder || told >= 8) continue;
            Text task = task(r, village);
            if (task == null) continue;
            elder.settlementsQueue.add(Text.translatable("settlements.assembly.task", r.getName(), task));
            told++;
        }
        Builder.Shortage s = Builder.shortage(village);
        if (s != null) elder.settlementsQueue.add(Text.translatable("settlements.assembly.short." + s.need().name().toLowerCase()));
        elder.settlementsQueue.add(Text.translatable("settlements.assembly.go"));
    }

    private static Text task(VillagerEntityMCA r, Village village) {
        VillagerProfession p = r.getProfession();
        if (p == VillagerProfession.CARTOGRAPHER) {
            if (village.buildOrigin != null && village.buildType != null)
                return Text.translatable("settlements.assembly.do.build", Text.translatable("settlements.building." + Buildings.base(village.buildType)));
            return Text.translatable("settlements.assembly.do.plan");
        }
        if (p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH) return Text.translatable("settlements.assembly.do.wood");
        if (p == VillagerProfession.MASON) return Text.translatable("settlements.assembly.do.mine");
        if (p == VillagerProfession.NONE || p == VillagerProfession.NITWIT) return Text.translatable("settlements.assembly.do.help");
        return null;
    }

    static MutableText name(VillagerEntityMCA v) {
        return v.getName().copy();
    }
}
