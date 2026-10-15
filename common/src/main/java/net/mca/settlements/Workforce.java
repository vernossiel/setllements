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
    private static final int HUNGER_RISE = 2;       // per second of work
    private static final int HUNGER_EAT_AT = 600;   // try to eat above this
    private static final int HUNGER_STOP_AT = 900;  // refuses to work above this
    private static final int START_WORK_ENERGY = 400;
    private static final int REST_DAY_EVERY = 4;    // every 4th day is a rest day
    private static final long WORK_START = 1000, WORK_END = 11500;

    public static final int MIN_TREES_LEFT = 6;      // lumberjacks stop when fewer trees stand nearby
    private static final int LOG_QUOTA = 256;         // stop chopping once the treasury holds this many logs
    private static final int STONE_QUOTA = 1200;
    private static final int KEEP_FOOD = 4;

    private Workforce() {
    }

    public static Chore choreFor(VillagerProfession p) {
        if (p == VillagerProfession.FARMER) return Chore.HARVEST;
        if (p == VillagerProfession.FISHERMAN) return Chore.FISH;
        if (p == VillagerProfession.LEATHERWORKER) return Chore.HUNT;
        return Chore.NONE;
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
                || p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH;
    }

    /** Server side, once per second. */
    public static void tick(VillagerEntityMCA v) {
        if (v.isBaby()) {
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

        Unstick.tick(v, village);
        Move.tick(v, village);
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld lifeWorld && Life.tick(v, village, lifeWorld, time)) return;
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld watchWorld) GuardIntel.civilianWatch(v, watchWorld);

        // the elder keeps office by the town hall's lectern
        if (v.isElder() && village != null && village.hallPos != null && Persona.working(v, time) && v.getBrain().getOptionalMemory(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET).isEmpty()
                && v.squaredDistanceTo(village.hallPos.getX() + 0.5, village.hallPos.getY(), village.hallPos.getZ() + 0.5) > 256) {
            v.getBrain().remember(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET, new net.minecraft.entity.ai.brain.WalkTarget(village.hallPos, 0.5f, 3));
        }

        // guards: tactics, gear and shifts
        if (v.isGuard() && village != null && v.getWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
            GuardAI.tick(v, village, sw, time);
            if (v.age % 1200 == 0) Gear.equipGuardKit(v, village);
        }

        if (v.age % 60 == 0) v.settlementsHunger = Math.min(MAX, v.settlementsHunger + 1); // slow idle hunger

        // hungry villagers eat in the tavern when there is one with food on the shelf
        if (village != null && village.tavern != null && v.settlementsHunger >= (job == Chore.NONE ? 250 : HUNGER_EAT_AT)
                && v.getProfession() != VillagerProfession.BUTCHER) {
            if (job != Chore.NONE) v.getVillagerBrain().abandonJob();
            if (Tavern.visit(v, village)) return;
        }

        // a meal comes first, stopping work if needed
        if (v.settlementsHunger >= HUNGER_EAT_AT && hasMeal(v, village)) {
            if (job != Chore.NONE) v.getVillagerBrain().abandonJob();
            if (startEating(v, village)) return;
        }

        Needs.update(v, village);

        // the day's rhythm: morning gathering with the elder, lunch at the hearth
        if (Persona.working(v, time) && v.getWorld() instanceof net.minecraft.server.world.ServerWorld rw && Routine.tick(v, village, rw, time)) return;

        // breaks come with tiredness, not by the clock: a long one when worn out, a short one when the strength
        // runs low (each at a moment of his own, so they do not all stop together)
        if (!v.settlementsBreak && autonomousWorking(v, job)) {
            if (v.settlementsEnergy <= EXHAUST_AT) {
                startBreak(v, 0);
                v.settlementsBreakLong = true; // worn out: back to work only when properly rested
                Speech.sayAny(v, "settlements.say.tired", 5);
            } else if (v.settlementsEnergy <= 450 && v.getRandom().nextDouble() < Persona.breakFactor(v) / 45) {
                startBreak(v, MICRO_BREAK_SECONDS);
                Speech.sayAny(v, "settlements.say.break", 5);
            }
            if (v.settlementsBreak && job != Chore.NONE) {
                v.getVillagerBrain().abandonJob();
                job = Chore.NONE;
            }
        }
        if (v.settlementsBreak) {
            boolean seated = Rest.tick(v, village);
            v.settlementsEnergy = Math.min(MAX, v.settlementsEnergy + (seated ? FIRE_REST_GAIN : REST_GAIN));
            // the break only counts once he sits at the fire (or after a long walk that got him nowhere)
            if (v.settlementsBreakLeft > 0 && (seated || v.settlementsBreakWalk++ > 60)) v.settlementsBreakLeft--;
            if (v.settlementsBreakLeft <= 0 && v.settlementsEnergy >= (v.settlementsBreakLong ? RESUME_AFTER_EXHAUST : START_WORK_ENERGY) || !Persona.working(v, time)) {
                v.settlementsBreak = false;
                Rest.end(v);
                v.settlementsBreakLong = false;
                v.settlementsWorkSeconds = 0;
            }
            return;
        }

        if (job != Chore.NONE) {
            // an autonomous chore is running
            Worksite.check(v, job);
            Trade.practise(v);
            v.settlementsEnergy -= WORK_DRAIN;
            v.settlementsHunger += HUNGER_RISE;
            v.settlementsWorkSeconds++;
            if (v.settlementsHunger >= HUNGER_STOP_AT || !Persona.working(v, time)) {
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
                if (Buildings.has(village, "mill")) bake(village);
                if (!village.smithyBuilt) smelt(village);
                if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld sw) Reputation.restock(sw, village);
            }
        }
        if (!Persona.working(v, time) || v.settlementsHunger >= HUNGER_STOP_AT || v.getVillagerBrain().isPanicking()) {
            v.settlementsWorkSeconds = 0;
            // supper and chat: eat a little earlier in the evening
            if (!Persona.free(v, time)) Rest.end(v); // bedtime: up and off home
            if (Persona.free(v, time) && !(v.isGuard() && GuardAI.onDuty(v, time))) {
                if (v.settlementsHunger >= 250 && hasMeal(v, village) && startEating(v, village, 250)) return;
                Leisure.tick(v, village, time);
            }
            return;
        }
        if (v.settlementsEnergy < START_WORK_ENERGY) return;
        // a word with a neighbour met on the way, now and then (more often for the sociable and the idle)
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld cw && Persona.chatting(v, village, cw)) return;
        // and a moment's pause now and then: nobody works like a machine
        if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld pw && Trade.pausing(v, village, pw)) return;

        VillagerProfession prof = v.getProfession();
        if (Seat.seated(v)) Rest.end(v); // up, back to work
        if (isCustomWorker(prof)) {
            boolean worked = false;
            // a worker with nothing to do for a while lends the builders a hand (and looks for his own work now and then)
            boolean helping = prof != VillagerProfession.CARTOGRAPHER && village != null && v.settlementsIdleWork >= 45 && v.settlementsIdleWork % 60 != 0
                    && v.getWorld() instanceof net.minecraft.server.world.ServerWorld hw && Life.helpBuilders(v, village, hw);
            // a hungry village: whoever has nothing to do goes out for food
            boolean foraging = !helping && village != null && v.settlementsIdleWork >= 20 && prof != VillagerProfession.CARTOGRAPHER
                    && Forage.hungry(village) && v.getWorld() instanceof net.minecraft.server.world.ServerWorld fw && Forage.work(v, village, fw);
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
            else if (prof == VillagerProfession.BUTCHER) worked = Cooking.work(v, village);
            else if (prof == VillagerProfession.MASON) {
                if (v.age % 400 == 0 || !Mining.hasPick(v)) Gear.equipTool(v, village, Chore.PROSPECT);
                worked = Mining.work(v, village);
            }
            else if (prof == VillagerProfession.ARMORER) worked = Smithing.work(v, village);
            else if (prof == VillagerProfession.CLERIC) worked = Healing.work(v, village);
            else if (prof == VillagerProfession.FLETCHER || prof == VillagerProfession.TOOLSMITH) {
                if (v.age % 400 == 0 || !Gear.has(v, net.minecraft.item.AxeItem.class)) Gear.equipTool(v, village, Chore.CHOP);
                worked = Lumberjack.work(v, village);
            }
            else worked = Husbandry.work(v, village);
            if (!helping && !foraging) v.settlementsIdleWork = worked || Move.goal(v) != null ? 0 : v.settlementsIdleWork + 1; // walking to work is not idling
            if (!worked) v.settlementsWorkSeconds++; // time on the job counts, walking included, for the next break
            if (worked) {
                Trade.practise(v);
                if (v.settlementsWorkSeconds == 0) Speech.sayWork(v);
                // digging and felling wear a man out faster than laying blocks
                int drain = prof == VillagerProfession.MASON || prof == VillagerProfession.FLETCHER || prof == VillagerProfession.TOOLSMITH ? 3 : 2;
                v.settlementsEnergy -= drain + REST_GAIN; // cancels the rest gain above
                v.settlementsHunger += HUNGER_RISE;
                v.settlementsWorkSeconds++;
            }
            return;
        }
        // no trade of his own: food first when the village is hungry, otherwise he lends the builders a hand
        if ((prof == VillagerProfession.NONE || prof == VillagerProfession.NITWIT) && village != null && !v.isElder() && Forage.hungry(village)
                && v.getWorld() instanceof net.minecraft.server.world.ServerWorld gw && Forage.work(v, village, gw)) {
            v.settlementsEnergy -= WORK_DRAIN + REST_GAIN;
            v.settlementsHunger += HUNGER_RISE;
            return;
        }
        if ((prof == VillagerProfession.NONE || prof == VillagerProfession.NITWIT) && village != null && !v.isElder()
                && v.getWorld() instanceof net.minecraft.server.world.ServerWorld lw && Life.helpBuilders(v, village, lw)) {
            v.settlementsEnergy -= WORK_DRAIN + REST_GAIN;
            v.settlementsHunger += HUNGER_RISE;
            return;
        }
        Chore wanted = choreFor(prof);
        if (wanted != Chore.NONE && quotaAllows(village, wanted) && v.getWorld().getTime() >= v.settlementsNoWorkUntil) {
            Gear.equipTool(v, village, wanted);
            Gear.equipArmor(v, village);
            v.getVillagerBrain().assignAutonomous(wanted);
            Speech.sayWork(v);
        }
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
    private static void bake(net.mca.server.world.data.Village village) {
        for (int i = 0; i < 4; i++) {
            int wheat = 0;
            for (ItemStack s : village.storageBuffer) if (s.isOf(net.minecraft.item.Items.WHEAT)) wheat += s.getCount();
            if (wheat < 3) return;
            int need = 3;
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

    private static boolean startEating(VillagerEntityMCA v, net.mca.server.world.data.Village village, int threshold) {
        if (v.settlementsHunger < threshold) return false;
        ItemStack meal = ItemStack.EMPTY;
        for (int i = 0; i < v.getInventory().size() && meal.isEmpty(); i++) {
            ItemStack s = v.getInventory().getStack(i);
            if (eatable(s)) meal = s.split(1);
        }
        if (meal.isEmpty() && village != null) {
            for (java.util.Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
                ItemStack s = it.next();
                if (!eatable(s)) continue;
                meal = s.split(1);
                if (s.isEmpty()) it.remove();
                village.markDirty();
                break;
            }
        }
        if (meal.isEmpty()) return false;
        beginMeal(v, meal, EAT_SECONDS);
        Speech.sayAny(v, "settlements.say.eat", 5);
        return true;
    }

    /** Puts the meal in the villager's hand and has him eat it like a player does: hand up, crumbs, the sound. */
    static void beginMeal(VillagerEntityMCA v, ItemStack meal, int seconds) {
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
            if (s.isEmpty() || s.getItem() instanceof net.minecraft.item.ToolItem || s.getItem() instanceof net.minecraft.item.ArmorItem) continue;
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
}
