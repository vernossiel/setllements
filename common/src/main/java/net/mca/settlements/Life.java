package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What makes a village feel inhabited: villagers notice the player and greet him according to his name in the
 * village, back away from thieves and from drawn weapons, talk to each other about what really happens in the village
 * (news, shortages, the weather, the player), get out of thunderstorms, share food with the hungry, and the children
 * play tag in the streets.
 */
public final class Life {
    public record News(String key, Object[] args, long time) {
    }

    private static final Map<Integer, Deque<News>> NEWS = new HashMap<>();
    private static final long NEWS_TTL = 24000 * 2;
    private static final String[] WORK_TALK = {"cartographer", "mason", "fletcher", "toolsmith", "farmer", "shepherd", "butcher", "armorer", "cleric", "fisherman"};

    private Life() {
    }

    // ---------- news ----------

    public static void news(Village village, String key, Object... args) {
        if (village == null) return;
        Deque<News> q = NEWS.computeIfAbsent(village.getId(), k -> new ArrayDeque<>());
        long now = village.getWorld() == null ? 0 : village.getWorld().getTime();
        q.addFirst(new News(key, args, now));
        while (q.size() > 8) q.removeLast();
    }

    /** A piece of news that is no longer true (he is out of the stocks): forgotten. */
    public static void forget(Village village, String key, String arg) {
        if (village == null) return;
        Deque<News> q = NEWS.get(village.getId());
        if (q != null) q.removeIf(n -> n.key().equals(key) && n.args().length > 0 && arg.equals(String.valueOf(n.args()[0])));
    }

    public static List<News> news(Village village) {
        Deque<News> q = NEWS.get(village.getId());
        if (q == null) return List.of();
        long now = village.getWorld() == null ? 0 : village.getWorld().getTime();
        q.removeIf(n -> now - n.time() > NEWS_TTL);
        return new ArrayList<>(q);
    }

