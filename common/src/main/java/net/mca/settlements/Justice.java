package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The village's justice. A theft, damage to the village or a villager pestered or struck makes a case against the
 * player. A guard comes over, says what it is about and offers the choice: give the goods back, pay a fine, deny it,
 * or walk away. Walking away (or refusing) makes the player wanted: the guards come to arrest him. He can give
 * himself up or resist; resisting is fought, but the guards beat a man down rather than kill him and arrest him
 * anyway. The sentence is served in the jail, or in the stocks on the square when the village has no jail; whoever
 * runs off is wanted again. A villager killed, or a third arrest, means exile: the guards drive him out on sight.
 */
public final class Justice {
    public enum Crime { THEFT, VANDALISM, HARASSMENT, ASSAULT, MURDER }

    enum Stage { REPORTED, DEMAND, WANTED, ARREST, RESISTING, SERVING, EXILED }

    static final class Case {
        final UUID player;
        int villageId;
        Crime crime;
        Stage stage = Stage.REPORTED;
        double value;
        boolean witnessed;
        final Map<Item, Integer> stolen = new HashMap<>();
        long deadline, lastTalk, until, since; // since: wanted since
        int arrests;
        BlockPos hold;
        boolean shown; // the guard's demand window has been put before him
        boolean inStocks; // held in the stocks: he does not break out of those, he is put back
        boolean pleaded, bribed; // each tried once a case
        boolean offered; // a rogue has offered to get him out

        Case(UUID player) {
            this.player = player;
        }
    }

    private static final Map<UUID, Case> CASES = new HashMap<>();
    private static final Map<UUID, int[]> STRIKES = new HashMap<>(); // {strikes, last tick / 20}
    private static final Map<UUID, Integer> RECORD = new HashMap<>(); // serious offences so far (beatings, real thefts)
    private static final Map<UUID, Long> LAST_OFFENCE = new HashMap<>(); // when the last of them was: three quiet days and the slate is clean
    private static final Map<UUID, Long> SPARED = new HashMap<>(); // beaten and let go, until (world time)

    private Justice() {
    }

    private static int fine(Case c) {
        return Math.max(1, (int) Math.ceil(c.value / 6));
    }

    public static boolean wanted(PlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        return c != null && (c.stage == Stage.WANTED || c.stage == Stage.ARREST || c.stage == Stage.RESISTING || c.stage == Stage.EXILED);
    }

    /** Fought by the guards on sight: resisting arrest, or exiled and back. */
    public static boolean hunted(Village village, PlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        return c != null && village != null && c.villageId == village.getId() && (c.stage == Stage.RESISTING || c.stage == Stage.EXILED);
    }

    // ---------- reports ----------

