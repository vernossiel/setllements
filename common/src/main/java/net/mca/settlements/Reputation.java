package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
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
        final Map<Item, Integer> stolen = new HashMap<>();
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

    // ---------- village chests: a small display stock ----------

    /** A village chest the player has open (single or double): its inventory and where it stands, or null. */
    private record Opened(Inventory inv, BlockPos pos) {
    }

    private static Opened villageChest(ScreenHandler menu, Village village, BlockPos near) {
        if (!(menu instanceof GenericContainerScreenHandler g)) return null;
        Inventory inv = g.getInventory();
        if (inv instanceof ChestBlockEntity chest) {
            if (village.lootChests.contains(chest.getPos()) || Haul.storeChest(village, chest.getPos())) return new Opened(inv, chest.getPos());
            return null;
        }
        if (inv instanceof net.minecraft.inventory.DoubleInventory dbl) {
            ServerWorld world = village.getWorld();
            if (world == null) return null;
            for (BlockPos p : village.hallChests) if (world.getBlockEntity(p) instanceof ChestBlockEntity c && dbl.isPart(c)) return new Opened(inv, p);
            if (near != null) {
                for (BlockPos p : BlockPos.iterate(near.add(-6, -3, -6), near.add(6, 3, 6))) {
                    if (world.getBlockEntity(p) instanceof ChestBlockEntity c && dbl.isPart(c) && Haul.storeChest(village, p)) return new Opened(inv, p.toImmutable());
                }
            }
            for (BlockPos p : village.lootChests) if (world.getBlockEntity(p) instanceof ChestBlockEntity c && dbl.isPart(c)) return new Opened(inv, p);
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
        Opened opened = villageChest(menu, village, player.getBlockPos());
        if (opened == null) return;
        Inventory chest = opened.inv();
        if (Haul.storeChest(village, opened.pos())) {
            // the store chests are windows into the stores: what is in the treasury is what you see
            sweepIntoTreasury(village, chest);
            int slot = 0;
            for (java.util.Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext() && slot < chest.size(); ) {
                chest.setStack(slot++, it.next().copy());
                it.remove();
            }
            village.markDirty();
        }
        SESSIONS.put(player.getUuid(), new Session(village, opened.pos(), counts(chest)));
    }

    public static void onClose(ServerPlayerEntity player, ScreenHandler menu) {
        Session session = SESSIONS.remove(player.getUuid());
        if (session == null) return;
        Village village = session.village();
        Opened opened = villageChest(menu, village, player.getBlockPos());
        if (opened == null) return;
        Inventory chest = opened.inv();
        Map<Item, Integer> after = counts(chest);
        double returned = 0, taken = 0;
        Map<Item, Integer> takenItems = new HashMap<>();
        Map<Item, Integer> before = session.before();
        for (Map.Entry<Item, Integer> e : before.entrySet()) {
            int diff = after.getOrDefault(e.getKey(), 0) - e.getValue();
            if (diff < 0) {
                taken += value(new ItemStack(e.getKey(), -diff));
                takenItems.put(e.getKey(), -diff);
            }
        }
        for (Map.Entry<Item, Integer> e : after.entrySet()) {
            int diff = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (diff > 0) returned += value(new ItemStack(e.getKey(), diff));
        }
        if (Haul.storeChest(village, opened.pos())) {
            sweepIntoTreasury(village, chest); // whatever is left goes back into the stores
            village.markDirty();
        }
        if (returned > 0 && Haul.storeChest(village, opened.pos())) Orders.brought(player, village, before, after);
        if (returned > 0 && Justice.owes(player)) Remember.note(village, player, "returned");
        if (returned > 0) settleDebt(player, village, returned);
        if (taken >= 1) steal(player, village, taken, takenItems);
    }

    /** Putting goods back only matters when the player owes something. There is no reward for stuffing chests. */
    private static void settleDebt(ServerPlayerEntity player, Village village, double value) {
        Justice.credit(player, value);
    }

    /** Idle villagers restock the display chests a little at a time from the treasury, common goods only. */
    public static void restock(ServerWorld world, Village village) {
        for (BlockPos pos : village.lootChests) {
            if (!world.isChunkLoaded(pos) || !(world.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
            int total = 0;
            for (int i = 0; i < chest.size(); i++) total += chest.getStack(i).getCount();
            if (total >= 48) continue;
            for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
                ItemStack s = it.next();
                if (value(new ItemStack(s.getItem())) >= 3) continue; // gems and ingots stay in the treasury
                int move = Math.min(8, s.getCount());
                ItemStack part = s.copy();
                part.setCount(move);
                for (int i = 0; i < chest.size() && !part.isEmpty(); i++) {
                    ItemStack slot = chest.getStack(i);
                    if (slot.isEmpty()) {
                        chest.setStack(i, part.copy());
                        part.setCount(0);
                    } else if (ItemStack.canCombine(slot, part) && slot.getCount() + part.getCount() <= slot.getMaxCount()) {
                        slot.increment(part.getCount());
                        part.setCount(0);
                    }
                }
                if (part.isEmpty()) {
                    s.decrement(move);
                    if (s.isEmpty()) it.remove();
                    village.markDirty();
                    break;
                }
            }
        }
    }

    private static void steal(ServerPlayerEntity player, Village village, double value, Map<Item, Integer> items) {
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
        if (!(player.getWorld() instanceof ServerWorld sw)) return;
        if (witness == null) { // nobody saw it: found out later, perhaps
            Justice.suspect(sw, village, player, Justice.Crime.THEFT, value, items, player.getBlockPos());
            return;
        }
        if (!witness.isGuard()) {
            Speech.say(witness, "settlements.say.report.thief");
            GuardIntel.alertNearestGuard(sw, witness, player);
        }
        village.pushHearts(player, -(int) Math.ceil(value * 1.5));
        Remember.note(village, player, "stole");
        witness.sendChatMessage(player, "settlements.theft.seen");
        Justice.report(sw, village, player, Justice.Crime.THEFT, value, items, true);
    }

    // ---------- guards react ----------

    /** Once per second per world: the village's justice takes its course. */
    public static void tick(ServerWorld world) {
        Justice.tick(world);
        Doing.tick(world);
    }

    /** /settle return: hands the stolen goods back from the player's inventory. */
    public static int returnGoods(ServerPlayerEntity player) {
        return Justice.giveBack(player);
    }

    /** /settle refuse: the demand is turned down, the guards act at once. */
    public static int refuse(ServerPlayerEntity player) {
        return Justice.resist(player);
    }


    // ---------- visualization ----------

    public static final int REP_MIN = -100, REP_MAX = 150;
    private static final Map<UUID, int[]> HUD = new HashMap<>();

    /** 0 enemy .. 6 hero. */
    public static int tier(int rep) {
        if (rep < -100) return 0;
        if (rep < -30) return 1;
        if (rep < 0) return 2;
        if (rep < 20) return 3;
        if (rep < 60) return 4;
        if (rep < 120) return 5;
        return 6;
    }

    private static net.minecraft.util.Formatting tierColor(int tier) {
        return switch (tier) {
            case 0 -> net.minecraft.util.Formatting.DARK_RED;
            case 1 -> net.minecraft.util.Formatting.RED;
            case 2 -> net.minecraft.util.Formatting.GOLD;
            case 3 -> net.minecraft.util.Formatting.GRAY;
            case 4 -> net.minecraft.util.Formatting.GREEN;
            case 5 -> net.minecraft.util.Formatting.DARK_GREEN;
            default -> net.minecraft.util.Formatting.AQUA;
        };
    }

    public static Text tierName(int rep) {
        int t = tier(rep);
        return Text.translatable("settlements.rep.tier." + t).formatted(tierColor(t));
    }

    /** A ten-cell bar from enemy to hero. */
    public static Text bar(int rep) {
        int cells = 10;
        int filled = Math.max(0, Math.min(cells, (int) Math.round((rep - REP_MIN) / (double) (REP_MAX - REP_MIN) * cells)));
        net.minecraft.text.MutableText t = Text.empty();
        t.append(Text.literal("\u25A0".repeat(filled)).formatted(tierColor(tier(rep))));
        t.append(Text.literal("\u25A1".repeat(cells - filled)).formatted(net.minecraft.util.Formatting.DARK_GRAY));
        return t;
    }

    /** Chat: where the player stands with the village they are in. */
    public static int showStanding(ServerPlayerEntity player) {
        Village v = VillageManager.get(player.getServerWorld()).findNearestVillage(player).orElse(null);
        if (v == null) {
            player.sendMessage(Text.translatable("settlements.rep.novillage"), false);
            return 0;
        }
        int rep = v.getReputation(player);
        player.sendMessage(Text.translatable("settlements.rep.header", v.getName()).formatted(net.minecraft.util.Formatting.GOLD), false);
        player.sendMessage(Text.empty().append(bar(rep)).append(Text.literal("  ")).append(tierName(rep)).append(Text.literal(" (" + rep + ")")), false);
        player.sendMessage(Text.translatable("settlements.rep.hint").formatted(net.minecraft.util.Formatting.GRAY), false);
        return 1;
    }

    /** Every two seconds: tell players when they enter a village and when their standing changes. */
    public static void hud(ServerWorld world) {
        VillageManager vm = VillageManager.get(world);
        for (ServerPlayerEntity p : world.getPlayers()) {
            Village v = vm.findNearestVillage(p).filter(vil -> vil.isWithinBorder(p)).orElse(null);
            int[] st = HUD.get(p.getUuid());
            if (v == null) {
                HUD.put(p.getUuid(), new int[]{-1, 0});
                continue;
            }
            int rep = v.getReputation(p);
            if (st == null || st[0] != v.getId()) {
                p.sendMessage(Text.empty().append(Text.literal(v.getName() + " \u00B7 ").formatted(net.minecraft.util.Formatting.GOLD))
                        .append(Text.translatable("settlements.level." + Plan.level(v).name().toLowerCase())).append(Text.literal(" \u00B7 "))
                        .append(bar(rep)).append(Text.literal(" ")).append(tierName(rep)), true);
            } else if (rep != st[1]) {
                int d = rep - st[1];
                p.sendMessage(Text.empty().append(Text.literal((d > 0 ? "+" : "") + d + " ").formatted(d > 0 ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.RED))
                        .append(Text.translatable("settlements.rep.word")).append(Text.literal(": ")).append(bar(rep)).append(Text.literal(" ")).append(tierName(rep)), true);
            }
            HUD.put(p.getUuid(), new int[]{v.getId(), rep});
        }
    }
}
