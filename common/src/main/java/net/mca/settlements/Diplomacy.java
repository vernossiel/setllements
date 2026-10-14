package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Once per day neighbouring villages compare their stocks. Surplus goes to the village that lacks it (caravans),
 * which warms relations. Villages competing for the same scarce resource grow hostile, and a hostile village
 * that is stronger than its neighbour may raid it: loot is taken and a house is set on fire.
 * Everything is resolved on the strategic level, nothing here needs the villages to be loaded.
 */
public final class Diplomacy {
    private static final int NEIGHBOUR_RANGE = 700;
    private static final int RAID_RELATION = -40;
    private static final int TRADE_RELATION = -20;

    private enum Cat {
        WOOD(s -> s.isIn(ItemTags.LOGS), 60, 8, "wood"),
        STONE(s -> s.isOf(Items.COBBLESTONE), 100, 10, "stone"),
        FOOD(s -> s.isFood() && Workforce.eatable(s), 40, 6, "food"),
        WOOL(s -> s.isIn(ItemTags.WOOL), 12, 1, "wool"),
        IRON(s -> s.isOf(Items.IRON_INGOT), 10, 2, "iron");

        final Predicate<ItemStack> match;
        final int base, perVillager;
        final String key;

        Cat(Predicate<ItemStack> match, int base, int perVillager, String key) {
            this.match = match;
            this.base = base;
            this.perVillager = perVillager;
            this.key = key;
        }
    }

    private Diplomacy() {
    }

    private static int stock(Village v, Cat c) {
        int n = 0;
        for (ItemStack s : v.storageBuffer) if (c.match.test(s)) n += s.getCount();
        return n;
    }

    private static int target(Village v, Cat c) {
        return c.base + c.perVillager * v.getPopulation();
    }

