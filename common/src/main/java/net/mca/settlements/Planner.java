package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A worker stuck for want of something makes a plan, as a person would, and goes down it: the first step that can
 * be done now is done; a step that does not work out is put aside for a while and the next one tried. Each second
 * the plan is looked at afresh (the world changes: the wood arrives, the player walks off), so it is always the
 * best step for things as they are. What he is doing, and what he gave up on, shows in /settle why.
 */
public final class Planner {
    /** A step: whether it can be tried now, and one second of it (true while it is under way). */
    interface Step {
        String key();

        boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need);

        boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need);
    }

    private static final Map<String, Long> SET_ASIDE = new HashMap<>();
    private static final Map<UUID, String> CURRENT = new HashMap<>();

    private Planner() {
    }

    private static String id(VillagerEntityMCA v, Step s) {
        return v.getUuid() + "|" + s.key();
    }

    /** The builder's plan when the stores are out of what the next block needs. True while it keeps him busy. */
    public static boolean supply(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
        return run(v, village, world, need, SUPPLY);
    }

    private static boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need, List<Step> plan) {
        long now = world.getTime();
        for (Step s : plan) {
            if (now < SET_ASIDE.getOrDefault(id(v, s), 0L)) continue;
            if (!s.possible(v, village, world, need)) continue;
            String was = CURRENT.put(v.getUuid(), s.key());
            String group = need == Needs.Need.NONE ? "idle." : "";
            if (!s.key().equals(was)) Speech.say(v, "settlements.plan.say." + group + s.key(), Text.translatable("settlements.need.name." + need.name().toLowerCase()));
            Doing.set(v, "settlements.plan.doing." + group + s.key(), Text.translatable("settlements.need.name." + need.name().toLowerCase()));
            if (s.run(v, village, world, need)) return true;
            SET_ASIDE.put(id(v, s), now + 20 * 90); // did not work out: the next way, and this one again later
        }
        CURRENT.remove(v.getUuid());
        return false;
    }

    /** A worker with nothing to do at his own trade. True while the plan keeps him busy. */
    public static boolean idle(VillagerEntityMCA v, Village village, ServerWorld world) {
        return run(v, village, world, Needs.Need.NONE, IDLE);
    }

    private static final List<Step> IDLE = List.of(
            new Step() { // what he has in his bag goes to the stores first
                public String key() {
                    return "haul";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return Workforce.isGatherer(v.getProfession()) && Haul.load(v) > 0;
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return Haul.carry(v, village, true);
                }
            },
            new Step() { // a hungry village: food before anything
                public String key() {
                    return "forage";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return Forage.hungry(village);
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return Forage.work(v, village, world);
                }
            },
            new Step() { // an order nobody has taken, or the builders short of something
                public String key() {
                    return "order";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return Orders.held(v, village) != null || Orders.free(village, Needs.Need.PLANKS, Needs.Need.COBBLE) != null || Builder.shortage(village) != null;
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return Life.helpBuilders(v, village, world);
                }
            },
            new Step() { // the roads want mending
                public String key() {
                    return "roads";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return village.buildOrigin == null || v.getRandom().nextBoolean();
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return Repair.roadWork(v, village, world);
                }
            },
            new Step() { // nothing at all: he says so to the elder, who may have something
                public String key() {
                    return "elder";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return village.hallPos != null && v.settlementsIdleWork >= 120;
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    VillagerEntityMCA elder = null;
                    for (VillagerEntityMCA r : village.getResidents(world)) if (r.isElder() && r.isAlive()) elder = r;
                    if (elder == null || elder == v) return false;
                    if (v.squaredDistanceTo(elder) > 16) {
                        Move.to(v, elder.getX(), elder.getY(), elder.getZ(), 0.5);
                        return true;
                    }
                    Speech.sayNow(v, "settlements.plan.idle.tell", elder.getName());
                    elder.settlementsReplyKey = "settlements.plan.idle.answer." + v.getRandom().nextInt(3);
                    elder.settlementsReplyArgs = new Object[]{v.getName()};
                    elder.settlementsReplyAt = world.getTime() + 40;
                    return false;
                }
            });

    // ---------- the builder short of material ----------

    private static final List<Step> SUPPLY = List.of(
            // someone has taken the order: the wood is coming - meanwhile something useful near at hand
            new Step() {
                public String key() {
                    return "wait";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    Orders.Order o = Orders.open(village).stream().filter(x -> x.what() == need).findFirst().orElse(null);
                    return o != null && o.taker() != null && !o.taker().equals(v.getUuid());
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    // not standing about while another brings it: he goes for it himself as well (the roads only when there
                    // is nothing to fetch near)
                    return Scavenge.work(v, village, world, need, need == Needs.Need.COBBLE) || Ground.work(v, village, world) || Repair.roadWork(v, village, world);
                }
            },
            // nobody on it: he gets it himself, from what lies about or grows near
            new Step() {
                public String key() {
                    return "gather";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return need == Needs.Need.PLANKS || need == Needs.Need.COBBLE;
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return Scavenge.work(v, village, world, need, need == Needs.Need.COBBLE); // short of wood: a tree felled, not earth dug (walls are no longer patched with it)
                }
            },
            // no stone lying about or showing, and no miner in the village: down the mine himself, as the miner would
            // (the builder stood a day asking the elder for stone, with the shaft beside the hall)
            new Step() {
                public String key() {
                    return "dig";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return need == Needs.Need.COBBLE && !Staffing.has(world, village, net.minecraft.village.VillagerProfession.MASON);
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    if (Builder.stockCobble(village) >= 64) return false; // enough for now: back to the building
                    return Chain.mine(v, village, world, world.getTimeOfDay()) || Move.goal(v) != null;
                }
            },
            // a friend of the village stands near: he asks
            new Step() {
                public String key() {
                    return "ask";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    PlayerEntity p = world.getClosestPlayer(v, 12);
                    return p != null && Reputation.tier(village.getReputation(p)) >= 3;
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    PlayerEntity p = world.getClosestPlayer(v, 12);
                    if (p == null) return false;
                    Gaze.at(v, p, 30f, 30f);
                    Needs.set(v, need); // handing it to him counts (Needs)
                    if (world.random.nextInt(30) == 0) Speech.sayNow(v, "settlements.plan.ask", p.getName(),
                            Text.translatable("settlements.need.name." + need.name().toLowerCase()));
                    return world.random.nextInt(40) != 0; // he waits a little for an answer, then tries something else
                }
            },
            // nothing for it: the elder must know
            new Step() {
                public String key() {
                    return "elder";
                }

                public boolean possible(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    return village.hallPos != null;
                }

                public boolean run(VillagerEntityMCA v, Village village, ServerWorld world, Needs.Need need) {
                    VillagerEntityMCA elder = null;
                    for (VillagerEntityMCA r : village.getResidents(world)) if (r.isElder() && r.isAlive()) elder = r;
                    if (elder == null) return false;
                    if (v.squaredDistanceTo(elder) > 16) {
                        Move.to(v, elder.getX(), elder.getY(), elder.getZ(), 0.5);
                        return true;
                    }
                    Speech.sayNow(v, "settlements.plan.tell", elder.getName(), Text.translatable("settlements.need.name." + need.name().toLowerCase()));
                    elder.settlementsReplyKey = "settlements.plan.elder";
                    elder.settlementsReplyArgs = new Object[]{v.getName()};
                    elder.settlementsReplyAt = world.getTime() + 40;
                    return false; // told: the next time round, the other steps again
                }
            });
}
