package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;

/**
 * The road brings traders. A village short of stone or timber buys a cartload from whoever passes, out of its coffers,
 * once a day - dearer than it would cost to quarry or fell it, and never with the money it needs for wages. A village
 * with nobody to work stone can still build; a poor one cannot buy its way out of everything.
 */
public final class Market {
    private static final int RESERVE_PER_HEAD = 6; // nuggets kept back for wages

    private Market() {
    }

    /** Once a village day. */
    public static void day(ServerWorld world, Village village) {
        int pop = Math.max(1, village.getPopulation());
        int spare = Money.coffers(village) - pop * RESERVE_PER_HEAD;
        if (spare <= 0) return;
        if (Builder.stockCobble(village) < 32 && spare >= 8) { // 32 stone for 8 nuggets
            Workforce.addToTreasury(village, new ItemStack(Items.COBBLESTONE, 32));
            Money.take(village, 8);
            spare -= 8;
            Life.news(village, "market.stone");
        }
        if (Builder.stockPlanks(village) < 64 && spare >= 8) { // 16 logs for 8 nuggets (dearer in a land with no trees, but it comes)
            Workforce.addToTreasury(village, new ItemStack(Items.OAK_LOG, 16));
            Money.take(village, 8);
            Life.news(village, "market.wood");
        }
        village.markDirty();
    }
}
