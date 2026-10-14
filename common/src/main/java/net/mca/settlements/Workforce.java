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
    private static final int WORK_DRAIN = 4;        // energy per second of work (~4 min of work per full bar)
    private static final int REST_GAIN = 12;        // energy per second of rest
    private static final int HUNGER_RISE = 2;       // per second of work
    private static final int HUNGER_EAT_AT = 600;   // try to eat above this
    private static final int HUNGER_STOP_AT = 900;  // refuses to work above this
    private static final int START_WORK_ENERGY = 400;
    private static final int REST_DAY_EVERY = 4;    // every 4th day is a rest day
    private static final long WORK_START = 1000, WORK_END = 11500;

    public static final int MIN_TREES_LEFT = 6;      // lumberjacks stop when fewer trees stand nearby
    private static final int LOG_QUOTA = 256;         // stop chopping once the treasury holds this many logs
    private static final int STONE_QUOTA = 512;
    private static final int KEEP_FOOD = 4;

    private Workforce() {
    }

    public static Chore choreFor(VillagerProfession p) {
        if (p == VillagerProfession.FARMER) return Chore.HARVEST;
        if (p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH) return Chore.CHOP;
        if (p == VillagerProfession.MASON || p == VillagerProfession.ARMORER) return Chore.PROSPECT;
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
    private static final int MICRO_BREAK_AFTER = 150; // seconds of continuous work before a short break
    private static final int MICRO_BREAK_SECONDS = 15;
    private static final int FIRE_REST_GAIN = 28;     // energy per second sitting at a campfire
    private static final int TORCH_STOCK = 48;

    private static boolean isCustomWorker(VillagerProfession p) {
        return p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.SHEPHERD;
    }

    /** Server side, once per second. */
    public static void tick(VillagerEntityMCA v) {
        if (v.isBaby()) return;
        if (Hire.tick(v)) return; // hired escorts and mercenaries take no village work
        if (v.settlementsEatTicks > 0) {
            eatTick(v);
            return;
        }
        long time = v.getWorld().getTimeOfDay();
        net.mca.server.world.data.Village village = net.mca.server.world.data.Village.findNearest(v).orElse(null);
        Chore job = v.getVillagerBrain().getCurrentJob();
        boolean autonomous = job != Chore.NONE && v.getVillagerBrain().getJobAssigner().isEmpty();
        if (job != Chore.NONE && !autonomous) return; // player-assigned chores ignore this system

        if (v.age % 60 == 0) v.settlementsHunger = Math.min(MAX, v.settlementsHunger + 1); // slow idle hunger

        // a meal comes first, stopping work if needed
        if (v.settlementsHunger >= HUNGER_EAT_AT && hasMeal(v, village)) {
            if (job != Chore.NONE) v.getVillagerBrain().abandonJob();
            if (startEating(v, village)) return;
        }

        Needs.update(v, village);

        // breaks: a long one when exhausted, a short one now and then
        if (!v.settlementsBreak && autonomousWorking(v, job)) {
            if (v.settlementsEnergy <= EXHAUST_AT) {
                startBreak(v, 0);
            } else if (v.settlementsWorkSeconds >= MICRO_BREAK_AFTER) {
                startBreak(v, MICRO_BREAK_SECONDS);
            }
            if (v.settlementsBreak && job != Chore.NONE) {
                v.getVillagerBrain().abandonJob();
                job = Chore.NONE;
            }
        }
        if (v.settlementsBreak) {
            boolean seated = Camp.goAndSit(v, village);
            v.settlementsEnergy = Math.min(MAX, v.settlementsEnergy + (seated ? FIRE_REST_GAIN : REST_GAIN));
            if (v.settlementsBreakLeft > 0) v.settlementsBreakLeft--;
            if (v.settlementsBreakLeft <= 0 && v.settlementsEnergy >= START_WORK_ENERGY || !isWorkTime(time)) {
                v.settlementsBreak = false;
                v.settlementsWorkSeconds = 0;
            }
            return;
        }

        if (job != Chore.NONE) {
            // an autonomous chore is running
            v.settlementsEnergy -= WORK_DRAIN;
            v.settlementsHunger += HUNGER_RISE;
            v.settlementsWorkSeconds++;
            if (v.settlementsHunger >= HUNGER_STOP_AT || !isWorkTime(time)) {
                v.getVillagerBrain().abandonJob();
            }
            return;
        }

        v.settlementsEnergy = Math.min(MAX, v.settlementsEnergy + REST_GAIN);
        if (village != null) {
            deposit(v, village);
            if (v.age % 200 == 0) {
                craftTorches(village);
                smelt(village);
                if (v.getWorld() instanceof net.minecraft.server.world.ServerWorld sw) Reputation.restock(sw, village);
            }
        }
        if (!isWorkTime(time) || v.settlementsHunger >= HUNGER_STOP_AT || v.getVillagerBrain().isPanicking()) {
            v.settlementsWorkSeconds = 0;
            // supper and chat: eat a little earlier in the evening
            if (Leisure.isLeisureTime(time)) {
                if (v.settlementsHunger >= 250 && hasMeal(v, village) && startEating(v, village, 250)) return;
                Leisure.tick(v, village, time);
            }
            return;
        }
        if (v.settlementsEnergy < START_WORK_ENERGY) return;

        VillagerProfession prof = v.getProfession();
        if (isCustomWorker(prof)) {
            boolean worked = prof == VillagerProfession.CARTOGRAPHER ? Builder.work(v, village) : Husbandry.work(v, village);
            if (worked) {
                v.settlementsEnergy -= WORK_DRAIN + REST_GAIN; // cancels the rest gain above
                v.settlementsHunger += HUNGER_RISE;
                v.settlementsWorkSeconds++;
            }
            return;
        }
        Chore wanted = choreFor(prof);
        if (wanted != Chore.NONE && quotaAllows(village, wanted)) {
            Gear.equipTool(v, village, wanted);
            Gear.equipArmor(v, village);
            v.getVillagerBrain().assignAutonomous(wanted);
        }
    }

    private static boolean autonomousWorking(VillagerEntityMCA v, Chore job) {
        if (job != Chore.NONE) return true;
        return isCustomWorker(v.getProfession()) && v.settlementsWorkSeconds > 0;
    }

    private static void startBreak(VillagerEntityMCA v, int seconds) {
        v.settlementsBreak = true;
        v.settlementsBreakLeft = seconds;
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

    /** Any idle villager can turn coal and a plank into torches for the builders. */
    private static void craftTorches(net.mca.server.world.data.Village village) {
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
        if (fuel == null) return;
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
        ItemStack held = v.getStackInHand(v.getDominantHand());
        if (!held.isEmpty()) v.getInventory().addStack(held);
        v.setStackInHand(v.getDominantHand(), meal);
        v.getNavigation().stop();
        v.settlementsEatTicks = EAT_SECONDS;
        return true;
    }

    /** One second of an ongoing meal. */
    private static void eatTick(VillagerEntityMCA v) {
        v.getNavigation().stop();
        ItemStack meal = v.getStackInHand(v.getDominantHand());
        if (meal.isEmpty() || !meal.isFood()) {
            v.settlementsEatTicks = 0;
            return;
        }
        v.getWorld().playSound(null, v.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EAT, net.minecraft.sound.SoundCategory.NEUTRAL, 0.8f, 1.0f);
        v.getWorld().sendEntityStatus(v, (byte) 45); // eating particles for the held item
        v.swingHand(v.getDominantHand());
        if (--v.settlementsEatTicks <= 0) {
            v.settlementsHunger = Math.max(0, v.settlementsHunger - nutrition(meal));
            v.eatFood(v.getWorld(), meal); // applies the food effects and consumes the item
            v.setStackInHand(v.getDominantHand(), ItemStack.EMPTY);
        }
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
