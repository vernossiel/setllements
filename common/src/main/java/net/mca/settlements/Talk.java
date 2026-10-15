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

        Conversation(UUID a, UUID b, String topic, Object[] args, long next) {
            this.a = a;
            this.b = b;
            this.topic = topic;
            this.args = args;
            this.next = next;
        }
    }

    private static final Map<UUID, Conversation> TALKING = new HashMap<>();

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
            if (v.squaredDistanceTo(other) > 6.25) {
                v.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(other, 0.45f, 1));
                return true;
            }
            v.getNavigation().stop();
            v.getLookControl().lookAt(other, 30f, 30f);
            if (c.a.equals(v.getUuid()) && now >= c.next) {
                VillagerEntityMCA speaker = c.line % 2 == 0 ? v : other;
                say(speaker, "settlements.talk." + c.topic + "." + "abcd".charAt(c.line), c.args);
                if (speaker.getRandom().nextInt(2) == 0) speaker.swingHand(speaker.getDominantHand()); // a gesture
                c.line++;
                c.next = now + 50 + world.random.nextInt(30);
                if (c.line >= lines(c.topic)) {
                    end(c);
                    v.settlementsTalkAfter = now + 900 + world.random.nextInt(900);
                    other.settlementsTalkAfter = v.settlementsTalkAfter;
                }
            }
            return true;
        }
        if (!free || v.isBaby() || now < v.settlementsTalkAfter || v.getVillagerBrain().isPanicking()) return false;
        if (world.getClosestPlayer(v, 24) == null) return false; // nobody about to hear: they get on with things
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
        Conversation conv = new Conversation(v.getUuid(), partner.getUuid(), t[0], t.length > 1 ? new Object[]{t[1]} : new Object[0], now + 20);
        TALKING.put(v.getUuid(), conv);
        TALKING.put(partner.getUuid(), conv);
        partner.getNavigation().stop();
        if (Seat.seated(v)) Rest.end(v);
        return true;
    }

    private static void end(Conversation c) {
        TALKING.remove(c.a);
        TALKING.remove(c.b);
    }

    /** Lines in a conversation: the old two-line topics are four lines now, greetings stay two. */
    private static int lines(String topic) {
        return topic.startsWith("hello") ? 2 : 4;
    }

    private static void say(VillagerEntityMCA v, String key, Object[] args) {
        net.minecraft.text.MutableText line = Text.translatable(key, args).formatted(Formatting.WHITE);
        for (PlayerEntity p : v.getWorld().getPlayers()) {
            if (p.squaredDistanceTo(v) <= HEAR * HEAR) v.sendChatMessage(line, p);
        }
        v.settlementsLastSay = v.getWorld().getTime();
    }

    /** What the two talk about. */
    private static String[] topic(VillagerEntityMCA v, VillagerEntityMCA w, Village village, ServerWorld world) {
        var r = world.random;
        long t = world.getTimeOfDay() % 24000L;
        PlayerEntity p = world.getClosestPlayer(v, 20);
        double roll = r.nextDouble();
        // the stranger about: you
        if (p != null && village != null && roll < 0.3) {
            int tier = Reputation.tier(village.getReputation(p));
            String name = p.getName().getString();
            if (tier >= 5) return new String[]{"player_good", name};
            if (tier <= 2) return new String[]{"player_bad", name};
            return new String[]{r.nextBoolean() ? "player.near" : "player.newcomer", name};
        }
        // the news
        if (village != null && roll < 0.5) {
            List<Life.News> news = Life.news(village);
            if (!news.isEmpty()) {
                Life.News n = news.get(r.nextInt(Math.min(3, news.size())));
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
        if (world.isRaining() && r.nextBoolean()) return new String[]{"rain"};
        if (t >= 11000 && t < 13500 && r.nextBoolean()) return new String[]{"evening"};
        if (village != null && r.nextBoolean()) {
            Needs.Need need = Plan.firstBlocker(village);
            if (need != Needs.Need.NONE) return new String[]{"need." + need.name().toLowerCase()};
        }
        String prof = v.isGuard() ? "guard" : v.isElder() ? "elder" : Registries.VILLAGER_PROFESSION.getId(v.getProfession()).getPath();
        if (r.nextBoolean() && java.util.Set.of("guard", "elder", "cartographer", "mason", "fletcher", "toolsmith", "farmer", "shepherd", "butcher",
                "armorer", "cleric", "fisherman").contains(prof)) return new String[]{"work." + prof};
        return new String[]{"small." + r.nextInt(8)};
    }
}
