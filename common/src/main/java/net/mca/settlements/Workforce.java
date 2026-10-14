package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.minecraft.item.ItemStack;
import net.minecraft.village.VillagerProfession;

/**
 * Autonomous work: villagers pick a chore from their profession on their own, spend energy and
 * hunger while working, eat, and take rest days. Called once per second from the villager tick.
 */
public final class Workforce {
    public static final int MAX = 1000;
    private static final int WORK_DRAIN = 4;        // energy per second of work (~4 min of work per full bar)
    private static final int REST_GAIN = 12;        // energy per second of rest
    private static final int HUNGER_RISE = 2;       // per second of work
    private static final int HUNGER_EAT_AT = 600;   // try to eat above this
    private static final int HUNGER_STOP_AT = 900;  // refuses to work above this
    private static final int START_WORK_ENERGY = 600;
    private static final int REST_DAY_EVERY = 4;    // every 4th day is a rest day
    private static final long WORK_START = 1000, WORK_END = 11500;

    private Workforce() {
    }

    public static Chore choreFor(VillagerProfession p) {
        if (p == VillagerProfession.FARMER) return Chore.HARVEST;
        if (p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH) return Chore.CHOP;
        if (p == VillagerProfession.MASON || p == VillagerProfession.ARMORER) return Chore.PROSPECT;
        return Chore.NONE;
    }

    public static boolean isRestDay(long worldTime) {
        return (worldTime / 24000L) % REST_DAY_EVERY == REST_DAY_EVERY - 1;
    }

    public static boolean isWorkTime(long worldTime) {
        long t = worldTime % 24000L;
        return !isRestDay(worldTime) && t >= WORK_START && t < WORK_END;
    }

    /** Server side, once per second. */
    public static void tick(VillagerEntityMCA v) {
        if (v.isBaby()) return;
        long time = v.getWorld().getTimeOfDay();
        Chore job = v.getVillagerBrain().getCurrentJob();
        boolean autonomous = job != Chore.NONE && v.getVillagerBrain().getJobAssigner().isEmpty();

        if (job == Chore.NONE) {
            v.settlementsEnergy = Math.min(MAX, v.settlementsEnergy + REST_GAIN);
            tryEat(v);
            Chore wanted = choreFor(v.getProfession());
            if (wanted != Chore.NONE && isWorkTime(time) && v.settlementsEnergy >= START_WORK_ENERGY
                    && v.settlementsHunger < HUNGER_STOP_AT && !v.getVillagerBrain().isPanicking()) {
                net.mca.server.world.data.Village village = net.mca.server.world.data.Village.findNearest(v).orElse(null);
                Gear.equipTool(v, village, wanted);
                Gear.equipArmor(v, village);
                v.getVillagerBrain().assignAutonomous(wanted);
            }
            return;
        }

        if (!autonomous) return; // player-assigned chores ignore this system

        v.settlementsEnergy -= WORK_DRAIN;
        v.settlementsHunger += HUNGER_RISE;
        if (v.settlementsHunger >= HUNGER_EAT_AT) tryEat(v);

        if (v.settlementsEnergy <= 0 || v.settlementsHunger >= HUNGER_STOP_AT || !isWorkTime(time)) {
            v.settlementsEnergy = Math.max(0, v.settlementsEnergy);
            v.getVillagerBrain().abandonJob();
        }
    }

    private static void tryEat(VillagerEntityMCA v) {
        if (v.settlementsHunger < HUNGER_EAT_AT) return;
        for (int i = 0; i < v.getInventory().size(); i++) {
            ItemStack s = v.getInventory().getStack(i);
            if (s.isFood() && s.getItem().getFoodComponent() != null) {
                int nutrition = s.getItem().getFoodComponent().getHunger();
                s.decrement(1);
                v.settlementsHunger = Math.max(0, v.settlementsHunger - nutrition * 120);
                return;
            }
        }
    }
}
