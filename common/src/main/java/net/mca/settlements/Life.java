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
        if (cause.getAttacker() instanceof PlayerEntity p) news(village, "murder", name, p.getName().getString());
        else news(village, "death", name);
        for (VillagerEntityMCA o : world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(16), o -> o != v && o.isAlive() && !o.isBaby())) {
            Speech.say(o, "settlements.react.mourn", name);
        }
    }

    // ---------- every second ----------

    /** True when the villager is busy with life itself (sheltering, bringing food) and should not work this second. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, long time) {
        long now = world.getTime();
        if (v.settlementsReplyKey != null && now >= v.settlementsReplyAt) {
            Speech.sayNow(v, v.settlementsReplyKey, v.settlementsReplyArgs);
            v.settlementsReplyKey = null;
        }
        if (v.isSleeping()) return false;

        notice(v, village, world, time);

        if (!v.isGuard() && shelter(v, village, world, time)) return true;
        if (share(v, village, world)) return true;
        if (!Workforce.isWorkTime(time) || v.settlementsBreak) converse(v, village, world);
        return false;
    }

    private static void notice(VillagerEntityMCA v, Village village, ServerWorld world, long time) {
        PlayerEntity p = world.getClosestPlayer(v, 7);
        if (p == null || p.isSpectator() || !v.canSee(p)) return;
        int rep = village == null ? 0 : village.getReputation(p);
        int tier = Reputation.tier(rep);
        double d = v.squaredDistanceTo(p);
        if (v.getNavigation().isIdle() && d < 25) v.getLookControl().lookAt(p, 30f, 30f);

        if (!v.isGuard() && tier <= 1 && d < 25) {
            // a known thief or an enemy: keep away from him
            Vec3d away = NoPenaltyTargeting.findFrom(v, 8, 4, p.getPos());
            if (away != null) v.getNavigation().startMovingTo(away.x, away.y, away.z, 0.7);
            Speech.say(v, "settlements.react.fear");
            return;
        }
        ItemStack held = p.getMainHandStack();
        boolean armed = held.getItem() instanceof SwordItem || held.getItem() instanceof AxeItem || held.getItem() instanceof BowItem
                || held.getItem() instanceof CrossbowItem || held.getItem() instanceof TridentItem;
        if (armed && !v.isGuard() && tier <= 3 && d < 16) {
            Speech.say(v, "settlements.react.weapon");
            return;
        }
        long day = time / 24000L;
        if (v.settlementsGreetDay != day && d < 36) {
            v.settlementsGreetDay = day;
            if (v.isElder() && tier >= 2 && village != null) {
                Speech.say(v, "settlements.greet.elder", p.getName(), village.getName());
                return;
            }
            long t = time % 24000L;
            if (tier >= 3 && t < 2000) Speech.say(v, "settlements.greet.morning", p.getName());
            else if (tier >= 3 && t > 11500) Speech.say(v, "settlements.greet.evening", p.getName());
            else Speech.say(v, "settlements.greet." + tier + "." + world.random.nextInt(3), p.getName());
        }
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
        v.getLookControl().lookAt(o, 30f, 30f);
        Needs.set(o, Needs.Need.NONE);
        Speech.sayNow(v, "settlements.react.share");
        o.settlementsReplyKey = "settlements.react.thanks";
        o.settlementsReplyArgs = new Object[]{v.getName()};
        o.settlementsReplyAt = world.getTime() + 40;
        return true;
    }

    // ---------- conversations ----------

    private static void converse(VillagerEntityMCA v, Village village, ServerWorld world) {
        long now = world.getTime();
        if (now < v.settlementsTalkAfter || world.getClosestPlayer(v, 12) == null) return;
        List<VillagerEntityMCA> near = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(4),
                o -> o != v && o.isAlive() && !o.isBaby() && !o.isSleeping() && o.settlementsReplyKey == null && now >= o.settlementsTalkAfter);
        if (near.isEmpty()) return;
        VillagerEntityMCA other = near.get(world.random.nextInt(near.size()));
        String[] topic = topic(v, village, world);
        if (topic == null) return;
        Object[] args = topic.length > 1 ? new Object[]{topic[1]} : new Object[0];
        v.getLookControl().lookAt(other, 30f, 30f);
        other.getLookControl().lookAt(v, 30f, 30f);
        Speech.sayNow(v, "settlements.talk." + topic[0] + ".a", args);
        other.settlementsReplyKey = "settlements.talk." + topic[0] + ".b";
        other.settlementsReplyArgs = args;
        other.settlementsReplyAt = now + 50;
        long pause = 1600 + world.random.nextInt(1600);
        v.settlementsTalkAfter = now + pause;
        other.settlementsTalkAfter = now + pause;
        world.playSoundFromEntity(null, v, SoundEvents.ENTITY_VILLAGER_AMBIENT, SoundCategory.NEUTRAL, 0.5f, 1.0f);
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
        List<VillagerEntityMCA> kids = world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(16), o -> o != v && o.isBaby() && o.isAlive());
        if (!kids.isEmpty() && world.random.nextInt(3) > 0) {
            VillagerEntityMCA friend = kids.get(world.random.nextInt(kids.size()));
            v.getNavigation().startMovingTo(friend, 0.8);
            if (v.squaredDistanceTo(friend) < 3) {
                Speech.say(v, "settlements.kid.tag." + world.random.nextInt(3));
                world.playSoundFromEntity(null, v, SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.NEUTRAL, 0.5f, 1.6f);
                Vec3d run = NoPenaltyTargeting.findFrom(friend, 10, 3, v.getPos());
                if (run != null) friend.getNavigation().startMovingTo(run.x, run.y, run.z, 0.85);
            }
        } else {
            Vec3d go = FuzzyTargeting.find(v, 10, 3);
            if (go != null) v.getNavigation().startMovingTo(go.x, go.y, go.z, 0.7);
        }
    }

    /** Idle workers lend the builders a hand when the village is short of materials. */
    public static boolean helpBuilders(VillagerEntityMCA v, Village village, ServerWorld world) {
        Builder.Shortage s = Builder.shortage(village);
        if (s == null) return false;
        if (!Scavenge.work(v, village, world, s.need(), s.any())) return false;
        Speech.say(v, "settlements.say.help.builders");
        return true;
    }

    static boolean isProfession(VillagerEntityMCA v, VillagerProfession p) {
        return v.getProfession() == p;
    }
}
