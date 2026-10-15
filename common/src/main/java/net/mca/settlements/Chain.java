package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.world.data.Village;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Chains of work worked out, not written down. A worker knows what he can do - each thing with what it needs and
 * what it brings about - and what he is after; he puts together the shortest chain from how things stand to his
 * goal (working backwards from the goal, as one does), and does its first link. Every second he looks again: when
 * the world has changed (the axe broke, the bag filled, the grove grew, an order came in) the chain is a new one.
 * A link that fails is avoided for a while and the chain made without it.
 */
public final class Chain {
    /** How things stand, as far as a worker's chains care. */
    enum Fact { HAS_AXE, HAS_PICK, BAG_FULL, BAG_HAS, SHIFT_ENDING, WOOD_WANTED, STONE_WANTED, GROVE_GROWN, DELIVERED, MINE_READY }

    /** Something a worker can do: what it needs, what it brings about, how much it costs him, and doing it. */
    record Act(String key, Set<Fact> needs, Set<Fact> gives, int cost, Exec exec) {
    }

    interface Exec {
        /** One second of it; true while under way (false: it failed or there was nothing to do). */
        boolean run(VillagerEntityMCA v, Village village, ServerWorld world);
    }

    private static final Map<String, Long> AVOID = new HashMap<>();
    private static final Map<UUID, Integer> FAILS = new HashMap<>();
    /** Seconds a link may come to nothing (no walking, nothing done) before it is set aside. */
    private static final int GIVE_UP = 20;
    private static final Map<UUID, List<Act>> SHOWN = new HashMap<>();

    private Chain() {
    }

    // ---------- the planner ----------

    private record Node(Set<Fact> open, List<Act> chain, int cost) {
    }

    /**
     * The cheapest chain of acts that, done in order from how things stand ({@code now}), brings about {@code goal};
     * null if there is none. Regressive search: from the goal back, replacing what is still wanted by what an act
     * needs.
     */
    static List<Act> plan(Set<Fact> now, Set<Fact> goal, List<Act> acts, VillagerEntityMCA v, long time) {
        PriorityQueue<Node> q = new PriorityQueue<>((a, b) -> Integer.compare(a.cost(), b.cost()));
        Set<Fact> start = EnumSet.noneOf(Fact.class);
        for (Fact f : goal) if (!now.contains(f)) start.add(f);
        if (start.isEmpty()) return new ArrayList<>();
        q.add(new Node(start, new ArrayList<>(), 0));
        int expanded = 0;
        while (!q.isEmpty() && expanded++ < 200) {
            Node n = q.poll();
            if (n.open().isEmpty()) return n.chain();
            if (n.chain().size() >= 6) continue;
            for (Act a : acts) {
                if (time < AVOID.getOrDefault(v.getUuid() + a.key(), 0L)) continue;
                boolean useful = false;
                for (Fact f : a.gives()) if (n.open().contains(f)) useful = true;
                if (!useful) continue;
                Set<Fact> open = EnumSet.noneOf(Fact.class);
                open.addAll(n.open());
                open.removeAll(a.gives());
                for (Fact f : a.needs()) if (!now.contains(f)) open.add(f);
                List<Act> chain = new ArrayList<>();
                chain.add(a);
                chain.addAll(n.chain());
                q.add(new Node(open, chain, n.cost() + a.cost()));
            }
        }
        return null;
    }

    /** Works out the chain and does its first link. True while he is at it. */
    static boolean follow(VillagerEntityMCA v, Village village, ServerWorld world, Set<Fact> now, Set<Fact> goal, List<Act> acts, Exec fallback) {
        long time = world.getTime();
        List<Act> chain = plan(now, goal, acts, v, time);
        if (chain == null || chain.isEmpty()) {
            SHOWN.remove(v.getUuid());
            // no chain to be had: he goes about his trade the plain way, as he always did
            return fallback != null && fallback.run(v, village, world);
        }
        Act first = chain.get(0);
        if (!chain.equals(SHOWN.get(v.getUuid()))) SHOWN.put(v.getUuid(), chain);
        Doing.plan(v, describe(chain));
        // a link "fails" in a second when the trade did nothing that second - but walking to the tree, down the
        // stairs of the shaft or to the stores is being at it, not failing
        if (first.exec().run(v, village, world) || Move.goal(v) != null || v.getNavigation().isFollowingPath() || Dig.busy(v)) {
            FAILS.remove(v.getUuid());
            return true;
        }
        int f = FAILS.merge(v.getUuid(), 1, Integer::sum);
        if (f < GIVE_UP) return true; // a moment's trouble: he keeps at it
        FAILS.remove(v.getUuid());
        AVOID.put(v.getUuid() + first.key(), time + 20 * 60); // it did not work out: a chain without it, for a minute
        return false;
    }

