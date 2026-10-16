package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * A word with the elder: the village's affairs from the one who runs them. He tells how the village stands, who is
 * at what, what it is short of and how the player can help, what is to be built next and what has happened lately;
 * the player picks the question from the line under his answer.
 */
public final class Elder {
    private static final String[] TOPICS = {"village", "people", "needs", "plans", "news", "bye"};
    /** What he says in answer, gathered and put before the player face to face (see DialogueHud). */
    private static final List<Text> OUT = new java.util.ArrayList<>();

    private Elder() {
    }

    /** The elder of the village the player is talking in, if he is within a few steps. */
    private static VillagerEntityMCA near(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        for (VillagerEntityMCA v : w.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(8), x -> x.isAlive() && !x.isBaby())) {
            Village vil = Village.findNearest(v).orElse(null);
            if (vil != null && Plan.elder(w, vil) == v) return v;
        }
        return null;
    }

    /** The talk opened: a greeting and the questions. */
    public static void open(VillagerEntityMCA elder, PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity sp) || !(elder.getWorld() instanceof ServerWorld world)) return;
        Village village = Village.findNearest(elder).orElse(null);
        if (village == null) {
            Plan.talk(elder, player); // a settlement not registered yet: the old way says so and helps it along
            return;
        }
        VillagerEntityMCA real = Plan.elder(world, village);
        if (real != null && real != elder) {
            elder.sendChatMessage(Text.translatable("settlements.elder.ask", real.getName()), player);
            return;
        }
        face(elder, player);
        OUT.clear();
        OUT.add(Text.translatable("settlements.elder.hello." + (Reputation.tier(village.getReputation(player)) >= 4 ? "friend" : "plain"), player.getName(), village.getName()));
        menu(sp, elder, null);
    }

    /** /settle elder <topic> */
    public static int ask(ServerPlayerEntity p, String topic) {
        VillagerEntityMCA elder = near(p);
        if (elder == null) {
            p.sendMessage(Text.translatable("settlements.elder.away"), true);
            return 0;
        }
        Village village = Village.findNearest(elder).orElse(null);
        ServerWorld world = p.getServerWorld();
        if (village == null) return 0;
        face(elder, p);
        OUT.clear();
        switch (topic) {
            case "village" -> story(elder, village, world, p);
            case "people" -> people(elder, village, world, p);
            case "needs" -> needs(elder, village, world, p);
            case "plans" -> plans(elder, village, p);
            case "news" -> news(elder, village, p);
            default -> {
                elder.sendChatMessage(Text.translatable("settlements.elder.bye"), p);
                net.mca.cobalt.network.NetworkHandler.sendToPlayer(new net.mca.network.s2c.JusticeScreenRequest(elder.getId(), elder.getName(),
                        List.of(Text.translatable("settlements.elder.bye")), new String[0], List.of(), 0), p);
                return 1;
            }
        }
        menu(p, elder, topic);
        return 1;
    }

    private static void face(VillagerEntityMCA elder, PlayerEntity p) {
        elder.getNavigation().stop();
        elder.getLookControl().lookAt(p, 30f, 30f);
        elder.swingHand(elder.getDominantHand());
    }

    /** His words, and the questions to go on with (the one just answered last), face to face at the bottom of the screen. */
    private static void menu(ServerPlayerEntity p, VillagerEntityMCA elder, String asked) {
        List<String> opts = new java.util.ArrayList<>();
        List<Text> labels = new java.util.ArrayList<>();
        for (String k : TOPICS) {
            if (k.equals(asked)) continue;
            opts.add("elder " + k);
            labels.add(Text.translatable("settlements.elder.q." + k).formatted(k.equals("bye") ? Formatting.GRAY : Formatting.GOLD));
        }
        net.mca.cobalt.network.NetworkHandler.sendToPlayer(new net.mca.network.s2c.JusticeScreenRequest(elder.getId(), elder.getName(),
                new java.util.ArrayList<>(OUT), opts.toArray(new String[0]), labels, 0), p);
        OUT.clear();
    }

    /** Who is at what, in a line each: what the notice board was for. */
    private static void people(VillagerEntityMCA elder, Village village, ServerWorld world, ServerPlayerEntity p) {
        List<VillagerEntityMCA> folk = village.getResidents(world);
        OUT.add(Text.translatable("settlements.elder.people", folk.size()));
        int n = 0;
        for (VillagerEntityMCA r : folk) {
            if (!r.isAlive() || r == elder) continue;
            if (n++ >= 14) break;
            String trade = r.isBaby() ? "settlements.trade.child" : r.isGuard() ? "settlements.role.guard"
                    : "settlements.role." + Registries.VILLAGER_PROFESSION.getId(r.getProfession()).getPath();
            OUT.add(Text.literal("  ").append(r.getName().copy().formatted(Formatting.YELLOW))
                    .append(Text.literal(" (").formatted(Formatting.GRAY)).append(Text.translatable(trade).formatted(Formatting.GRAY))
                    .append(Text.literal("): ").formatted(Formatting.GRAY)).append(Doing.describe(r).copy().formatted(Formatting.WHITE)));
        }
    }

    /** What the village is short of, and where to bring it. */
    private static void needs(VillagerEntityMCA elder, Village village, ServerWorld world, ServerPlayerEntity p) {
        Needs.Need need = Plan.firstBlocker(village);
        Builder.Shortage s = Builder.shortage(village);
        if (s != null && s.need() != Needs.Need.NONE) need = s.need();
        if (need == Needs.Need.NONE) {
            OUT.add(Text.translatable("settlements.elder.fine"));
            return;
        }
        OUT.add(Text.translatable("settlements.elder.help." + need.name().toLowerCase()));
        var store = Haul.store(elder, village);
        if (store != null) OUT.add(Text.translatable("settlements.elder.bring", store.getX(), store.getY(), store.getZ()));
    }

    /** What is being built, and what comes after. */
    private static void plans(VillagerEntityMCA elder, Village village, ServerPlayerEntity p) {
        if (village.buildOrigin != null && village.buildType != null) {
            int total = Builder.stepsOf(village);
            int pct = total > 0 ? Math.min(100, village.buildStep * 100 / total) : 0;
            OUT.add(Text.translatable("settlements.elder.building", Text.translatable("settlements.building." + Buildings.base(village.buildType)), pct));
        } else {
            OUT.add(Text.translatable("settlements.elder.idle"));
        }
        int shown = 0;
        for (Plan.Status s : Plan.pending(village)) {
            if (village.buildType != null && s.goal().id().equals(Buildings.base(village.buildType))) continue;
            if (shown++ >= 3) break;
            MutableText line = Text.literal("  • ").append(Text.translatable("settlements.goal." + s.goal().id()).formatted(Formatting.AQUA));
            if (!s.ready()) {
                MutableText missing = Text.empty();
                if (s.missPlanks() > 0) missing.append(Text.translatable("settlements.missing.planks", s.missPlanks())).append(" ");
                if (s.missCobble() > 0) missing.append(Text.translatable("settlements.missing.cobble", s.missCobble())).append(" ");
                if (s.missIron() > 0) missing.append(Text.translatable("settlements.missing.iron", s.missIron())).append(" ");
                if (s.missWool() > 0) missing.append(Text.translatable("settlements.missing.wool", s.missWool()));
                line.append(Text.literal(" - ").formatted(Formatting.GRAY)).append(Text.translatable("settlements.book.missing", missing).formatted(Formatting.GRAY));
            }
            OUT.add(line);
        }
    }

    /** What has happened lately. */
    private static void news(VillagerEntityMCA elder, Village village, ServerPlayerEntity p) {
        List<Life.News> news = Life.news(village);
        if (news.isEmpty()) {
            OUT.add(Text.translatable("settlements.elder.nonews"));
            return;
        }
        OUT.add(Text.translatable("settlements.elder.news"));
        int n = 0;
        for (Life.News e : news) {
            if (n++ >= 5) break;
            OUT.add(Text.literal("  • ").append(Text.translatable("settlements.news." + e.key(), e.args()).formatted(Formatting.WHITE)));
        }
    }

    /**
     * How things are in the village, told the way an old man tells it: who we are and how we live, how we stand for
     * bread, what is going up and who is building it, what we are short of and who goes for it, what has happened.
     */
    private static void story(VillagerEntityMCA elder, Village village, ServerWorld world, ServerPlayerEntity p) {
        java.util.Random r = new java.util.Random(world.getTime() / 1200 + village.getId()); // the same words for a while, not the same each day
        List<VillagerEntityMCA> folk = village.getResidents(world);
        int pop = folk.size();
        double pros = Prosperity.factor(village);
        String open = pop <= 6 ? "small" : pros < 0.85 ? "poor" : pop >= 15 ? "thriving" : "growing";
        OUT.add(Text.translatable("settlements.elder.story.open." + open + "." + r.nextInt(2), village.getName(), pop));
        // bread
        int food = 0;
        for (net.minecraft.item.ItemStack s : village.storageBuffer) if (s.isFood()) food += s.getCount();
        String farmer = name(folk, net.minecraft.village.VillagerProfession.FARMER);
        double per = food / (double) Math.max(1, pop);
        String f = per >= 6 ? "plenty" : per >= 2 ? "ok" : food > 0 ? "short" : "none";
        OUT.add(Text.translatable("settlements.elder.story.food." + f + "." + r.nextInt(2), farmer == null ? Text.translatable("settlements.elder.story.nobody") : Text.literal(farmer)));
        // building
        String builder = name(folk, net.minecraft.village.VillagerProfession.CARTOGRAPHER);
        Text who = builder == null ? Text.translatable("settlements.elder.story.nobody") : Text.literal(builder);
        if (village.buildOrigin != null && village.buildType != null) {
            int total = Builder.stepsOf(village);
            int pct = total > 0 ? Math.min(100, village.buildStep * 100 / total) : 0;
            String stage = pct < 25 ? "start" : pct < 75 ? "mid" : "end";
            OUT.add(Text.translatable("settlements.elder.story.build." + stage + "." + r.nextInt(2), who,
                    Text.translatable("settlements.building." + Buildings.base(village.buildType))));
        } else {
            OUT.add(Text.translatable("settlements.elder.story.build.idle." + r.nextInt(2), who));
        }
        // what holds us back
        Needs.Need need = Plan.firstBlocker(village);
        Builder.Shortage sh = Builder.shortage(village);
        if (sh != null && sh.need() != Needs.Need.NONE) need = sh.need();
        if (need != Needs.Need.NONE) {
            String bringer = need == Needs.Need.PLANKS ? name(folk, net.minecraft.village.VillagerProfession.FLETCHER)
                    : need == Needs.Need.COBBLE ? name(folk, net.minecraft.village.VillagerProfession.MASON) : null;
            String k = need.name().toLowerCase();
            OUT.add(bringer != null ? Text.translatable("settlements.elder.story.short." + k + ".who", Text.literal(bringer))
                    : Text.translatable("settlements.elder.story.short." + k));
        }
        // the mood, and the latest that happened
        Mood.Kind mood = Mood.now(village);
        if (mood != null) OUT.add(Text.translatable("settlements.elder.story.mood." + mood.name().toLowerCase()));
        List<Life.News> news = Life.news(village);
        if (!news.isEmpty() && r.nextBoolean()) {
            Life.News n = news.get(0);
            OUT.add(Text.translatable("settlements.elder.story.news", Text.translatable("settlements.news." + n.key(), n.args())));
        }
        OUT.add(Text.translatable(need == Needs.Need.NONE ? "settlements.elder.story.end.fine" : "settlements.elder.story.end.help." + need.name().toLowerCase()));
    }

    private static String name(List<VillagerEntityMCA> folk, net.minecraft.village.VillagerProfession prof) {
        for (VillagerEntityMCA v : folk) if (v.isAlive() && !v.isBaby() && !v.isGuard() && v.getProfession() == prof) return v.getName().getString();
        return null;
    }
}
