package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a villager is up to and why, in his own words: every trade writes it down as it goes ("felling a tree - the
 * builder is 41 planks short"). A player who turns on {@code /settle why} sees it for whoever he looks at.
 */
public final class Doing {
    private record Note(String key, Object[] args, long at) {
    }

    private static final Map<UUID, Note> NOTES = new HashMap<>();
    private static final Set<UUID> WATCHING = new HashSet<>();

    private Doing() {
    }

    /** He is now doing this ({@code settlements.doing.*}). */
    public static void set(VillagerEntityMCA v, String key, Object... args) {
        // written afresh every time, the same as before or not: an hour at the same tree is still at it, not idle
        NOTES.put(v.getUuid(), new Note(key, args, v.getWorld().getTime()));
    }

    /** Every trade's seconds of the working day, by what they were doing (for the morning record: where the day went). */
    private static final Map<String, Map<String, Integer>> TALLY = new java.util.TreeMap<>();
    /** The current trade a count goes to (set by tally, read by count). */
    private static String current = "builder";

    private static String trade(VillagerEntityMCA v) {
        if (v.isElder()) return "elder";
        if (v.isGuard()) return "guard";
        var p = v.getProfession();
        if (p == net.minecraft.village.VillagerProfession.CARTOGRAPHER) return "builder";
        if (p == net.minecraft.village.VillagerProfession.MASON) return "miner";
        if (p == net.minecraft.village.VillagerProfession.FLETCHER || p == net.minecraft.village.VillagerProfession.TOOLSMITH) return "woodsman";
        return net.minecraft.registry.Registries.VILLAGER_PROFESSION.getId(p).getPath();
    }

    /**
     * The trace for the log: every ten seconds, for each grown villager, where he is, what he is doing, where he is
     * bound and how far he got since the last line - enough to follow anyone's day through the log afterwards
     * ({@code /settle trace} turns it off and on).
     */
    public static boolean TRACE = true;
    private static final Map<UUID, Vec3d> TRACED = new HashMap<>();

    private static void trace(VillagerEntityMCA v) {
        if (!TRACE || v.isBaby() || (v.age + (v.getId() & 0xff)) % 200 >= 20) return; // once in ten seconds, spread over the villagers
        Note n = NOTES.get(v.getUuid());
        String key = v.isSleeping() ? "sleep" : n == null || v.getWorld().getTime() - n.at() > 40 ? "none" : n.key().replace("settlements.doing.", "").replace("settlements.plan.doing.", "plan.");
        Vec3d was = TRACED.put(v.getUuid(), v.getPos());
        double moved = was == null ? 0 : Math.sqrt(was.squaredDistanceTo(v.getPos()));
        var nav = v.getNavigation().getCurrentPath();
        String goal = v.settlementsGoal != null ? v.settlementsGoal.toShortString() : "-";
        String path = nav == null ? "-" : (nav.reachesTarget() ? "to " : "short ") + nav.getTarget().toShortString();
        String seat = (Seat.seated(v) ? " seated" : "") + (LAID.containsKey(v.getUuid()) ? " laid " + LAID.remove(v.getUuid()) : "");
        String village = net.mca.server.world.data.Village.findNearest(v).map(x -> x.getName()).orElse("-");
        net.mca.MCA.LOGGER.info("[Settlements] T {} {}/{} at {} moved {} doing {} goal {} path {}{}", village, v.getName().getString(), trade(v),
                v.getBlockPos().toShortString(), String.format("%.1f", moved), key, goal, path, seat);
    }

    /** Blocks he laid for the building in hand since his last trace line. */
    private static final Map<UUID, Integer> LAID = new HashMap<>();

    /** A block laid for the plan of a building (counted into his next trace line, not a line of its own). */
    public static void laid(VillagerEntityMCA v) {
        LAID.merge(v.getUuid(), 1, Integer::sum);
    }

    /** What he is doing now, short (the key of his last note, if fresh). */
    static String now(VillagerEntityMCA v) {
        Note n = NOTES.get(v.getUuid());
        return v.isSleeping() ? "sleep" : n == null || v.getWorld().getTime() - n.at() > 40 ? "none" : n.key().replace("settlements.doing.", "").replace("settlements.plan.doing.", "plan.");
    }

    /**
     * Something he did to the world outside the plan of a building - a block dug, a block put down to walk on or
     * climb by, a hole filled - a line in the log at once: who, what, where, and at what work (the why).
     */
    public static void event(VillagerEntityMCA v, String what, net.minecraft.util.math.BlockPos at, net.minecraft.block.BlockState block) {
        if (!TRACE || v == null) return;
        net.mca.MCA.LOGGER.info("[Settlements] E {}/{} {} {} at {} while {}", v.getName().getString(), trade(v), what,
                block == null ? "" : net.minecraft.registry.Registries.BLOCK.getId(block.getBlock()).getPath(), at.toShortString(), now(v));
    }

    /** Once a second, before the new second's note is written: last second's note, counted for his trade. */
    static void tally(VillagerEntityMCA v) {
        trace(v);
        if (v.isBaby()) return;
        long tod = v.getWorld().getTimeOfDay() % 24000L;
        current = trade(v);
        if (tod >= 12000) return;
        Note n = NOTES.get(v.getUuid());
        String k = v.isSleeping() ? "sleep" : v.settlementsEatTicks > 0 ? "eating" : n == null || v.getWorld().getTime() - n.at() > 40 ? "none" : n.key().replace("settlements.doing.", "").replace("settlements.plan.doing.", "plan.");
        TALLY.computeIfAbsent(current, t -> new HashMap<>()).merge(k, 1, Integer::sum);
    }

    /** One more of something done within the work (a block laid, a turn, a walk), for the trade being ticked. */
    static void count(String what) {
        TALLY.computeIfAbsent(current, t -> new HashMap<>()).merge(what, 1, Integer::sum);
    }

    /** Where each trade's day went, most first, one line per trade; and the count starts afresh. */
    public static java.util.List<String> daySummary() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var t : TALLY.entrySet()) {
            out.add(t.getKey() + ": " + t.getValue().entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(18)
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(" ")));
        }
        TALLY.clear();
        return out;
    }

    private record Plan(Text chain, long at) {
    }

    private static final Map<UUID, Plan> PLANS = new HashMap<>();

    /** The chain of work he has worked out (see Chain), shown after what he is doing. */
    public static void plan(VillagerEntityMCA v, Text chain) {
        PLANS.put(v.getUuid(), new Plan(chain, v.getWorld().getTime()));
    }

    public static Text describe(VillagerEntityMCA v) {
        Note n = NOTES.get(v.getUuid());
        long now = v.getWorld().getTime();
        if (v.isSleeping()) return Text.translatable("settlements.doing.sleep");
        if (n == null || now - n.at() > 200) return Text.translatable("settlements.doing.idle");
        net.minecraft.text.MutableText t = Text.translatable(n.key(), n.args()).copy();
        Plan p = PLANS.get(v.getUuid());
        if (p != null && now - p.at() < 60) t.append(Text.literal("  [").formatted(Formatting.GRAY))
                .append(Text.translatable("settlements.chain.plan", p.chain()).formatted(Formatting.GRAY)).append(Text.literal("]").formatted(Formatting.GRAY));
        return t;
    }

    /** /settle why: on or off for this player. */
    public static int toggle(ServerPlayerEntity p) {
        boolean on = WATCHING.add(p.getUuid());
        if (!on) WATCHING.remove(p.getUuid());
        p.sendMessage(Text.translatable(on ? "settlements.why.on" : "settlements.why.off"), false);
        return 1;
    }

    /** Once a second: whoever a watching player looks at, what he is doing, over the hotbar. */
    public static void tick(ServerWorld world) {
        if (WATCHING.isEmpty()) return;
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (!WATCHING.contains(p.getUuid())) continue;
            VillagerEntityMCA v = lookedAt(p, 24);
            if (v == null) continue;
            MutableText line = Text.empty().append(v.getName().copy().formatted(Formatting.GOLD)).append(Text.literal(" - ").formatted(Formatting.GRAY))
                    .append(describe(v).copy().formatted(Formatting.WHITE));
            p.sendMessage(line, true);
        }
    }

    private static VillagerEntityMCA lookedAt(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos(), dir = p.getRotationVec(1f), end = eye.add(dir.multiply(range));
        Box box = p.getBoundingBox().stretch(dir.multiply(range)).expand(1);
        EntityHitResult hit = net.minecraft.entity.projectile.ProjectileUtil.raycast(p, eye, end, box, e -> e instanceof VillagerEntityMCA, range * range);
        Entity e = hit == null ? null : hit.getEntity();
        return e instanceof VillagerEntityMCA v ? v : null;
    }
}
