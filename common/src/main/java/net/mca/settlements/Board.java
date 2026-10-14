package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;

/**
 * The village notice board: six signs on the plaza that the villagers keep up to date (what is being built, what is
 * missing, the latest news, war and peace with the neighbours). Right-clicking it opens the full board as a book:
 * who is doing what, who needs what, the plans, the neighbours and the news.
 */
public final class Board {
    private static final int LINE = 15;

    private Board() {
    }

    /** Every ten seconds per world. */
    public static void tick(ServerWorld world) {
        for (Village v : VillageManager.get(world)) if (!v.boardSigns.isEmpty()) refresh(world, v);
    }

    public static Village villageOf(ServerWorld world, BlockPos pos) {
        for (Village v : VillageManager.get(world)) if (v.boardSigns.contains(pos)) return v;
        return null;
    }

    // ---------- the signs ----------

    public static void refresh(ServerWorld world, Village village) {
        List<Text[]> signs = new ArrayList<>();
        signs.add(new Text[]{Text.literal(village.getName()).formatted(Formatting.BOLD),
                Text.translatable("settlements.level." + Plan.level(village).name().toLowerCase()),
                Text.translatable("settlements.board.residents", village.getPopulation(), village.getMaxPopulation()), Prosperity.describe(village)});
        signs.add(project(village));
        signs.add(needs(village));
        List<Life.News> news = Life.news(village);
        signs.add(newsSign(news, 0));
        signs.add(newsSign(news, 1));
        signs.add(relationsSign(world, village));

        int i = 0;
        for (BlockPos p : village.boardSigns) {
            if (i >= signs.size()) break;
            Text[] lines = signs.get(i++);
            if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (!(world.getBlockEntity(p) instanceof SignBlockEntity sign)) continue;
            SignText text = new SignText();
            for (int l = 0; l < 4; l++) text = text.withMessage(l, lines[l]);
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.markDirty();
            BlockState st = world.getBlockState(p);
            world.updateListeners(p, st, st, 3);
        }
    }

    private static Text[] project(Village village) {
        Text[] t = {Text.translatable("settlements.board.building").formatted(Formatting.DARK_BLUE), Text.empty(), Text.empty(), Text.empty()};
        if (village.buildOrigin != null && village.buildType != null) {
            t[1] = Text.translatable("settlements.building." + village.buildType);
            int total = Builder.stepsOf(village);
            if (total > 0) t[2] = Text.literal(Math.min(100, village.buildStep * 100 / total) + "%");
            if (Builder.shortage(village) != null) t[3] = Text.translatable("settlements.board.gathering").formatted(Formatting.DARK_RED);
        } else {
            List<Plan.Status> pending = Plan.pending(village);
            if (pending.isEmpty()) {
                t[1] = Text.translatable("settlements.board.nothing");
            } else {
                t[1] = Text.translatable("settlements.board.next");
                t[2] = Text.translatable("settlements.goal." + pending.get(0).goal().id());
            }
        }
        return t;
    }

    private static Text[] needs(Village village) {
        Text[] t = {Text.translatable("settlements.board.needs").formatted(Formatting.DARK_RED), Text.empty(), Text.empty(), Text.empty()};
        int line = 1;
        for (Plan.Status s : Plan.pending(village)) {
            if (s.ready()) continue;
            if (s.missPlanks() > 0 && line < 4) t[line++] = Text.translatable("settlements.missing.planks", s.missPlanks());
            if (s.missCobble() > 0 && line < 4) t[line++] = Text.translatable("settlements.missing.cobble", s.missCobble());
            if (s.missIron() > 0 && line < 4) t[line++] = Text.translatable("settlements.missing.iron", s.missIron());
            if (s.missWool() > 0 && line < 4) t[line++] = Text.translatable("settlements.missing.wool", s.missWool());
            break;
        }
        int food = 0;
        for (ItemStack s : village.storageBuffer) if (s.isFood()) food += s.getCount();
        if (food < village.getPopulation() * 3 && line < 4) t[line++] = Text.translatable("settlements.board.food");
        if (line == 1) t[1] = Text.translatable("settlements.board.allgood");
        return t;
    }

