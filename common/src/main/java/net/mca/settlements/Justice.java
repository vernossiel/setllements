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
        long deadline, lastTalk, until;
        int arrests;
        BlockPos hold;

        Case(UUID player) {
            this.player = player;
        }
    }

    private static final Map<UUID, Case> CASES = new HashMap<>();
    private static final Map<UUID, int[]> STRIKES = new HashMap<>(); // {strikes, last tick / 20}
    private static final Map<UUID, Integer> RECORD = new HashMap<>(); // arrests so far
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
        if (village == null || player.isCreative() || player.isSpectator()) return;
        Case c = CASES.computeIfAbsent(player.getUuid(), Case::new);
        boolean fresh = c.stage == Stage.REPORTED && c.crime == null;
        c.villageId = village.getId();
        if (c.crime == null || crime.ordinal() > c.crime.ordinal()) c.crime = crime;
        c.value += value;
        c.witnessed |= witnessed;
        if (items != null) items.forEach((k, n) -> c.stolen.merge(k, n, Integer::sum));
        if (crime == Crime.MURDER) {
            c.stage = Stage.EXILED;
            c.until = world.getTime() + 24000L * 3;
        } else if (crime == Crime.ASSAULT && c.stage.ordinal() < Stage.RESISTING.ordinal()) {
            // a villager is being beaten: the guards step in at once, to beat him down and arrest him
            c.stage = Stage.RESISTING;
            for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, player.getBoundingBox().expand(48), VillagerEntityMCA::isGuard)) {
                g.getBrain().remember(MemoryModuleType.ATTACK_TARGET, player, 1200);
            }
        }
        if (fresh) Life.news(village, "crime." + crime.name().toLowerCase(), player.getName().getString());
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

    private record Suspicion(UUID player, int villageId, Crime crime, double value, Map<Item, Integer> items, BlockPos where, boolean seen, long[] at) {
    }

    private static final List<Suspicion> SUSPICIONS = new java.util.ArrayList<>();

    /**
     * Something done unseen: a few minutes later somebody finds the chest emptied or the wall broken. If the player
     * was seen about the place, he is suspected; otherwise it stays a mystery the village talks about.
     */
    public static void suspect(ServerWorld world, Village village, PlayerEntity p, Crime crime, double value, Map<Item, Integer> items, BlockPos where) {
        if (village == null || p.isCreative()) return;
        boolean seen = !world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(32),
                v -> v.isAlive() && !v.isSleeping() && v.canSee(p)).isEmpty();
        long at = world.getTime() + 20L * (120 + world.random.nextInt(240));
        SUSPICIONS.add(new Suspicion(p.getUuid(), village.getId(), crime, value, items == null ? Map.of() : Map.copyOf(items), where, seen, new long[]{at}));
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
            VillagerEntityMCA guard = nearestGuard(world, p, 64);
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
                        talk(world, village, guard, p, "settlements.justice.wanted", now);
                    } else if (now - c.lastTalk > 400) {
                        demand(world, village, guard, p, c);
                    }
                }
                case WANTED -> {
                    if (guard == null) continue;
                    guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(p, 0.6f, 1));
                    if (guard.squaredDistanceTo(p) < 16) {
                        c.stage = Stage.ARREST;
                        c.deadline = now + 20 * 20;
                        c.lastTalk = now;
                        guard.sendChatMessage(Text.translatable("settlements.justice.arrest", p.getName()), p);
                        p.sendMessage(buttons(new String[]{"surrender", "resist"}), false);
                    }
                }
                case ARREST -> {
                    if (guard != null && guard.squaredDistanceTo(p) > 9) guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(p, 0.6f, 1));
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
                    if (c.hold != null && p.squaredDistanceTo(c.hold.getX() + 0.5, c.hold.getY(), c.hold.getZ() + 0.5) > 9) {
                        // broke out
                        c.stage = Stage.WANTED;
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
                    if (village.isWithinBorder(p) && now - c.lastTalk > 600) talk(world, village, guard, p, "settlements.justice.exiled_back", now);
                }
            }
        }
    }

    private static int strikes(PlayerEntity p) {
        int[] s = STRIKES.get(p.getUuid());
        return s == null ? 0 : s[0];
    }

    private static VillagerEntityMCA nearestGuard(ServerWorld world, PlayerEntity p, double range) {
        return world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(range), g -> g.isGuard() && g.isAlive() && !g.isSleeping())
                .stream().min(Comparator.comparingDouble(g -> g.squaredDistanceTo(p))).orElse(null);
    }

    /** Whoever speaks for the village: the guard, or else the elder, or else nobody but the message. */
    private static void talk(ServerWorld world, Village village, VillagerEntityMCA guard, ServerPlayerEntity p, String key, long now) {
        VillagerEntityMCA speaker = guard != null ? guard : Plan.elder(world, village);
        if (speaker != null && speaker.squaredDistanceTo(p) < 48 * 48) speaker.sendChatMessage(Text.translatable(key, p.getName()), p);
        else p.sendMessage(Text.translatable(key, p.getName()).formatted(Formatting.GOLD), false);
        Case c = CASES.get(p.getUuid());
        if (c != null) c.lastTalk = now;
    }

    private static void demand(ServerWorld world, Village village, VillagerEntityMCA guard, ServerPlayerEntity p, Case c) {
        String key = "settlements.justice.demand." + c.crime.name().toLowerCase();
        talk(world, village, guard, p, key, world.getTime());
        if (!c.stolen.isEmpty()) p.sendMessage(Text.translatable("settlements.justice.fine_or_return", fine(c)).formatted(Formatting.GRAY), false);
        else p.sendMessage(Text.translatable("settlements.justice.fine", fine(c)).formatted(Formatting.GRAY), false);
        p.sendMessage(buttons(c.stolen.isEmpty() ? new String[]{"pay", "deny", "leave"} : new String[]{"return", "pay", "deny", "leave"}), false);
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
        ServerWorld world = sp.getServerWorld();
        Case c = CASES.get(p.getUuid());
        if (c != null && c.stage != Stage.SERVING && c.stage != Stage.EXILED) {
            guard.sendChatMessage(Text.translatable("settlements.justice.subdued", p.getName()), p);
            sentence(world, sp, c);
            return true;
        }
        // nothing to arrest him for (a sworn enemy, an exile come back): beaten, thrown out, left alone a while
        SPARED.put(p.getUuid(), world.getTime() + 1200);
        for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(64), VillagerEntityMCA::isGuard)) {
            if (g.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == p) g.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
        }
        double dx = p.getX() - guard.getX(), dz = p.getZ() - guard.getZ(), len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        p.takeKnockback(1.2, -dx / len, -dz / len);
        sp.velocityModified = true;
        guard.sendChatMessage(Text.translatable("settlements.justice.beaten", p.getName()), p);
        return true;
    }

    /** Beaten down and thrown out a moment ago: the guards let him crawl away. */
    public static boolean spared(PlayerEntity p) {
        Long until = SPARED.get(p.getUuid());
        if (until == null) return false;
        if (p.getWorld().getTime() < until) return true;
        SPARED.remove(p.getUuid());
        return false;
    }

    /** A guard's blow on a player hurts, but never kills: a few hearts at most. */
    public static float blow(PlayerEntity p, float damage) {
        return Math.min(damage, Math.max(1f, Math.min(5f, p.getHealth() - 4f)));
    }

    // ---------- punishment ----------

    private static void sentence(ServerWorld world, ServerPlayerEntity p, Case c) {
        Village village = VillageManager.get(world).getOrEmpty(c.villageId).orElse(null);
        for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(64), VillagerEntityMCA::isGuard)) {
            if (g.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == p) g.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
        }
        int record = RECORD.merge(p.getUuid(), 1, Integer::sum);
        if (village != null) Life.forgive(village, p);
        if (village == null) {
            CASES.remove(p.getUuid());
            return;
        }
        if (!c.stolen.isEmpty()) takeBack(p, c, village); // confiscated
        if (record >= 3) { // a known criminal: out of the village
            exile(world, village, p, c);
            return;
        }
        int seconds = (int) Math.max(60, Math.min(300, 60 + c.value * 4 + (c.crime == Crime.ASSAULT ? 120 : 0)));
        c.stage = Stage.SERVING;
        c.until = world.getTime() + seconds * 20L;
        Built jail = Buildings.find(village, "jail");
        if (jail != null) {
            BlockPos cell = jail.point(2, 1, 1);
            p.teleport(world, cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, p.getYaw(), p.getPitch());
            c.hold = cell;
            p.sendMessage(Text.translatable("settlements.justice.jailed", seconds).formatted(Formatting.GOLD), false);
            Life.news(village, "crime.jailed", p.getName().getString());
        } else {
            // the stocks on the square: he cannot move until his time is up
            BlockPos sq = village.hallPos != null ? village.hallPos : new BlockPos(village.getCenter());
            BlockPos spot = Camp.hearth(village, world) != null ? Camp.hearth(village, world).add(0, 0, 3) : sq;
            p.teleport(world, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, p.getYaw(), p.getPitch());
            int ticks = seconds * 20;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 6, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, ticks, 128, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, ticks, 4, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, ticks, 4, false, false));
            c.hold = spot;
            p.sendMessage(Text.translatable("settlements.justice.stocks", seconds).formatted(Formatting.GOLD), false);
            Life.news(village, "crime.stocks", p.getName().getString());
        }
    }

    private static void release(ServerWorld world, Village village, ServerPlayerEntity p, Case c) {
        Built jail = Buildings.find(village, "jail");
        if (jail != null && c.hold != null) {
            BlockPos out = jail.point(3, 0, 7);
            p.teleport(world, out.getX() + 0.5, out.getY(), out.getZ() + 0.5, p.getYaw(), p.getPitch());
        }
        p.removeStatusEffect(StatusEffects.SLOWNESS);
        p.removeStatusEffect(StatusEffects.JUMP_BOOST);
        p.removeStatusEffect(StatusEffects.MINING_FATIGUE);
        p.removeStatusEffect(StatusEffects.WEAKNESS);
        village.pushHearts(p, 8); // the debt is paid
        p.sendMessage(Text.translatable("settlements.justice.released").formatted(Formatting.GREEN), false);
    }

    private static void exile(ServerWorld world, Village village, ServerPlayerEntity p, Case c) {
        c.stage = Stage.EXILED;
        c.until = world.getTime() + 24000L;
        p.sendMessage(Text.translatable("settlements.justice.exiled").formatted(Formatting.DARK_RED), false);
        Life.news(village, "crime.exiled", p.getName().getString());
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
