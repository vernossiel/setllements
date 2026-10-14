package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The village's roadmap. The village has a level that grows with what it has built, every goal is gated by it, and the
 * village elder can tell a player what comes next and what is missing, so the player knows where help is needed.
 */
public final class Plan {
    public enum Level { HAMLET, VILLAGE, TOWN, FORTRESS }

    public record Goal(String id, Level minLevel, Predicate<Village> active, int planks, int cobble, int iron, int wool) {
    }

    public record Status(Goal goal, int missPlanks, int missCobble, int missIron, int missWool) {
        public boolean ready() {
            return missPlanks == 0 && missCobble == 0 && missIron == 0 && missWool == 0;
        }
    }

    public static final List<Goal> GOALS = List.of(
            new Goal("well", Level.HAMLET, v -> !v.wellBuilt, 30, 0, 0, 0),
            new Goal("storage", Level.HAMLET, v -> !v.storageBuilt && v.getPopulation() >= 3, 150, 0, 0, 0),
            new Goal("housing", Level.HAMLET, v -> v.getPopulation() >= v.getMaxPopulation(), 100, 0, 0, 3),
            new Goal("farm", Level.HAMLET, v -> v.farms < Math.max(1, v.getPopulation() / 4), 30, 0, 0, 0),
            new Goal("mine", Level.HAMLET, v -> !v.mineBuilt && Builder.stockCobble(v) < 128, 40, 0, 0, 0),
            new Goal("smithy", Level.VILLAGE, v -> !v.smithyBuilt && v.storageBuilt, 100, 12, 40, 0),
            new Goal("tavern", Level.VILLAGE, v -> v.tavern == null && v.storageBuilt && v.getPopulation() >= 6, 160, 12, 0, 0),
            new Goal("armory", Level.VILLAGE, v -> v.smithyBuilt && !v.armoryBuilt && v.getPopulation() >= 6, 150, 0, 0, 0),
            new Goal("tower", Level.VILLAGE, v -> v.towers < 2 && v.getPopulation() >= 8, 150, 60, 0, 0),
            new Goal("wall", Level.TOWN, v -> !v.wallBuilt && v.storageBuilt && v.houses.size() >= 4 && v.getPopulation() >= 10 && v.towers >= 1, 0, 300, 0, 0)
    );

    private Plan() {
    }

    public static Level level(Village v) {
        boolean village = v.getPopulation() >= 6 && v.storageBuilt && v.wellBuilt;
        boolean town = village && v.getPopulation() >= 12 && v.houses.size() >= 5 && v.smithyBuilt && v.farms >= 2 && v.towers >= 1;
        boolean fortress = town && v.wallBuilt && v.armoryBuilt;
        return fortress ? Level.FORTRESS : town ? Level.TOWN : village ? Level.VILLAGE : Level.HAMLET;
    }

    /** All goals that apply now, in order, with what the treasury still lacks for each. */
    public static List<Status> pending(Village v) {
        Level level = level(v);
        List<Status> list = new ArrayList<>();
        for (Goal g : GOALS) {
            if (g.minLevel().ordinal() > level.ordinal() || !g.active().test(v)) continue;
            list.add(new Status(g,
                    Math.max(0, g.planks() - Builder.stockPlanks(v)),
                    Math.max(0, g.cobble() - Builder.stockCobble(v)),
                    Math.max(0, g.iron() - Builder.stockIron(v)),
                    Math.max(0, g.wool() - Builder.stockWool(v))));
        }
        return list;
    }

    /** What the builder lacks for the first goal that is waiting on materials, for the "needs help" marker. */
    public static Needs.Need firstBlocker(Village v) {
        for (Status s : pending(v)) {
            if (s.ready()) continue;
            if (s.missPlanks() > 0) return Needs.Need.PLANKS;
            if (s.missCobble() > 0) return Needs.Need.COBBLE;
            if (s.missIron() > 0) return Needs.Need.IRON;
            if (s.missWool() > 0) return Needs.Need.WOOL;
        }
        return Needs.Need.NONE;
    }

    // ---------- the elder ----------

    /** The elder is the oldest-registered adult civilian of the village, chosen deterministically so it never changes. */
    public static VillagerEntityMCA elder(ServerWorld world, Village v) {
        return v.getResidents(world).stream()
                .filter(r -> !r.isBaby() && !r.isGuard() && r.isAlive())
                .min(Comparator.comparing(r -> r.getUuid()))
                .orElse(null);
    }

