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
            new Goal("townhall", Level.HAMLET, v -> v.hall == null, 55, 0, 0, 0),
            new Goal("hallup", Level.HAMLET, v -> v.hall != null && v.hall.floors() < 3 && v.getPopulation() >= (v.hall.floors() == 1 ? 6 : 12), 150, 0, 0, 0),
            new Goal("well", Level.HAMLET, v -> !v.wellBuilt, 30, 0, 0, 0),
            new Goal("storage", Level.HAMLET, v -> !v.storageBuilt && v.getPopulation() >= 3, 150, 0, 0, 0),
            new Goal("housing", Level.HAMLET, v -> v.getPopulation() >= v.getMaxPopulation(), 100, 0, 0, 3),
            new Goal("farm", Level.HAMLET, v -> v.farms < Math.max(1, v.getPopulation() / (Forage.hungry(v) ? 3 : 4)), 30, 0, 0, 0),
            new Goal("pen", Level.HAMLET, v -> v.penCenter == null && v.getPopulation() >= 4, 50, 0, 0, 0),
            new Goal("rebuild", Level.HAMLET, v -> v.getPopulation() >= 3 && v.hall != null && Rebuild.candidate(v) != null, 120, 0, 0, 0),
            new Goal("sawmill", Level.HAMLET, v -> !Buildings.has(v, "sawmill") && v.getPopulation() >= 3, 40, 8, 0, 0),
            new Goal("charcoal", Level.HAMLET, v -> !Buildings.has(v, "charcoal") && v.getPopulation() >= 3, 12, 16, 0, 0),
            new Goal("granary", Level.HAMLET, v -> !Buildings.has(v, "granary") && v.getPopulation() >= 5, 70, 20, 0, 0),
            new Goal("mason", Level.VILLAGE, v -> !Buildings.has(v, "mason"), 50, 40, 0, 0),
            new Goal("mill", Level.VILLAGE, v -> !Buildings.has(v, "mill") && v.farms >= 1 && v.getPopulation() >= 6, 120, 40, 0, 0),
            new Goal("jail", Level.VILLAGE, v -> !Buildings.has(v, "jail") && v.getPopulation() >= 6, 40, 40, 0, 0),
            new Goal("barracks", Level.VILLAGE, v -> !Buildings.has(v, "barracks") && v.getPopulation() >= 8, 120, 30, 0, 0),
            new Goal("market", Level.VILLAGE, v -> !Buildings.has(v, "market") && v.getPopulation() >= 8, 80, 0, 0, 0),
            new Goal("chapel", Level.VILLAGE, v -> !Buildings.has(v, "chapel") && v.getPopulation() >= 10, 120, 70, 0, 0),
            new Goal("mine", Level.HAMLET, v -> !v.mineBuilt && Builder.stockCobble(v) < 128, 40, 0, 0, 0),
            new Goal("smithy", Level.VILLAGE, v -> !v.smithyBuilt && v.storageBuilt, 100, 12, 40, 0),
            new Goal("tavern", Level.VILLAGE, v -> v.tavern == null && v.storageBuilt && v.getPopulation() >= 6, 160, 12, 0, 0),
            new Goal("armory", Level.VILLAGE, v -> v.smithyBuilt && !v.armoryBuilt && v.getPopulation() >= 6, 150, 0, 0, 0),
            new Goal("tower", Level.VILLAGE, v -> v.towers < (Prosperity.tier(v) >= Tiers.CITY ? 4 : 2) && v.getPopulation() >= 8, 150, 60, 0, 0),
            new Goal("shop", Level.VILLAGE, v -> Prosperity.tier(v) >= Tiers.WEALTHY && Buildings.count(v, "shop") < (Prosperity.tier(v) >= Tiers.CITY ? 1 + v.getPopulation() / 6 : 1), 140, 40, 0, 4),
            new Goal("renovate", Level.VILLAGE, v -> v.getPopulation() >= 6 && Rebuild.renovation(v) != null, 160, 40, 0, 0),
            new Goal("palisade", Level.HAMLET, v -> v.palisadeR == 0 && (v.getPopulation() >= 6 || Prosperity.danger(v) >= 3), 150, 0, 0, 0),
            new Goal("wall", Level.TOWN, v -> !v.wallBuilt && v.palisadeR > 0 && v.getPopulation() >= 10, 0, 300, 0, 0)
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
            if (urgency(g.id(), v) < 0) continue; // not now
            list.add(new Status(g,
                    Math.max(0, g.planks() - Builder.stockPlanks(v)),
                    Math.max(0, g.cobble() - Builder.stockCobble(v)),
                    Math.max(0, g.iron() - Builder.stockIron(v)),
                    Math.max(0, g.wool() - Builder.stockWool(v))));
        }
        // most needed first: what the village lacks right now decides, not a fixed order
        list.sort(java.util.Comparator.comparingInt((Status s) -> -urgency(s.goal().id(), v)));
        return list;
    }

    /** How badly the village needs this now. */
    public static int urgency(String id, Village v) {
        int pop = v.getPopulation(), beds = v.getMaxPopulation();
        int food = 0;
        for (net.minecraft.item.ItemStack s : v.storageBuffer) if (s.isFood()) food += s.getCount();
        boolean hungry = food < Math.max(1, pop) * 3;
        double danger = Prosperity.danger(v);
        Builder.Shortage sh = Builder.shortage(v);
        boolean recentDeath = false, recentTheft = false;
        for (Life.News n : Life.news(v)) {
            if (n.key().equals("death") || n.key().equals("murder")) recentDeath = true;
            if (n.key().equals("theft")) recentTheft = true;
        }
        return switch (id) {
            case "townhall" -> 1000;
            case "board" -> 300;
            case "housing" -> pop >= beds ? 220 + 30 * (pop - beds) : 40;
            case "farm" -> hungry ? 260 : 70;
            case "granary" -> hungry ? 150 : 40;
            case "mill" -> hungry ? 140 : 45;
            case "pen" -> hungry ? 120 : 45;
            case "well" -> 160;
            case "storage" -> 90 + Math.min(80, v.storageBuffer.size() * 2);
            case "sawmill" -> sh != null && sh.need() == Needs.Need.PLANKS ? 170 : 75;
            case "charcoal" -> 65;
            case "mason" -> sh != null && sh.need() == Needs.Need.COBBLE ? 140 : 55;
            case "tower" -> danger >= 3 ? 200 : hungry ? -1 : 55;
            case "palisade" -> danger >= 3 ? 230 : 85;
            case "wall" -> danger >= 3 ? 190 : 50;
            case "barracks" -> danger >= 3 ? 180 : 45;
            case "tavern" -> 60;
            case "jail" -> recentTheft ? 160 : 30;
            // a hungry village builds for food and shelter; fine buildings and pulling down the old ones wait
            case "rebuild", "renovate", "shop", "market", "chapel", "hallup", "armory", "smithy" -> hungry ? -1 : switch (id) {
                case "hallup" -> 75;
                case "armory" -> danger >= 3 ? 120 : 35;
                case "smithy" -> 80;
                case "market" -> 45;
                case "chapel" -> recentDeath ? 150 : 35;
                default -> 55;
            };
            default -> 50;
        };
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
        // always the same one (the first by id), so the office does not pass from hand to hand
        List<VillagerEntityMCA> folk = v.getResidents(world);
        return folk.stream()
                .filter(r -> !r.isBaby() && !r.isGuard() && r.isAlive())
                .min(Comparator.comparing(r -> r.getUuid()))
                .orElse(null);
    }

    /** The elder tells the player in a few words where the village stands, what comes next and how he can help. */
    static void tellPublic(VillagerEntityMCA elder, Village village, ServerWorld world, PlayerEntity player, java.util.function.Consumer<Text> out) {
        tell(elder, village, world, player, out);
    }

    private static void tell(VillagerEntityMCA elder, Village village, ServerWorld world, PlayerEntity player) {
        tell(elder, village, world, player, t -> elder.sendChatMessage(t.copy(), player));
    }

    private static void tell(VillagerEntityMCA elder, Village village, ServerWorld world, PlayerEntity player, java.util.function.Consumer<Text> out) {
        elder.getLookControl().lookAt(player, 30f, 30f);
        elder.sendChatMessage(Text.translatable("settlements.elder.intro", village.getName(),
                Text.translatable("settlements.level." + level(village).name().toLowerCase()), village.getPopulation()), player);
        out.accept(Text.translatable("settlements.elder.pace", Prosperity.describe(village), reason(village)));
        if (village.buildOrigin != null && village.buildType != null) {
            int total = Builder.stepsOf(village);
            int pct = total > 0 ? Math.min(100, village.buildStep * 100 / total) : 0;
            out.accept(Text.translatable("settlements.elder.building", Text.translatable("settlements.building." + Buildings.base(village.buildType)), pct));
        } else {
            out.accept(Text.translatable("settlements.elder.idle"));
        }
        List<Status> pending = pending(village);
        Status next = null;
        for (Status s : pending) {
            if (!s.goal().id().equals(Buildings.base(village.buildType))) {
                next = s;
                break;
            }
        }
        if (next != null) out.accept(Text.translatable("settlements.elder.next", Text.translatable("settlements.goal." + next.goal().id())));
        Needs.Need need = firstBlocker(village);
        out.accept(Text.translatable(need == Needs.Need.NONE ? "settlements.elder.fine" : "settlements.elder.help." + need.name().toLowerCase()));
    }

    /** The main thing holding the village back, in the elder's words. */
    private static Text reason(Village village) {
        if (Prosperity.danger(village) >= 4) return Text.translatable("settlements.elder.reason.danger");
        int food = 0;
        for (net.minecraft.item.ItemStack s : village.storageBuffer) if (s.isFood()) food += s.getCount();
        if (food < Math.max(1, village.getPopulation()) * 3) return Text.translatable("settlements.elder.reason.food");
        if (Prosperity.factor(village) < 0.85) return Text.translatable("settlements.elder.reason.land");
        return Text.translatable("settlements.elder.reason.none");
    }

    public static void talk(VillagerEntityMCA speaker, PlayerEntity player) {
        Village village = Village.findNearest(speaker).orElse(null);
        if (village == null) {
            player.sendMessage(Text.translatable("settlements.plan.unregistered"), false);
            if (speaker.getWorld() instanceof ServerWorld w0) Registration.report(w0, speaker);
            return;
        }
        if (!(speaker.getWorld() instanceof ServerWorld world) || !(player instanceof net.minecraft.server.network.ServerPlayerEntity sp)) return;
        VillagerEntityMCA elder = elder(world, village);
        if (elder != null && elder != speaker) {
            // plans are the elder's business: everybody else sends the player to the town hall
            speaker.sendChatMessage(Text.translatable("settlements.elder.ask", elder.getName()), player);
            return;
        }
        if (elder == null) elder = speaker;
        tell(elder, village, world, player);
        List<Text> pages = new ArrayList<>();
        Level level = level(village);
        // page 1: where the village stands
        MutableText p1 = Text.empty();
        p1.append(Text.literal(village.getName() + "\n").formatted(net.minecraft.util.Formatting.BOLD, net.minecraft.util.Formatting.DARK_RED));
        p1.append(Text.translatable("settlements.level." + level.name().toLowerCase()).formatted(net.minecraft.util.Formatting.DARK_GREEN));
        p1.append(Text.translatable("settlements.book.residents", village.getPopulation(), village.getMaxPopulation()));
        p1.append(Text.translatable("settlements.book.built", village.houses.size(), village.farms, village.towers));
        pages.add(p1);
        for (int i = 1; i <= 3; i++) {
            pages.add(Text.empty().append(Text.translatable("settlements.book.how.title").formatted(net.minecraft.util.Formatting.BOLD, net.minecraft.util.Formatting.DARK_BLUE))
                    .append(Text.literal("\n\n")).append(Text.translatable("settlements.book.how." + i)));
        }
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