    /** A wrong done to the village. {@code witnessed}: seen being done; otherwise only suspected. */
    public static void report(ServerWorld world, Village village, PlayerEntity player, Crime crime, double value, Map<Item, Integer> items, boolean witnessed) {
        if (village == null || player.isSpectator()) return;
        Case c = CASES.computeIfAbsent(player.getUuid(), Case::new);
        boolean fresh = c.stage == Stage.REPORTED && c.crime == null;
        c.villageId = village.getId();
        if (c.crime == null || crime.ordinal() > c.crime.ordinal()) c.crime = crime;
        c.value += value;
        c.witnessed |= witnessed;
        if (items != null) items.forEach((k, n) -> c.stolen.merge(k, n, Integer::sum));
        if (crime == Crime.MURDER) {
            if (player instanceof ServerPlayerEntity sp && c.stage != Stage.EXILED) exile(world, village, sp, c, 3);
        } else if (crime == Crime.ASSAULT && c.stage.ordinal() < Stage.RESISTING.ordinal()) {
            // a villager is being beaten: the guards step in at once, to beat him down and arrest him
            c.stage = Stage.RESISTING;
            for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, player.getBoundingBox().expand(48), VillagerEntityMCA::isGuard)) {
                g.getBrain().remember(MemoryModuleType.ATTACK_TARGET, player, 1200);
            }
        }
        if (fresh) Life.news(village, "crime." + crime.name().toLowerCase(), player.getName().getString());
        if (crime == Crime.THEFT) Fame.deed(world, village, player, Fame.Deed.THEFT, null, Math.max(0.5, value / 8));
        else if (crime == Crime.VANDALISM) Fame.deed(world, village, player, Fame.Deed.VANDALISM, null, Math.max(0.5, value / 8));
    }

    /** A villager struck by a player: a warning first, then a fine, then it is assault. */
    public static Crime hit(ServerWorld world, Village village, PlayerEntity player) {
        int[] s = STRIKES.computeIfAbsent(player.getUuid(), k -> new int[2]);
        long now = world.getTime() / 20;
        if (now - s[1] > 600) s[0] = 0; // ten minutes of good behaviour and it is forgotten
        s[0]++;
        s[1] = (int) now;
        Crime crime = s[0] >= 3 ? Crime.ASSAULT : Crime.HARASSMENT;
        report(world, village, player, crime, 4 * s[0], null, true);
        return crime;
    }

    // ---------- found out later ----------

    private record Suspicion(UUID player, int villageId, Crime crime, double value, Map<Item, Integer> items, BlockPos where, boolean seen, long[] at, boolean hall) {
    }

    private static final List<Suspicion> SUSPICIONS = new java.util.ArrayList<>();

    /**
     * Something done unseen: a few minutes later somebody finds the chest emptied or the wall broken. If the player
     * was seen about the place, he is suspected; otherwise it stays a mystery the village talks about.
     */
    public static void suspect(ServerWorld world, Village village, PlayerEntity p, Crime crime, double value, Map<Item, Integer> items, BlockPos where) {
        if (village == null || p.isSpectator()) return;
        boolean seen = !world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(32),
                v -> v.isAlive() && !v.isSleeping() && v.canSee(p)).isEmpty();
        // the stores in the town hall are the village's own: they are counted every day, a hole in them is soon found
        boolean hall = village.hallPos != null && where.isWithinDistance(village.hallPos, 12);
        long at = world.getTime() + 20L * (hall ? 40 + world.random.nextInt(60) : 120 + world.random.nextInt(240));
        // the same hand at more chests before anybody noticed: one theft, all of it together
        for (int i = 0; i < SUSPICIONS.size(); i++) {
            Suspicion o = SUSPICIONS.get(i);
            if (!o.player().equals(p.getUuid()) || o.villageId() != village.getId() || o.crime() != crime) continue;
            Map<Item, Integer> all = new HashMap<>(o.items());
            if (items != null) items.forEach((k, n) -> all.merge(k, n, Integer::sum));
            SUSPICIONS.set(i, new Suspicion(o.player(), o.villageId(), crime, o.value() + value, all, hall ? where : o.where(), o.seen() || seen,
                    new long[]{Math.min(o.at()[0], at)}, o.hall() || hall));
            return;
        }
        SUSPICIONS.add(new Suspicion(p.getUuid(), village.getId(), crime, value, items == null ? Map.of() : Map.copyOf(items), where, seen, new long[]{at}, hall));
    }

    private static void discover(ServerWorld world) {
        long now = world.getTime();
        for (Iterator<Suspicion> it = SUSPICIONS.iterator(); it.hasNext(); ) {
            Suspicion s = it.next();
            if (now < s.at()[0]) continue;
            Village village = VillageManager.get(world).getOrEmpty(s.villageId()).orElse(null);
            if (village == null) {
                it.remove();
                continue;
            }
            VillagerEntityMCA finder = world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(s.where()).expand(40),
                    v -> v.isAlive() && !v.isBaby() && !v.isSleeping()).stream()
                    .min(Comparator.comparingDouble(v -> v.squaredDistanceTo(s.where().getX(), s.where().getY(), s.where().getZ()))).orElse(null);
            if (finder == null) {
                s.at()[0] = now + 1200; // nobody about yet
                continue;
            }
            it.remove();
            ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(s.player());
            String what = s.crime().name().toLowerCase();
            if (s.seen() && p != null && s.crime() == Crime.THEFT && p.getWorld() == world) {
                // he was seen about the place: the watch will want a look in his pockets
                Speech.sayNow(finder, "settlements.justice.found." + what + ".seen", p.getName());
                askSearch(world, village, p, s.items(), s.value(), now, true);
                continue;
            }
            if (!s.seen() && s.hall() && p != null && s.crime() == Crime.THEFT && p.getWorld() == world
                    && p.squaredDistanceTo(s.where().getX(), s.where().getY(), s.where().getZ()) < 96 * 96) {
                // the village's own stores emptied, and a stranger about: the guard comes for him in earnest
                Speech.sayNow(finder, "settlements.justice.found.hall");
                Life.news(village, "crime.hall");
                askSearch(world, village, p, s.items(), s.value(), now, true);
                continue;
            }
            if (!s.seen() && p != null && s.crime() == Crime.THEFT && p.getWorld() == world
                    && p.squaredDistanceTo(s.where().getX(), s.where().getY(), s.where().getZ()) < 64 * 64
                    && world.random.nextDouble() < suspicion(village, p)) {
                // nobody saw a thing, but a stranger is about: the watch will want a look all the same
                Speech.sayNow(finder, "settlements.justice.found." + what);
                askSearch(world, village, p, s.items(), s.value(), now, false);
                continue;
            }
            if (s.seen() && p != null) {
                Speech.sayNow(finder, "settlements.justice.found." + what + ".seen", p.getName());
                report(world, village, p, s.crime(), s.value(), s.items(), false);
                if (p.getWorld() == world) GuardIntel.alertNearestGuard(world, finder, p);
            } else {
                Speech.sayNow(finder, "settlements.justice.found." + what);
                Life.news(village, "crime.unsolved." + what);
            }
        }
    }

    // ---------- every second ----------

    public static void tick(ServerWorld world) {
        if (!SUSPICIONS.isEmpty()) discover(world);
        if (!SEARCHES.isEmpty()) searches(world);
        if (CASES.isEmpty()) return;
        long now = world.getTime();
        for (Iterator<Map.Entry<UUID, Case>> it = CASES.entrySet().iterator(); it.hasNext(); ) {
            Case c = it.next().getValue();
            ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(c.player);
            if (p == null || p.getWorld() != world) continue;
            Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
            if (village == null) {
                it.remove();
                continue;
            }
            VillagerEntityMCA guard = c.stage == Stage.SERVING || c.stage == Stage.EXILED ? nearestGuard(world, p, 64) : onCase(world, p, 64);
            switch (c.stage) {
                case REPORTED -> {
                    if (c.crime == Crime.HARASSMENT && strikes(p) <= 1) {
                        // a first time: a word of warning and no more
                        talk(world, village, guard, p, "settlements.justice.warn", now);
                        it.remove();
                        continue;
                    }
                    if (guard == null || guard.squaredDistanceTo(p) < 36) {
                        c.stage = Stage.DEMAND;
                        c.deadline = now + 20 * 60;
                        demand(world, village, guard, p, c);
                    } else {
                        guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(p, 0.6f, 2));
                    }
                }
                case DEMAND -> {
                    if (guard != null && guard.squaredDistanceTo(p) > 16) guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(p, 0.5f, 2));
                    if (now >= c.deadline) {
                        c.stage = Stage.WANTED;
                        c.since = now;
                        talk(world, village, guard, p, "settlements.justice.wanted", now);
                    } else if (now - c.lastTalk > 400) {
                        demand(world, village, guard, p, c);
                    }
                }
                case WANTED -> {
                    if (c.since == 0) c.since = now;
                    // every guard about goes after him; one who keeps out of their way in the village resists arrest
                    for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(48), o -> o.isGuard() && o.isAlive() && !o.isSleeping())) {
                        g.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(p, 0.8f, 1));
                    }
                    if (now - c.since > 20 * 45 && village.isWithinBorder(p)) {
                        resist(p);
                        continue;
                    }
                    if (guard == null) continue;
                    if (guard.squaredDistanceTo(p) < 36 && guard.canSee(p)) {
                        c.stage = Stage.ARREST;
                        c.deadline = now + 20 * 20;
                        c.lastTalk = now;
                        guard.sendChatMessage(Text.translatable("settlements.justice.arrest", p.getName()), p);
                        java.util.List<String> o = new java.util.ArrayList<>(List.of("surrender"));
                        if (!c.pleaded) o.add("plead");
                        if (!c.bribed) o.add("bribe");
                        o.add("resist");
                        window(guard, village, p, List.of(Text.translatable("settlements.justice.arrest", p.getName()), Text.empty(),
                                Text.translatable("settlements.justice.arrest.choice").formatted(Formatting.YELLOW)), o.toArray(new String[0]), 20);
                    }
                }
                case ARREST -> {
                    if (guard != null && guard.squaredDistanceTo(p) > 9) guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(p, 0.6f, 1));
                    // the time to answer runs only while the guard stands before him: no guard by, no running out of time
                    if (guard == null || guard.squaredDistanceTo(p) > 6 * 6) c.deadline = Math.max(c.deadline, now + 20 * 10);
                    if (now >= c.deadline) resist(p);
                }
                case RESISTING -> {
                    // the guards are on him (GuardAI); he is beaten down, then arrested (see subdue)
                }
                case SERVING -> {
                    if (now >= c.until) {
                        release(world, village, p, c);
                        it.remove();
                        continue;
                    }
                    if (c.inStocks && c.hold != null && !p.hasVehicle()) {
                        if (p.squaredDistanceTo(c.hold.getX() + 0.5, c.hold.getY(), c.hold.getZ() + 0.5) > 4) {
                            // wrenched himself out of the stocks and off: a runaway now
                            c.stage = Stage.WANTED;
                            c.since = now;
                            c.inStocks = false;
                            c.value += 6;
                            p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
                            p.removeStatusEffect(StatusEffects.WEAKNESS);
                            p.sendMessage(Text.translatable("settlements.justice.escaped").formatted(Formatting.RED), false);
                            Life.forget(village, "crime.stocks", p.getName().getString());
                            Life.news(village, "crime.escape", p.getName().getString());
                            continue;
                        }
                        // the board holds him: back on his knees
                        Seat.kneel(p, c.hold.getX() + 0.5, c.hold.getY(), c.hold.getZ() + 0.5, 180f);
                    } else if (c.inStocks) {
                        jeers(world, village, p, c, now);
                    } else if (!c.inStocks && c.hold != null && p.squaredDistanceTo(c.hold.getX() + 0.5, c.hold.getY(), c.hold.getZ() + 0.5) > 9) {
                        // broke out
                        c.stage = Stage.WANTED;
                        c.since = now;
                        c.until += 20 * 60;
                        p.sendMessage(Text.translatable("settlements.justice.escaped").formatted(Formatting.RED), false);
                        Life.news(village, "crime.escape", p.getName().getString());
                    }
                }
                case EXILED -> {
                    if (now >= c.until) {
                        it.remove();
                        continue;
                    }
                    if (village.isWithinBorder(p)) {
                        // an exile inside the village is driven out by force, by every guard about
                        if (now - c.lastTalk > 600) talk(world, village, guard, p, "settlements.justice.exiled_back", now);
                        for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(48), o -> o.isGuard() && o.isAlive() && !o.isSleeping())) {
                            g.getBrain().remember(MemoryModuleType.ATTACK_TARGET, p, 200);
                        }
                    }
                }
            }
        }
    }

    private static int strikes(PlayerEntity p) {
        int[] s = STRIKES.get(p.getUuid());
        return s == null ? 0 : s[0];
    }

    /** guard -> {player, until}: a guard on a player's case minds nothing else (see {@link #duty}). */
    private static final Map<UUID, Object[]> DUTY = new HashMap<>();

    /** The guard on this player's case: the one already on it if he is still about, otherwise the nearest - and he is put on it. */
    private static VillagerEntityMCA onCase(ServerWorld world, PlayerEntity p, double range) {
        for (Map.Entry<UUID, Object[]> e : DUTY.entrySet()) {
            if (!p.getUuid().equals(e.getValue()[0])) continue;
            if (world.getEntity(e.getKey()) instanceof VillagerEntityMCA g && g.isAlive() && !g.isSleeping() && g.squaredDistanceTo(p) < range * range) {
                DUTY.put(g.getUuid(), new Object[]{p.getUuid(), world.getTime() + 20 * 30});
                return g;
            }
        }
        VillagerEntityMCA g = nearestGuard(world, p, range);
        if (g != null) DUTY.put(g.getUuid(), new Object[]{p.getUuid(), world.getTime() + 20 * 30});
        return g;
    }

    /**
     * Once a second for a guard: true while he is on a player's case - he goes to him and stays by him, facing him,
     * whatever his round or his shift would have him do (a fight still comes first).
     */
    public static boolean duty(VillagerEntityMCA g, ServerWorld world) {
        Object[] d = DUTY.get(g.getUuid());
        if (d == null) return false;
        ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer((UUID) d[0]);
        if (p == null || p.getWorld() != world || world.getTime() > (Long) d[1] || !CASES.containsKey(p.getUuid()) && !SEARCHES.containsKey(p.getUuid())) {
            DUTY.remove(g.getUuid());
            return false;
        }
        if (g.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent()) return false;
        Case c = CASES.get(p.getUuid());
        if (c != null && (c.stage == Stage.SERVING || c.stage == Stage.EXILED)) return false;
        Doing.set(g, "settlements.doing.guard.case", p.getName());
        if (g.squaredDistanceTo(p) > 2.8 * 2.8) Move.to(g, p, 0.6);
        else g.getNavigation().stop();
        Gaze.at(g, p, 30f, 30f);
        return true;
    }

    private static VillagerEntityMCA nearestGuard(ServerWorld world, PlayerEntity p, double range) {
        return world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(range), g -> g.isGuard() && g.isAlive() && !g.isSleeping())
                .stream().min(Comparator.comparingDouble(g -> g.squaredDistanceTo(p))).orElse(null);
    }

    /** Whoever speaks for the village: the guard, or else the elder, or else nobody but the message. */
    private static void talk(ServerWorld world, Village village, VillagerEntityMCA guard, ServerPlayerEntity p, String key, long now) {
        VillagerEntityMCA speaker = guard != null ? guard : Plan.elder(world, village);
        if (speaker != null && speaker.squaredDistanceTo(p) < 48 * 48) {
            speaker.sendChatMessage(Text.translatable(key, p.getName()), p); // over his head
            // and at the bottom of the screen, face to face, till the next words
            net.mca.cobalt.network.NetworkHandler.sendToPlayer(new net.mca.network.s2c.JusticeScreenRequest(speaker.getId(), speaker.getName(),
                    List.of(Text.translatable(key, p.getName())), new String[0], List.of(), 0), p);
        } else p.sendMessage(Text.translatable(key, p.getName()).formatted(Formatting.GOLD), false);
        Case c = CASES.get(p.getUuid());
        if (c != null) c.lastTalk = now;
    }

    private static void demand(ServerWorld world, Village village, VillagerEntityMCA guard, ServerPlayerEntity p, Case c) {
        String key = "settlements.justice.demand." + c.crime.name().toLowerCase();
        talk(world, village, guard, p, key, world.getTime());
        Text money = !c.stolen.isEmpty() ? Text.translatable("settlements.justice.fine_or_return", fine(c)) : Text.translatable("settlements.justice.fine", fine(c));
        java.util.List<String> o = new java.util.ArrayList<>();
        if (!c.stolen.isEmpty()) o.add("return");
        o.add("pay");
        if (!c.pleaded) o.add("plead");
        if (!c.bribed) o.add("bribe");
        o.add("deny");
        o.add("leave");
        String[] options = o.toArray(new String[0]);
        if (!c.shown) { // face to face, the first time: a window with his words and the answers
            c.shown = true;
            window(guard, village, p, List.of(Text.translatable(key, p.getName()), Text.empty(), money.copy().formatted(Formatting.YELLOW)), options,
                    (int) Math.max(0, (c.deadline - world.getTime()) / 20));
        } else { // reminded: the same words and answers again, not a line more in the chat
            window(guard, village, p, List.of(Text.translatable(key, p.getName()), Text.empty(), money.copy().formatted(Formatting.YELLOW)), options,
                    (int) Math.max(0, (c.deadline - world.getTime()) / 20));
        }
    }

    /** The guard's demand or arrest, in a window of its own over the game (the chat has the same, to answer later). */
    private static void window(VillagerEntityMCA guard, Village village, ServerPlayerEntity p, List<Text> lines, String[] options, int seconds) {
        Text who = guard != null ? guard.getName() : Text.literal(village.getName());
        Case c = CASES.get(p.getUuid());
        List<Text> labels = new java.util.ArrayList<>();
        for (String o : options) {
            MutableText l = Text.translatable("settlements.justice.button." + o);
            // what an answer turns on, the way it is shown in a good story: the odds of talking him round
            if (o.equals("plead") && c != null) l.append(Text.literal(" [" + (int) (pleadChance(p, village, c) * 100) + "%]").formatted(Formatting.GRAY));
            if (o.equals("bribe") && c != null) l.append(Text.literal(" [" + bribeCost(c) + "§a◆§7, " + (int) (bribeChance(p, village, c) * 100) + "%]").formatted(Formatting.GRAY));
            labels.add(l);
        }
        net.mca.cobalt.network.NetworkHandler.sendToPlayer(new net.mca.network.s2c.JusticeScreenRequest(guard != null ? guard.getId() : -1, who, lines, options, labels, seconds), p);
    }

    private static MutableText buttons(String[] which) {
        MutableText t = Text.empty();
        for (String w : which) {
            Formatting color = switch (w) {
                case "return", "pay", "surrender" -> Formatting.GREEN;
                case "deny" -> Formatting.YELLOW;
                default -> Formatting.RED;
            };
            t.append(Text.literal("[").append(Text.translatable("settlements.justice.button." + w)).append("]")
                    .styled(st -> st.withBold(true).withColor(color).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/settle " + w))));
            t.append(Text.literal("  "));
        }
        return t;
    }

    // ---------- the player's answers (/settle ...) ----------

    public static int giveBack(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null || c.stolen.isEmpty()) {
            p.sendMessage(Text.translatable("settlements.theft.nodebt"), false);
            return 0;
        }
        Village village = VillageManager.get(p.getServerWorld()).getOrEmpty(c.villageId).orElse(null);
        if (village == null) return 0;
        double returned = takeBack(p, c, village);
        if (c.stolen.isEmpty()) {
            CASES.remove(p.getUuid());
            village.pushHearts(p, 5);
            Fame.deed(p.getServerWorld(), village, p, Fame.Deed.PAID, null, 1);
            p.sendMessage(Text.translatable("settlements.justice.settled").formatted(Formatting.GREEN), false);
        } else {
            p.sendMessage(Text.translatable("settlements.theft.partial", (int) Math.ceil(Math.max(0, c.value - returned))), false);
        }
        return 1;
    }

    /** The stolen goods out of the player's pockets, back into the stores. */
    private static double takeBack(ServerPlayerEntity p, Case c, Village village) {
        double returned = 0;
        for (Map.Entry<Item, Integer> e : c.stolen.entrySet()) {
            int left = e.getValue();
            for (int i = 0; i < p.getInventory().size() && left > 0; i++) {
                ItemStack s = p.getInventory().getStack(i);
                if (!s.isOf(e.getKey())) continue;
                int d = Math.min(left, s.getCount());
                ItemStack part = s.copy();
                part.setCount(d);
                s.decrement(d);
                Workforce.addToTreasury(village, part);
                returned += Reputation.value(part);
                left -= d;
            }
            e.setValue(left);
        }
        c.stolen.values().removeIf(n -> n <= 0);
        c.value = Math.max(0, c.value - returned);
        village.markDirty();
        return returned;
    }

    /** Whether he has a theft standing against him. */
    public static boolean owes(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        return c != null && c.crime == Crime.THEFT;
    }

    /** Goods put back into a village chest count against the theft. */
    public static void credit(ServerPlayerEntity p, double value) {
        Case c = CASES.get(p.getUuid());
        if (c == null || c.crime != Crime.THEFT) return;
        c.value -= value;
        if (c.value <= 0) {
            CASES.remove(p.getUuid());
            p.sendMessage(Text.translatable("settlements.justice.settled").formatted(Formatting.GREEN), false);
        }
    }

    public static int pay(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null) return 0;
        int fine = fine(c);
        int have = p.getInventory().count(Items.EMERALD);
        if (have < fine) {
            p.sendMessage(Text.translatable("settlements.justice.cannot_pay", fine).formatted(Formatting.RED), false);
            return 0;
        }
        Village village = VillageManager.get(p.getServerWorld()).getOrEmpty(c.villageId).orElse(null);
        int left = fine;
        for (int i = 0; i < p.getInventory().size() && left > 0; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isOf(Items.EMERALD)) continue;
            int d = Math.min(left, s.getCount());
            s.decrement(d);
            left -= d;
        }
        if (village != null) {
            // bought off: the goods stay with him, the fine is worth more than they are, and it is not forgotten
            Workforce.addToTreasury(village, new ItemStack(Items.EMERALD, fine));
            village.pushHearts(p, -(int) Math.ceil(c.value / 2));
        }
        CASES.remove(p.getUuid());
        p.sendMessage(Text.translatable("settlements.justice.paid", fine).formatted(Formatting.GREEN), false);
        return 1;
    }

    public static int deny(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null || c.stage != Stage.DEMAND) return 0;
        ServerWorld world = p.getServerWorld();
        Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
        VillagerEntityMCA guard = nearestGuard(world, p, 32);
        if (c.witnessed) {
            // there were witnesses: no use denying it
            talk(world, village, guard, p, "settlements.justice.deny_witnessed", world.getTime());
            c.deadline = Math.min(c.deadline, world.getTime() + 20 * 20);
        } else {
            talk(world, village, guard, p, "settlements.justice.deny_accepted", world.getTime());
            if (village != null) village.pushHearts(p, -3);
            CASES.remove(p.getUuid());
        }
        return 1;
    }

    // ---------- talking one's way out ----------

    private static double pleadChance(PlayerEntity p, Village village, Case c) {
        int hearts = village.getReputation(p);
        double ch = 0.3 + hearts / 150.0;
        if (RECORD.getOrDefault(p.getUuid(), 0) == 0) ch += 0.15; // a first offence
        if (c.witnessed) ch -= 0.15;
        if (c.crime == Crime.ASSAULT || c.crime == Crime.MURDER) ch -= 0.25;
        if (c.stage == Stage.ARREST) ch -= 0.1;
        return Math.max(0.05, Math.min(0.85, ch));
    }

    private static int bribeCost(Case c) {
        return Math.max(1, (fine(c) + 1) / 2);
    }

    private static double bribeChance(PlayerEntity p, Village village, Case c) {
        double ch = 0.45 + village.getReputation(p) / 300.0 - (c.witnessed ? 0.15 : 0) - (c.crime == Crime.MURDER ? 0.4 : 0);
        return Math.max(0.05, Math.min(0.8, ch));
    }

    /** "It was a misunderstanding...": talked round, he lets it go (or, at an arrest, back to a fine); or not, and it costs more. */
    public static int plead(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null || c.pleaded || (c.stage != Stage.DEMAND && c.stage != Stage.ARREST)) return 0;
        ServerWorld world = p.getServerWorld();
        Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
        if (village == null) return 0;
        VillagerEntityMCA guard = nearestGuard(world, p, 32);
        c.pleaded = true;
        if (world.random.nextDouble() < pleadChance(p, village, c)) {
            if (c.stage == Stage.DEMAND || c.value < 6) {
                talk(world, village, guard, p, "settlements.justice.plead.ok", world.getTime());
                village.pushHearts(p, -2);
                CASES.remove(p.getUuid());
                p.sendMessage(Text.translatable("settlements.justice.settled").formatted(Formatting.GREEN), true);
            } else {
                talk(world, village, guard, p, "settlements.justice.plead.fine", world.getTime());
                c.stage = Stage.DEMAND;
                c.deadline = world.getTime() + 20 * 60;
                c.shown = false;
                demand(world, village, guard, p, c);
            }
        } else {
            talk(world, village, guard, p, "settlements.justice.plead.no", world.getTime());
            c.value *= 1.5;
            c.shown = false;
            if (c.stage == Stage.DEMAND) demand(world, village, guard, p, c);
            else c.deadline = Math.min(c.deadline, world.getTime() + 20 * 10);
        }
        return 1;
    }

    /** A few emeralds for the guard himself, quietly. Taken, the matter is closed; refused, it is one more offence. */
    public static int bribe(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null || c.bribed || (c.stage != Stage.DEMAND && c.stage != Stage.ARREST)) return 0;
        ServerWorld world = p.getServerWorld();
        Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
        if (village == null) return 0;
        VillagerEntityMCA guard = nearestGuard(world, p, 32);
        int cost = bribeCost(c);
        if (p.getInventory().count(Items.EMERALD) < cost) {
            p.sendMessage(Text.translatable("settlements.justice.cannot_pay", cost).formatted(Formatting.RED), true);
            return 0;
        }
        c.bribed = true;
        if (world.random.nextDouble() < bribeChance(p, village, c)) {
            int left = cost;
            for (int i = 0; i < p.getInventory().size() && left > 0; i++) {
                ItemStack s = p.getInventory().getStack(i);
                if (!s.isOf(Items.EMERALD)) continue;
                int d = Math.min(left, s.getCount());
                s.decrement(d);
                left -= d;
            }
            talk(world, village, guard, p, "settlements.justice.bribe.ok", world.getTime());
            CASES.remove(p.getUuid());
        } else {
            talk(world, village, guard, p, "settlements.justice.bribe.no", world.getTime());
            village.pushHearts(p, -5);
            c.value += cost * 6;
            c.shown = false;
            if (c.stage == Stage.DEMAND) demand(world, village, guard, p, c);
        }
        return 1;
    }

    public static int leave(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null) return 0;
        c.deadline = p.getWorld().getTime(); // the guards will not wait
        return 1;
    }

    public static int surrender(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null || (c.stage != Stage.ARREST && c.stage != Stage.WANTED && c.stage != Stage.RESISTING)) return 0;
        sentence(p.getServerWorld(), p, c);
        return 1;
    }

    public static int resist(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null) return 0;
        c.stage = Stage.RESISTING;
        ServerWorld world = p.getServerWorld();
        for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(48), VillagerEntityMCA::isGuard)) {
            g.getBrain().remember(MemoryModuleType.ATTACK_TARGET, p, 1200);
        }
        p.sendMessage(Text.translatable("settlements.justice.resisting").formatted(Formatting.RED), false);
        return 1;
    }

    /** A guard about to land a blow on a resisting player: when he is down to his last strength, he is arrested instead. */
    public static boolean subdue(VillagerEntityMCA guard, PlayerEntity p) {
        if (p.getHealth() > 6f || !(p instanceof ServerPlayerEntity sp)) return false;
        Case c = CASES.get(p.getUuid());
        if (!detaining(c)) return false; // an exile or a sworn enemy is not arrested: he is fought like anyone
        guard.sendChatMessage(Text.translatable("settlements.justice.subdued", p.getName()), p);
        sentence(sp.getServerWorld(), sp, c);
        return true;
    }

    /** A case the guards mean to end with an arrest, not a killing. */
    private static boolean detaining(Case c) {
        return c != null && c.stage != Stage.SERVING && c.stage != Stage.EXILED;
    }

    /** Beaten down and thrown out a moment ago: the guards let him crawl away. */
    public static boolean spared(PlayerEntity p) {
        Long until = SPARED.get(p.getUuid());
        if (until == null) return false;
        if (p.getWorld().getTime() < until) return true;
        SPARED.remove(p.getUuid());
        return false;
    }

    /** How hard a guard's blow lands on a player. */
    public static float blow(PlayerEntity p, float damage) {
        // the village's guards beat a man, they do not kill him: one they are arresting is beaten down and taken (see
        // subdue); one the village only wants rid of is beaten and let crawl away; one in the stocks is not touched
        Case c = CASES.get(p.getUuid());
        if (c != null && c.stage == Stage.SERVING) return 0f;
        if (detaining(c)) return Math.min(damage, Math.max(1f, p.getHealth() - 5f));
        float hit = Math.min(damage, Math.max(0f, p.getHealth() - 2f));
        if (p.getHealth() - hit <= 4f && p.getWorld() instanceof ServerWorld w) spare(w, p);
        return hit;
    }

    /** Beaten and let go: the guards stand off for a minute and a half while he gets himself away. */
    private static void spare(ServerWorld world, PlayerEntity p) {
        if (spared(p)) return;
        SPARED.put(p.getUuid(), world.getTime() + 20 * 90);
        for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(64), VillagerEntityMCA::isGuard)) {
            if (g.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == p) g.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
        }
        VillagerEntityMCA g = nearestGuard(world, p, 24);
        if (g != null && p instanceof ServerPlayerEntity sp) {
            Village village = VillageManager.get(world).findNearestVillage(p).orElse(null);
            if (village != null) talk(world, village, g, sp, "settlements.justice.beaten", world.getTime());
        }
    }

    // ---------- punishment ----------

    private static void sentence(ServerWorld world, ServerPlayerEntity p, Case c) {
        Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
        for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(64), VillagerEntityMCA::isGuard)) {
            if (g.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == p) g.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
        }
        long nowT = world.getTime();
        if (nowT - LAST_OFFENCE.getOrDefault(p.getUuid(), nowT) > 24000L * 3) RECORD.remove(p.getUuid());
        // a fine for a shove or a loaf is not a record; a beating or a real theft is
        boolean serious = c.crime == Crime.ASSAULT || c.crime == Crime.THEFT && c.value >= 8;
        int record = serious ? RECORD.merge(p.getUuid(), 1, Integer::sum) : RECORD.getOrDefault(p.getUuid(), 0);
        if (serious) LAST_OFFENCE.put(p.getUuid(), nowT);
        if (village != null) Life.forgive(village, p);
        if (village == null) {
            CASES.remove(p.getUuid());
            return;
        }
        if (!c.stolen.isEmpty()) takeBack(p, c, village); // confiscated
        if (serious && record >= 3) { // the third serious offence in a few days: out of the village
            exile(world, village, p, c, 1);
            return;
        }
        int seconds = (int) Math.max(60, Math.min(300, 60 + c.value * 4 + (c.crime == Crime.ASSAULT ? 120 : 0)));
        c.stage = Stage.SERVING;
        c.until = world.getTime() + seconds * 20L;
        Built jail = Buildings.find(village, "jail");
        if (jail != null) {
            int[] jc = Tiers.jailCell(jail.type());
            BlockPos cell = jail.point(jc[0], jc[1], jc[2]);
            p.teleport(world, cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, p.getYaw(), p.getPitch());
            c.hold = cell;
            p.sendMessage(Text.translatable("settlements.justice.jailed", seconds).formatted(Formatting.GOLD), false);
            Life.news(village, "crime.jailed", p.getName().getString());
        } else {
            // the stocks on the square, on a platform for all to see: he cannot move until his time is up
            BlockPos spot = pillory(world, village);
            p.teleport(world, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 180f, 10f); // facing the square, the board at his neck
            Seat.kneel(p, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 180f); // on his knees, head and hands through the board
            c.inStocks = true;
            int ticks = seconds * 20;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, ticks, 4, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, ticks, 4, false, false));
            c.hold = spot;
            p.sendMessage(Text.translatable("settlements.justice.stocks", seconds).formatted(Formatting.GOLD), false);
            Life.news(village, "crime.stocks", p.getName().getString());
            stocksWindow(world, village, p, c);
        }
    }

    /**
     * Where the stocks stand when the village has no jail: on its square, always the same place - in front of the well,
     * or else the middle of the village on open ground - so everybody sees who did wrong.
     */
    static BlockPos stocks(ServerWorld world, Village village) {
        for (Built b : village.built) {
            if (!Buildings.base(b.type()).equals("well")) continue;
            BlockPos q = open(world, b.point(2, 0, 5));
            if (q != null) return q;
        }
        // open ground near the middle: earth, a path or stone underfoot, sky above, not inside or on top of a building
        BlockPos c = new BlockPos(village.getCenter());
        for (int r = 2; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos top = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.add(dx, 0, dz));
                    var ground = world.getBlockState(top.down());
                    boolean natural = ground.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || ground.isOf(net.minecraft.block.Blocks.DIRT_PATH)
                            || ground.isOf(net.minecraft.block.Blocks.GRAVEL) || ground.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                            || ground.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD);
                    if (!natural || inBuilding(village, top)) continue;
                    BlockPos q = open(world, top);
                    if (q != null) return q;
                }
            }
        }
        return world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c);
    }

    /**
     * The village's stocks, put up once on the square and kept: a plank platform a step high, two posts and the board
     * across them at neck height, the prisoner's head and hands through it. Returns where he stands.
     */
    static BlockPos pillory(ServerWorld world, Village village) {
        BlockPos at = village.stocksPos;
        if (at == null || !world.getBlockState(at.down()).isOf(net.minecraft.block.Blocks.SPRUCE_PLANKS)) {
            BlockPos ground = stocks(world, village);
            // the platform: 3 wide, 3 deep, one block up, a step in front
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos q = ground.add(dx, 0, dz);
                    world.setBlockState(q, net.minecraft.block.Blocks.SPRUCE_PLANKS.getDefaultState());
                    for (int y = 1; y <= 3; y++) if (!world.getBlockState(q.up(y)).isAir()) world.setBlockState(q.up(y), net.minecraft.block.Blocks.AIR.getDefaultState());
                    if (world.getBlockState(q.down()).isReplaceable()) world.setBlockState(q.down(), net.minecraft.block.Blocks.DIRT.getDefaultState());
                }
            }
            for (int dx = -1; dx <= 1; dx++) {
                BlockPos step = ground.add(dx, 0, -2);
                if (world.getBlockState(step).isReplaceable())
                    world.setBlockState(step, net.minecraft.block.Blocks.SPRUCE_STAIRS.getDefaultState().with(net.minecraft.block.StairsBlock.FACING, net.minecraft.util.math.Direction.SOUTH));
            }
            at = ground.up();
            // the posts either side of him and the board between them, at his neck, behind him the post's back plank
            for (int dx : new int[]{-1, 1}) {
                world.setBlockState(at.add(dx, 0, 0), net.minecraft.block.Blocks.SPRUCE_FENCE.getDefaultState());
                world.setBlockState(at.add(dx, 1, 0), net.minecraft.block.Blocks.SPRUCE_SLAB.getDefaultState());
            }
            world.setBlockState(at.add(0, 0, 1), net.minecraft.block.Blocks.SPRUCE_FENCE.getDefaultState());
            world.setBlockState(at.add(0, 1, 1), net.minecraft.block.Blocks.SPRUCE_TRAPDOOR.getDefaultState()
                    .with(net.minecraft.block.TrapdoorBlock.FACING, net.minecraft.util.math.Direction.NORTH)
                    .with(net.minecraft.block.TrapdoorBlock.OPEN, true)
                    .with(net.minecraft.block.TrapdoorBlock.HALF, net.minecraft.block.enums.BlockHalf.TOP));
            village.stocksPos = at.toImmutable();
            village.markDirty();
        }
        return at;
    }

    private static BlockPos open(ServerWorld world, BlockPos p) {
        return Move.passable(world, p) && Move.passable(world, p.up()) && world.getBlockState(p.down()).isSolidBlock(world, p.down())
                && world.getFluidState(p).isEmpty() ? p.toImmutable() : null;
    }

    private static boolean inBuilding(Village village, BlockPos p) {
        for (Built b : village.built) if (b.contains(p)) return true;
        for (var b : village.getBuildings().values()) {
            if (b.getBuildingType().grouped()) continue;
            BlockPos a = b.getPos0(), z = b.getPos1();
            if (p.getX() >= Math.min(a.getX(), z.getX()) - 1 && p.getX() <= Math.max(a.getX(), z.getX()) + 1
                    && p.getZ() >= Math.min(a.getZ(), z.getZ()) - 1 && p.getZ() <= Math.max(a.getZ(), z.getZ()) + 1) return true;
        }
        return false;
    }

    private static void release(ServerWorld world, Village village, ServerPlayerEntity p, Case c) {
        Seat.rise(p);
        Life.forget(village, "crime.stocks", p.getName().getString());
        Built jail = Buildings.find(village, "jail");
        if (jail != null && c.hold != null) {
            int[] je = Tiers.jailExit(jail.type());
            BlockPos out = jail.point(je[0], je[1], je[2]);
            p.teleport(world, out.getX() + 0.5, out.getY(), out.getZ() + 0.5, p.getYaw(), p.getPitch());
        }
        p.removeStatusEffect(StatusEffects.SLOWNESS);
        p.removeStatusEffect(StatusEffects.JUMP_BOOST);
        p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
        p.removeStatusEffect(StatusEffects.WEAKNESS);
        village.pushHearts(p, 8); // the debt is paid
        Fame.deed(world, village, p, Fame.Deed.SERVED, null, 1);
        p.sendMessage(Text.translatable("settlements.justice.released").formatted(Formatting.GREEN), false);
    }

    /** Out of the village for {@code days}: told to his face by a guard (or the elder), and the village hears of it. */
    private static void exile(ServerWorld world, Village village, ServerPlayerEntity p, Case c, int days) {
        c.stage = Stage.EXILED;
        c.until = world.getTime() + 24000L * days;
        c.lastTalk = world.getTime();
        talk(world, village, nearestGuard(world, p, 48), p, days > 1 ? "settlements.justice.exiled.long" : "settlements.justice.exiled", world.getTime());
        Life.news(village, "crime.exiled", p.getName().getString());
    }

    // ---------- a look in his pockets ----------

    private static Village village(ServerWorld world, int id) {
        return VillageManager.get(world).getOrEmpty(id).orElse(null);
    }

    /** One the village has no love for: a bad name for honesty or peace, or the hearts long gone. */
    static boolean hostile(Village village, PlayerEntity p) {
        if (village == null) return false;
        return Reputation.tier(village.getReputation(p)) <= 2 || Fame.get(village, p, Fame.Axis.HONEST) <= -20 || Fame.get(village, p, Fame.Axis.PEACE) <= -20;
    }

    /** How readily the village looks at him when something goes missing: a known thief first, an honest man hardly at all. */
    static double suspicion(Village village, PlayerEntity p) {
        int honest = Fame.get(village, p, Fame.Axis.HONEST);
        return Math.max(0.1, Math.min(0.9, 0.5 - honest / 80.0));
    }

    /** {@code strong}: he was seen there, and a refusal is as good as a confession; otherwise only suspected. */
    private record Search(int villageId, Map<Item, Integer> items, double value, long from, long until, boolean strong) {
    }

    /**
     * Something has gone missing and the player is the one the village looks at: the guard will come and ask to see
     * his pockets, from {@code from} (world time) on. A word from a villager who saw him is enough; so is being the
     * only stranger about when the stores came up short.
     */
    public static void askSearch(ServerWorld world, Village village, PlayerEntity p, Map<Item, Integer> items, double value, long from, boolean strong) {
        if (village == null || p.isSpectator() || p.isCreative() || CASES.containsKey(p.getUuid()) || SEARCHES.containsKey(p.getUuid())) return;
        SEARCHES.put(p.getUuid(), new Search(village.getId(), items == null ? Map.of() : Map.copyOf(items), value, from, from + 20 * 240, strong));
    }

    private static final Map<UUID, Search> SEARCHES = new HashMap<>();
    private static final java.util.Set<UUID> ASKED = new java.util.HashSet<>();

    /** A guard goes up to the suspect and asks to see what he has on him. */
    private static void searches(ServerWorld world) {
        long now = world.getTime();
        for (Iterator<Map.Entry<UUID, Search>> it = SEARCHES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Search> e = it.next();
            ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(e.getKey());
            if (p == null || now > e.getValue().until()) {
                it.remove();
                ASKED.remove(e.getKey());
                continue;
            }
            if (p.getWorld() != world || ASKED.contains(p.getUuid()) || now < e.getValue().from() || p.isSleeping()) continue;
            VillagerEntityMCA guard = onCase(world, p, 64);
            if (guard == null) continue;
            if (guard.squaredDistanceTo(p) > 16) {
                guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(p, 0.6f, 2));
                continue;
            }
            ASKED.add(p.getUuid());
            Gaze.at(guard, p, 30f, 30f);
            List<Text> labels = List.of(Text.translatable("settlements.justice.button.search_allow"), Text.translatable("settlements.justice.button.search_refuse"));
            net.mca.cobalt.network.NetworkHandler.sendToPlayer(new net.mca.network.s2c.JusticeScreenRequest(guard.getId(), guard.getName(),
                    List.of(Text.translatable(hostile(village(world, e.getValue().villageId()), p) ? "settlements.justice.search.ask.cold"
                            : e.getValue().strong() ? "settlements.justice.search.ask" : "settlements.justice.search.ask.weak", p.getName())), new String[]{"search allow", "search refuse"}, labels, 30), p);
        }
    }

    /** /settle search allow|refuse */
    public static int search(ServerPlayerEntity p, boolean allow) {
        Search s = SEARCHES.remove(p.getUuid());
        ASKED.remove(p.getUuid());
        if (s == null) return 0;
        ServerWorld world = p.getServerWorld();
        Village village = VillageManager.get(world).getOrEmpty(s.villageId()).orElse(null);
        VillagerEntityMCA guard = nearestGuard(world, p, 32);
        if (village == null) return 0;
        if (!allow && s.strong()) {
            // seen at the place and won't turn out his pockets: that says enough
            talk(world, village, guard, p, "settlements.justice.search.refused.strong", world.getTime());
            report(world, village, p, Crime.THEFT, s.value(), s.items(), true);
            return 1;
        }
        if (!allow) {
            talk(world, village, guard, p, "settlements.justice.search.refused", world.getTime());
            village.pushHearts(p, -3);
            Fame.deed(world, village, p, Fame.Deed.REFUSED_SEARCH, guard, 1);
            if (guard != null) guard.settlementsWatch = p.getUuid(); // he keeps an eye on him from now on
            Life.news(village, "search.refused", p.getName().getString());
            return 1;
        }
        // what of the missing he has on him
        Map<Item, Integer> found = new HashMap<>();
        for (Map.Entry<Item, Integer> m : s.items().entrySet()) {
            int have = p.getInventory().count(m.getKey());
            // on a mere suspicion, a crust or a few planks of his own prove nothing: only the very goods, and enough of them
            if (!s.strong() && (have < m.getValue() || Reputation.value(new ItemStack(m.getKey())) < 3)) continue;
            if (have > 0) found.put(m.getKey(), Math.min(have, m.getValue()));
        }
        if (found.isEmpty()) {
            boolean cold = hostile(village, p);
            // clean: an honest man gets an apology; one the village has no love for gets a warning
            talk(world, village, guard, p, cold ? "settlements.justice.search.clean.cold" : "settlements.justice.search.clean", world.getTime());
            if (!cold) village.pushHearts(p, 2); // he let them look, and was in the clear
            Fame.deed(world, village, p, Fame.Deed.CLEARED, guard, 1);
            return 1;
        }
        talk(world, village, guard, p, "settlements.justice.search.found", world.getTime());
        report(world, village, p, Crime.THEFT, s.value(), found, true);
        return 1;
    }

    // ---------- the stocks: the village about him, and the ways out ----------

    private static final Map<UUID, Long> JEERED = new HashMap<>();

    /** The square goes by him: a look, a laugh, a word, a child's taunt - and now and then somebody kind with water. */
    private static void jeers(ServerWorld world, Village village, ServerPlayerEntity p, Case c, long now) {
        if (now < JEERED.getOrDefault(p.getUuid(), 0L)) return;
        List<VillagerEntityMCA> by = world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(10, 3, 10),
                v -> v.isAlive() && !v.isSleeping() && v.canSee(p) && !v.isGuard());
        if (by.isEmpty()) return;
        JEERED.put(p.getUuid(), now + 20 * (15 + world.random.nextInt(20)));
        VillagerEntityMCA v = by.get(world.random.nextInt(by.size()));
        Gaze.at(v, p, 30f, 30f);
        int hearts = village.getReputation(p);
        String key = v.isBaby() ? "settlements.say.stocks.child" : hearts > 20 && world.random.nextBoolean() ? "settlements.say.stocks.kind" : "settlements.say.stocks.jeer";
        Speech.sayAny(v, key, 3, p.getName());
        if (key.endsWith("kind") && world.random.nextInt(3) == 0) {
            p.getHungerManager().add(2, 0.3f); // a drink of water, a crust
            Anim.set(v, Anim.Act.FEED, 30);
        }
    }

    /** In the stocks: the answers he has (the time to serve, a fine to buy his way out, a plea for mercy). */
    private static void stocksWindow(ServerWorld world, Village village, ServerPlayerEntity p, Case c) {
        java.util.List<String> o = new java.util.ArrayList<>(List.of("serve", "buyout"));
        if (!c.pleaded) o.add("mercy");
        List<Text> labels = new java.util.ArrayList<>();
        for (String s : o) {
            MutableText l = Text.translatable("settlements.justice.button." + s);
            if (s.equals("buyout")) l.append(Text.literal(" [" + buyout(world, c) + "◆]").formatted(Formatting.GRAY));
            if (s.equals("mercy")) l.append(Text.literal(" [" + (int) (pleadChance(p, village, c) * 100) + "%]").formatted(Formatting.GRAY));
            labels.add(l);
        }
        VillagerEntityMCA guard = nearestGuard(world, p, 32);
        net.mca.cobalt.network.NetworkHandler.sendToPlayer(new net.mca.network.s2c.JusticeScreenRequest(guard != null ? guard.getId() : -1,
                guard != null ? guard.getName() : Text.literal(village.getName()),
                List.of(Text.translatable("settlements.justice.stocks.talk", p.getName(), (int) Math.max(0, (c.until - world.getTime()) / 20))),
                o.toArray(new String[0]), labels, 0), p);
    }

    private static int buyout(ServerWorld world, Case c) {
        return Math.max(2, (int) Math.ceil((c.until - world.getTime()) / 20.0 / 30)); // an emerald for each half-minute left
    }

    /** /settle buyout: emeralds for the time left. */
    public static int buyoutStocks(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null || c.stage != Stage.SERVING) return 0;
        ServerWorld world = p.getServerWorld();
        Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
        int cost = buyout(world, c);
        if (village == null || p.getInventory().count(Items.EMERALD) < cost) {
            p.sendMessage(Text.translatable("settlements.justice.cannot_pay", cost).formatted(Formatting.RED), true);
            return 0;
        }
        int left = cost;
        for (int i = 0; i < p.getInventory().size() && left > 0; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isOf(Items.EMERALD)) continue;
            int d = Math.min(left, s.getCount());
            s.decrement(d);
            left -= d;
        }
        Workforce.addToTreasury(village, new ItemStack(Items.EMERALD, cost));
        release(world, village, p, c);
        CASES.remove(p.getUuid());
        return 1;
    }

    /** /settle mercy: a plea from the stocks; the village that thinks well of him lets him off early. */
    public static int mercy(ServerPlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null || c.stage != Stage.SERVING || c.pleaded) return 0;
        ServerWorld world = p.getServerWorld();
        Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
        if (village == null) return 0;
        c.pleaded = true;
        VillagerEntityMCA guard = nearestGuard(world, p, 32);
        if (world.random.nextDouble() < pleadChance(p, village, c)) {
            c.until = world.getTime() + (c.until - world.getTime()) / 3;
            talk(world, village, guard, p, "settlements.justice.mercy.ok", world.getTime());
        } else {
            talk(world, village, guard, p, "settlements.justice.mercy.no", world.getTime());
        }
        return 1;
    }

    /** /settle serve: he takes it. */
    public static int serve(ServerPlayerEntity p) {
        return 1;
    }

    // ---------- for the village's rogues (see Rogue) ----------

    /** The players doing their time in this village's stocks or jail. */
    static List<ServerPlayerEntity> prisoners(ServerWorld world, Village village) {
        List<ServerPlayerEntity> out = new java.util.ArrayList<>();
        for (Case c : CASES.values()) {
            if (c.stage != Stage.SERVING || c.villageId != village.getId() || c.offered) continue;
            ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(c.player);
            if (p != null && p.getWorld() == world) out.add(p);
        }
        return out;
    }

    static void markOffered(PlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c != null) c.offered = true;
    }

    static int secondsLeft(PlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        return c == null || c.stage != Stage.SERVING ? 0 : (int) Math.max(0, (c.until - p.getWorld().getTime()) / 20);
    }

    static boolean serving(PlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        return c != null && c.stage == Stage.SERVING;
    }

    /** Let out on the quiet: free and clear; or seen at it, and then he is wanted again with more against him. */
    static void broken(ServerPlayerEntity p, boolean seen) {
        Case c = CASES.get(p.getUuid());
        if (c == null || c.stage != Stage.SERVING) return;
        ServerWorld world = p.getServerWorld();
        Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
        Seat.rise(p);
        if (village != null) Life.forget(village, "crime.stocks", p.getName().getString());
        p.removeStatusEffect(StatusEffects.SLOWNESS);
        p.removeStatusEffect(StatusEffects.JUMP_BOOST);
        p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
        p.removeStatusEffect(StatusEffects.WEAKNESS);
        if (seen) {
            c.stage = Stage.WANTED;
            c.since = world.getTime();
            c.inStocks = false;
            c.value += 12;
            p.sendMessage(Text.translatable("settlements.justice.escaped").formatted(Formatting.RED), false);
            if (village != null) Life.news(village, "crime.escape", p.getName().getString());
        } else {
            CASES.remove(p.getUuid());
            if (village != null) {
                village.pushHearts(p, -4);
                Life.news(village, "crime.vanished", p.getName().getString());
            }
        }
    }

    /** For the elder's book and the board: what the village holds against this player, or null. */
    public static Text standing(PlayerEntity p) {
        Case c = CASES.get(p.getUuid());
        if (c == null) return null;
        return Text.translatable("settlements.justice.stage." + c.stage.name().toLowerCase());
    }

    static List<UUID> players() {
        return List.copyOf(CASES.keySet());
    }
}
