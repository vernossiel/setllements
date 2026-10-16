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
    private static final java.util.Set<UUID> FORCED = new java.util.HashSet<>(); // made rogues for testing

    public static boolean is(VillagerEntityMCA v) {
        if (v.isBaby() || v.isElder() || v.isGuard()) return false;
        if (FORCED.contains(v.getUuid())) return true;
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

    private static boolean stocks(VillagerEntityMCA v, Village village, ServerWorld world, long until) {
        if (world.getTime() >= until) {
            STOCKED.remove(v.getUuid());
            Seat.rise(v);
            Speech.sayNow(v, "settlements.say.rogue.released");
            return false;
        }
        BlockPos at = Justice.pillory(world, village);
        if (v.isSleeping()) v.wakeUp();
        if (!v.hasVehicle()) Seat.kneel(v, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180f); // bent over in the stocks, head and hands through the board
        v.getNavigation().stop();
        Doing.set(v, "settlements.doing.rogue.stocks");
        if (world.random.nextInt(60) == 0) Speech.sayAny(v, "settlements.say.rogue.stocks", 3);
        return true;
    }

    /** Shift + right click on a rogue in the stocks: the player pulls the pin and lets him out. True if he was in them. */
    public static boolean freeByHand(VillagerEntityMCA v, net.minecraft.server.network.ServerPlayerEntity p) {
        if (!STOCKED.containsKey(v.getUuid()) || !(v.getWorld() instanceof ServerWorld world)) return false;
        Village village = v.getResidency().getHomeVillage().orElse(null);
        if (village == null) return false;
        freedBy(v, village, world, p);
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
        int coins = Money.count(v.getInventory()) / 2;
        if (coins > 0 && Money.take(v.getInventory(), coins)) p.giveItemStack(new ItemStack(net.mca.item.ItemsMCA.EMERALD_NUGGET.get(), coins));
        boolean seen = !world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(14, 4, 14),
                o -> o != v && o.isAlive() && !o.isSleeping() && !o.isBaby() && o.canSee(p)).isEmpty();
        if (seen) {
            Justice.report(world, village, p, Justice.Crime.HARASSMENT, 10, new java.util.HashMap<>(), true);
            Fame.deed(world, village, p, Fame.Deed.FREED_ROGUE, null, 1);
            Life.news(village, "rogue.freed", v.getName().getString(), p.getName().getString());
        } else {
            Life.news(village, "rogue.vanished", v.getName().getString());
        }
    }

    /** The rogue a player let out of the stocks owes him: he tips him off (see Talk) and offers him a hand. */
    public static boolean owes(VillagerEntityMCA v, net.minecraft.entity.player.PlayerEntity p) {
        return p.getUuid().equals(OWES.get(v.getUuid()));
    }

    /**
     * The way a rogue goes about his business: like anybody till he is near his mark; then bent low and slow, stopping to
     * look about him; and whenever somebody awake could see him, he stands stock still in the shadow till they look away or go.
     */
    private static void creep(VillagerEntityMCA v, ServerWorld world, double x, double y, double z) {
        double far = v.squaredDistanceTo(x, y, z);
        if (far > 14 * 14) {
            // still a way off: he strolls along like anyone out late, only a look about him now and then
            if (world.random.nextInt(8) == 0) {
                double a = world.random.nextDouble() * Math.PI * 2;
                Gaze.at(v, v.getX() + Math.cos(a) * 8, v.getEyeY(), v.getZ() + Math.sin(a) * 8);
            }
            Move.to(v, x, y, z, 0.45);
            return;
        }
        // close now: down low, and every few steps he stops and looks round
        Anim.set(v, Anim.Act.BEND, 30);
        VillagerEntityMCA eyes = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(10, 3, 10),
                o -> o != v && o.isAlive() && !o.isSleeping() && !o.isBaby() && !is(o) && o.canSee(v)).stream().findFirst().orElse(null);
        if (eyes != null) {
            v.getNavigation().stop();
            Gaze.at(v, eyes, 20f, 20f); // watching them, holding his breath
            return;
        }
        if (world.random.nextInt(3) == 0) { // a look back over his shoulder, a pause
            double a = world.random.nextDouble() * Math.PI * 2;
            Gaze.at(v, v.getX() + Math.cos(a) * 6, v.getEyeY(), v.getZ() + Math.sin(a) * 6);
            v.getNavigation().stop();
            return;
        }
        Move.to(v, x, y, z, 0.3);
    }

    // ---------- a little something from the stores ----------

    private record Mark(UUID victim, BlockPos chest) {
    }

    private record Loss(UUID victim, UUID rogue, int nuggets, int village) {
    }

    private static final Map<UUID, Mark> MARKS = new HashMap<>(); // rogue -> whose savings tonight
    private static final java.util.List<Loss> LOSSES = new java.util.ArrayList<>(); // found out in the morning

    /** Somebody's savings, in a chest in his house: who has enough put by to be worth the risk. */
    private static Mark pickMark(VillagerEntityMCA v, Village village, ServerWorld world) {
        java.util.List<Mark> marks = new java.util.ArrayList<>();
        for (VillagerEntityMCA r : village.getResidents(world)) {
            if (r == v || r.settlementsChest == null || r.settlementsChest.equals(v.settlementsChest) || is(r)) continue;
            if (Home.savings(r) >= 6) marks.add(new Mark(r.getUuid(), r.settlementsChest));
        }
        return marks.isEmpty() ? null : marks.get(world.random.nextInt(marks.size()));
    }

    /** Into somebody's house by night, to the chest where he keeps his money: a third of it, no more, so it is not missed at once. */
    private static boolean purse(VillagerEntityMCA v, Village village, ServerWorld world, Mark m, long now, long next) {
        if (!(world.getBlockEntity(m.chest()) instanceof net.minecraft.block.entity.ChestBlockEntity chest)) {
            MARKS.remove(v.getUuid());
            return false;
        }
        if (v.squaredDistanceTo(Vec3d.ofCenter(m.chest())) > 2.5 * 2.5) {
            creep(v, world, m.chest().getX() + 0.5, m.chest().getY(), m.chest().getZ() + 0.5);
            if (now - next > 20 * 120) { // could not get in: another night
                NEXT_THEFT.put(v.getUuid(), now + 24000L);
                MARKS.remove(v.getUuid());
            }
            return true;
        }
        MARKS.remove(v.getUuid());
        NEXT_THEFT.put(v.getUuid(), now + 24000L * (2 + world.random.nextInt(3)));
        v.getNavigation().stop();
        Gaze.at(v, m.chest().getX() + 0.5, m.chest().getY() + 0.5, m.chest().getZ() + 0.5);
        Anim.set(v, Anim.Act.BEND, 40);
        int n = Math.min(15, Money.count(chest) / 3);
        if (n <= 0 || !Money.take(chest, n)) return true;
        Money.give(v.getInventory(), n);
        // a sleeper in the house may wake
        VillagerEntityMCA woke = world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(m.chest()).expand(6, 3, 6),
                o -> o != v && o.isSleeping() && !o.isBaby()).stream().filter(o -> world.random.nextInt(4) == 0).findFirst().orElse(null);
        if (woke != null) {
            woke.wakeUp();
            Speech.sayNow(woke, "settlements.say.rogue.caught", v.getName());
            Money.take(v.getInventory(), n);
            Money.give(chest, n);
            STOCKED.put(v.getUuid(), now + 20 * 180);
            Life.news(village, "rogue.caught.purse", v.getName().getString(), woke.getName().getString());
            return true;
        }
        LOSSES.add(new Loss(m.victim(), v.getUuid(), n, village.getId()));
        return true;
    }

    /**
     * Morning: whoever was robbed in the night finds his chest lighter and goes to the guard (or the elder) about it.
     * Sometimes they work out who - the one seen about at night, the one suddenly flush - and he goes to the stocks
     * and gives it back; sometimes a stranger in the village is looked at; sometimes nobody ever knows.
     */
    public static void morning(ServerWorld world, Village village) {
        for (java.util.Iterator<Loss> it = LOSSES.iterator(); it.hasNext(); ) {
            Loss l = it.next();
            if (l.village() != village.getId()) continue;
            it.remove();
            if (!(world.getEntity(l.victim()) instanceof VillagerEntityMCA victim) || !victim.isAlive()) continue;
            VillagerEntityMCA rogue = world.getEntity(l.rogue()) instanceof VillagerEntityMCA r && r.isAlive() ? r : null;
            Speech.sayNow(victim, "settlements.say.robbed", l.nuggets());
            VillagerEntityMCA to = Plan.elder(world, village);
            for (VillagerEntityMCA g : village.getResidents(world)) if (g.isGuard()) to = g;
            if (to != null) victim.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.entity.ai.brain.WalkTarget(to, 0.55f, 2)); // off to complain
            boolean guards = village.getResidents(world).stream().anyMatch(VillagerEntityMCA::isGuard);
            double found = 0.3 + (guards ? 0.25 : 0) + (Staffing.has(world, village, net.mca.ProfessionsMCA.WATCHMAN.get()) ? 0.15 : 0);
            if (rogue != null && world.random.nextDouble() < found) {
                int back = Math.min(l.nuggets(), Money.count(rogue.getInventory()));
                if (back > 0 && Money.take(rogue.getInventory(), back)) Money.give(victim.getInventory(), back);
                STOCKED.put(rogue.getUuid(), world.getTime() + 20 * 240);
                Life.news(village, "rogue.caught.purse", rogue.getName().getString(), victim.getName().getString());
                continue;
            }
            Life.news(village, "robbed", victim.getName().getString());
            // nobody knows who: a stranger who spent the night here is the first they look at
            net.minecraft.entity.player.PlayerEntity stranger = world.getClosestPlayer(victim.getX(), victim.getY(), victim.getZ(), 64, false);
            if (stranger != null && (rogue == null || !owes(rogue, stranger)) && world.random.nextDouble() < Justice.suspicion(village, stranger) * 0.6) {
                Justice.askSearch(world, village, stranger, java.util.Map.of(net.mca.item.ItemsMCA.EMERALD_NUGGET.get(), l.nuggets()), l.nuggets(), world.getTime() + 20 * 60, false);
            }
        }
    }

    /** For testing: one grown villager (not the elder, not a guard, not the builder) becomes a rogue. */
    public static String make(ServerWorld world) {
        for (net.minecraft.entity.Entity e : world.iterateEntities()) {
            if (e instanceof VillagerEntityMCA v && !v.isBaby() && !v.isElder() && !v.isGuard() && !is(v)
                    && v.getProfession() != net.minecraft.village.VillagerProfession.CARTOGRAPHER) {
                FORCED.add(v.getUuid());
                return v.getName().getString();
            }
        }
        return "none";
    }

    /** For testing: every rogue in the world goes out tonight. Returns their names. */
    public static String readyTonight(ServerWorld world) {
        StringBuilder b = new StringBuilder();
        for (net.minecraft.entity.Entity e : world.iterateEntities()) {
            if (e instanceof VillagerEntityMCA v && is(v)) {
                NEXT_THEFT.put(v.getUuid(), 0L);
                MARKS.remove(v.getUuid());
                b.append(v.getName().getString()).append(' ');
            }
        }
        return b.length() == 0 ? "none" : b.toString().trim();
    }

    private static boolean steal(VillagerEntityMCA v, Village village, ServerWorld world) {
        long now = world.getTime();
        long next = NEXT_THEFT.computeIfAbsent(v.getUuid(), k -> now + 24000L + world.random.nextInt(48000));
        if (!dark(world)) MARKS.remove(v.getUuid()); // a new night, a new choice
        if (now < next || !dark(world)) return false;
        // tonight: somebody's savings, or the village's stores
        if (!MARKS.containsKey(v.getUuid())) MARKS.put(v.getUuid(), world.random.nextBoolean() ? pickMark(v, village, world) : null); // decided once a night
        Mark mark = MARKS.get(v.getUuid());
        if (mark != null) {
            Doing.set(v, "settlements.doing.rogue.sneak");
            if (v.isSleeping()) v.wakeUp();
            return purse(v, village, world, mark, now, next);
        }
        if (village.storageBuffer.isEmpty()) return false;
        BlockPos store = Haul.store(v, village);
        if (store == null) return false;
        if (Watch.near(world, store)) return false; // the watchman is about: not tonight
        Doing.set(v, "settlements.doing.rogue.sneak");
        if (v.isSleeping()) v.wakeUp();
        if (v.squaredDistanceTo(Vec3d.ofCenter(store)) > 9) {
            creep(v, world, store.getX() + 0.5, store.getY(), store.getZ() + 0.5);
            if (now - next > 20 * 120) { // could not get there: another night
                NEXT_THEFT.put(v.getUuid(), now + 24000L);
                MARKS.remove(v.getUuid());
            }
            return true;
        }
        NEXT_THEFT.put(v.getUuid(), now + 24000L * (2 + world.random.nextInt(3)));
        MARKS.remove(v.getUuid());
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
            // come morning, a stranger who spent the night in the village is the first one they look at
            net.minecraft.entity.player.PlayerEntity stranger = world.getClosestPlayer(store.getX(), store.getY(), store.getZ(), 48, false);
            if (stranger != null && !owes(v, stranger) && world.random.nextDouble() < Justice.suspicion(village, stranger) * 0.6) {
                long tod = world.getTimeOfDay() % 24000L;
                long morning = now + (24000L - tod + 1500L) % 24000L;
                Justice.askSearch(world, village, stranger, java.util.Map.of(took.getItem(), n), Reputation.value(took), morning, false);
            }
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
            creep(v, world, target.getX(), target.getY(), target.getZ());
            return true;
        }
        v.getNavigation().stop();
        Gaze.at(v, target, 30f, 30f);
        GOING_TO.remove(v.getUuid());
        Justice.markOffered(target);
        int price = owes(v, target) ? 0 : 9 * (4 + Justice.secondsLeft(target) / 30); // in nuggets; one he owes, he lets out for nothing
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
        if (o.price() > 0 && !Money.take(p.getInventory(), o.price())) {
            Speech.sayNow(rogue, "settlements.say.rogue.nomoney");
            return 0;
        }
        Money.give(rogue.getInventory(), o.price());
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
