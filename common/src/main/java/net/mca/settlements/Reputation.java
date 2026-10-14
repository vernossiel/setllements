package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The village chests are windows into the treasury. Opening one fills it from the treasury, closing it puts everything
 * back. What the player put in is a donation (reputation, and with it trade discounts), what the player took is theft:
 * if a villager saw it the guards demand the goods back, and refusing makes the guards hostile.
 */
public final class Reputation {
    private static final int DEMAND_SECONDS = 90;
    private static final double WITNESS_RANGE = 14;

    private record Session(Village village, BlockPos pos, Map<Item, Integer> before) {
    }

    private static final class Debt {
        double value;
        long deadline;
        int villageId;
        long lastShout;
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Debt> DEBTS = new HashMap<>();

    private Reputation() {
    }

    // ---------- item values ----------

    public static double value(ItemStack s) {
        Item i = s.getItem();
        double unit;
        if (i == Items.EMERALD) unit = 8;
        else if (i == Items.DIAMOND) unit = 20;
        else if (i == Items.NETHERITE_INGOT) unit = 60;
        else if (i == Items.GOLD_INGOT) unit = 4;
        else if (i == Items.IRON_INGOT) unit = 3;
        else if (i == Items.COAL || i == Items.CHARCOAL) unit = 1;
        else if (s.isFood()) unit = 1;
        else if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) unit = 0.5;
        else if (s.isIn(net.minecraft.registry.tag.ItemTags.WOOL)) unit = 1;
        else if (i == Items.COBBLESTONE) unit = 0.15;
        else unit = 0.5;
        return unit * s.getCount();
    }

    private static Map<Item, Integer> counts(Inventory inv) {
        Map<Item, Integer> m = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return m;
    }

    // ---------- chest as treasury window ----------

    private static ChestBlockEntity villageChest(ScreenHandler menu, Village village) {
        if (menu instanceof GenericContainerScreenHandler g && g.getInventory() instanceof ChestBlockEntity chest
                && village.lootChests.contains(chest.getPos())) {
            return chest;
        }
        return null;
    }

    private static void sweepIntoTreasury(Village village, Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                Workforce.addToTreasury(village, s.copy());
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    public static void onOpen(ServerPlayerEntity player, ScreenHandler menu) {
        Village village = Village.findNearest(player).orElse(null);
        if (village == null) return;
        ChestBlockEntity chest = villageChest(menu, village);
        if (chest == null) return;
        sweepIntoTreasury(village, chest);
        int slot = 0;
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext() && slot < chest.size(); ) {
            ItemStack s = it.next();
            chest.setStack(slot++, s.copy());
            it.remove();
        }
        village.markDirty();
        SESSIONS.put(player.getUuid(), new Session(village, chest.getPos(), counts(chest)));
    }

    public static void onClose(ServerPlayerEntity player, ScreenHandler menu) {
        Session session = SESSIONS.remove(player.getUuid());
        if (session == null) return;
        Village village = session.village();
        ChestBlockEntity chest = villageChest(menu, village);
        if (chest == null) return;
        Map<Item, Integer> after = counts(chest);
        double donated = 0, taken = 0;
        Map<Item, Integer> before = session.before();
        for (Map.Entry<Item, Integer> e : before.entrySet()) {
            int diff = after.getOrDefault(e.getKey(), 0) - e.getValue();
            if (diff < 0) taken += value(new ItemStack(e.getKey(), -diff));
        }
        for (Map.Entry<Item, Integer> e : after.entrySet()) {
            int diff = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (diff > 0) donated += value(new ItemStack(e.getKey(), diff));
        }
        sweepIntoTreasury(village, chest);
        village.markDirty();

        if (donated >= 3) donate(player, village, donated);
        if (taken >= 1) steal(player, village, taken);
    }

    private static void donate(ServerPlayerEntity player, Village village, double value) {
        int hearts = (int) Math.min(30, value / 3);
        Debt debt = DEBTS.get(player.getUuid());
        if (debt != null) {
            debt.value -= value;
            if (debt.value <= 0) {
                DEBTS.remove(player.getUuid());
                village.pushHearts(player, 10);
                player.sendMessage(Text.translatable("settlements.theft.settled"), false);
                return;
            }
            player.sendMessage(Text.translatable("settlements.theft.partial", (int) Math.ceil(debt.value)), false);
            return;
        }
        if (hearts > 0) {
            village.pushHearts(player, hearts);
            player.sendMessage(Text.translatable("settlements.donate", hearts), true);
        }
    }

    private static void steal(ServerPlayerEntity player, Village village, double value) {
        VillagerEntityMCA witness = null;
        double best = WITNESS_RANGE * WITNESS_RANGE;
        for (VillagerEntityMCA v : player.getWorld().getEntitiesByClass(VillagerEntityMCA.class, player.getBoundingBox().expand(WITNESS_RANGE),
                o -> !o.isBaby() && !o.isSleeping() && o.isAlive())) {
            double d = v.squaredDistanceTo(player);
            if (d < best && v.canSee(player)) {
                best = d;
                witness = v;
            }
        }
        if (witness == null) return; // nobody saw it
        village.pushHearts(player, -(int) Math.ceil(value * 1.5));
        Debt debt = DEBTS.computeIfAbsent(player.getUuid(), k -> new Debt());
        debt.value += value;
        debt.deadline = player.getWorld().getTime() + DEMAND_SECONDS * 20L;
        debt.villageId = village.getId();
        witness.sendChatMessage(player, "settlements.theft.seen");
    }

    // ---------- guards react ----------

    /** Once per second per world. */
    public static void tick(ServerWorld world) {
        if (DEBTS.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Debt>> it = DEBTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Debt> e = it.next();
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(e.getKey());
            if (player == null || player.getWorld() != world) continue;
            Debt debt = e.getValue();
            var guards = world.getEntitiesByClass(VillagerEntityMCA.class, player.getBoundingBox().expand(40), g -> g.isGuard() && g.isAlive());
            if (world.getTime() >= debt.deadline) {
                it.remove();
                Village.findNearest(player).ifPresent(v -> v.pushHearts(player, -(int) Math.ceil(debt.value * 3)));
                for (VillagerEntityMCA g : guards) g.getBrain().remember(MemoryModuleType.ATTACK_TARGET, player, 1200);
                player.sendMessage(Text.translatable("settlements.theft.refused"), false);
                continue;
            }
            VillagerEntityMCA nearest = guards.stream().min(java.util.Comparator.comparingDouble(g -> g.squaredDistanceTo(player))).orElse(null);
            if (nearest == null) continue;
            nearest.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(player, 0.7f, 2));
            if (world.getTime() - debt.lastShout > 300 && nearest.squaredDistanceTo(player) < 100) {
                debt.lastShout = world.getTime();
                nearest.sendChatMessage(player, "settlements.theft.demand");
            }
        }
    }
}
