package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A villager who catches the player stealing, with no guard by, has it out with him first, face to face: put it back,
 * or make it worth his while to have seen nothing (he may not be bought), or he goes to the guards. Turned down, walked
 * away from or left unanswered, it goes to the guards as any theft seen.
 */
public final class Witness {
    private record Offer(UUID witness, int village, double value, Map<Item, Integer> items, long until, int hush) {
    }

    private static final Map<UUID, Offer> OFFERS = new HashMap<>();

    private Witness() {
    }

    /** Seen stealing by {@code w}: true if he deals with it himself (no guard about who sees it too). */
    static boolean confront(ServerWorld world, Village village, VillagerEntityMCA w, ServerPlayerEntity p, double value, Map<Item, Integer> items) {
        if (OFFERS.containsKey(p.getUuid())) return false; // already being had out with: the second thing seen goes straight to the guards
        for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(16), VillagerEntityMCA::isGuard)) {
            if (g.isAlive() && !g.isSleeping() && g.canSee(p)) return false;
        }
        int hush = Math.max(5, 9 * Math.max(1, (int) Math.ceil(value / 6)) / 2); // half what the fine would be, in nuggets
        OFFERS.put(p.getUuid(), new Offer(w.getUuid(), village.getId(), value, new HashMap<>(items), world.getTime() + 20 * 25, hush));
        Move.to(w, p, 0.7);
        Gaze.at(w, p, 30f, 30f);
        int chance = (int) (chance(village, p) * 100);
        List<Text> labels = List.of(
                Text.translatable("settlements.witness.button.return"),
                Text.translatable("settlements.witness.button.pay", hush, chance),
                Text.translatable("settlements.witness.button.refuse"));
        Text line = Text.translatable(Speech.voiced(w, "settlements.say.witness.caught"), p.getName());
        w.sendChatMessage(line.copy(), p);
        net.mca.cobalt.network.NetworkHandler.sendToPlayer(new net.mca.network.s2c.JusticeScreenRequest(w.getId(), w.getName(),
                List.of(line), new String[]{"witness return", "witness pay", "witness refuse"}, labels, 25), p);
        return true;
    }

    /** How likely he takes the money and keeps quiet: likelier for somebody the village thinks well of. */
    /** One who will not take money to hold his tongue: the hard-working and the brave, and one in three of the rest. */
    static boolean honest(VillagerEntityMCA w) {
        Persona.Traits t = Persona.of(w);
        return t.diligence() > 1.15 || t.bravery() > 0.8 || Math.floorMod(w.getUuid().hashCode(), 3) == 0;
    }

    private static double chance(Village village, ServerPlayerEntity p) {
        return Math.max(0.15, Math.min(0.85, 0.5 + village.getReputation(p) / 300.0));
    }

    /** Once a second: he keeps before the thief while it is talked out; walked off from or unanswered, the guards. */
    public static void tick(ServerWorld world) {
        long now = world.getTime();
        for (Iterator<Map.Entry<UUID, Offer>> it = OFFERS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Offer> e = it.next();
            Offer o = e.getValue();
            ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(e.getKey());
            Entity we = world.getEntity(o.witness());
            if (p == null || p.getWorld() != world) {
                it.remove();
                continue;
            }
            if (!(we instanceof VillagerEntityMCA w) || !w.isAlive()) {
                it.remove();
                tell(world, null, p, o); // he is gone; what he saw he will have told before
                continue;
            }
            if (now > o.until() || w.squaredDistanceTo(p) > 24 * 24) {
                it.remove();
                Speech.sayNow(w, "settlements.say.witness.toguard", p.getName());
                tell(world, w, p, o);
                continue;
            }
            if (w.squaredDistanceTo(p) > 3 * 3) Move.to(w, p, 0.7);
            else {
                w.getNavigation().stop();
                Move.stay(w);
            }
            Gaze.at(w, p, 30f, 30f);
            Doing.set(w, "settlements.doing.witness", p.getName());
        }
    }

    /** True while somebody is having it out with this villager's thief (he does nothing else meanwhile). */
    public static boolean busy(VillagerEntityMCA v) {
        for (Offer o : OFFERS.values()) if (o.witness().equals(v.getUuid())) return true;
        return false;
    }

    /** /settle witness return|pay|refuse */
    public static int answer(ServerPlayerEntity p, String choice) {
        Offer o = OFFERS.remove(p.getUuid());
        ServerWorld world = p.getServerWorld();
        if (o == null) return 0;
        Village village = VillageManager.get(world).getOrEmpty(o.village()).orElse(null);
        VillagerEntityMCA w = world.getEntity(o.witness()) instanceof VillagerEntityMCA x ? x : null;
        if (village == null || w == null) return 0;
        switch (choice) {
            case "return" -> {
                double left = o.value();
                Map<Item, Integer> missing = new HashMap<>();
                for (Map.Entry<Item, Integer> e : o.items().entrySet()) {
                    int want = e.getValue();
                    for (int i = 0; i < p.getInventory().size() && want > 0; i++) {
                        ItemStack s = p.getInventory().getStack(i);
                        if (!s.isOf(e.getKey())) continue;
                        int d = Math.min(want, s.getCount());
                        ItemStack part = s.copy();
                        part.setCount(d);
                        s.decrement(d);
                        Workforce.addToTreasury(village, part);
                        left -= Reputation.value(part);
                        want -= d;
                    }
                    if (want > 0) missing.put(e.getKey(), want);
                }
                village.markDirty();
                if (missing.isEmpty() || left <= 0.5) {
                    Speech.sayNow(w, "settlements.say.witness.thanks", p.getName());
                    village.pushHearts(p, -2); // put back, but seen all the same
                    return 1;
                }
                // not all of it: the rest is the guards' business
                Speech.sayNow(w, "settlements.say.witness.toguard", p.getName());
                tell(world, w, p, new Offer(o.witness(), o.village(), Math.max(0, left), missing, 0, o.hush()));
                return 1;
            }
            case "pay" -> {
                if (Money.count(p.getInventory()) < o.hush()) {
                    p.sendMessage(Text.translatable("settlements.justice.cannot_pay", o.hush()).formatted(Formatting.RED), true);
                    OFFERS.put(p.getUuid(), o); // still standing there: something else, then
                    return 0;
                }
                if (honest(w)) {
                    // some cannot be bought at any price: and an offer of money is an insult on top of the theft
                    Speech.sayNow(w, "settlements.say.witness.honest", p.getName());
                    tell(world, w, p, new Offer(o.witness(), o.village(), o.value() + o.hush() / 3.0, o.items(), 0, o.hush()));
                    return 1;
                }
                if (world.random.nextDouble() < chance(village, p)) {
                    Money.take(p.getInventory(), o.hush());
                    Money.give(w.getInventory(), o.hush());
                    Speech.sayNow(w, "settlements.say.witness.hush", p.getName());
                    return 1;
                }
                Speech.sayNow(w, "settlements.say.witness.nohush", p.getName());
                tell(world, w, p, new Offer(o.witness(), o.village(), o.value() + o.hush() / 3.0, o.items(), 0, o.hush())); // and he tried to buy him
                return 1;
            }
            default -> {
                Speech.sayNow(w, "settlements.say.witness.toguard", p.getName());
                tell(world, w, p, o);
                return 1;
            }
        }
    }

    /** To the guards with it, as a theft seen. */
    private static void tell(ServerWorld world, VillagerEntityMCA w, ServerPlayerEntity p, Offer o) {
        Village village = VillageManager.get(world).getOrEmpty(o.village()).orElse(null);
        if (village == null) return;
        if (w != null) GuardIntel.alertNearestGuard(world, w, p);
        village.pushHearts(p, -(int) Math.ceil(o.value() * 1.5));
        Remember.note(village, p, "stole");
        Justice.report(world, village, p, Justice.Crime.THEFT, o.value(), o.items(), true);
    }
}
