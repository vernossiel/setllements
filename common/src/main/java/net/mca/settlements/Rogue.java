package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Not everyone in a village is honest. About one in eight grown villagers is a rogue: he works like the others, but
 * now and then, of an evening when nobody is looking, something from the stores finds its way into his pockets - and if
 * somebody sees him, the village puts him in the stocks like anyone else, and talks of it. And when a player is doing
 * time in the village's stocks or jail, a rogue may come up to him after dark, quietly, and offer to let him out, for
 * a price: mostly he keeps his word; sometimes a guard sees them at it; and sometimes he takes the money and laughs.
 */
public final class Rogue {
    private record Offer(UUID rogue, int price, long until) {
    }

    private static final Map<UUID, Offer> OFFERS = new HashMap<>(); // by player
    private static final Map<UUID, UUID> GOING_TO = new HashMap<>(); // rogue -> the prisoner he is making for
    private static final Map<UUID, Long> NEXT_THEFT = new HashMap<>();
    private static final Map<UUID, Long> STOCKED = new HashMap<>(); // rogue -> until (world time)

    private Rogue() {
    }

    /** One in eight grown villagers, always the same ones; never the elder or a guard. */
    public static boolean is(VillagerEntityMCA v) {
        if (v.isBaby() || v.isElder() || v.isGuard()) return false;
        return Math.floorMod(v.getUuid().getLeastSignificantBits() ^ 0x5DEECE66DL, 8L) == 0;
    }

    private static boolean dark(ServerWorld world) {
        long t = world.getTimeOfDay() % 24000L;
        return t >= 12500 && t <= 23000;
    }