    public static void talk(VillagerEntityMCA speaker, PlayerEntity player) {
        Village village = Village.findNearest(speaker).orElse(null);
        if (village == null || !(speaker.getWorld() instanceof ServerWorld world) || !(player instanceof net.minecraft.server.network.ServerPlayerEntity sp)) return;
        VillagerEntityMCA elder = elder(world, village);
        if (elder != speaker) {
            if (elder != null) {
                player.sendMessage(Text.translatable("settlements.plan.notme", elder.getName()), false);
            }
            return;
        }
        List<Text> pages = new ArrayList<>();
        Level level = level(village);
        // page 1: where the village stands
        MutableText p1 = Text.empty();
        p1.append(Text.literal(village.getName() + "\n").formatted(net.minecraft.util.Formatting.BOLD, net.minecraft.util.Formatting.DARK_RED));
        p1.append(Text.translatable("settlements.level." + level.name().toLowerCase()).formatted(net.minecraft.util.Formatting.DARK_GREEN));
        p1.append(Text.translatable("settlements.book.residents", village.getPopulation(), village.getMaxPopulation()));
        p1.append(Text.translatable("settlements.book.built", village.houses.size(), village.farms, village.towers));
        pages.add(p1);
        // pages 2..: the plan
        List<Status> pending = pending(village);
        MutableText p2 = Text.empty();
        p2.append(Text.translatable("settlements.book.plan").formatted(net.minecraft.util.Formatting.BOLD, net.minecraft.util.Formatting.DARK_RED));
        if (pending.isEmpty()) p2.append(Text.translatable("settlements.plan.none"));
        int shown = 0;
        for (Status s : pending) {
            if (shown++ >= 5) break;
            p2.append(Text.literal("\n\n• ").append(Text.translatable("settlements.goal." + s.goal().id()).formatted(net.minecraft.util.Formatting.BLUE)));
            if (s.ready()) {
                p2.append(Text.literal("\n  ").append(Text.translatable("settlements.book.ready").formatted(net.minecraft.util.Formatting.DARK_GREEN)));
            } else {
                MutableText missing = Text.empty();
                if (s.missPlanks() > 0) missing.append(Text.translatable("settlements.missing.planks", s.missPlanks())).append(" ");
                if (s.missCobble() > 0) missing.append(Text.translatable("settlements.missing.cobble", s.missCobble())).append(" ");
                if (s.missIron() > 0) missing.append(Text.translatable("settlements.missing.iron", s.missIron())).append(" ");
                if (s.missWool() > 0) missing.append(Text.translatable("settlements.missing.wool", s.missWool()));
                p2.append(Text.literal("\n  ").append(Text.translatable("settlements.book.missing", missing).formatted(net.minecraft.util.Formatting.DARK_GRAY)));
            }
        }
        pages.add(p2);
        // last page: how the player can help
        MutableText p3 = Text.empty();
        p3.append(Text.translatable("settlements.book.help").formatted(net.minecraft.util.Formatting.BOLD, net.minecraft.util.Formatting.DARK_RED));
        VillagerEntityMCA builder = village.getResidents(world).stream()
                .filter(r -> r.getProfession() == net.minecraft.village.VillagerProfession.CARTOGRAPHER).findFirst().orElse(null);
        p3.append(Text.translatable("settlements.book.help.text", builder == null ? Text.translatable("settlements.book.nobuilder") : builder.getName()));
        pages.add(p3);

        net.minecraft.item.ItemStack book = new net.minecraft.item.ItemStack(net.minecraft.item.Items.WRITTEN_BOOK);
        net.minecraft.nbt.NbtCompound tag = book.getOrCreateNbt();
        tag.putString("title", village.getName());
        tag.putString("author", elder.getName().getString());
        net.minecraft.nbt.NbtList list = new net.minecraft.nbt.NbtList();
        for (Text page : pages) list.add(net.minecraft.nbt.NbtString.of(Text.Serializer.toJson(page)));
        tag.put("pages", list);
        sp.useBook(book, net.minecraft.util.Hand.MAIN_HAND);
    }
}