    public static void onDeath(VillagerEntityMCA v, DamageSource cause) {
        if (!(v.getWorld() instanceof ServerWorld world)) return;
        Village village = Village.findNearest(v).orElse(null);
        String name = v.getName().getString();
        // for the record (and the tests): when, where and at what he died
        net.mca.MCA.LOGGER.info("[Settlements] {} died at {} (time {}, sky light {}, in {}): {}", name, v.getBlockPos().toShortString(),
                world.getTimeOfDay() % 24000L, world.getLightLevel(net.minecraft.world.LightType.SKY, v.getBlockPos()),
                v.getResidency().getHome().isPresent() ? "a village with a home" : "no home", Doing.describe(v).getString());
        if (cause.getAttacker() instanceof PlayerEntity p) {
            news(village, "murder", name, p.getName().getString());
            Justice.report(world, village, p, Justice.Crime.MURDER, 50, null, true);
            Fame.deed(world, village, p, Fame.Deed.MURDER, v, 1);
            // a killing: everyone who saw it or heard the scream runs, and every guard within earshot comes running
            if (village != null) {
                FEAR.put(key(village, p), world.getTime() + FEAR_TICKS);
                Remember.note(village, p, "struck");
                village.pushHearts(p, -40);
                for (VillagerEntityMCA o : world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(64),
                        o -> o != v && o.isAlive() && !o.isSleeping())) {
                    if (o.isGuard()) charge(o, p);
                    else if (o.squaredDistanceTo(v) < 32 * 32 || o.canSee(p)) flee(o, p, village, world);
                }
                callGuards(world, village, v, p, true);
            }
        }
        else news(village, "death", name);
        if (village != null) Chapel.bury(world, village, name);
        if (village != null) { // the village mourns, at the churchyard if there is one
            Built chapel = Buildings.find(village, "chapel");
            Mood.event(village, Mood.Kind.GRIEF, chapel != null ? chapel.point(2, 0, 2) : village.hallPos != null ? village.hallPos : v.getBlockPos());
        }
        for (VillagerEntityMCA o : world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(16), o -> o != v && o.isAlive() && !o.isBaby())) {
            Speech.say(o, "settlements.react.mourn", name);
        }
    }

    // ---------- fear ----------

    private static final Map<String, Long> FEAR = new HashMap<>();
    private static final Map<String, Long> CALLED = new HashMap<>();
    private static final long FEAR_TICKS = 24000L * 3;

    private static String key(Village village, PlayerEntity p) {
        return village.getId() + ":" + p.getUuid();
    }

    /** A player who attacked the village is feared for three days. */
    public static boolean feared(Village village, PlayerEntity p) {
        if (village == null) return false;
        Long until = FEAR.get(key(village, p));
        return until != null && village.getWorld() != null && until > village.getWorld().getTime();
    }

    /** Players the guards go for on sight: those who attacked the village, and outlaws with a bad name here. */
    public static boolean hostile(Village village, PlayerEntity p) {
        if (village == null || p.isSpectator() || Justice.spared(p)) return false; // creative or not: a killer is a killer
        // the guards go for those resisting arrest, the exiled, and the village's sworn enemies; a bad name alone
        // gets a guard watching you, not a sword
        return Justice.hunted(village, p) || Reputation.tier(village.getReputation(p)) == 0;
    }

    /** The sentence is served: the village stops running from him. */
    public static void forgive(Village village, PlayerEntity p) {
        FEAR.remove(key(village, p));
    }

    public static PlayerEntity hostilePlayer(ServerWorld world, Village village, VillagerEntityMCA watcher, double range) {
        return nearestPlayer(world, watcher, range, p -> hostile(village, p));
    }

    /** Whom the villagers run from: everyone the guards fight, and whoever beat one of them lately. */
    static PlayerEntity scaryPlayer(ServerWorld world, Village village, VillagerEntityMCA watcher, double range) {
        return nearestPlayer(world, watcher, range, p -> hostile(village, p) || feared(village, p));
    }

    private static PlayerEntity nearestPlayer(ServerWorld world, VillagerEntityMCA watcher, double range, java.util.function.Predicate<PlayerEntity> which) {
        PlayerEntity best = null;
        double bd = range * range;
        for (PlayerEntity p : world.getPlayers()) {
            double d = p.squaredDistanceTo(watcher);
            if (d < bd && p.isAlive() && which.test(p) && watcher.canSee(p)) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** Somebody hit a villager: screams, everybody who saw it runs, the guards come running. */
    public static void onAttacked(VillagerEntityMCA victim, PlayerEntity attacker) {
        if (!(victim.getWorld() instanceof ServerWorld world) || attacker.isSpectator()) return;
        Village village = Village.findNearest(victim).orElse(null);
        if (village == null) return;
        long now = world.getTime();
        // a first or second blow is pestering: the victim protests and the guard has a word (see Justice);
        // only a third makes it an assault that frightens the village
        Justice.Crime crime = Justice.hit(world, village, attacker);
        Fame.deed(world, village, attacker, crime == Justice.Crime.HARASSMENT ? Fame.Deed.HARASS : Fame.Deed.ASSAULT, victim, 1);
        if (crime == Justice.Crime.HARASSMENT) {
            Speech.sayNow(victim, "settlements.react.hit");
            Gaze.at(victim, attacker, 30f, 30f);
            return;
        }
        String k = key(village, attacker);
        boolean fresh = !feared(village, attacker);
        FEAR.put(k, now + FEAR_TICKS);
        if (fresh) {
            Mood.event(village, Mood.Kind.FEAR, null);
            news(village, "attacked", attacker.getName().getString(), victim.getName().getString());
            Remember.note(village, attacker, "struck");
            village.pushHearts(attacker, -15);
        }
        Speech.sayNow(victim, "settlements.react.help");
        victim.settlementsFleeUntil = now + 20 * 60; // the one who was struck is a while getting over it
        for (VillagerEntityMCA o : world.getEntitiesByClass(VillagerEntityMCA.class, victim.getBoundingBox().expand(32),
                o -> o.isAlive() && !o.isSleeping() && (o.canSee(attacker) || o.canSee(victim) || o.squaredDistanceTo(victim) < 64))) {
            if (o.isGuard()) {
                charge(o, attacker);
            } else if (o != victim) {
                flee(o, attacker, village, world);
                if (world.random.nextInt(3) == 0) Speech.say(o, "settlements.react.attack");
            }
        }
        if (!victim.isGuard()) flee(victim, attacker, village, world);
        callGuards(world, village, victim, attacker, true);
    }

    private static void charge(VillagerEntityMCA guard, PlayerEntity target) {
        guard.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET, target, 400);
        Speech.say(guard, "settlements.say.guard.charge", target.getName());
    }

    /**
     * Getting over a fright: away from the one who did it while he is about, then home (or to the town hall) to sit
     * it out, with a word now and then; nobody goes straight back to work after being beaten.
     */
    private static void shaken(VillagerEntityMCA v, Village village, ServerWorld world) {
        Doing.set(v, "settlements.doing.shaken");
        PlayerEntity bad = village == null ? null : scaryPlayer(world, village, v, 24);
        if (bad != null && v.getNavigation().isIdle()) {
            flee(v, bad, village, world);
            v.settlementsFleeUntil = Math.max(v.settlementsFleeUntil, world.getTime() + 20 * 20);
            return;
        }
        if (!v.getNavigation().isIdle()) return;
        BlockPos safe = v.getResidency().getHome().filter(h -> h.getDimension() == world.getRegistryKey()).map(net.minecraft.util.math.GlobalPos::getPos)
                .orElse(village != null ? village.hallPos : null);
        if (safe != null && v.squaredDistanceTo(Vec3d.ofBottomCenter(safe)) > 9) {
            v.getNavigation().startMovingTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, 0.6);
        } else if (world.random.nextInt(25) == 0) {
            Speech.sayAny(v, "settlements.say.shaken", 4);
        }
    }

    /** Runs from the danger: towards the town hall if it lies away from the threat, otherwise simply away. */
    static void flee(VillagerEntityMCA v, PlayerEntity from, Village village, ServerWorld world) {
        Rest.end(v); // up from the seat first
        Vec3d away = null;
        if (village != null && village.hallPos != null) {
            Vec3d hall = Vec3d.ofBottomCenter(village.hallPos);
            if (hall.squaredDistanceTo(from.getPos()) > hall.squaredDistanceTo(v.getPos()) + 16 && hall.squaredDistanceTo(v.getPos()) < 64 * 64) away = hall;
        }
        if (away == null) away = NoPenaltyTargeting.findFrom(v, 14, 5, from.getPos());
        if (away != null) v.getNavigation().startMovingTo(away.x, away.y, away.z, 0.7); // running, but not like a madman
        v.settlementsFleeUntil = world.getTime() + 160;
    }

    /** Runs from a monster: towards the nearest guard if there is one, else away from it. */
    static void fleeFrom(VillagerEntityMCA v, net.minecraft.entity.Entity from, ServerWorld world) {
        Rest.end(v);
        net.mca.settlements.Dig.stop(v);
        VillagerEntityMCA guard = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(40), g -> g.isGuard() && g.isAlive() && !g.isSleeping())
                .stream().min(java.util.Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
        Vec3d to = null;
        if (guard != null && guard.squaredDistanceTo(from) > v.squaredDistanceTo(from) * 0.5) to = guard.getPos();
        if (to == null) to = NoPenaltyTargeting.findFrom(v, 14, 5, from.getPos());
        if (to != null) v.getNavigation().startMovingTo(to.x, to.y, to.z, 0.75);
        Doing.set(v, "settlements.doing.flee", from.getName());
        v.settlementsFleeUntil = world.getTime() + 160;
    }

    /** Calls the guards on a player, at most every twenty seconds per player. */
    static void callGuards(ServerWorld world, Village village, VillagerEntityMCA caller, PlayerEntity p, boolean attack) {
        String k = key(village, p);
        long now = world.getTime();
        if (now < CALLED.getOrDefault(k, 0L)) return;
        CALLED.put(k, now + 400);
        GuardIntel.reportThreat(world, p);
        List<VillagerEntityMCA> guards = world.getEntitiesByClass(VillagerEntityMCA.class, caller.getBoundingBox().expand(48),
                g -> g.isGuard() && g.isAlive() && !g.isSleeping());
        guards.sort(java.util.Comparator.comparingDouble(g -> g.squaredDistanceTo(p)));
        int n = 0;
        for (VillagerEntityMCA g : guards) {
            if (n++ >= (attack ? 3 : 1)) break;
            if (attack) charge(g, p);
            else {
                g.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET, new net.minecraft.entity.ai.brain.WalkTarget(p, 0.5f, 3));
                g.settlementsWatch = p.getUuid();
            }
        }
        if (!guards.isEmpty()) Speech.say(caller, attack ? "settlements.react.call_guards" : "settlements.react.call_guard");
    }

    // ---------- every second ----------

    /** True when the villager is busy with life itself (sheltering, bringing food) and should not work this second. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, long time) {
        long now = world.getTime();
        if (v.settlementsReplyKey != null && now >= v.settlementsReplyAt) {
            Speech.sayNow(v, v.settlementsReplyKey, v.settlementsReplyArgs);
            v.settlementsReplyKey = null;
        }
        if (!v.settlementsQueue.isEmpty() && now >= v.settlementsQueueAt) {
            Speech.sayText(v, v.settlementsQueue.poll());
            v.settlementsQueueAt = now + 40;
        }
        if (v.isSleeping()) return false;

        if (notice(v, village, world, time)) return true;
        if (now < v.settlementsFleeUntil && !v.isGuard()) { // still running and shaking
            shaken(v, village, world);
            return true;
        }

        if (!v.isGuard() && shelter(v, village, world, time)) return true;
        if (!Seat.seated(v)) passing(v, world);
        if (share(v, village, world)) return true;
        if (!Persona.working(v, time) || v.settlementsBreak) converse(v, village, world);
        return false;
    }

    /** True when the villager is running from the player this second. */
    private static boolean notice(VillagerEntityMCA v, Village village, ServerWorld world, long time) {
        // somebody who attacked the village, or an outlaw: seen from afar, the alarm is raised and everybody runs
        if (!v.isGuard() && village != null) {
            PlayerEntity enemy = scaryPlayer(world, village, v, 20);
            if (enemy != null) {
                flee(v, enemy, village, world);
                Doing.set(v, "settlements.doing.flee", enemy.getName());
                Speech.say(v, feared(village, enemy) ? "settlements.react.attacker_seen" : "settlements.react.fear");
                callGuards(world, village, v, enemy, true);
                return true;
            }
        }
        PlayerEntity p = world.getClosestPlayer(v, 12);
        if (p == null || p.isSpectator() || p.isCreative() && p.isInvisible() || !v.canSee(p)) return false;
        int rep = village == null ? 0 : village.getReputation(p);
        int tier = Reputation.tier(rep);
        double d = v.squaredDistanceTo(p);
        if (v.getNavigation().isIdle() && d < 25) Gaze.at(v, p, 30f, 30f);

        // a bad name: nobody runs yet, but the guard is fetched to keep an eye on him
        if (tier == 2 && !v.isGuard() && village != null) callGuards(world, village, v, p, false);

        ItemStack held = p.getMainHandStack();
        boolean armed = held.getItem() instanceof SwordItem || held.getItem() instanceof AxeItem || held.getItem() instanceof BowItem
                || held.getItem() instanceof CrossbowItem || held.getItem() instanceof TridentItem;
        if (armed && !v.isGuard() && tier <= 3 && d < 16) {
            Speech.say(v, "settlements.react.weapon");
            if (d < 9) { // too close for comfort: step back
                Vec3d back = NoPenaltyTargeting.findFrom(v, 6, 3, p.getPos());
                if (back != null) v.getNavigation().startMovingTo(back.x, back.y, back.z, 0.6);
            }
            return false;
        }
        if (d > 49) return false;
        long day = time / 24000L;
        if (v.settlementsGreetDay != day && d < 36) {
            v.settlementsGreetDay = day;
            if (v.isElder() && tier >= 2 && village != null) {
                Speech.say(v, "settlements.greet.elder", p.getName(), village.getName());
                return false;
            }
            long t = time % 24000L;
            if (tier >= 3 && t < 2000) Speech.say(v, "settlements.greet.morning", p.getName());
            else if (tier >= 3 && t > 11500) Speech.say(v, "settlements.greet.evening", p.getName());
            else Speech.say(v, "settlements.greet." + tier + "." + world.random.nextInt(3), p.getName());
        }
        return false;
    }

    private static boolean shelter(VillagerEntityMCA v, Village village, ServerWorld world, long time) {
        BlockPos feet = v.getBlockPos();
        boolean storm = world.isThundering();
        boolean rain = world.isRaining() && world.hasRain(feet.up());
        if (!(storm || rain && !Workforce.isWorkTime(time))) return false;
        if (!world.isSkyVisible(feet.up())) return false;
        BlockPos roof = null;
        if (village != null && village.hallPos != null && village.hallPos.isWithinDistance(feet, 48)) roof = village.hallPos;
        for (int i = 0; i < 24 && roof == null; i++) {
            BlockPos q = feet.add(world.random.nextBetween(-12, 12), world.random.nextBetween(-2, 2), world.random.nextBetween(-12, 12));
            if (!world.isSkyVisible(q) && Move.passable(world, q) && Move.passable(world, q.up()) && world.getBlockState(q.down()).isSolidBlock(world, q.down())) roof = q;
        }
        if (roof == null) return false;
        Rest.end(v);
        Move.to(v, roof.getX() + 0.5, roof.getY(), roof.getZ() + 0.5, 0.7);
        Speech.say(v, storm ? "settlements.react.storm" : "settlements.react.rain");
        return true;
    }

    /** A villager with food to spare brings some to a hungry neighbour who has none. */
    private static boolean share(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (v.settlementsHunger > 300 || Needs.get(v) == Needs.Need.FOOD) return false;
        int spare = 0, slot = -1;
        var inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isFood() && Workforce.eatable(s)) {
                spare += s.getCount();
                slot = i;
            }
        }
        if (spare < 2) return false;
        List<VillagerEntityMCA> hungry = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(14),
                o -> o != v && o.isAlive() && Needs.get(o) == Needs.Need.FOOD);
        if (hungry.isEmpty()) return false;
        VillagerEntityMCA o = hungry.get(0);
        if (v.squaredDistanceTo(o) > 6) {
            Move.to(v, o, 0.65);
            Speech.say(v, "settlements.react.bring_food", o.getName());
            return true;
        }
        o.getInventory().addStack(inv.getStack(slot).split(1));
        v.swingHand(v.getDominantHand());
        Gaze.at(v, o, 30f, 30f);
        Needs.set(o, Needs.Need.NONE);
        Speech.sayNow(v, "settlements.react.share");
        o.settlementsReplyKey = "settlements.react.thanks";
        o.settlementsReplyArgs = new Object[]{v.getName()};
        o.settlementsReplyAt = world.getTime() + 40;
        return true;
    }

    // ---------- asking each other ----------

    private static final Map<Integer, Long> ASKED = new HashMap<>();

    /**
     * The builder short of something calls on the one who brings it: the lumberjack for wood, the miner for stone.
     * The other answers and takes what he carries to the stores at once.
     */
    public static void askForHelp(VillagerEntityMCA builder, Village village, ServerWorld world, Needs.Need need) {
        if (village == null || need == null || need == Needs.Need.NONE) return;
        long now = world.getTime();
        if (now < ASKED.getOrDefault(village.getId(), 0L)) return;
        VillagerProfession who = need == Needs.Need.PLANKS ? VillagerProfession.FLETCHER : need == Needs.Need.COBBLE ? VillagerProfession.MASON : null;
        if (who == null) return;
        VillagerEntityMCA helper = world.getEntitiesByClass(VillagerEntityMCA.class, builder.getBoundingBox().expand(96, 24, 96),
                o -> o.isAlive() && (o.getProfession() == who || who == VillagerProfession.FLETCHER && o.getProfession() == VillagerProfession.TOOLSMITH))
                .stream().min(java.util.Comparator.comparingDouble(o -> o.squaredDistanceTo(builder))).orElse(null);
        if (helper == null) return;
        ASKED.put(village.getId(), now + 2400);
        Speech.sayNow(builder, "settlements.say.ask." + need.name().toLowerCase(), helper.getName());
        helper.settlementsReplyKey = "settlements.say.ask.ok";
        helper.settlementsReplyArgs = new Object[]{builder.getName()};
        helper.settlementsReplyAt = now + 60;
        helper.settlementsDeliverNow = true;
    }

    // ---------- passing by ----------

    /** Two villagers passing each other say hello now and then: a wave, a word, an answer. */
    private static void passing(VillagerEntityMCA v, ServerWorld world) {
        long now = world.getTime();
        if (now < v.settlementsHelloAfter || v.getRandom().nextInt(6) != 0) return;
        VillagerEntityMCA other = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(3),
                o -> o != v && o.isAlive() && !o.isBaby() && !o.isSleeping() && o.settlementsReplyKey == null && now >= o.settlementsHelloAfter)
                .stream().findFirst().orElse(null);
        if (other == null) return;
        v.settlementsHelloAfter = now + 2400 + v.getRandom().nextInt(2400);
        other.settlementsHelloAfter = v.settlementsHelloAfter;
        Gaze.at(v, other, 30f, 30f);
        Anim.set(v, Anim.Act.WAVE, 30);
        Speech.say(v, "settlements.talk.hello." + v.getRandom().nextInt(4), other.getName());
        other.settlementsReplyKey = "settlements.talk.hello_back." + v.getRandom().nextInt(4);
        other.settlementsReplyArgs = new Object[]{v.getName()};
        other.settlementsReplyAt = now + 30;
    }

    // ---------- conversations ----------

    /** A word with whoever sits nearby during a break. */
    public static void restingChat(VillagerEntityMCA v, ServerWorld world) {
        Village village = Village.findNearest(v).orElse(null);
        converse(v, village, world);
    }

    private static void converse(VillagerEntityMCA v, Village village, ServerWorld world) {
        long now = world.getTime();
        if (now < v.settlementsTalkAfter || world.getClosestPlayer(v, 12) == null) return;
        List<VillagerEntityMCA> near = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(4),
                o -> o != v && o.isAlive() && !o.isBaby() && !o.isSleeping() && o.settlementsReplyKey == null && now >= o.settlementsTalkAfter);
        if (near.isEmpty()) return;
        exchange(v, near.get(world.random.nextInt(near.size())), village, world);
    }

    /** A few words between two villagers: one says what is on his mind, the other answers. */
    static void exchange(VillagerEntityMCA v, VillagerEntityMCA other, Village village, ServerWorld world) {
        long now = world.getTime();
        if (now < v.settlementsTalkAfter || other.settlementsReplyKey != null) return;
        String[] topic = topic(v, village, world);
        if (topic == null) return;
        Object[] args = topic.length > 1 ? new Object[]{topic[1]} : new Object[0];
        Gaze.at(v, other, 30f, 30f);
        Gaze.at(other, v, 30f, 30f);
        Speech.sayNow(v, "settlements.talk." + topic[0] + ".a", args);
        other.settlementsReplyKey = "settlements.talk." + topic[0] + ".b";
        other.settlementsReplyArgs = args;
        other.settlementsReplyAt = now + 50;
        long pause = 1600 + world.random.nextInt(1600);
        v.settlementsTalkAfter = now + pause;
        other.settlementsTalkAfter = now + pause;
    }

    /** What is on this villager's mind: {topic} or {topic, argument}. */
    private static String[] topic(VillagerEntityMCA v, Village village, ServerWorld world) {
        var r = world.random;
        if (village != null) {
            List<News> news = news(village);
            if (!news.isEmpty() && r.nextInt(2) == 0) {
                News n = news.get(r.nextInt(Math.min(3, news.size())));
                Object a = n.args().length > 0 ? n.args()[0] : "";
                return new String[]{"news." + n.key(), a instanceof Text t ? t.getString() : String.valueOf(a)};
            }
            Needs.Need need = Plan.firstBlocker(village);
            if (need != Needs.Need.NONE && r.nextInt(3) == 0) return new String[]{"need." + need.name().toLowerCase()};
        }
        if (v.settlementsHunger >= 400 && r.nextInt(2) == 0) return new String[]{"hungry"};
        if (world.isRaining() && r.nextInt(3) == 0) return new String[]{"rain"};
        PlayerEntity p = world.getClosestPlayer(v, 24);
        if (p != null && village != null && r.nextInt(3) == 0) {
            int tier = Reputation.tier(village.getReputation(p));
            if (tier >= 5) return new String[]{"player_good", p.getName().getString()};
            if (tier <= 2) return new String[]{"player_bad", p.getName().getString()};
        }
        String prof = v.isGuard() ? "guard" : Registries.VILLAGER_PROFESSION.getId(v.getProfession()).getPath();
        if (v.isElder()) prof = "elder";
        if (r.nextInt(2) == 0 && (prof.equals("guard") || prof.equals("elder") || List.of(WORK_TALK).contains(prof))) return new String[]{"work." + prof};
        return new String[]{"small." + r.nextInt(8)};
    }

    // ---------- children ----------

    public static void child(VillagerEntityMCA v) {
        if (!(v.getWorld() instanceof ServerWorld world) || v.isSleeping()) return;
        long t = world.getTimeOfDay() % 24000L;
        if (t < 1000 || t > 11000 || world.isRaining()) return;
        if ((v.age / 20 + v.getUuid().hashCode()) % 4 != 0) return;
        // now and then off to watch father or mother at work, and copy them
        if (!v.isBaby() || v.getRandom().nextInt(3) == 0) {
            net.mca.server.world.data.FamilyTreeNode me = v.getRelationships().getFamilyEntry();
            for (java.util.UUID pid : new java.util.UUID[]{me.father(), me.mother()}) {
                if (pid == null || !(world.getEntity(pid) instanceof VillagerEntityMCA parent) || !parent.isAlive() || parent.isSleeping()) continue;
                if (!Persona.working(parent, world.getTimeOfDay()) || parent.squaredDistanceTo(v) > 48 * 48) continue;
                Doing.set(v, "settlements.doing.kid.watch", parent.getName());
                if (v.squaredDistanceTo(parent) > 12) v.getNavigation().startMovingTo(parent, 0.55);
                else {
                    v.getNavigation().stop();
                    Gaze.at(v, parent, 30f, 30f);
                    if (world.random.nextInt(4) == 0) v.swingHand(v.getDominantHand()); // doing it too, after a fashion
                    if (world.random.nextInt(20) == 0) {
                        Speech.say(v, "settlements.kid.watch." + world.random.nextInt(3), parent.getName());
                        parent.settlementsReplyKey = "settlements.kid.answer." + world.random.nextInt(3);
                        parent.settlementsReplyArgs = new Object[]{v.getName()};
                        parent.settlementsReplyAt = world.getTime() + 40;
                    }
                }
                return;
            }
        }
        List<VillagerEntityMCA> kids = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(16), o -> o != v && o.isBaby() && o.isAlive());
        if (!kids.isEmpty() && world.random.nextInt(3) > 0) {
            VillagerEntityMCA friend = kids.get(world.random.nextInt(kids.size()));
            v.getNavigation().startMovingTo(friend, 0.6);
            if (v.squaredDistanceTo(friend) < 3) {
                Speech.say(v, "settlements.kid.tag." + world.random.nextInt(3));
                Vec3d run = NoPenaltyTargeting.findFrom(friend, 10, 3, v.getPos());
                if (run != null) friend.getNavigation().startMovingTo(run.x, run.y, run.z, 0.65);
            }
        } else {
            Vec3d go = FuzzyTargeting.find(v, 10, 3);
            if (go != null) v.getNavigation().startMovingTo(go.x, go.y, go.z, 0.5);
        }
    }

    /** Idle workers lend the builders a hand when the village is short of materials. */
    public static boolean helpBuilders(VillagerEntityMCA v, Village village, ServerWorld world) {
        // an open order nobody has taken: he takes it on himself, with what he has to hand
        Orders.Order o = Orders.held(v, village);
        if (o == null) {
            Orders.Order free = Orders.free(village, Needs.Need.PLANKS, Needs.Need.COBBLE);
            if (free != null) o = Orders.take(v, village, free.what());
        }
        Builder.Shortage s = Builder.shortage(village);
        Needs.Need need = o != null ? o.what() : s != null ? s.need() : null;
        if (need == null || need == Needs.Need.FOOD) return false;
        if (!Scavenge.work(v, village, world, need, true)) return false;
        Doing.set(v, "settlements.doing.help", Text.translatable("settlements.need.name." + need.name().toLowerCase()));
        Speech.say(v, "settlements.say.help.builders");
        return true;
    }

    static boolean isProfession(VillagerEntityMCA v, VillagerProfession p) {
        return v.getProfession() == p;
    }

    private static final Map<java.util.UUID, Long> DREAD_SAID = new HashMap<>();

    /**
     * A monster close by: anyone but the watch drops what he is doing and runs - away from it, towards home or the
     * town hall - shouting for the guards. True while he is getting away.
     */
    private static final Map<UUID, net.minecraft.util.math.Vec3d> LAST_POS = new HashMap<>();

    /**
     * Driven into a corner, or the thing already on him, a grown villager does not just stand and take it: he hits back
     * with whatever is in his hand. And one with an axe or a blade in his hand goes to the help of a neighbour the
     * monster has got hold of. Children only ever run.
     */
    private static final Map<UUID, Integer> STUCK = new HashMap<>(); // seconds running and getting nowhere, the thing on him

    /**
     * The last resort. A villager runs and shouts for the guards; he stands and hits back with whatever is in his hand
     * only when there is nowhere left to run - the monster on him and two seconds of running have got him nowhere, or it
     * has him inside a house with no way past it. One with an axe or a blade goes to a neighbour's help only when no
     * guard is near enough to come. Children only ever run. As soon as he can get away again, he runs.
     */
    private static boolean fight(VillagerEntityMCA v, Village village, net.minecraft.entity.mob.HostileEntity foe, ServerWorld world, net.minecraft.util.math.Vec3d was, boolean indoors) {
        if (v.isBaby() || foe instanceof net.minecraft.entity.mob.CreeperEntity) return false;
        double d = v.squaredDistanceTo(foe);
        boolean onHim = foe.getTarget() == v && d < 2.5 * 2.5;
        int stuck = onHim && was != null && was.squaredDistanceTo(v.getPos()) < 0.6 * 0.6 ? STUCK.merge(v.getUuid(), 1, Integer::sum) : 0;
        if (stuck == 0) STUCK.remove(v.getUuid());
        boolean cornered = stuck >= 2 || indoors && onHim && v.getAttacker() == foe;
        boolean guardNear = !world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(32, 8, 32), o -> o.isGuard() && o.isAlive()).isEmpty();
        var held = v.getMainHandStack().getItem();
        boolean armed = held instanceof net.minecraft.item.AxeItem || held instanceof net.minecraft.item.SwordItem;
        boolean neighbour = !cornered && armed && !guardNear && foe.getTarget() instanceof VillagerEntityMCA other && other != v && !other.isGuard() && d < 6 * 6;
        if (!cornered && !neighbour) return false;
        Doing.set(v, "settlements.doing.fight.monster");
        Gaze.at(v, foe, 30f, 30f);
        callGuards(v, village, foe, world, neighbour ? "settlements.say.fight.help" : "settlements.say.fight.back");
        if (d > 2.2 * 2.2) {
            Move.to(v, foe.getX(), foe.getY(), foe.getZ(), 0.75);
            return true;
        }
        v.getNavigation().stop();
        v.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        v.tryAttack(foe);
        return true;
    }

    /** A shout (now and then, not every breath), and every guard within earshot comes for the thing. */
    private static void callGuards(VillagerEntityMCA v, Village village, net.minecraft.entity.mob.HostileEntity foe, ServerWorld world, String line) {
        long now = world.getTime();
        if (now - DREAD_SAID.getOrDefault(v.getUuid(), -10000L) <= 200) return;
        DREAD_SAID.put(v.getUuid(), now);
        Speech.sayNow(v, line);
        if (village != null) for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(48, 12, 48), VillagerEntityMCA::isGuard)) {
            if (g.getBrain().getOptionalMemory(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET).isEmpty()) g.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET, foe, 300);
        }
    }

    public static boolean dread(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (v.isGuard() || v.isSleeping() || v.getProfession() == net.mca.ProfessionsMCA.WATCHMAN.get()) return false;
        boolean indoors = !world.isSkyVisible(v.getBlockPos().up());
        net.minecraft.entity.mob.HostileEntity foe = world.getEntitiesByClass(net.minecraft.entity.mob.HostileEntity.class, v.getBoundingBox().expand(10, 4, 10),
                m -> m.isAlive() && !(m instanceof net.minecraft.entity.mob.CreeperEntity && v.squaredDistanceTo(m) > 36) && v.canSee(m)).stream()
                .min(java.util.Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
        net.minecraft.util.math.Vec3d was = LAST_POS.put(v.getUuid(), v.getPos());
        if (foe == null) return false;
        if (fight(v, village, foe, world, was, indoors)) return true;
        if (indoors) {
            // under a roof he stays in (he does not run out into it), but he shouts for the guards if the thing is in there with him
            if (foe.getTarget() == v) callGuards(v, village, foe, world, "settlements.say.flee.monster");
            return false;
        }
        Doing.set(v, "settlements.doing.flee.monster");
        long night0 = world.getTimeOfDay() % 24000L;
        if ((night0 >= 12500 && night0 <= 23500) && Bed.unseen(v, world) && Bed.shelter(v, village, world)) return true; // off-screen he makes it indoors
        net.minecraft.util.math.Vec3d away = v.getPos().subtract(foe.getPos()).multiply(1, 0, 1).normalize();
        net.minecraft.util.math.BlockPos safe = v.getResidency().getHome().map(net.minecraft.util.math.GlobalPos::getPos).orElse(village != null ? village.hallPos : null);
        long tod = world.getTimeOfDay() % 24000L;
        boolean night = tod >= 12500 && tod <= 23500;
        // by night only indoors will do (home, or the town hall); by day, away from it, homewards if that is away
        net.minecraft.util.math.Vec3d to = safe != null && (night || safe.toCenterPos().subtract(v.getPos()).dotProduct(away) > 0) ? safe.toCenterPos() : v.getPos().add(away.multiply(10));
        Move.to(v, to.x, to.y, to.z, 0.8);
        callGuards(v, village, foe, world, "settlements.say.flee.monster");
        return true;
    }
}
