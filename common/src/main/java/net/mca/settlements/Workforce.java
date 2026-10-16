package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.minecraft.item.ItemStack;
import net.minecraft.village.VillagerProfession;

/**
 * Autonomous work: villagers pick a chore from their profession on their own, spend energy and
 * hunger while working, eat, and take rest days. Called once per second from the villager tick.
 */
public final class Workforce {
    public static final int MAX = 1000;
    private static final int WORK_DRAIN = 4;        // energy per second of work (~3.5 min of work per full bar)
    private static final int REST_GAIN = 3;         // energy per second of rest
    private static final int RESUME_AFTER_EXHAUST = 700;
    private static final int HUNGER_RISE = 1;       // per second of work (a working man eats two or three loaves a day)
    private static final int HUNGER_EAT_AT = 600;   // try to eat above this
    private static final int HUNGER_STOP_AT = 900;  // refuses to work above this
    private static final int START_WORK_ENERGY = 400;
    private static final int REST_DAY_EVERY = 7;    // one day in seven is a rest day
    private static final long WORK_START = 1000, WORK_END = 11500;

    public static final int MIN_TREES_LEFT = 6;      // lumberjacks stop when fewer trees stand nearby
    private static final int LOG_QUOTA = 256;         // stop chopping once the treasury holds this many logs
    private static final int STONE_QUOTA = 1200;
    private static final int KEEP_FOOD = 4;

    private Workforce() {
    }

    /** How far through his second a villager got last time (for /settlements dump). */
    static final java.util.Map<java.util.UUID, String> STAGE = new java.util.HashMap<>();

    public static Chore choreFor(VillagerProfession p) {
        if (p == VillagerProfession.FARMER) return Chore.HARVEST;
        if (p == VillagerProfession.FISHERMAN) return Chore.FISH;
        if (p == VillagerProfession.LEATHERWORKER) return Chore.HUNT;
        return Chore.NONE;
    }

    /** A rest day for him: not for those who bring in food while the village goes hungry. */
    public static boolean restsToday(VillagerEntityMCA v, long worldTime) {
        if (!isRestDay(worldTime)) return false;
        VillagerProfession p = v.getProfession();
        boolean feeds = p == VillagerProfession.FARMER || p == VillagerProfession.SHEPHERD || p == VillagerProfession.BUTCHER
                || p == VillagerProfession.LEATHERWORKER || p == VillagerProfession.FISHERMAN || p == net.mca.ProfessionsMCA.MILLER.get();
        if (!feeds) return true;
        net.mca.server.world.data.Village village = v.getResidency().getHomeVillage().orElse(null);
        return village == null || !Forage.hungry(village);
    }

    public static boolean isRestDay(long worldTime) {
        return (worldTime / 24000L) % REST_DAY_EVERY == REST_DAY_EVERY - 1;
    }

    public static boolean isWorkTime(long worldTime) {
        long t = worldTime % 24000L;
        return !isRestDay(worldTime) && t >= WORK_START && t < WORK_END;
    }

    private static final int EXHAUST_AT = 150;       // below this the villager takes a long break
    private static final int MICRO_BREAK_AFTER = 180; // seconds at work before a break at the hearth
    private static final int MICRO_BREAK_SECONDS = 40; // counted once seated at the fire
    private static final int FIRE_REST_GAIN = 7;      // energy per second sitting at a campfire
    private static final int TORCH_STOCK = 48;

    static boolean isGatherer(VillagerProfession p) {
        return p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH || p == VillagerProfession.MASON;
    }

    private static boolean isCustomWorker(VillagerProfession p) {
        return p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.SHEPHERD || p == VillagerProfession.BUTCHER
                || p == VillagerProfession.MASON || p == VillagerProfession.ARMORER || p == VillagerProfession.CLERIC
                || p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH || p == VillagerProfession.LEATHERWORKER
                || p == net.mca.ProfessionsMCA.INNKEEPER.get() || p == net.mca.ProfessionsMCA.MILLER.get()
                || p == net.mca.ProfessionsMCA.COLLIER.get() || p == net.mca.ProfessionsMCA.MERCHANT.get()
                || p == net.mca.ProfessionsMCA.GLAZIER.get() || p == net.mca.ProfessionsMCA.PRIEST.get() || p == net.mca.ProfessionsMCA.WATCHMAN.get();
    }

