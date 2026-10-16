package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import java.util.List;

/**
 * Every villager has his own money. Each morning the village pays him for yesterday's work out of its coffers - by
 * his trade and how good he is at it - and what he eats from the stores he pays for, the coins going back into the
 * coffers: bread is cheap, meat and cooked meals dearer, and a man with money eats better. Nobody starves for want of
 * a coin while there is food: the poor eat the cheapest there is and run up a debt, and are known for it. So some
 * get on and some do not, and it shows (see Housing and the elder's talk).
 */
public final class Purse {
    private static final int DEBT_LIMIT = -30;

    private Purse() {
    }

    /** A day's pay for his trade, before his skill. */
    static int wage(VillagerEntityMCA v) {
        if (v.isElder()) return 6;
        if (v.isGuard()) return 5;
        VillagerProfession p = v.getProfession();
        if (p == VillagerProfession.ARMORER || p == net.mca.ProfessionsMCA.MERCHANT.get()) return 8;
        if (p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.MASON || p == net.mca.ProfessionsMCA.GLAZIER.get()) return 6;
        if (p == VillagerProfession.CLERIC || p == net.mca.ProfessionsMCA.PRIEST.get() || p == net.mca.ProfessionsMCA.MILLER.get()) return 6;
        if (p == VillagerProfession.NONE || p == VillagerProfession.NITWIT) return 1;
        return 4;
    }

    /** The morning's wages, out of the coffers (shared out by what is there when it runs short). */
    public static void payday(ServerWorld world, Village village) {
        List<VillagerEntityMCA> folk = village.getResidents(world).stream().filter(r -> r.isAlive() && !r.isBaby()).toList();
        int due = 0;
        int[] pay = new int[folk.size()];
        for (int i = 0; i < folk.size(); i++) {
            VillagerEntityMCA r = folk.get(i);
            pay[i] = (int) Math.round(wage(r) * (0.8 + 0.1 * Trade.level(r)));
            due += pay[i];
        }
        village.coffers += folk.size() * 2; // what the village earns from the world about it, through the market and the road
        if (due == 0) return;
        double share = Math.min(1.0, village.coffers / (double) due);
        for (int i = 0; i < folk.size(); i++) {
            int got = (int) Math.floor(pay[i] * share);
            folk.get(i).settlementsPurse += got;
            village.coffers -= got;
        }
        village.markDirty();
    }

    /** What a meal of this costs. */
    static int price(ItemStack meal) {
        var food = meal.getItem().getFoodComponent();
        int hunger = food == null ? 1 : food.getHunger();
        return Math.max(1, hunger / 2);
    }

    /** He pays for a meal from the stores; false only if he is too deep in debt for anything but the cheapest. */
    static boolean pay(VillagerEntityMCA v, Village village, ItemStack meal) {
        int cost = price(meal);
        if (v.settlementsPurse - cost < DEBT_LIMIT && cost > 1) return false;
        v.settlementsPurse -= cost;
        village.coffers += cost;
        village.markDirty();
        return true;
    }

    /** Whether he can afford the better food (eats the cheap things first otherwise). */
    static boolean welloff(VillagerEntityMCA v) {
        return v.settlementsPurse >= 25;
    }

    /** In debt past what is decent: the village knows it. */
    public static boolean poor(VillagerEntityMCA v) {
        return v.settlementsPurse < -10;
    }
}
