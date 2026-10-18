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
    static final int FULL_LOAD = 48;

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
        // the hall's chest on its ground floor, not one up under the roof (they fell to their deaths going for that)
        if (!village.hallChests.isEmpty()) return village.hallChests.stream().min(java.util.Comparator.comparingInt(BlockPos::getY)).get();
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

    /** Lifts the lid of the chest at {@code p} for a moment, with its sound, as anybody using it would. */
    static void openChest(net.minecraft.server.world.ServerWorld world, BlockPos p) {
        if (p == null || !(world.getBlockState(p).getBlock() instanceof net.minecraft.block.ChestBlock chest)) return;
        world.addSyncedBlockEvent(p, chest, 1, 1);
        world.playSound(null, p, net.minecraft.sound.SoundEvents.BLOCK_CHEST_OPEN, net.minecraft.sound.SoundCategory.BLOCKS, 0.5f, 1f);
        OPEN.put(p.toImmutable(), world.getTime() + 25);
    }

    private static final java.util.Map<BlockPos, Long> OPEN = new java.util.HashMap<>();

    /** Every second: closes the lids lifted a moment ago. */
    public static void closeChests(net.minecraft.server.world.ServerWorld world) {
        for (var it = OPEN.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (world.getTime() < e.getValue()) continue;
            if (world.getBlockState(e.getKey()).getBlock() instanceof net.minecraft.block.ChestBlock chest) {
                world.addSyncedBlockEvent(e.getKey(), chest, 1, 0);
                world.playSound(null, e.getKey(), net.minecraft.sound.SoundEvents.BLOCK_CHEST_CLOSE, net.minecraft.sound.SoundCategory.BLOCKS, 0.5f, 1f);
            }
            it.remove();
        }
    }

    /** The chests of the village's stores (storehouse, granary, sawmill, mason's) and the hall's: what a player sees in them is the stock. */
    public static boolean storeChest(Village village, BlockPos pos) {
        if (village.hallChests.contains(pos)) return true;
        for (Built b : village.built) {
            String t = Buildings.base(b.type());
            if (!(t.equals("storage") || t.equals("granary") || t.equals("sawmill") || t.equals("mason"))) continue;
            if (!b.contains(pos)) continue;
            for (Blueprints.Step s : b.steps()) if (s.kind() == Blueprints.Kind.CHEST && b.point(s.dx(), s.dy(), s.dz()).equals(pos)) return true;
        }
        return false;
    }

    /** Walks to the stores. True once he stands at the chest. */
    public static boolean reach(VillagerEntityMCA v, Village village) {
        BlockPos s = store(v, village);
        if (s == null) return true; // no hall yet: the stores are wherever he is
        if (v.squaredDistanceTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5) > 9) {
            // (out of the mine by its stairs: his own route takes them, see Route; a step gone, he puts it back first)
            if (village.mineOrigin != null && v.getWorld() instanceof net.minecraft.server.world.ServerWorld sw && Mining.keepStairs(v, village, sw)) return false;
            Move.to(v, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0.6);
            return false;
        }
        v.getNavigation().stop();
        Gaze.at(v, s.getX() + 0.5, s.getY() + 0.5, s.getZ() + 0.5);
        return true;
    }

    static int load(VillagerEntityMCA v) {
        int n = 0;
        var inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.isDamageable() || s.getItem() instanceof ToolItem || s.getItem() instanceof ArmorItem || s.isFood() || Money.is(s)) continue;
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
                Doing.set(v, "settlements.doing.haul");
            } else {
                return false;
            }
        }
        Doing.set(v, "settlements.doing.haul");
        Anim.set(v, Anim.Act.CARRY, 30);
        holdLoad(v);
        if (!reach(v, village)) return true;
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld world) openChest(world, store(v, village));
        var inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.isDamageable() || s.getItem() instanceof ToolItem || s.getItem() instanceof ArmorItem || s.isFood() || Money.is(s)) continue;
            Workforce.addToTreasury(village, s.copy());
            inv.setStack(i, ItemStack.EMPTY);
        }
        v.swingHand(v.getDominantHand());
        Anim.clear(v);
        if (!(v.getMainHandStack().getItem() instanceof ToolItem)) v.setStackInHand(v.getDominantHand(), ItemStack.EMPTY);
        village.markDirty();
        v.settlementsHauling = false;
        Speech.say(v, "settlements.say.haul.done");
        return true;
    }

    /** The builder picks up a load before building; true while he is on his way to the stores. */
    public static boolean fetch(VillagerEntityMCA v, Village village) {
        BlockPos s = store(v, village);
        if (v.settlementsCarry > 0 || s == null) return false;
        Doing.set(v, "settlements.doing.fetch");
        if (!s.isWithinDistance(v.getBlockPos(), 160)) {
            v.settlementsCarry = FULL_LOAD; // too far to walk back for every load
            return false;
        }
        if (!reach(v, village)) {
            Speech.say(v, "settlements.say.build.fetch");
            return true;
        }
        v.settlementsCarry = FULL_LOAD;
        Workforce.provision(v, village); // at the store anyway: his food for the day along with the load
        v.swingHand(v.getDominantHand());
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld world) openChest(world, s);
        return false;
    }

    /** In his arms, for all to see, what he carries the most of (a copy: the load itself stays in his bag). */
    private static void holdLoad(VillagerEntityMCA v) {
        var inv = v.getInventory();
        java.util.Map<net.minecraft.item.Item, Integer> n = new java.util.HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && !s.isDamageable() && !s.isFood()) n.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        n.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).ifPresent(e -> {
            if (!v.getMainHandStack().isOf(e.getKey())) v.setStackInHand(v.getDominantHand(), new ItemStack(e.getKey()));
        });
    }
}
