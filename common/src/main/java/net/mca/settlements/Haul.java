package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolItem;
import net.minecraft.util.math.BlockPos;

/**
 * Goods travel by hand. The lumberjack and the miner fill their pockets and carry the load to the town hall's store
 * chests; the builder walks there to pick up a load of materials before he starts laying blocks.
 */
public final class Haul {
    private static final int FULL_LOAD = 48;

    private Haul() {
    }

    /**
     * Where this villager takes his goods: the lumberjack to the sawmill, the miner to the stonemason's, everybody
     * to the nearest storehouse chest; without those, to the town hall.
     */
    public static BlockPos store(VillagerEntityMCA v, Village village) {
        var p = v.getProfession();
        String trade = p == net.minecraft.village.VillagerProfession.FLETCHER || p == net.minecraft.village.VillagerProfession.TOOLSMITH ? "sawmill"
                : p == net.minecraft.village.VillagerProfession.MASON ? "mason" : null;
        BlockPos best = trade == null ? null : nearestChest(v, village, trade);
        if (best == null) best = nearestChest(v, village, "storage");
        if (best == null) best = nearestChest(v, village, "granary");
        if (best != null) return best;
        if (!village.hallChests.isEmpty()) return village.hallChests.get(0);
        return village.hallPos;
    }

    private static BlockPos nearestChest(VillagerEntityMCA v, Village village, String type) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (Built b : village.built) {
            if (!Buildings.base(b.type()).equals(type)) continue;
            for (Blueprints.Step s : b.steps()) {
                if (s.kind() != Blueprints.Kind.CHEST) continue;
                BlockPos c = b.point(s.dx(), s.dy(), s.dz());
                double d = v.squaredDistanceTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
                if (d < bd) {
                    bd = d;
                    best = c;
                }
            }
        }
        return best;
    }

    /** Walks to the stores. True once he stands at the chest. */
    public static boolean reach(VillagerEntityMCA v, Village village) {
        BlockPos s = store(v, village);
        if (s == null) return true; // no hall yet: the stores are wherever he is
        if (v.squaredDistanceTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5) > 9) {
            Move.to(v, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0.6);
            return false;
        }
        v.getNavigation().stop();
        v.getLookControl().lookAt(s.getX() + 0.5, s.getY() + 0.5, s.getZ() + 0.5);
        return true;
    }

    static int load(VillagerEntityMCA v) {
        int n = 0;
        var inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() instanceof ToolItem || s.getItem() instanceof ArmorItem || s.isFood()) continue;
            n += s.getCount();
        }
        return n;
    }

    static boolean pocketsFull(VillagerEntityMCA v) {
        var inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isEmpty()) return false;
        return true;
    }

    /**
     * The gatherer's trip to the stores. Called before his work each second: true while he is carrying a load home.
     * {@code done} says he has nothing more to gather right now, so whatever he carries goes home.
     */
    public static boolean carry(VillagerEntityMCA v, Village village, boolean done) {
        int load = load(v);
        if (!v.settlementsHauling) {
            if (load >= FULL_LOAD || pocketsFull(v) || done && load > 0 || v.settlementsDeliverNow && load > 0) {
                v.settlementsDeliverNow = false;
                v.settlementsHauling = true;
                Speech.say(v, "settlements.say.haul");
            } else {
                return false;
            }
        }
        if (!reach(v, village)) return true;
        var inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() instanceof ToolItem || s.getItem() instanceof ArmorItem || s.isFood()) continue;
            Workforce.addToTreasury(village, s.copy());
            inv.setStack(i, ItemStack.EMPTY);
        }
        v.swingHand(v.getDominantHand());
        village.markDirty();
        v.settlementsHauling = false;
        Speech.say(v, "settlements.say.haul.done");
        return true;
    }

    /** The builder picks up a load before building; true while he is on his way to the stores. */
    public static boolean fetch(VillagerEntityMCA v, Village village) {
        BlockPos s = store(v, village);
        if (v.settlementsCarry > 0 || s == null) return false;
        if (!s.isWithinDistance(v.getBlockPos(), 160)) {
            v.settlementsCarry = FULL_LOAD; // too far to walk back for every load
            return false;
        }
        if (!reach(v, village)) {
            Speech.say(v, "settlements.say.build.fetch");
            return true;
        }
        v.settlementsCarry = FULL_LOAD;
        v.swingHand(v.getDominantHand());
        return false;
    }
}
