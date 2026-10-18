package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real conversations. Two villagers with a moment to spare go up to each other, stop face to face and talk: four
 * lines back and forth, with a gesture now and then, about what is on their minds - the news of the village, a
 * neighbour (gossip, kind or not, after the neighbour's character), the stranger walking about (that is you), the
 * weather, the work, the day's end. Whoever stands near hears it.
 */
public final class Talk {
    private static final double HEAR = 16;

    private static final class Conversation {
        final UUID a, b;
        final String topic;
        final Object[] args;
        int line;
        long next;
        final long began;

        Conversation(UUID a, UUID b, String topic, Object[] args, long next) {
            this.began = next;
            this.a = a;
            this.b = b;
            this.topic = topic;
            this.args = args;
            this.next = next;
        }
    }

    private static final Map<UUID, Conversation> TALKING = new HashMap<>();
    private static final java.util.Set<String> TOLD = new java.util.HashSet<>(); // who has passed on which news already

    private Talk() {
    }

    public static boolean talking(VillagerEntityMCA v) {
        return TALKING.containsKey(v.getUuid());
    }

    /**
     * Once a second. Carries a conversation on (true while he is in one); or, when he has a moment ({@code free}) and
     * somebody is near to hear, now and then starts one.
     */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, boolean free) {
        long now = world.getTime();
        Conversation c = TALKING.get(v.getUuid());
        if (c != null) {
            UUID otherId = c.a.equals(v.getUuid()) ? c.b : c.a;
            Entity o = world.getEntity(otherId);
            if (!(o instanceof VillagerEntityMCA other) || !other.isAlive() || other.isSleeping() || other.squaredDistanceTo(v) > 100
                    || v.getVillagerBrain().isPanicking()) {
                end(c);
                return false;
            }
            // up to each other, then face to face
            // up to a couple of paces apart, not nose to nose
            if (v.squaredDistanceTo(other) > 9) {
                // a quarter of a minute and still not together (the other is off at work, or there is no way round):
                // it was not to be; the builder was seen following a neighbour about all day
                if (c.line == 0 && now - c.began > 20 * 15) {
                    end(c);
                    return false;
                }
                v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(other, 0.45f, 2));
                Doing.set(v, "settlements.doing.talk", other.getName());
                return true;
            }
            v.getBrain().forget(MemoryModuleType.WALK_TARGET);
            v.getNavigation().stop();
            if (v.squaredDistanceTo(other) < 1.7 && c.b.equals(v.getUuid())) Move.stepBack(v, other); // a step back to talk
            Gaze.at(v, other, 30f, 30f);
            Doing.set(v, "settlements.doing.talk", other.getName());
            if (c.a.equals(v.getUuid()) && now >= c.next) {
                VillagerEntityMCA speaker = c.line % 2 == 0 ? v : other;
                say(speaker, speaker == v ? other : v, "settlements.talk." + c.topic + "." + "abcd".charAt(c.line), c.args);
                Anim.set(speaker, Anim.Act.TALK, 50); // hands that talk too
                c.line++;
                c.next = now + 50 + world.random.nextInt(30);
                if (c.line >= lines(c.topic)) {
                    end(c);
                    Mind.talked(v);
                    Mind.talked(other);
                    Persona.befriend(v, other); // they get on a little better each time
                    Fame.gossip(v, other); // and what they think of the players they know rubs off
                    v.settlementsTalkAfter = now + 900 + world.random.nextInt(900);
                    other.settlementsTalkAfter = v.settlementsTalkAfter;
                }
            }
            return true;
        }
        if (!free || v.isBaby() || now < v.settlementsTalkAfter || v.getVillagerBrain().isPanicking()) return false;
        // they talk whether anyone is listening or not (what is said is heard only by a player near enough)
        double chance = 0.02 + 0.08 * Persona.of(v).sociability();
        if (world.random.nextDouble() > chance) return false;
        VillagerEntityMCA partner = null;
        double bd = 12 * 12;
        for (VillagerEntityMCA o : world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(12, 3, 12),
                o -> o != v && o.isAlive() && !o.isBaby() && !o.isSleeping() && !TALKING.containsKey(o.getUuid()) && now >= o.settlementsTalkAfter
                        && !o.getVillagerBrain().isPanicking() && !Dig.busy(o))) {
            double d = o.squaredDistanceTo(v) - Persona.affinity(v, o) * 40; // friends first
            if (d < bd) {
                bd = d;
                partner = o;
            }
        }
        if (partner == null) return false;
        String[] t = topic(v, partner, village, world);
        t[0] = variant(t[0], world.random); // one of the several ways the same talk goes
        // a talk with no lines written for it (news of a sale to another village): not had at all, rather than the
        // key itself said out loud
        if (!net.minecraft.util.Language.getInstance().hasTranslation("settlements.talk." + t[0] + ".a")) return false;
        Conversation conv = new Conversation(v.getUuid(), partner.getUuid(), t[0], t.length > 1 ? new Object[]{t[1]} : new Object[0], now + 20);
        TALKING.put(v.getUuid(), conv);
        TALKING.put(partner.getUuid(), conv);
        partner.getNavigation().stop();
        if (Seat.seated(v)) Rest.end(v);
        return true;
    }

    /** Breaks off the conversation he is in (it is late: both go home). */
    public static void end(VillagerEntityMCA v) {
        Conversation c = TALKING.get(v.getUuid());
        if (c != null) end(c);
    }

    private static void end(Conversation c) {
        TALKING.remove(c.a);
        TALKING.remove(c.b);

    }

    /** Lines in a conversation: the old two-line topics are four lines now, greetings stay two. */
    private static int lines(String topic) {
        return topic.startsWith("hello") ? 2 : 4;
    }

    private static void say(VillagerEntityMCA v, VillagerEntityMCA to, String key, Object[] args) {
        // in the speaker's own grammar, and in the grammar of the one he speaks to ("did you sleep well" changes with her)
        String k = Speech.voiced(v, key);
        if (to.getGenetics().getGender() == net.mca.entity.ai.relationship.Gender.FEMALE && net.minecraft.util.Language.getInstance().hasTranslation(k + ".lf")) k += ".lf";
        net.minecraft.text.MutableText line = Text.translatable(k, args).formatted(Formatting.WHITE);
        for (PlayerEntity p : v.getWorld().getPlayers()) {
            if (p.squaredDistanceTo(v) <= HEAR * HEAR) v.sendChatMessage(line, p);
        }
        v.settlementsLastSay = v.getWorld().getTime();
    }

    private static Map<String, Integer> VARIANTS;

    /** How many more ways each talk can go (assets/mca/settlements/talk_variants.json, written with the lines). */
    private static synchronized Map<String, Integer> variants() {
        if (VARIANTS != null) return VARIANTS;
        VARIANTS = new HashMap<>();
        try (var in = Talk.class.getResourceAsStream("/assets/mca/settlements/talk_variants.json")) {
            if (in != null) {
                var j = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : j.entrySet()) VARIANTS.put(e.getKey(), e.getValue().getAsInt());
            }
        } catch (Exception ignored) {
        }
        return VARIANTS;
    }

    private static String variant(String topic, net.minecraft.util.math.random.Random r) {
        int n = variants().getOrDefault(topic, 0);
        int k = r.nextInt(n + 1);
        return k == 0 ? topic : topic + ".v" + k;
    }

    /** What the two talk about. */
    private static String[] topic(VillagerEntityMCA v, VillagerEntityMCA w, Village village, ServerWorld world) {
        var r = world.random;
        long t = world.getTimeOfDay() % 24000L;
        PlayerEntity p = world.getClosestPlayer(v, 20);
        double roll = r.nextDouble();
        // the stranger about: you
        // (once a day for each of them: three hundred times "he steals from us" was too many)
        long today = world.getTimeOfDay() / 24000L;
        if (p != null && village != null && roll < 0.15 && TOLD.add(v.getUuid() + "|player|" + p.getUuid() + "|" + today)) {
            int tier = Reputation.tier(village.getReputation(p));
            String name = p.getName().getString();
            if (tier >= 5) return new String[]{"player_good", name};
            if (tier <= 2) return new String[]{"player_bad", name};
            return new String[]{r.nextBoolean() ? "player.near" : "player.newcomer", name};
        }
        // the news
        if (village != null && roll < 0.5) {
            List<Life.News> news = new ArrayList<>(Life.news(village));
            // each passes a piece of news on once, not every time he stops to talk
            news.removeIf(n -> TOLD.contains(v.getUuid() + "|" + n.key() + "|" + (n.args().length > 0 ? n.args()[0] : "") + "|" + n.time()));
            if (!news.isEmpty()) {
                Life.News n = news.get(r.nextInt(Math.min(3, news.size())));
                if (TOLD.size() > 5000) TOLD.clear();
                TOLD.add(v.getUuid() + "|" + n.key() + "|" + (n.args().length > 0 ? n.args()[0] : "") + "|" + n.time());
                Object a = n.args().length > 0 ? n.args()[0] : "";
                return new String[]{"news." + n.key(), a instanceof Text tx ? tx.getString() : String.valueOf(a)};
            }
        }
        // a neighbour
        if (village != null && roll < 0.75) {
            List<VillagerEntityMCA> others = new ArrayList<>();
            for (VillagerEntityMCA o : village.getResidents(world)) if (o != v && o != w && o.isAlive() && !o.isBaby()) others.add(o);
            if (!others.isEmpty()) {
                VillagerEntityMCA x = others.get(r.nextInt(others.size()));
                Persona.Traits tr = Persona.of(x);
                String kind = Trade.level(x) >= 3 ? "master" : tr.diligence() < 0.8 ? "lazy" : tr.diligence() > 1.15 ? "hard"
                        : tr.temper() > 0.7 ? "grumpy" : tr.sociability() < 0.3 ? "shy" : tr.sociability() > 0.85 ? "friendly" : "plain";
                return new String[]{"gossip." + kind, x.getName().getString()};
            }
        }
        if (village != null && Forage.hungry(village) && r.nextBoolean()) return new String[]{"hungry"};
        // the moment: a storm, the cold, early morning, late at night, a day off, two old friends or two who hardly know each other
        if (Festival.fair(world.getTimeOfDay()) && r.nextBoolean()) return new String[]{"fair"};
        if (world.isThundering() && r.nextBoolean()) return new String[]{"storm"};
        if (world.getBiome(v.getBlockPos()).value().isCold(v.getBlockPos()) && r.nextInt(3) == 0) return new String[]{"cold"};
        if (t < 2500 && r.nextBoolean()) return new String[]{"morning"};
        if (t >= 12500 && r.nextBoolean()) return new String[]{"night"};
        if (Workforce.isRestDay(world.getTimeOfDay()) && r.nextInt(3) == 0) return new String[]{"restday"};
        double bond = Persona.affinity(v, w);
        if (bond > 0.6 && r.nextInt(3) == 0) return new String[]{"friends"};
        if (bond < 0.15 && r.nextInt(4) == 0) return new String[]{"acquaint"};
        if (world.isRaining() && r.nextBoolean()) return new String[]{"rain"};
        if (t >= 11000 && t < 13500 && r.nextBoolean()) return new String[]{"evening"};
        if (village != null && r.nextBoolean()) {
            Needs.Need need = Plan.firstBlocker(village);
            if (need != Needs.Need.NONE) return new String[]{"need." + need.name().toLowerCase()};
        }
        String prof = v.isGuard() ? "guard" : v.isElder() ? "elder" : Registries.VILLAGER_PROFESSION.getId(v.getProfession()).getPath();
        if (r.nextBoolean() && java.util.Set.of("guard", "elder", "cartographer", "mason", "fletcher", "toolsmith", "farmer", "shepherd", "butcher",
                "armorer", "cleric", "fisherman").contains(prof)) return new String[]{"work." + prof};
        return new String[]{"small." + r.nextInt(12)};
    }
}
