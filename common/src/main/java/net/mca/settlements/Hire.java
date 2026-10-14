package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.MoveState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Villagers do not follow players, except village guards who can be hired as an escort for one day. */
public final class Hire {
    public static final int COST_EMERALDS = 5;
    public static final long DURATION = 24000L;

    private Hire() {
    }

    public static boolean isHired(VillagerEntityMCA v) {
        return v.settlementsHireUntil > v.getWorld().getTime();
    }

    public static void hireGuard(VillagerEntityMCA v, PlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        int have = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.EMERALD)) have += s.getCount();
        }
        if (have < COST_EMERALDS) {
            v.sendChatMessage(player, "settlements.hire.noemeralds");
            return;
        }
        int pay = COST_EMERALDS;
        for (int i = 0; i < inv.size() && pay > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isOf(Items.EMERALD)) continue;
            int d = Math.min(pay, s.getCount());
            s.decrement(d);
            pay -= d;
        }
        v.settlementsHireUntil = v.getWorld().getTime() + DURATION;
        v.getVillagerBrain().setMoveState(MoveState.FOLLOW, player);
        v.sendChatMessage(player, "settlements.hire.success");
    }

    public static void release(VillagerEntityMCA v) {
        v.settlementsHireUntil = 0;
    }

    /** Called every second from the villager tick. Returns true while the villager is escorting and takes no village work. */
    public static boolean tick(VillagerEntityMCA v) {
        if (v.settlementsHireUntil > 0 && v.settlementsHireUntil <= v.getWorld().getTime()) {
            v.settlementsHireUntil = 0;
            v.getVillagerBrain().setMoveState(MoveState.MOVE, null);
        }
        return v.getVillagerBrain().getMoveState() != MoveState.MOVE;
    }
}
