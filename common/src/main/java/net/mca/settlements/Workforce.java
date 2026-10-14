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
    private static final int START_WORK_ENERGY = 600;
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

    /** Server side, once per second. */
    public static void tick(VillagerEntityMCA v) {
        if (v.isBaby()) return;
        long time = v.getWorld().getTimeOfDay();
        Chore job = v.getVillagerBrain().getCurrentJob();
        boolean autonomous = job != Chore.NONE && v.getVillagerBrain().getJobAssigner().isEmpty();

        if (job == Chore.NONE) {
            v.settlementsEnergy = Math.min(MAX, v.settlementsEnergy + REST_GAIN);
            if (v.age % 60 == 0) v.settlementsHunger = Math.min(MAX, v.settlementsHunger + 1); // slow idle hunger
            net.mca.server.world.data.Village treasury = net.mca.server.world.data.Village.findNearest(v).orElse(null);
            tryEat(v, treasury);
            if (treasury != null) deposit(v, treasury);
            if (v.getProfession() == VillagerProfession.CARTOGRAPHER) {
                if (isWorkTime(time) && v.settlementsHunger < HUNGER_STOP_AT && !v.getVillagerBrain().isPanicking()) {
                    if (v.settlementsEnergy <= 0) v.settlementsResting = true;
                    if (v.settlementsEnergy >= START_WORK_ENERGY) v.settlementsResting = false;
                    if (!v.settlementsResting && Builder.work(v, treasury)) {
                        v.settlementsEnergy -= WORK_DRAIN + REST_GAIN; // cancels the rest gain above
                        v.settlementsHunger += HUNGER_RISE;
                    }
                }
                return;
            }
            Chore wanted = choreFor(v.getProfession());
            if (wanted != Chore.NONE && isWorkTime(time) && v.settlementsEnergy >= START_WORK_ENERGY
                    && v.settlementsHunger < HUNGER_STOP_AT && quotaAllows(treasury, wanted) && !v.getVillagerBrain().isPanicking()) {
                Gear.equipTool(v, treasury, wanted);
                Gear.equipArmor(v, treasury);
                v.getVillagerBrain().assignAutonomous(wanted);
            }
            return;
        }

        if (!autonomous) return; // player-assigned chores ignore this system

        v.settlementsEnergy -= WORK_DRAIN;
        v.settlementsHunger += HUNGER_RISE;
        if (v.settlementsHunger >= HUNGER_EAT_AT) tryEat(v, net.mca.server.world.data.Village.findNearest(v).orElse(null));

        if (v.settlementsEnergy <= 0 || v.settlementsHunger >= HUNGER_STOP_AT || !isWorkTime(time)) {
            v.settlementsEnergy = Math.max(0, v.settlementsEnergy);
            v.getVillagerBrain().abandonJob();
        }
    }

    private static void tryEat(VillagerEntityMCA v, net.mca.server.world.data.Village village) {
        if (v.settlementsHunger < HUNGER_EAT_AT) return;
        for (int i = 0; i < v.getInventory().size(); i++) {
            ItemStack s = v.getInventory().getStack(i);
            if (eatable(s)) {
                v.settlementsHunger = Math.max(0, v.settlementsHunger - nutrition(s));
                s.decrement(1);
                return;
            }
        }
        // nothing on them: take a meal from the village stores
        if (village == null) return;
        for (java.util.Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (eatable(s)) {
                v.settlementsHunger = Math.max(0, v.settlementsHunger - nutrition(s));
                s.decrement(1);
                if (s.isEmpty()) it.remove();
                village.markDirty();
                return;
            }
        }
    }

    private static boolean eatable(ItemStack s) {
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

    private static void addToTreasury(net.mca.server.world.data.Village village, ItemStack stack) {
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
