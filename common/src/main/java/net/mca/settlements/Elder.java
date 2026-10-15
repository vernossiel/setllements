package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
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
        elder.sendChatMessage(Text.translatable("settlements.elder.hello." + (Reputation.tier(village.getReputation(player)) >= 4 ? "friend" : "plain"), player.getName(), village.getName()), player);
        menu(sp);
    }

    /** /settle elder <topic> */
    public static int ask(ServerPlayerEntity p, String topic) {
        VillagerEntityMCA elder = near(p);
        if (elder == null) {
            p.sendMessage(Text.translatable("settlements.elder.away"), false);
            return 0;
        }
        Village village = Village.findNearest(elder).orElse(null);
        ServerWorld world = p.getServerWorld();
        if (village == null) return 0;
        face(elder, p);
        switch (topic) {
            case "village" -> Plan.tellPublic(elder, village, world, p);
            case "people" -> people(elder, village, world, p);
            case "needs" -> needs(elder, village, world, p);
            case "plans" -> plans(elder, village, p);
            case "news" -> news(elder, village, p);
            default -> {
                elder.sendChatMessage(Text.translatable("settlements.elder.bye"), p);
                return 1;
            }
        }
        menu(p);
        return 1;
    }

    private static void face(VillagerEntityMCA elder, PlayerEntity p) {
        elder.getNavigation().stop();
        elder.getLookControl().lookAt(p, 30f, 30f);
        elder.swingHand(elder.getDominantHand());
    }

    private static void menu(ServerPlayerEntity p) {
        MutableText t = Text.empty();
        for (String k : TOPICS) {
            t.append(Text.literal("[").append(Text.translatable("settlements.elder.q." + k)).append("]")
                    .styled(s -> s.withColor(k.equals("bye") ? Formatting.GRAY : Formatting.GOLD).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/settle elder " + k))));
            t.append(Text.literal(" "));
        }
        p.sendMessage(t, false);
    }

    /** Who is at what, in a line each: what the notice board was for. */
    private static void people(VillagerEntityMCA elder, Village village, ServerWorld world, ServerPlayerEntity p) {
        List<VillagerEntityMCA> folk = village.getResidents(world);
        elder.sendChatMessage(Text.translatable("settlements.elder.people", folk.size()), p);
        int n = 0;
        for (VillagerEntityMCA r : folk) {
            if (!r.isAlive() || r == elder) continue;
            if (n++ >= 14) break;
            String trade = r.isBaby() ? "settlements.trade.child" : r.isGuard() ? "settlements.role.guard"
                    : "settlements.role." + Registries.VILLAGER_PROFESSION.getId(r.getProfession()).getPath();
            p.sendMessage(Text.literal("  ").append(r.getName().copy().formatted(Formatting.YELLOW))
                    .append(Text.literal(" (").formatted(Formatting.GRAY)).append(Text.translatable(trade).formatted(Formatting.GRAY))
                    .append(Text.literal("): ").formatted(Formatting.GRAY)).append(Doing.describe(r).copy().formatted(Formatting.WHITE)), false);
        }
    }

    /** What the village is short of, and where to bring it. */
    private static void needs(VillagerEntityMCA elder, Village village, ServerWorld world, ServerPlayerEntity p) {
        Needs.Need need = Plan.firstBlocker(village);
        Builder.Shortage s = Builder.shortage(village);
        if (s != null && s.need() != Needs.Need.NONE) need = s.need();
        if (need == Needs.Need.NONE) {
            elder.sendChatMessage(Text.translatable("settlements.elder.fine"), p);
            return;
        }
        elder.sendChatMessage(Text.translatable("settlements.elder.help." + need.name().toLowerCase()), p);
        var store = Haul.store(elder, village);
        if (store != null) elder.sendChatMessage(Text.translatable("settlements.elder.bring", store.getX(), store.getY(), store.getZ()), p);
    }

    /** What is being built, and what comes after. */
    private static void plans(VillagerEntityMCA elder, Village village, ServerPlayerEntity p) {
        if (village.buildOrigin != null && village.buildType != null) {
            int total = Builder.stepsOf(village);
            int pct = total > 0 ? Math.min(100, village.buildStep * 100 / total) : 0;
            elder.sendChatMessage(Text.translatable("settlements.elder.building", Text.translatable("settlements.building." + Buildings.base(village.buildType)), pct), p);
        } else {
            elder.sendChatMessage(Text.translatable("settlements.elder.idle"), p);
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
            p.sendMessage(line, false);
        }
    }

    /** What has happened lately. */
    private static void news(VillagerEntityMCA elder, Village village, ServerPlayerEntity p) {
        List<Life.News> news = Life.news(village);
        if (news.isEmpty()) {
            elder.sendChatMessage(Text.translatable("settlements.elder.nonews"), p);
            return;
        }
        elder.sendChatMessage(Text.translatable("settlements.elder.news"), p);
        int n = 0;
        for (Life.News e : news) {
            if (n++ >= 5) break;
            p.sendMessage(Text.literal("  • ").append(Text.translatable("settlements.news." + e.key(), e.args()).formatted(Formatting.WHITE)), false);
        }
    }
}
