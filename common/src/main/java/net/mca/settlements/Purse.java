package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import java.util.List;

/**
 * Every villager has his own money, in his pockets: emerald nuggets (see Money). Each morning the village pays him for
 * yesterday's work out of its coffers - by his trade and how good he is at it - and what he eats from the stores he
 * pays for, the nuggets going back into the coffers: bread is cheap, meat and cooked meals dearer, and a man with
 * money eats better. Nobody starves for want of a coin while there is food: the poor eat the cheapest there is on
 * credit, and the debt is taken out of their next wages; the village knows who owes. So some get on and some do not,
 * and it shows. What a man carries can be stolen from him - and falls from him if he dies.
 */
public final class Purse {
    private static final int DEBT_LIMIT = -30;

    private Purse() {
    }

    /** A day's pay for his trade, in nuggets, before his skill. */
    static int wage(VillagerEntityMCA v) {
        if (v.isElder()) return 4;
        if (v.isGuard()) return 3;
        VillagerProfession p = v.getProfession();
        if (p == VillagerProfession.ARMORER || p == net.mca.ProfessionsMCA.MERCHANT.get()) return 5;
        if (p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.MASON || p == net.mca.ProfessionsMCA.GLAZIER.get()) return 4;
        if (p == VillagerProfession.CLERIC || p == net.mca.ProfessionsMCA.PRIEST.get() || p == net.mca.ProfessionsMCA.MILLER.get()) return 4;
        if (p == VillagerProfession.NONE || p == VillagerProfession.NITWIT) return 1;
        return 3;
    }

    /** His money, in nuggets: on him and put by at home. */
    public static int money(VillagerEntityMCA v) {
        return Money.count(v.getInventory()) + Home.savings(v);
    }

    /** The morning's wages, out of the coffers (shared out by what is there when it runs short); debts come off first. */
    public static void payday(ServerWorld world, Village village) {
        List<VillagerEntityMCA> folk = village.getResidents(world).stream().filter(r -> r.isAlive() && !r.isBaby()).toList();
        Money.deposit(village, folk.size()); // what the village earns from the world about it, through the market and the road
        int due = 0;
        int[] pay = new int[folk.size()];
        for (int i = 0; i < folk.size(); i++) {
            pay[i] = (int) Math.round(wage(folk.get(i)) * (0.8 + 0.1 * Trade.level(folk.get(i))));
            due += pay[i];
        }
        if (due == 0) return;
        double share = Math.min(1.0, Money.coffers(village) / (double) due);
        for (int i = 0; i < folk.size(); i++) {
            VillagerEntityMCA r = folk.get(i);
            int got = Money.take(village, (int) Math.floor(pay[i] * share));
            if (r.settlementsPurse < 0) { // what he owes for his bread is kept back
                int back = Math.min(got, -r.settlementsPurse);
                r.settlementsPurse += back;
                Money.deposit(village, back);
                got -= back;
            }
            Money.give(r.getInventory(), got);
        }
        village.markDirty();
    }

    /** What a meal of this costs, in nuggets. */
    static int price(ItemStack meal) {
        var food = meal.getItem().getFoodComponent();
        int hunger = food == null ? 1 : food.getHunger();
        return Math.max(1, hunger / 3);
    }

    /** He pays for a meal from the stores (on credit if his pockets are empty); false only if he is too deep in debt for this. */
    static boolean pay(VillagerEntityMCA v, Village village, ItemStack meal) {
        int cost = price(meal);
        if (Money.take(v.getInventory(), cost)) {
            Money.deposit(village, cost);
            return true;
        }
        if (v.settlementsPurse - cost < DEBT_LIMIT && cost > 1) return false;
        v.settlementsPurse -= cost; // on credit
        village.markDirty();
        return true;
    }

    /** Whether he can afford the better food. */
    static boolean welloff(VillagerEntityMCA v) {
        return money(v) >= 27 && v.settlementsPurse >= 0;
    }

    /** In debt past what is decent: the village knows it. */
    public static boolean poor(VillagerEntityMCA v) {
        return v.settlementsPurse < -10;
    }
}