    /** Server side, once per second. */
    public static void tick(VillagerEntityMCA v) {
        Pose.tick(v);
        Anim.tick(v);
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld lw && Arrivals.leaving(v, lw)) return; // leaving a hungry village
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld rw) {
            net.mca.server.world.data.Village rv = net.mca.server.world.data.Village.findNearest(v).orElse(null);
            if (Life.dread(v, rv, rw)) return; // a monster close by: away from it, shouting for the guards
            if (Rogue.tick(v, rv, rw)) return; // a rogue about his business, or in the stocks for it
            if (rv != null && v.getProfession() == net.mca.ProfessionsMCA.WATCHMAN.get() && Watch.tick(v, rv, rw)) return; // the night watch
            // no watchman yet (a small village): the guard on the evening shift puts up the lamps at dusk
            long dusk = rw.getTimeOfDay() % 24000L;
            if (rv != null && v.isGuard() && dusk >= 11500 && dusk < 13500 && !Staffing.has(rw, rv, net.mca.ProfessionsMCA.WATCHMAN.get())
                    && v.getBrain().getOptionalMemory(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET).isEmpty() && Watch.lamps(v, rv, rw)) return;
        }
        if (v.isBaby()) {
            if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld kw && Games.tick(v, kw)) return; // at play with the others
            Life.child(v);
            return;
        }
        if (v.settlementsCaravan) return; // trade caravan merchants only walk the road
        if (Hire.tick(v)) return; // hired escorts and mercenaries take no village work
        if (v.settlementsEatTicks > 0) {
            eatTick(v);
            return;
        }
        long time = v.getWorld().getTimeOfDay();
        // deep in the mine or far out in the woods a worker is still of his own village
        net.mca.server.world.data.Village village = net.mca.server.world.data.Village.findNearest(v)
                .or(() -> v.getResidency().getHomeVillage()).orElse(null);
        // what he is about, written down first; whatever he takes up below writes over it
        Doing.set(v, village == null ? "settlements.doing.novillage" : "settlements.doing.idle");
        Chore job = v.getVillagerBrain().getCurrentJob();
        boolean autonomous = job != Chore.NONE && v.getVillagerBrain().getJobAssigner().isEmpty();
        if (job != Chore.NONE && !autonomous) return; // player-assigned chores ignore this system

        // chores that older versions assigned (and the world still remembers) are done with MCA tasks that no longer apply
        if (autonomous && (job == Chore.CHOP || job == Chore.PROSPECT)) {
            v.getVillagerBrain().abandonJob();
            job = Chore.NONE;
        }

        // a settlement MCA has not registered yet does nothing: help it along by reporting the beds around
        if (village == null && v.age % 100 == 0 && v.getWorld() instanceof net.minecraft.server.world.ServerWorld regWorld) {
            Registration.report(regWorld, v);
        }

        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld cw0) Haul.closeChests(cw0);
        if (!Talk.talking(v)) Move.elbowRoom(v); // nobody stands pressed into another
        Move.shutBehind(v);
        Unstick.tick(v, village);
        Move.tick(v, village);
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld gw) {
            Gaze.tick(v, gw); // eyes on what goes on about him; his work, below, takes them back
            if (Notice.tick(v, gw)) return; // a player building something near: he stands and looks
            Weather.lantern(v, gw); // a lantern in the dark
            boolean free = !Persona.working(v, time) || v.settlementsIdleWork > 30;
            if (village != null && Weather.shelter(v, village, gw, free)) return; // in out of the rain
        }
        STAGE.put(v.getUuid(), "life");
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld lifeWorld && Life.tick(v, village, lifeWorld, time)) return;
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld watchWorld) {
            GuardIntel.civilianWatch(v, watchWorld);
            Remember.tick(v, village, watchWorld); // a word about what that player did, if anything stands out
        }
        STAGE.put(v.getUuid(), "escort");
        if (v.isGuard() && v.getWorld() instanceof net.minecraft.server.world.ServerWorld ew && Escort.guard(v, village, ew)) return; // seeing a worker safe out and back
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld skw && Sickness.tick(v, skw)) return; // ill: in bed at home today
        // a conversation: carried on, or started when he has a moment (on the guards' watch, nobody stands chatting)
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld tw && !(v.isGuard() && GuardAI.onDuty(v, time))) {
            boolean moment = !Mind.onDuty(v, village, time) || v.settlementsBreak || v.settlementsIdleWork >= 10 || Morning.active(v, time) || Festival.fair(time)
                    || Mind.before(v, village, time, Mind.Drive.TALK);
            STAGE.put(v.getUuid(), "talk");
            if (Talk.tick(v, village, tw, moment && !Dig.busy(v) && !v.settlementsHauling)) return;
        }

        // the elder keeps office by the town hall's lectern
        if (v.isElder() && Persona.working(v, time)) Doing.set(v, "settlements.doing.elder");
        if (v.isElder() && village != null && village.hallPos != null && Persona.working(v, time) && v.getBrain().getOptionalMemory(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET).isEmpty()
                && v.squaredDistanceTo(village.hallPos.getX() + 0.5, village.hallPos.getY(), village.hallPos.getZ() + 0.5) > 256) {
            v.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET, new net.minecraft.entity.ai.brain.WalkTarget(village.hallPos, 0.5f, 3));
        }

        // guards: tactics, gear and shifts
        if (v.isGuard() && village != null && v.getWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
            GuardAI.tick(v, village, sw, time);
            Doing.set(v, GuardAI.onDuty(v, time) ? "settlements.doing.guard" : "settlements.doing.guard.off");
            if (v.age % 1200 == 0) Gear.equipGuardKit(v, village);
        }

        if (v.age % 60 == 0 && v.settlementsHunger < MAX) hunger(v, 1); // slow idle hunger

        // hungry villagers eat in the tavern when there is one with food on the shelf
        if (village != null && village.tavern != null && v.settlementsHunger >= 250 && (v.settlementsHunger >= HUNGER_STOP_AT || Mind.before(v, village, time, Mind.Drive.EAT))
                && v.getProfession() != net.mca.ProfessionsMCA.INNKEEPER.get()) {
            if (job != Chore.NONE) v.getVillagerBrain().abandonJob();
            STAGE.put(v.getUuid(), "tavern");
            if (Tavern.visit(v, village)) {
                Doing.set(v, "settlements.doing.tavern");
                return;
            }
        }

        // a meal comes first, stopping work if needed
        // a meal when hunger outweighs the work (a busy man eats late, an idle one early); past the limit, always
        if (v.settlementsHunger >= 250 && hasMeal(v, village) && (v.settlementsHunger >= HUNGER_STOP_AT || v.isGuard() || Mind.before(v, village, time, Mind.Drive.EAT))) {
            if (job != Chore.NONE) v.getVillagerBrain().abandonJob();
            STAGE.put(v.getUuid(), "eat");
            if (startEating(v, village, 250)) return;
        }

        Needs.update(v, village);
        if (village != null && v.getWorld() instanceof net.minecraft.server.world.ServerWorld ow) Orders.refreshDue(village, ow);

        // the morning: breakfast, a wash at the well, a word with the neighbours - then work
        STAGE.put(v.getUuid(), "morning");
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld mw && Morning.tick(v, village, mw, time)) return;
        // a wedding, a fair morning: the village leaves its work for it
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld fw3 && Festival.tick(v, village, fw3, time)) return;

        // the day's rhythm: morning gathering with the elder, lunch at the hearth
        STAGE.put(v.getUuid(), "routine");
        if (Persona.working(v, time) && v.getWorld() instanceof net.minecraft.server.world.ServerWorld rw && Routine.tick(v, village, rw, time)) {
            Doing.set(v, "settlements.doing.routine");
            return;
        }

        // breaks come with tiredness, not by the clock: a long one when worn out, a short one when the strength
        // runs low (each at a moment of his own, so they do not all stop together)
        if (!v.settlementsBreak && autonomousWorking(v, job)) {
            if (v.settlementsEnergy <= EXHAUST_AT) {
                startBreak(v, 0);
                v.settlementsBreakLong = true; // worn out: back to work only when properly rested
                Speech.sayAny(v, "settlements.say.tired", 5);
            } else if (v.settlementsEnergy <= 500 && Mind.before(v, village, time, Mind.Drive.REST) && v.getRandom().nextInt(4) == 0) {
                startBreak(v, MICRO_BREAK_SECONDS);
                Speech.sayAny(v, "settlements.say.break", 5);
            }
            if (v.settlementsBreak && job != Chore.NONE) {
                v.getVillagerBrain().abandonJob();
                job = Chore.NONE;
            }
        }
        STAGE.put(v.getUuid(), "break");
        if (v.settlementsBreak) {
            Gear.pocket(v);
            Doing.set(v, v.settlementsBreakLong ? "settlements.doing.break.long" : "settlements.doing.break");
            boolean seated = Rest.tick(v, village);
            v.settlementsEnergy = Math.min(MAX, v.settlementsEnergy + (seated ? FIRE_REST_GAIN : REST_GAIN));
            // the break only counts once he sits at the fire (or after a long walk that got him nowhere)
            if (v.settlementsBreakLeft > 0 && (seated || v.settlementsBreakWalk++ > 60)) v.settlementsBreakLeft--;
            // back to work when rested enough - sooner if the work is pressing (Mind), later for the idle
            boolean rested = v.settlementsEnergy >= (v.settlementsBreakLong ? RESUME_AFTER_EXHAUST : START_WORK_ENERGY)
                    && (v.settlementsBreakLeft <= 0 || !Mind.before(v, village, time, Mind.Drive.REST) && v.settlementsBreakLeft < MICRO_BREAK_SECONDS / 2);
            if (rested || !Mind.onDuty(v, village, time)) {
                v.settlementsBreak = false;
                Rest.end(v);
                v.settlementsBreakLong = false;
                v.settlementsWorkSeconds = 0;
            }
            return;
        }

        STAGE.put(v.getUuid(), "mcajob");
        if (job != Chore.NONE) {
            // an autonomous chore is running
            Worksite.check(v, job);
            Doing.set(v, "settlements.doing." + net.minecraft.registry.Registries.VILLAGER_PROFESSION.getId(v.getProfession()).getPath());
            if (v.getProfession() == VillagerProfession.FARMER && v.getWorld() instanceof net.minecraft.server.world.ServerWorld fw)
                Farming.tend(v, village, fw); // hoeing, sowing, feeding the crop between the harvests
            Trade.practise(v);
            v.settlementsEnergy -= WORK_DRAIN;
            hunger(v, HUNGER_RISE);
            v.settlementsWorkSeconds++;
            if (v.settlementsHunger >= HUNGER_STOP_AT || !Mind.onDuty(v, village, time)) {
                v.getVillagerBrain().abandonJob();
            }
            return;
        }

        v.settlementsEnergy = Math.min(MAX, v.settlementsEnergy + REST_GAIN);
        if (village != null) {
            if (!isGatherer(v.getProfession())) deposit(v, village); // gatherers carry their load to the hall by hand
            if (v.age % 200 == 0) {
                craftTorches(village);
                craftArrows(village);
                // bread from the wheat, by hand at home - unless there is a miller, who does it at the mill and better
                if (!(v.getWorld() instanceof net.minecraft.server.world.ServerWorld bw && Staffing.has(bw, village, net.mca.ProfessionsMCA.MILLER.get()))) bake(village);
                if (!village.smithyBuilt) smelt(village);
                if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld sw) Reputation.restock(sw, village);
            }
        }
        STAGE.put(v.getUuid(), "offduty");
        // starving with nothing to eat in the village: he goes and finds something, as anyone would, before anything else
        if (v.settlementsHunger >= HUNGER_STOP_AT && !hasMeal(v, village) && village != null && !v.getVillagerBrain().isPanicking()
                && v.getWorld() instanceof net.minecraft.server.world.ServerWorld sw2) {
            VillagerProfession fp = v.getProfession();
            // those whose work is food go on with it, hungry or not: the hunt, the herd, the field; the rest gather
            boolean found = fp == VillagerProfession.LEATHERWORKER && Butchery.hunt(v, village, sw2)
                    || (fp == VillagerProfession.BUTCHER || fp == VillagerProfession.SHEPHERD) && Butchery.work(v, village, sw2)
                    || fp == VillagerProfession.FARMER && Farming.fields(v, village, sw2) || Forage.work(v, village, sw2);
            if (found) {
                Doing.set(v, "settlements.doing.starving.search");
                return;
            }
        }
        // hunger stops a man's work - but not the work that brings in food: the hunt, the herd, the field
        boolean feeds = v.getProfession() == VillagerProfession.BUTCHER || v.getProfession() == VillagerProfession.SHEPHERD || v.getProfession() == VillagerProfession.FARMER
                || v.getProfession() == VillagerProfession.LEATHERWORKER || v.getProfession() == net.mca.ProfessionsMCA.INNKEEPER.get();
        if (!Mind.onDuty(v, village, time) && !Persona.working(v, time) || v.settlementsHunger >= HUNGER_STOP_AT && !feeds || v.getVillagerBrain().isPanicking()) {
            v.settlementsWorkSeconds = 0;
            Gear.pocket(v); // off work: the tools go back in the bag
            Doing.set(v, v.settlementsHunger >= HUNGER_STOP_AT ? "settlements.doing.hungry" : Persona.free(v, time) ? "settlements.doing.free" : "settlements.doing.home");
            // supper and chat: eat a little earlier in the evening
            if (!Persona.free(v, time)) Rest.end(v); // bedtime: up and off home
            if (!Persona.free(v, time) && !(v.isGuard() && GuardAI.onDuty(v, time)) && !Persona.working(v, time)
                    && v.getWorld() instanceof net.minecraft.server.world.ServerWorld bw && Bed.tick(v, village, bw)) return; // to his own bed
            if (Persona.free(v, time) && !(v.isGuard() && GuardAI.onDuty(v, time))) {
                if (v.settlementsHunger >= 250 && hasMeal(v, village) && startEating(v, village, 250)) return;
                if (village != null && v.getWorld() instanceof net.minecraft.server.world.ServerWorld mw3 && Mood.tick(v, village, mw3, true)) return; // the village's mood
                if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld pw3 && Notice.pet(v, pw3)) return; // a pat for the cat
                Leisure.tick(v, village, time);
            }
            return;
        }
        if (v.settlementsEnergy < START_WORK_ENERGY) {
            Doing.set(v, "settlements.doing.tired");
            return;
        }
        if (!Persona.working(v, time)) Doing.set(v, "settlements.doing.overtime"); // stayed on: the work cannot wait
        // and a moment's pause now and then: nobody works like a machine
        STAGE.put(v.getUuid(), "pause");
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld pw && Trade.pausing(v, village, pw)) return;

        VillagerProfession prof = v.getProfession();
        // something just built: a look at it on the way, like everybody else
        if (village != null && Mood.now(village) == Mood.Kind.JOY && v.getWorld() instanceof net.minecraft.server.world.ServerWorld jw && Mood.tick(v, village, jw, false)) return;
        if (Seat.seated(v)) Rest.end(v); // up, back to work
        STAGE.put(v.getUuid(), "custom");
        if (isCustomWorker(prof)) {
            if (Forge.waiting(v, village)) return; // at the smithy, waiting for his new tool
            boolean worked = false;
            // a worker with nothing to do for a while lends the builders a hand (and looks for his own work now and then)
            // nothing at his own trade: a plan of what else is worth doing (see Planner.idle) - carry his load in,
            // take on an order nobody has, find food for a hungry village, mend the roads, tell the elder
            boolean helping = prof != VillagerProfession.CARTOGRAPHER && village != null && v.settlementsIdleWork >= 15 && v.settlementsIdleWork % 60 != 0
                    && v.getWorld() instanceof net.minecraft.server.world.ServerWorld hw && Planner.idle(v, village, hw);
            boolean foraging = false;
            if (foraging) {
                worked = true;
                v.settlementsIdleWork = 20; // still idle at his own trade
            } else if (helping) {
                v.settlementsIdleWork++;
                worked = true;
            } else if (village != null && isGatherer(prof) && Haul.carry(v, village, v.settlementsIdleWork >= 20)) {
                worked = true; // carrying the load to the hall is work too
            } else if (village == null) worked = false; // no village (yet): no village work
            else if (prof == VillagerProfession.CARTOGRAPHER) worked = Builder.work(v, village);
            else if (prof == VillagerProfession.BUTCHER && v.getWorld() instanceof net.minecraft.server.world.ServerWorld bw) worked = Butchery.work(v, village, bw);
            else if (prof == VillagerProfession.SHEPHERD && v.getWorld() instanceof net.minecraft.server.world.ServerWorld shw) worked = Herding.work(v, village, shw);
            else if (prof == VillagerProfession.LEATHERWORKER && v.getWorld() instanceof net.minecraft.server.world.ServerWorld huw) worked = Butchery.hunt(v, village, huw);
            else if (prof == net.mca.ProfessionsMCA.INNKEEPER.get()) worked = Cooking.work(v, village);
            else if (prof == VillagerProfession.MASON && v.getWorld() instanceof net.minecraft.server.world.ServerWorld mw2) {
                if (v.age % 400 == 0) Gear.equipShovel(v, village); // and a shovel for the earth and gravel
                worked = Chain.mine(v, village, mw2, time); // the chain he works out: a pick, the mine, a full bag, the stores
            }
            else if (prof == VillagerProfession.ARMORER) worked = Smithing.work(v, village);
            else if (prof == VillagerProfession.CLERIC) worked = Healing.work(v, village);
            else if (prof == net.mca.ProfessionsMCA.MILLER.get() && v.getWorld() instanceof net.minecraft.server.world.ServerWorld cw1) worked = Crafts.miller(v, village, cw1);
            else if (prof == net.mca.ProfessionsMCA.COLLIER.get() && v.getWorld() instanceof net.minecraft.server.world.ServerWorld cw2) worked = Crafts.collier(v, village, cw2);
            else if (prof == net.mca.ProfessionsMCA.MERCHANT.get() && v.getWorld() instanceof net.minecraft.server.world.ServerWorld cw3) worked = Crafts.merchant(v, village, cw3);
            else if (prof == net.mca.ProfessionsMCA.GLAZIER.get() && v.getWorld() instanceof net.minecraft.server.world.ServerWorld cw4) worked = Crafts.glazier(v, village, cw4);
            else if (prof == net.mca.ProfessionsMCA.PRIEST.get()) worked = Healing.work(v, village); // between services he tends the sick and the hurt
            else if ((prof == VillagerProfession.FLETCHER || prof == VillagerProfession.TOOLSMITH) && v.getWorld() instanceof net.minecraft.server.world.ServerWorld lw2) {
                // in a village with no mason yet, the woodman breaks stone too when the wood is in and the stone is out
                if (quarry(v, village, lw2)) {
                    Doing.set(v, "settlements.doing.quarry");
                    worked = Chain.mine(v, village, lw2, time);
                } else {
                    if (v.age % 400 == 0) Gear.equipTool(v, village, Chore.CHOP); // a better axe, when there is one to be had
                    worked = Chain.lumber(v, village, lw2, time); // an axe, a tree (the grove, the woods), a full bag, the stores
                }
            }
            else worked = Husbandry.work(v, village);
            if (!helping && !foraging) v.settlementsIdleWork = worked || Move.goal(v) != null ? 0 : v.settlementsIdleWork + 1; // walking to work is not idling
            if (v.settlementsIdleWork == 120) { // two minutes with nothing to do: he says so, and looks for a hand to lend
                Speech.say(v, "settlements.say.idle.nothing");
                Doing.set(v, "settlements.doing.idle.nothing");
            }
            if (!worked) v.settlementsWorkSeconds++; // time on the job counts, walking included, for the next break
            if (worked) {
                Trade.practise(v);
                if (v.settlementsWorkSeconds == 0) Speech.sayWork(v);
                // digging and felling wear a man out faster than laying blocks
                int drain = prof == VillagerProfession.MASON ? 3 : 2;
                v.settlementsEnergy -= drain + REST_GAIN; // cancels the rest gain above
                hunger(v, HUNGER_RISE);
                v.settlementsWorkSeconds++;
            }
            return;
        }
        // no trade of his own: food first when the village is hungry, otherwise he lends the builders a hand
        if ((prof == VillagerProfession.NONE || prof == VillagerProfession.NITWIT) && village != null && !v.isElder() && Forage.hungry(village)
                && v.getWorld() instanceof net.minecraft.server.world.ServerWorld gw && Forage.work(v, village, gw)) {
            v.settlementsEnergy -= WORK_DRAIN + REST_GAIN;
            hunger(v, HUNGER_RISE);
            return;
        }
        if ((prof == VillagerProfession.NONE || prof == VillagerProfession.NITWIT) && village != null && !v.isElder()
                && v.getWorld() instanceof net.minecraft.server.world.ServerWorld lw && Life.helpBuilders(v, village, lw)) {
            v.settlementsEnergy -= WORK_DRAIN + REST_GAIN;
            hunger(v, HUNGER_RISE);
            return;
        }
        // nothing for him to do: he goes along with a master at work and learns the trade by watching and fetching
        if ((prof == VillagerProfession.NONE || prof == VillagerProfession.NITWIT) && village != null && !v.isElder()
                && v.getWorld() instanceof net.minecraft.server.world.ServerWorld aw && apprentice(v, village, aw)) return;
        // the farmer works the village's fields, ours and the old ones alike: reaps, sows, feeds the crop
        if (prof == VillagerProfession.FARMER && village != null && v.getWorld() instanceof net.minecraft.server.world.ServerWorld fw2
                && Farming.fields(v, village, fw2)) {
            if (v.age % 400 == 0) Gear.equipTool(v, village, Chore.HARVEST);
            v.settlementsEnergy -= WORK_DRAIN + REST_GAIN;
            hunger(v, HUNGER_RISE);
            v.settlementsWorkSeconds++;
            return;
        }
        // the crop growing and nothing to do in the fields: he lends the builders a hand
        if (prof == VillagerProfession.FARMER && village != null && v.getWorld() instanceof net.minecraft.server.world.ServerWorld hw2
                && v.getWorld().getTime() >= v.settlementsNoWorkUntil && (Life.helpBuilders(v, village, hw2)
                || !Staffing.has(hw2, village, VillagerProfession.SHEPHERD) && Herding.work(v, village, hw2) || Planner.idle(v, village, hw2))) {
            v.settlementsEnergy -= WORK_DRAIN + REST_GAIN;
            hunger(v, HUNGER_RISE);
            return;
        }
        if (prof == VillagerProfession.FARMER) Doing.set(v, "settlements.doing.farm.waiting"); // the fields tended, the crop growing
        Chore wanted = choreFor(prof);
        if (wanted != Chore.NONE && quotaAllows(village, wanted) && v.getWorld().getTime() >= v.settlementsNoWorkUntil) {
            Gear.equipTool(v, village, wanted);
            Gear.equipArmor(v, village);
            v.getVillagerBrain().assignAutonomous(wanted);
            Speech.sayWork(v);
        }
    }

    private static final java.util.Map<java.util.UUID, java.util.UUID> MASTER = new java.util.HashMap<>();

    /** One second as an apprentice: at the master's elbow while he works. True while he is at it. */
    private static boolean apprentice(VillagerEntityMCA v, net.mca.server.world.data.Village village, net.minecraft.server.world.ServerWorld world) {
        if (!Persona.working(v, world.getTimeOfDay())) return false;
        VillagerEntityMCA master = null;
        java.util.UUID m = v.settlementsMaster != null ? v.settlementsMaster : MASTER.get(v.getUuid());
        if (m != null && world.getEntity(m) instanceof VillagerEntityMCA mm && mm.isAlive() && !mm.isSleeping() && Persona.working(mm, world.getTimeOfDay())) {
            master = mm;
            MASTER.put(v.getUuid(), m);
        }
        if (master == null) {
            double bd = 64 * 64;
            for (VillagerEntityMCA r : village.getResidents(world)) {
                if (r == v || !r.isAlive() || r.isSleeping() || !isCustomWorker(r.getProfession()) || r.settlementsIdleWork > 10) continue;
                if (MASTER.containsValue(r.getUuid())) continue; // one apprentice a master
                double d = r.squaredDistanceTo(v) / (1 + Trade.level(r));
                if (d < bd) {
                    bd = d;
                    master = r;
                }
            }
            if (master == null) return false;
            MASTER.put(v.getUuid(), master.getUuid());
            v.settlementsMaster = master.getUuid(); // remembered: he goes back to the same master
            Speech.sayNow(v, "settlements.say.apprentice.ask", master.getName());
            master.settlementsReplyKey = "settlements.say.apprentice.ok";
            master.settlementsReplyArgs = new Object[]{v.getName()};
            master.settlementsReplyAt = world.getTime() + 40;
        }
        Doing.set(v, "settlements.doing.apprentice", master.getName());
        if (v.squaredDistanceTo(master) > 16) Move.to(v, master.getX(), master.getY(), master.getZ(), 0.5);
        else {
            v.getNavigation().stop();
            Gaze.at(v, master, 30f, 30f);
            Dig.gather(v, 3); // picks up what falls, as a lad at the elbow does
            if (Dig.busy(master) && world.random.nextInt(3) == 0) v.swingHand(v.getDominantHand());
            Trade.practise(v);
        }
        v.settlementsEnergy -= WORK_DRAIN + REST_GAIN;
        hunger(v, HUNGER_RISE);
        if (world.random.nextInt(400) == 0) Speech.say(v, "settlements.say.apprentice.learn", master.getName());
        return true;
    }

    private static boolean autonomousWorking(VillagerEntityMCA v, Chore job) {
        if (job != Chore.NONE) return true;
        return isCustomWorker(v.getProfession()) && v.settlementsWorkSeconds > 0;
    }

    private static void startBreak(VillagerEntityMCA v, int seconds) {
        v.settlementsBreak = true;
        v.settlementsBreakWalk = 0;
        v.settlementsBreakLeft = seconds;
    }

    /** The mill grinds the farmer's wheat and the bakery turns it into bread: three sheaves a loaf. */
    private static final java.util.Map<Integer, long[]> BAKED = new java.util.HashMap<>(); // {day, loaves that day}

    /**
     * Bread without a mill: the grain ground at home on a hand quern and baked on the hearth - slow, wasteful work. Four
     * wheat to a loaf, and only a few loaves a day for the whole village; the miller does far better (see Crafts).
     */
    private static void bake(net.mca.server.world.data.Village village) {
        long day = village.getWorld() == null ? 0 : village.getWorld().getTimeOfDay() / 24000L;
        long[] b = BAKED.computeIfAbsent(village.getId(), k -> new long[]{day, 0});
        if (b[0] != day) {
            b[0] = day;
            b[1] = 0;
        }
        int cap = 2 + village.getPopulation() / 3;
        for (int i = 0; i < 2 && b[1] < cap; i++) {
            int wheat = 0;
            for (ItemStack s : village.storageBuffer) if (s.isOf(net.minecraft.item.Items.WHEAT)) wheat += s.getCount();
            if (wheat < 4) return;
            b[1]++;
            int need = 4;
            for (ItemStack s : village.storageBuffer) {
                if (need > 0 && s.isOf(net.minecraft.item.Items.WHEAT)) {
                    int d = Math.min(need, s.getCount());
                    s.decrement(d);
                    need -= d;
                }
            }
            village.storageBuffer.removeIf(ItemStack::isEmpty);
            addToTreasury(village, new ItemStack(net.minecraft.item.Items.BREAD, 1));
        }
    }

    private static boolean burnCharcoal(net.mca.server.world.data.Village village) {
        int logs = 0;
        for (ItemStack s : village.storageBuffer) if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) logs += s.getCount();
        boolean pit = Buildings.has(village, "charcoal");
        boolean furnace = pit || village.smithyFurnace != null || village.tavern != null;
        if (logs < (furnace ? 2 : 8)) return false;
        for (ItemStack s : village.storageBuffer) {
            if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
                s.decrement(1);
                break;
            }
        }
        village.storageBuffer.removeIf(ItemStack::isEmpty);
        addToTreasury(village, new ItemStack(net.minecraft.item.Items.CHARCOAL, pit ? 2 : 1)); // the pit burns a whole stack at once
        return true;
    }

    /** Idle villagers smelt raw ore from the mines into ingots. With Pyrotech the work takes twice the fuel. */
    private static void smelt(net.mca.server.world.data.Village village) {
        net.minecraft.item.Item[][] pairs = {
                {net.minecraft.item.Items.RAW_IRON, net.minecraft.item.Items.IRON_INGOT},
                {net.minecraft.item.Items.RAW_GOLD, net.minecraft.item.Items.GOLD_INGOT},
                {net.minecraft.item.Items.RAW_COPPER, net.minecraft.item.Items.COPPER_INGOT}};
        int fuelNeeded = Compat.pyrotech() ? 2 : 1;
        for (net.minecraft.item.Item[] pair : pairs) {
            ItemStack raw = null;
            for (ItemStack s : village.storageBuffer) if (s.isOf(pair[0])) { raw = s; break; }
            if (raw == null) continue;
            int fuel = 0;
            for (ItemStack s : village.storageBuffer) if (s.isOf(net.minecraft.item.Items.COAL) || s.isOf(net.minecraft.item.Items.CHARCOAL)) fuel += s.getCount();
            if (fuel < fuelNeeded) return;
            int need = fuelNeeded;
            for (ItemStack s : village.storageBuffer) {
                if (need <= 0) break;
                if (s.isOf(net.minecraft.item.Items.COAL) || s.isOf(net.minecraft.item.Items.CHARCOAL)) {
                    int d = Math.min(need, s.getCount());
                    s.decrement(d);
                    need -= d;
                }
            }
            raw.decrement(1);
            village.storageBuffer.removeIf(ItemStack::isEmpty);
            addToTreasury(village, new ItemStack(pair[1], 1));
            return;
        }
    }

    /** Any idle villager can fletch arrows: flint (or gravel sifted for it), a feather and a plank make four. */
    private static void craftArrows(net.mca.server.world.data.Village village) {
        int arrows = 0;
        for (ItemStack s : village.storageBuffer) if (s.isOf(net.minecraft.item.Items.ARROW)) arrows += s.getCount();
        if (arrows >= 96) return;
        if (!Builder.takeItem(village, s -> s.isOf(net.minecraft.item.Items.FEATHER), 1)) return;
        boolean flint = Builder.takeItem(village, s -> s.isOf(net.minecraft.item.Items.FLINT), 1)
                || Builder.takeItem(village, s -> s.isOf(net.minecraft.item.Items.GRAVEL), 3);
        if (!flint || !Builder.takeItem(village, s -> s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS), 1)) {
            addToTreasury(village, new ItemStack(net.minecraft.item.Items.FEATHER, 1)); // nothing to make a head or a shaft of
            return;
        }
        addToTreasury(village, new ItemStack(net.minecraft.item.Items.ARROW, 4));
    }

    /** Any idle villager can turn coal and a plank into torches for the builders. */
    static void craftTorches(net.mca.server.world.data.Village village) {
        int torches = 0;
        for (ItemStack s : village.storageBuffer) if (s.isOf(net.minecraft.item.Items.TORCH)) torches += s.getCount();
        if (torches >= TORCH_STOCK) return;
        ItemStack fuel = null;
        for (ItemStack s : village.storageBuffer) {
            if (s.isOf(net.minecraft.item.Items.COAL) || s.isOf(net.minecraft.item.Items.CHARCOAL)) {
                fuel = s;
                break;
            }
        }
        if (fuel == null) {
            // no coal about: the villagers burn logs to charcoal in a furnace (or a charcoal pit when they have none yet)
            if (!burnCharcoal(village)) return;
            for (ItemStack s : village.storageBuffer) {
                if (s.isOf(net.minecraft.item.Items.CHARCOAL)) {
                    fuel = s;
                    break;
                }
            }
            if (fuel == null) return;
        }
        net.minecraft.item.Item plank = null;
        for (ItemStack s : village.storageBuffer) {
            if (s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
                plank = s.getItem();
                s.decrement(1);
                break;
            }
        }
        if (plank == null) return;
        fuel.decrement(1);
        village.storageBuffer.removeIf(ItemStack::isEmpty);
        addToTreasury(village, new ItemStack(net.minecraft.item.Items.TORCH, 4));
    }

    private static final int EAT_SECONDS = 3;

    /** Starts a meal: the food goes into the hand and the villager stands still for a few seconds. */
    /** Lunch at the hearth: something from the pocket or the stores, however hungry he is. */
    static boolean lunch(VillagerEntityMCA v, net.mca.server.world.data.Village village) {
        return hasMeal(v, village) && startEating(v, village, 0);
    }

    private static boolean startEating(VillagerEntityMCA v, net.mca.server.world.data.Village village) {
        return startEating(v, village, HUNGER_EAT_AT);
    }

    static boolean startEating(VillagerEntityMCA v, net.mca.server.world.data.Village village, int threshold) {
        if (v.settlementsHunger < threshold) return false;
        ItemStack meal = ItemStack.EMPTY;
        for (int i = 0; i < v.getInventory().size() && meal.isEmpty(); i++) {
            ItemStack s = v.getInventory().getStack(i);
            if (eatable(s)) meal = s.split(1);
        }
        if (meal.isEmpty() && village != null) {
            // from the stores, paid for: the well-off take the best there is, the rest the cheapest
            boolean best = Purse.welloff(v);
            ItemStack pick = null;
            for (ItemStack s : village.storageBuffer) {
                if (!eatable(s)) continue;
                if (pick == null || (best ? Purse.price(s) > Purse.price(pick) : Purse.price(s) < Purse.price(pick))) pick = s;
            }
            if (pick != null && !Purse.pay(v, village, pick)) { // too deep in debt: the cheapest only
                for (ItemStack s : village.storageBuffer) if (eatable(s) && Purse.price(s) <= 1) pick = s;
                if (Purse.price(pick) > 1) pick = null;
                else Purse.pay(v, village, pick);
            }
            if (pick != null) {
                meal = pick.split(1);
                village.storageBuffer.removeIf(ItemStack::isEmpty);
                village.markDirty();
            }
        }
        if (meal.isEmpty()) return false;
        beginMeal(v, meal, EAT_SECONDS);
        Speech.sayAny(v, "settlements.say.eat", 5);
        return true;
    }

    /** Puts the meal in the villager's hand and has him eat it like a player does: hand up, crumbs, the sound. */
    static void beginMeal(VillagerEntityMCA v, ItemStack meal, int seconds) {
        Doing.set(v, "settlements.doing.eat");
        ItemStack held = v.getStackInHand(v.getDominantHand());
        if (!held.isEmpty()) v.getInventory().addStack(held);
        v.setStackInHand(v.getDominantHand(), meal);
        v.getNavigation().stop();
        v.settlementsMealValue = nutrition(meal);
        v.settlementsEatTicks = seconds + 3; // a safety limit; the bite itself ends the meal
        v.setCurrentHand(v.getDominantHand());
    }

    /** One second of an ongoing meal. */
    private static void eatTick(VillagerEntityMCA v) {
        v.getNavigation().stop();
        if (v.isUsingItem() && --v.settlementsEatTicks > 0) return; // still chewing
        if (v.isUsingItem()) v.stopUsingItem();
        // the meal is eaten (the food item used itself up), or it took too long: either way he has had his meal
        v.settlementsHunger = Math.max(0, v.settlementsHunger - v.settlementsMealValue);
        v.settlementsMealValue = 0;
        v.settlementsEatTicks = 0;
        ItemStack left = v.getStackInHand(v.getDominantHand());
        if (!left.isEmpty()) v.getInventory().addStack(left); // the bowl, or a meal he did not finish
        v.setStackInHand(v.getDominantHand(), ItemStack.EMPTY);
    }

    static boolean hasMeal(VillagerEntityMCA v, net.mca.server.world.data.Village village) {
        for (int i = 0; i < v.getInventory().size(); i++) if (eatable(v.getInventory().getStack(i))) return true;
        if (village != null) for (ItemStack s : village.storageBuffer) if (eatable(s)) return true;
        return false;
    }

    static boolean eatable(ItemStack s) {
        if (!s.isFood() || s.getItem().getFoodComponent() == null) return false;
        for (var effect : s.getItem().getFoodComponent().getStatusEffects()) {
            if (net.mca.entity.ai.StatusEffectDangerSet.isDanger.contains(effect.getFirst().getEffectType())) return false;
        }
        return true;
    }

    private static int nutrition(ItemStack s) {
        return s.getItem().getFoodComponent().getHunger() * 120;
    }

    private static final java.util.Set<java.util.UUID> QUARRYING = new java.util.HashSet<>();

    /** The lumberjack takes up the pick while there is no mason, wood enough in the stores and too little stone; and puts it down again. */
    private static boolean quarry(VillagerEntityMCA v, net.mca.server.world.data.Village village, net.minecraft.server.world.ServerWorld world) {
        if (village == null) return false;
        if (v.age % 200 == 0 || !QUARRYING.contains(v.getUuid()) && v.age % 20 == 0) {
            int logs = 0, stone = 0;
            for (ItemStack s : village.storageBuffer) {
                if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) logs += s.getCount();
                else if (s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) logs += s.getCount() / 4; // wood as logs' worth
                else if (s.isOf(net.minecraft.item.Items.COBBLESTONE) || s.isOf(net.minecraft.item.Items.SANDSTONE)) stone += s.getCount();
            }
            boolean mason = Staffing.has(world, village, VillagerProfession.MASON);
            boolean was = QUARRYING.contains(v.getUuid());
            boolean now = !mason && (was ? stone < 128 && logs >= 16 : stone < 48 && logs >= 48);
            if (now && !was) {
                QUARRYING.add(v.getUuid());
                Speech.say(v, "settlements.say.quarry");
            } else if (!now && was) {
                QUARRYING.remove(v.getUuid());
            }
        }
        return QUARRYING.contains(v.getUuid());
    }

    private static boolean quotaAllows(net.mca.server.world.data.Village village, Chore chore) {
        if (village == null) return true;
        int logs = 0, stone = 0;
        for (ItemStack s : village.storageBuffer) {
            if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) logs += s.getCount();
            else if (s.isOf(net.minecraft.item.Items.COBBLESTONE)) stone += s.getCount();
        }
        return switch (chore) {
            case CHOP -> logs < LOG_QUOTA;
            case PROSPECT -> stone < STONE_QUOTA;
            default -> true;
        };
    }

    /** Hand gathered goods over to the village treasury; keep tools, armor and a little food. */
    private static void deposit(VillagerEntityMCA v, net.mca.server.world.data.Village village) {
        int food = 0;
        for (int i = 0; i < v.getInventory().size(); i++) {
            ItemStack s = v.getInventory().getStack(i);
            // his own things stay with him: tools, shears, a bow, armour - whatever wears out with use
            if (s.isEmpty() || s.isDamageable() || s.getItem() instanceof net.minecraft.item.ToolItem || s.getItem() instanceof net.minecraft.item.ArmorItem || Money.is(s)) continue; // his own money stays his
            if (s.isFood()) {
                int keep = Math.max(0, KEEP_FOOD - food);
                food += Math.min(keep, s.getCount());
                if (s.getCount() <= keep) continue;
                s = s.split(s.getCount() - keep);
            } else {
                s = s.copy();
                v.getInventory().setStack(i, ItemStack.EMPTY);
            }
            addToTreasury(village, s);
        }
    }

    public static void addToTreasury(net.mca.server.world.data.Village village, ItemStack stack) {
        for (ItemStack s : village.storageBuffer) {
            if (ItemStack.canCombine(s, stack)) {
                int move = Math.min(stack.getCount(), s.getMaxCount() - s.getCount());
                s.increment(move);
                stack.decrement(move);
            }
        }
        while (!stack.isEmpty()) {
            ItemStack part = stack.split(Math.min(stack.getCount(), stack.getMaxCount()));
            village.storageBuffer.add(part);
        }
        village.markDirty();
    }

    /**
     * Hunger rises with the body and the trade: a child eats half what a grown man does, the old less, while the ones
     * who fell trees, cut stone, swing a hammer or walk behind a plough eat the most; a clerk at his books, least.
     */
    static float appetite(VillagerEntityMCA v) {
        if (v.isBaby()) return 0.5f;
        if (v.isElder()) return 0.75f;
        if (v.isGuard()) return 1.15f;
        var p = v.getProfession();
        if (p == VillagerProfession.FLETCHER || p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.TOOLSMITH
                || p == VillagerProfession.MASON || p == VillagerProfession.ARMORER || p == VillagerProfession.WEAPONSMITH
                || p == VillagerProfession.FARMER || p == VillagerProfession.BUTCHER || p == VillagerProfession.FISHERMAN
                || p == net.mca.ProfessionsMCA.COLLIER.get() || p == net.mca.ProfessionsMCA.MILLER.get() || p == net.mca.ProfessionsMCA.GLAZIER.get())
            return 1.35f;
        if (p == VillagerProfession.CLERIC || p == VillagerProfession.LIBRARIAN || p == net.mca.ProfessionsMCA.PRIEST.get()
                || p == net.mca.ProfessionsMCA.MERCHANT.get() || p == net.mca.ProfessionsMCA.WATCHMAN.get()) return 0.85f;
        return 1.0f;
    }

    /** {@code base} points of hunger, scaled by his appetite (the fraction left to chance, so it evens out). */
    static void hunger(VillagerEntityMCA v, int base) {
        float a = base * appetite(v);
        int whole = (int) a;
        if (v.getRandom().nextFloat() < a - whole) whole++;
        v.settlementsHunger += whole;
    }
}