    private static double distance(Village a, Village b) {
        var ca = a.getCenter();
        var cb = b.getCenter();
        double dx = ca.getX() - cb.getX(), dz = ca.getZ() - cb.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int relation(Village a, Village b) {
        return a.relations.getOrDefault(b.getId(), 0);
    }

    private static void shift(Village a, Village b, int delta) {
        int r = Math.max(-100, Math.min(100, relation(a, b) + delta));
        a.relations.put(b.getId(), r);
        b.relations.put(a.getId(), r);
        a.markDirty();
    }

    /** Once per in-game day. */
    public static void daily(ServerWorld world) {
        List<Village> villages = new ArrayList<>();
        for (Village v : VillageManager.get(world)) villages.add(v);
        for (int i = 0; i < villages.size(); i++) {
            for (int j = i + 1; j < villages.size(); j++) {
                Village a = villages.get(i), b = villages.get(j);
                if (distance(a, b) > NEIGHBOUR_RANGE) continue;
                meet(world, a, b);
            }
        }
    }

    private static void meet(ServerWorld world, Village a, Village b) {
        // relations drift back to neutral
        int r = relation(a, b);
        if (r != 0) shift(a, b, r > 0 ? -1 : 1);

        boolean traded = false;
        boolean competing = false;
        for (Cat c : Cat.values()) {
            int sa = stock(a, c), sb = stock(b, c);
            int ta = target(a, c), tb = target(b, c);
            if (sa < ta && sb < tb) competing = true;
            if (relation(a, b) < TRADE_RELATION) continue;
            if (sa > ta * 1.5 && sb < tb) traded |= send(world, a, b, c, Math.min((int) (sa - ta * 1.5), tb - sb));
            else if (sb > tb * 1.5 && sa < ta) traded |= send(world, b, a, c, Math.min((int) (sb - tb * 1.5), ta - sa));
        }
        if (traded) shift(a, b, 1);
        else if (competing) shift(a, b, -3);

        if (relation(a, b) <= RAID_RELATION) maybeRaid(world, a, b);
    }

    private static boolean send(ServerWorld world, Village from, Village to, Cat cat, int amount) {
        amount = Math.min(amount, 64);
        if (amount <= 0) return false;
        int left = amount, moved = 0;
        List<ItemStack> cargo = new ArrayList<>();
        for (Iterator<ItemStack> it = from.storageBuffer.iterator(); it.hasNext() && left > 0; ) {
            ItemStack s = it.next();
            if (!cat.match.test(s)) continue;
            int d = Math.min(left, s.getCount());
            ItemStack part = s.copy();
            part.setCount(d);
            s.decrement(d);
            left -= d;
            moved += d;
            cargo.add(part);
            if (s.isEmpty()) it.remove();
        }
        if (moved == 0) return false;
        from.markDirty();
        Caravans.launch(world, from, to, cargo);
        announce(world, from, to, Text.translatable("settlements.caravan", from.getName(), to.getName(), moved, Text.translatable("settlements.cat." + cat.key)));
        return true;
    }

    public static void shiftRelation(Village a, Village b, int delta) {
        shift(a, b, delta);
    }

    public static void announceNear(ServerWorld world, Village v, Text text) {
        announce(world, v, v, text);
    }

    // ---------- raids ----------

    private static double military(ServerWorld world, Village v) {
        double sum = 0;
        for (VillagerEntityMCA r : v.getResidents(world)) {
            if (!r.isGuard()) continue;
            sum += 1;
            if (r.getEquippedStack(EquipmentSlot.CHEST).getItem() instanceof ArmorItem a) sum += a.getMaterial().getProtection(ArmorItem.Type.CHESTPLATE) / 4.0;
        }
        return Math.max(sum, v.getPopulation() / 6.0);
    }

    private record PendingRaid(int attackerId, int defenderId, long resolveAt) {
    }

    private static final List<PendingRaid> PENDING = new ArrayList<>();

    private static void maybeRaid(ServerWorld world, Village a, Village b) {
        if (world.random.nextFloat() > 0.35f) return;
        double ma = military(world, a), mb = military(world, b);
        Village attacker = ma >= mb ? a : b, defender = attacker == a ? b : a;
        double att = Math.max(ma, mb), def = Math.min(ma, mb) * 1.2;
        if (att < def * 1.1) return; // not strong enough to risk it
        for (PendingRaid r : PENDING) if (r.defenderId() == defender.getId()) return;
        PENDING.add(new PendingRaid(attacker.getId(), defender.getId(), world.getTime() + 3600));
        announce(world, attacker, defender, Text.translatable("settlements.raid.start", attacker.getName(), defender.getName()));
    }

    /** Players near a village count as fighters for that side: 2 each plus a bonus for chest armor. */
    private static double playerStrength(ServerWorld world, Village v) {
        double sum = 0;
        var c = v.getCenter();
        for (var p : world.getPlayers()) {
            if (p.isSpectator() || p.squaredDistanceTo(c.getX(), p.getY(), c.getZ()) > 90 * 90) continue;
            sum += 2;
            if (p.getEquippedStack(EquipmentSlot.CHEST).getItem() instanceof ArmorItem a) sum += a.getMaterial().getProtection(ArmorItem.Type.CHESTPLATE) / 4.0;
        }
        return sum;
    }

    /** Once per second per world: raids announced earlier come to a head, with time for players to take a side. */
    public static void tickRaids(ServerWorld world) {
        if (PENDING.isEmpty()) return;
        VillageManager vm = VillageManager.get(world);
        for (Iterator<PendingRaid> it = PENDING.iterator(); it.hasNext(); ) {
            PendingRaid r = it.next();
            if (world.getTime() < r.resolveAt()) continue;
            it.remove();
            Village attacker = vm.getOrEmpty(r.attackerId()).orElse(null), defender = vm.getOrEmpty(r.defenderId()).orElse(null);
            if (attacker == null || defender == null) continue;
            double att = military(world, attacker) + playerStrength(world, attacker);
            double def = (military(world, defender) + playerStrength(world, defender)) * 1.2;
            double roll = att * (0.8 + world.random.nextDouble() * 0.4);
            boolean won = roll > def;
            if (won) {
                loot(attacker, defender);
                burnHouse(world, defender);
                announce(world, attacker, defender, Text.translatable("settlements.raid.won", attacker.getName(), defender.getName()));
                shift(attacker, defender, -20);
            } else {
                announce(world, attacker, defender, Text.translatable("settlements.raid.failed", attacker.getName(), defender.getName()));
                shift(attacker, defender, -5);
            }
            rewardPlayers(world, attacker, defender, won);
        }
    }

    private static void rewardPlayers(ServerWorld world, Village attacker, Village defender, boolean attackerWon) {
        var ca = attacker.getCenter();
        var cd = defender.getCenter();
        for (var p : world.getPlayers()) {
            boolean withDefender = p.squaredDistanceTo(cd.getX(), p.getY(), cd.getZ()) <= 90 * 90;
            boolean withAttacker = p.squaredDistanceTo(ca.getX(), p.getY(), ca.getZ()) <= 90 * 90;
            if (withDefender && !withAttacker) {
                if (attackerWon) {
                    defender.pushHearts(p, 5);
                } else {
                    defender.pushHearts(p, 30);
                    attacker.pushHearts(p, -20);
                    p.sendMessage(Text.translatable("settlements.raid.defended", defender.getName()), false);
                }
            } else if (withAttacker && !withDefender) {
                if (attackerWon) {
                    attacker.pushHearts(p, 30);
                    defender.pushHearts(p, -40);
                    p.giveItemStack(new ItemStack(Items.EMERALD, 5));
                    p.sendMessage(Text.translatable("settlements.raid.shared", attacker.getName()), false);
                } else {
                    attacker.pushHearts(p, -10);
                }
            }
        }
    }

    private static void loot(Village winner, Village loser) {
        for (Iterator<ItemStack> it = loser.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            int take = (s.getCount() + 3) / 4;
            ItemStack part = s.copy();
            part.setCount(take);
            s.decrement(take);
            Workforce.addToTreasury(winner, part);
            if (s.isEmpty()) it.remove();
        }
        loser.markDirty();
        winner.markDirty();
    }

    private static void burnHouse(ServerWorld world, Village loser) {
        if (loser.houses.isEmpty()) return;
        var h = loser.houses.get(world.random.nextInt(loser.houses.size()));
        BlockPos roof = h.origin().add(h.fw() / 2, h.floors() * Blueprints.LEVEL_HEIGHT + 2, h.fd() / 2);
        if (world.isChunkLoaded(roof) && world.getBlockState(roof).isAir()) world.setBlockState(roof, Blocks.FIRE.getDefaultState());
    }

    private static void announce(ServerWorld world, Village a, Village b, Text text) {
        var ca = a.getCenter();
        var cb = b.getCenter();
        for (var p : world.getPlayers()) {
            boolean nearA = p.squaredDistanceTo(ca.getX(), p.getY(), ca.getZ()) < 250 * 250;
            boolean nearB = p.squaredDistanceTo(cb.getX(), p.getY(), cb.getZ()) < 250 * 250;
            if (nearA || nearB) p.sendMessage(text, false);
        }
    }
}