    /** "the axe → felling till the bag is full → to the stores" */
    private static MutableText describe(List<Act> chain) {
        MutableText t = Text.empty();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) t.append(Text.literal(" → "));
            t.append(Text.translatable("settlements.chain." + chain.get(i).key()));
        }
        return t;
    }

    // ---------- the lumberjack ----------

    private static final List<Act> LUMBER = List.of(
            new Act("axe", EnumSet.noneOf(Fact.class), EnumSet.of(Fact.HAS_AXE), 2, (v, village, world) -> {
                Gear.equipTool(v, village, Chore.CHOP);
                return Gear.has(v, net.minecraft.item.AxeItem.class) || Forge.waiting(v, village);
            }),
            new Act("fell", EnumSet.of(Fact.HAS_AXE, Fact.WOOD_WANTED), EnumSet.of(Fact.BAG_FULL), 4,
                    (v, village, world) -> Lumberjack.work(v, village, Orders.held(v, village) != null)),
            new Act("grove", EnumSet.of(Fact.HAS_AXE, Fact.GROVE_GROWN), EnumSet.of(Fact.BAG_FULL), 3,
                    (v, village, world) -> Lumberjack.work(v, village, true)),
            new Act("haul", EnumSet.of(Fact.BAG_FULL), EnumSet.of(Fact.DELIVERED), 1, (v, village, world) -> Haul.carry(v, village, true)),
            new Act("haul_end", EnumSet.of(Fact.BAG_HAS, Fact.SHIFT_ENDING), EnumSet.of(Fact.DELIVERED), 1, (v, village, world) -> Haul.carry(v, village, true)));

    /** One second of the lumberjack's day, by chain. True when he worked. */
    public static boolean lumber(VillagerEntityMCA v, Village village, ServerWorld world, long timeOfDay) {
        Set<Fact> now = EnumSet.noneOf(Fact.class);
        if (Gear.has(v, net.minecraft.item.AxeItem.class)) now.add(Fact.HAS_AXE);
        sense(v, timeOfDay, now);
        if (Orders.take(v, village, Needs.Need.PLANKS) != null || Lumberjack.stockLogs(village) < Lumberjack.QUOTA) now.add(Fact.WOOD_WANTED);
        if (!Grove.grown(world, village).isEmpty()) now.add(Fact.GROVE_GROWN);
        // what he is after: a load at the stores (when wood is wanted, or there is a load to bring in)
        if (!now.contains(Fact.WOOD_WANTED) && !now.contains(Fact.BAG_HAS)) return false;
        return follow(v, village, world, now, EnumSet.of(Fact.DELIVERED), LUMBER, (a, b, c) -> Lumberjack.work(a, b));
    }

    // ---------- the miner ----------

    private static final List<Act> MINE = List.of(
            new Act("pick", EnumSet.noneOf(Fact.class), EnumSet.of(Fact.HAS_PICK), 2, (v, village, world) -> {
                Gear.equipTool(v, village, Chore.PROSPECT);
                Gear.equipShovel(v, village);
                return Mining.hasPick(v) || Forge.waiting(v, village);
            }),
            new Act("dig", EnumSet.of(Fact.HAS_PICK, Fact.STONE_WANTED), EnumSet.of(Fact.BAG_FULL), 4, (v, village, world) -> Mining.work(v, village)),
            new Act("haul", EnumSet.of(Fact.BAG_FULL), EnumSet.of(Fact.DELIVERED), 1, (v, village, world) -> Haul.carry(v, village, true)),
            new Act("haul_end", EnumSet.of(Fact.BAG_HAS, Fact.SHIFT_ENDING), EnumSet.of(Fact.DELIVERED), 1, (v, village, world) -> Haul.carry(v, village, true)));

    /** One second of the miner's day, by chain. True when he worked. */
    public static boolean mine(VillagerEntityMCA v, Village village, ServerWorld world, long timeOfDay) {
        Set<Fact> now = EnumSet.noneOf(Fact.class);
        if (Mining.hasPick(v)) now.add(Fact.HAS_PICK);
        sense(v, timeOfDay, now);
        if (Orders.take(v, village, Needs.Need.COBBLE) != null || Builder.stockCobble(village) < Mining.QUOTA) now.add(Fact.STONE_WANTED);
        if (!now.contains(Fact.STONE_WANTED) && !now.contains(Fact.BAG_HAS)) return false;
        return follow(v, village, world, now, EnumSet.of(Fact.DELIVERED), MINE, (a, b, c) -> Mining.work(a, b));
    }

    private static void sense(VillagerEntityMCA v, long timeOfDay, Set<Fact> now) {
        int load = Haul.load(v);
        if (load > 0) now.add(Fact.BAG_HAS);
        if (load >= Haul.FULL_LOAD || Haul.pocketsFull(v) || v.settlementsHauling || v.settlementsDeliverNow && load > 0) now.add(Fact.BAG_FULL);
        long t = timeOfDay % 24000L;
        if (t > Persona.workEnd(v) - 600 || !Persona.working(v, timeOfDay)) now.add(Fact.SHIFT_ENDING);
    }
}
