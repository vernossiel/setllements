package net.mca.settlements;

import net.mca.item.ItemsMCA;
import net.mca.server.world.data.Village;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.Iterator;

/**
 * Money is real: emerald nuggets, nine to an emerald. A villager's purse is what he carries in his pockets; the
 * village's coffers are what lies in its stores. Sums are counted in nuggets; an emerald is broken into nuggets for
 * change when need be.
 */
public final class Money {
    private Money() {
    }

    public static boolean is(ItemStack s) {
        return s.isOf(Items.EMERALD) || s.isOf(ItemsMCA.EMERALD_NUGGET.get());
    }

    private static int worth(ItemStack s) {
        return s.isOf(Items.EMERALD) ? s.getCount() * 9 : s.isOf(ItemsMCA.EMERALD_NUGGET.get()) ? s.getCount() : 0;
    }

    /** In nuggets, in a villager's or a player's pockets. */
    public static int count(Inventory inv) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) n += worth(inv.getStack(i));
        return n;
    }

    /** In nuggets, in the village's stores. */
    public static int coffers(Village v) {
        int n = 0;
        for (ItemStack s : v.storageBuffer) n += worth(s);
        return n;
    }

    /** Takes {@code n} nuggets' worth out of the pockets (nuggets first, then an emerald broken for change). False if short. */
    public static boolean take(Inventory inv, int n) {
        if (count(inv) < n) return false;
        for (int i = 0; i < inv.size() && n > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isOf(ItemsMCA.EMERALD_NUGGET.get())) continue;
            int d = Math.min(n, s.getCount());
            s.decrement(d);
            n -= d;
        }
        for (int i = 0; i < inv.size() && n > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isOf(Items.EMERALD)) continue;
            while (n > 0 && !s.isEmpty()) {
                s.decrement(1);
                int change = 9 - Math.min(9, n);
                n -= Math.min(9, n);
                if (change > 0) give(inv, change);
            }
        }
        return true;
    }

    /** Takes {@code n} nuggets' worth out of the stores; returns what was actually there to take. */
    public static int take(Village v, int n) {
        int took = 0;
        for (Iterator<ItemStack> it = v.storageBuffer.iterator(); it.hasNext() && took < n; ) {
            ItemStack s = it.next();
            if (!s.isOf(ItemsMCA.EMERALD_NUGGET.get())) continue;
            int d = Math.min(n - took, s.getCount());
            s.decrement(d);
            took += d;
            if (s.isEmpty()) it.remove();
        }
        while (took < n) {
            ItemStack em = null;
            for (ItemStack s : v.storageBuffer) if (s.isOf(Items.EMERALD)) em = s;
            if (em == null) break;
            em.decrement(1);
            v.storageBuffer.removeIf(ItemStack::isEmpty);
            int d = Math.min(9, n - took);
            took += d;
            if (9 - d > 0) Workforce.addToTreasury(v, new ItemStack(ItemsMCA.EMERALD_NUGGET.get(), 9 - d));
        }
        v.markDirty();
        return took;
    }

    /** Into the pockets (as much as fits). */
    public static void give(Inventory inv, int n) {
        if (n <= 0) return;
        ItemStack rest = new ItemStack(ItemsMCA.EMERALD_NUGGET.get(), n);
        if (inv instanceof net.minecraft.inventory.SimpleInventory si) rest = si.addStack(rest);
        else for (int i = 0; i < inv.size() && !rest.isEmpty(); i++) if (inv.getStack(i).isEmpty()) {
            inv.setStack(i, rest);
            rest = ItemStack.EMPTY;
        }
    }

    /** Into the stores. */
    public static void deposit(Village v, int n) {
        if (n > 0) Workforce.addToTreasury(v, new ItemStack(ItemsMCA.EMERALD_NUGGET.get(), n));
    }
}