    private static Text[] newsSign(List<Life.News> news, int idx) {
        Text[] t = {Text.translatable("settlements.board.news").formatted(Formatting.DARK_GREEN), Text.empty(), Text.empty(), Text.empty()};
        if (news.size() <= idx) {
            if (idx == 0) t[1] = Text.translatable("settlements.board.quiet");
            return t;
        }
        List<String> lines = wrap(newsText(news.get(idx)).getString(), 3);
        for (int i = 0; i < lines.size(); i++) t[i + 1] = Text.literal(lines.get(i));
        return t;
    }

    private static Text[] relationsSign(ServerWorld world, Village village) {
        Text[] t = {Text.translatable("settlements.board.neighbours").formatted(Formatting.DARK_PURPLE), Text.empty(), Text.empty(), Text.empty()};
        int line = 1;
        for (var e : village.relations.entrySet()) {
            if (line >= 4) break;
            Village o = VillageManager.get(world).getOrEmpty(e.getKey()).orElse(null);
            if (o == null) continue;
            String name = o.getName();
            if (name.length() > 8) name = name.substring(0, 8);
            t[line++] = Text.literal(name + ": ").append(relationText(e.getValue()));
        }
        if (line == 1) t[1] = Text.translatable("settlements.board.alone");
        return t;
    }

    static Text relationText(int r) {
        if (r <= -40) return Text.translatable("settlements.relation.war").formatted(Formatting.DARK_RED);
        if (r <= -20) return Text.translatable("settlements.relation.hostile").formatted(Formatting.RED);
        if (r >= 40) return Text.translatable("settlements.relation.friends").formatted(Formatting.DARK_GREEN);
        return Text.translatable("settlements.relation.peace").formatted(Formatting.GRAY);
    }

    static Text newsText(Life.News n) {
        return Text.translatable("settlements.news." + n.key(), n.args());
    }