    /** Once a second for every villager: true while his rogue's business (or his punishment) takes him up. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null) return false;
        Long stocked = STOCKED.get(v.getUuid());
        if (stocked != null) return stocks(v, village, world, stocked);
        if (!is(v)) return false;
        if (offer(v, village, world)) return true;
        return steal(v, village, world);
    }

    // ---------- in the stocks himself ----------

    private static final Map<UUID, Integer> PICKING = new HashMap<>(); // seconds a player has been working the board loose

    private static boolean stocks(VillagerEntityMCA v, Village village, ServerWorld world, long until) {
        if (world.getTime() >= until) {
            STOCKED.remove(v.getUuid());
            Seat.rise(v);
            Speech.sayNow(v, "settlements.say.rogue.released");
            return false;
        }
        BlockPos at = Justice.pillory(world, village);
        if (v.isSleeping()) v.wakeUp();
        if (!v.hasVehicle()) Seat.kneel(v, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180f); // on his knees, head and hands through the board
        v.getNavigation().stop();
        Doing.set(v, "settlements.doing.rogue.stocks");
        // a player crouched by the board, working at the pin (a few seconds): he is let out
        net.minecraft.server.network.ServerPlayerEntity helper = (net.minecraft.server.network.ServerPlayerEntity) world.getClosestPlayer(v, 2.5);
        if (helper != null && helper.isSneaking() && !helper.isSpectator()) {
            int t = PICKING.merge(v.getUuid(), 1, Integer::sum);
            if (t == 1) Speech.sayNow(v, "settlements.say.rogue.freeing");
            if (t >= 3) {
                PICKING.remove(v.getUuid());
                freedBy(v, village, world, helper);
                return false;
            }
        } else PICKING.remove(v.getUuid());
        if (world.random.nextInt(60) == 0) Speech.sayAny(v, "settlements.say.rogue.stocks", 3);
        return true;
    }

    private static final Map<UUID, UUID> OWES = new HashMap<>(); // rogue -> the player who let him out

    /** Let out of the stocks by a player: off he goes, owing him; if anyone saw it, the player answers for it. */
    private static void freedBy(VillagerEntityMCA v, Village village, ServerWorld world, net.minecraft.server.network.ServerPlayerEntity p) {
        STOCKED.remove(v.getUuid());
        Seat.rise(v);
        OWES.put(v.getUuid(), p.getUuid());
        Speech.sayNow(v, "settlements.say.rogue.thanks", p.getName());
        // a little of what he had put by, for his saviour
        int coins = Math.max(0, v.settlementsPurse / 2);
        if (coins >= 10) p.giveItemStack(new ItemStack(Items.EMERALD, coins / 10));
        v.settlementsPurse -= coins;
        boolean seen = !world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(14, 4, 14),
                o -> o != v && o.isAlive() && !o.isSleeping() && !o.isBaby() && o.canSee(p)).isEmpty();
        if (seen) {
            Justice.report(world, village, p, Justice.Crime.HARASSMENT, 10, new java.util.HashMap<>(), true);
            Life.news(village, "rogue.freed", v.getName().getString(), p.getName().getString());
        } else {
            Life.news(village, "rogue.vanished", v.getName().getString());
        }
    }

    /** The rogue a player let out of the stocks owes him: he tips him off (see Talk) and offers him a hand. */
    public static boolean owes(VillagerEntityMCA v, net.minecraft.entity.player.PlayerEntity p) {
        return p.getUuid().equals(OWES.get(v.getUuid()));
    }

    // ---------- a little something from the stores ----------

    private static boolean steal(VillagerEntityMCA v, Village village, ServerWorld world) {
        long now = world.getTime();
        long next = NEXT_THEFT.computeIfAbsent(v.getUuid(), k -> now + 24000L + world.random.nextInt(48000));
        if (now < next || !dark(world) || village.storageBuffer.isEmpty()) return false;
        BlockPos store = Haul.store(v, village);
        if (store == null) return false;
        if (Watch.near(world, store)) return false; // the watchman is about: not tonight
        Doing.set(v, "settlements.doing.rogue.sneak");
        if (v.isSleeping()) v.wakeUp();
        if (v.squaredDistanceTo(Vec3d.ofCenter(store)) > 9) {
            Move.to(v, store.getX() + 0.5, store.getY(), store.getZ() + 0.5, 0.45);
            if (now - next > 20 * 120) NEXT_THEFT.put(v.getUuid(), now + 24000L); // could not get there: another night
            return true;
        }
        NEXT_THEFT.put(v.getUuid(), now + 24000L * (2 + world.random.nextInt(3)));
        // the best of what lies to hand: emeralds, iron, meat, bread
        ItemStack best = null;
        for (ItemStack s : village.storageBuffer) {
            if (s.isEmpty()) continue;
            if (best == null || Reputation.value(new ItemStack(s.getItem())) > Reputation.value(new ItemStack(best.getItem()))) best = s;
        }
        if (best == null) return false;
        int n = Math.min(best.getCount(), 1 + world.random.nextInt(4));
        ItemStack took = best.split(n);
        village.storageBuffer.removeIf(ItemStack::isEmpty);
        village.markDirty();
        v.getInventory().addStack(took);
        // was he seen?
        VillagerEntityMCA witness = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(12, 4, 12),
                o -> o != v && o.isAlive() && !o.isSleeping() && !o.isBaby() && o.canSee(v)).stream().findFirst().orElse(null);
        if (witness != null) {
            Speech.sayNow(witness, "settlements.say.rogue.caught", v.getName());
            // the goods back where they belong
            int back = n;
            for (int i = 0; i < v.getInventory().size() && back > 0; i++) {
                ItemStack s = v.getInventory().getStack(i);
                if (!ItemStack.canCombine(s, took)) continue;
                int d = Math.min(back, s.getCount());
                s.decrement(d);
                back -= d;
            }
            Workforce.addToTreasury(village, new ItemStack(took.getItem(), n - back));
            STOCKED.put(v.getUuid(), now + 20 * 180);
            Life.news(village, "rogue.caught", v.getName().getString());
        } else {
            Life.news(village, "rogue.missing");
        }
        return true;
    }

    // ---------- a way out, for a price ----------

    private static boolean offer(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (!dark(world)) {
            GOING_TO.remove(v.getUuid());
            return false;
        }
        ServerPlayerEntity target = null;
        UUID going = GOING_TO.get(v.getUuid());
        for (ServerPlayerEntity p : Justice.prisoners(world, village)) {
            if (going != null && !p.getUuid().equals(going)) continue;
            if (OFFERS.containsKey(p.getUuid()) || Justice.secondsLeft(p) < 40) continue;
            target = p;
            break;
        }
        if (target == null) {
            GOING_TO.remove(v.getUuid());
            return false;
        }
        if (going == null) {
            if (world.random.nextInt(30) != 0) return false; // he thinks it over a while first
            GOING_TO.put(v.getUuid(), target.getUuid());
        }
        if (v.isSleeping()) v.wakeUp();
        Doing.set(v, "settlements.doing.rogue.sneak");
        if (v.squaredDistanceTo(target) > 2.5 * 2.5) {
            Move.to(v, target.getX(), target.getY(), target.getZ(), 0.45);
            return true;
        }
        v.getNavigation().stop();
        Gaze.at(v, target, 30f, 30f);
        GOING_TO.remove(v.getUuid());
        Justice.markOffered(target);
        int price = owes(v, target) ? 0 : 4 + Justice.secondsLeft(target) / 30; // one he owes, he lets out for nothing
        OFFERS.put(target.getUuid(), new Offer(v.getUuid(), price, world.getTime() + 20 * 30));
        List<Text> labels = List.of(
                Text.translatable("settlements.rogue.button.accept", price),
                Text.translatable("settlements.rogue.button.refuse"));
        net.mca.cobalt.network.NetworkHandler.sendToPlayer(new net.mca.network.s2c.JusticeScreenRequest(v.getId(), v.getName(),
                List.of(Text.translatable("settlements.rogue.offer", target.getName(), price)), new String[]{"rogue accept", "rogue refuse"}, labels, 30), target);
        return true;
    }

    /** /settle rogue accept|refuse */
    public static int answer(ServerPlayerEntity p, boolean accept) {
        Offer o = OFFERS.remove(p.getUuid());
        ServerWorld world = p.getServerWorld();
        if (o == null || world.getTime() > o.until() || !Justice.serving(p)) return 0;
        VillagerEntityMCA rogue = world.getEntity(o.rogue()) instanceof VillagerEntityMCA r ? r : null;
        if (rogue == null) return 0;
        if (!accept) {
            Speech.sayNow(rogue, "settlements.say.rogue.refused");
            return 1;
        }
        if (o.price() > 0 && p.getInventory().count(Items.EMERALD) < o.price()) {
            Speech.sayNow(rogue, "settlements.say.rogue.nomoney");
            return 0;
        }
        int left = o.price();
        for (int i = 0; i < p.getInventory().size() && left > 0; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isOf(Items.EMERALD)) continue;
            int d = Math.min(left, s.getCount());
            s.decrement(d);
            left -= d;
        }
        if (o.price() > 0) rogue.getInventory().addStack(new ItemStack(Items.EMERALD, o.price()));
        double roll = world.random.nextDouble();
        if (o.price() == 0) roll = Math.max(roll, 0.15); // a friend does not cheat him
        Village village = Village.findNearest(rogue).orElse(null);
        if (roll < 0.15) { // took the money and laughed
            Speech.sayNow(rogue, "settlements.say.rogue.cheat");
            return 1;
        }
        // he works the board loose; a guard about and awake may see them at it
        boolean guardNear = !world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(14, 4, 14),
                g -> g.isGuard() && g.isAlive() && !g.isSleeping() && g.canSee(p)).isEmpty();
        boolean seen = guardNear ? roll < 0.6 : roll < 0.25;
        rogue.swingHand(rogue.getDominantHand());
        Speech.sayNow(rogue, seen ? "settlements.say.rogue.run" : "settlements.say.rogue.done");
        Justice.broken(p, seen);
        if (seen && village != null) {
            STOCKED.put(rogue.getUuid(), world.getTime() + 20 * 240);
            Life.news(village, "rogue.helped", rogue.getName().getString(), p.getName().getString());
        }
        return 1;
    }
}