    private static List<String> wrap(String s, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > LINE) {
                out.add(cur.toString());
                cur = new StringBuilder();
                if (out.size() >= maxLines) return out;
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0 && out.size() < maxLines) out.add(cur.toString());
        return out;
    }

    // ---------- the book ----------

    public static void open(ServerPlayerEntity player, ServerWorld world, Village village) {
        List<Text> pages = new ArrayList<>();
        List<VillagerEntityMCA> people = new ArrayList<>(village.getResidents(world));
        people.removeIf(r -> !r.isAlive());

        // who is doing what, six people a page
        for (int start = 0; start < Math.max(1, people.size()); start += 6) {
            MutableText p = Text.empty().append(Text.translatable("settlements.board.book.doing").formatted(Formatting.BOLD, Formatting.DARK_BLUE));
            for (int i = start; i < Math.min(people.size(), start + 6); i++) {
                VillagerEntityMCA r = people.get(i);
                p.append(Text.literal("\n\n").append(r.getName().copy().formatted(Formatting.BLACK)));
                p.append(Text.literal("\n ").append(doing(r, village).copy().formatted(Formatting.DARK_GRAY)));
            }
            pages.add(p);
        }

        // needs: of the people and of the village
        MutableText needs = Text.empty().append(Text.translatable("settlements.board.book.needs").formatted(Formatting.BOLD, Formatting.DARK_RED));
        boolean any = false;
        for (VillagerEntityMCA r : people) {
            Needs.Need n = Needs.get(r);
            if (n == Needs.Need.NONE) continue;
            any = true;
            needs.append(Text.literal("\n• ").append(r.getName()).append(": ").append(Text.translatable("settlements.board.need." + n.name().toLowerCase())));
        }
        Text[] village_needs = needs(village);
        for (int i = 1; i < village_needs.length; i++) {
            if (!village_needs[i].getString().isEmpty()) needs.append(Text.literal("\n• ").append(village_needs[i]));
        }
        if (!any) needs.append(Text.literal("\n"));
        pages.add(needs);

        // building and plans
        MutableText plan = Text.empty().append(Text.translatable("settlements.board.book.plans").formatted(Formatting.BOLD, Formatting.DARK_GREEN));
        for (Text line : project(village)) if (line != null && !line.getString().isEmpty()) plan.append(Text.literal("\n")).append(line);
        int shown = 0;
        for (Plan.Status s : Plan.pending(village)) {
            if (shown++ >= 5) break;
            plan.append(Text.literal("\n• ").append(Text.translatable("settlements.goal." + s.goal().id()))
                    .append(s.ready() ? Text.literal(" ✔").formatted(Formatting.DARK_GREEN) : Text.literal(" …").formatted(Formatting.GRAY)));
        }
        pages.add(plan);

        // neighbours: war and peace
        MutableText rel = Text.empty().append(Text.translatable("settlements.board.book.neighbours").formatted(Formatting.BOLD, Formatting.DARK_PURPLE));
        if (village.relations.isEmpty()) rel.append(Text.literal("\n").append(Text.translatable("settlements.board.alone")));
        for (var e : village.relations.entrySet()) {
            Village o = VillageManager.get(world).getOrEmpty(e.getKey()).orElse(null);
            if (o == null) continue;
            rel.append(Text.literal("\n• " + o.getName() + ": ").append(relationText(e.getValue())));
        }
        pages.add(rel);

        // news
        MutableText news = Text.empty().append(Text.translatable("settlements.board.book.news").formatted(Formatting.BOLD, Formatting.DARK_GREEN));
        List<Life.News> list = Life.news(village);
        if (list.isEmpty()) news.append(Text.literal("\n").append(Text.translatable("settlements.board.quiet")));
        for (Life.News n : list) news.append(Text.literal("\n• ").append(newsText(n)));
        pages.add(news);

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        NbtCompound tag = book.getOrCreateNbt();
        tag.putString("title", village.getName());
        tag.putString("author", village.getName());
        NbtList nbt = new NbtList();
        for (Text page : pages) nbt.add(NbtString.of(Text.Serializer.toJson(page)));
        tag.put("pages", nbt);
        player.useBook(book, Hand.MAIN_HAND);
    }

    /** What this villager is up to, in a few words. */
    public static Text doing(VillagerEntityMCA r, Village village) {
        if (r.isBaby()) return Text.translatable("settlements.doing.child");
        if (r.isSleeping()) return Text.translatable("settlements.doing.sleep");
        if (r.settlementsEatTicks > 0) return Text.translatable("settlements.doing.eat");
        if (Needs.get(r) == Needs.Need.FOOD) return Text.translatable("settlements.doing.hungry");
        if (r.isElder()) return Text.translatable("settlements.doing.elder");
        if (r.isGuard()) return Text.translatable("settlements.doing.guard");
        if (r.settlementsBreak) return Text.translatable("settlements.doing.rest");
        long time = r.getWorld().getTimeOfDay();
        if (!Workforce.isWorkTime(time)) return Text.translatable("settlements.doing.leisure");
        if (r.settlementsIdleWork >= 45 && Builder.shortage(village) != null) return Text.translatable("settlements.doing.helping");
        VillagerProfession p = r.getProfession();
        if (p == VillagerProfession.CARTOGRAPHER) {
            if (village.buildOrigin == null) return Text.translatable("settlements.doing.builder_idle");
            if (Builder.shortage(village) != null) return Text.translatable("settlements.doing.gathering");
            return Text.translatable("settlements.doing.building", Text.translatable("settlements.building." + village.buildType));
        }
        if (p == VillagerProfession.MASON) return Text.translatable("settlements.doing.mining", Math.max(1, village.mineLevel));
        if (p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH)
            return Text.translatable(r.lumberTarget != null ? "settlements.doing.felling" : "settlements.doing.searching_trees");
        String id = Registries.VILLAGER_PROFESSION.getId(p).getPath();
        return Text.translatable("settlements.doing." + id);
    }

    public static boolean onUse(PlayerEntity player, BlockPos pos) {
        if (!(player instanceof ServerPlayerEntity sp) || !(sp.getWorld() instanceof ServerWorld world)) return false;
        Village v = villageOf(world, pos);
        if (v == null) return false;
        open(sp, world, v);
        return true;
    }
}
