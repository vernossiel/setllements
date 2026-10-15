package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.world.data.Village;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tools come from the smith. A worker whose tool broke (or who could have a better one) goes to the smithy and asks;
 * the smith hammers it out at the anvil from the village's iron or stone and timber, and hands it over. A worker
 * without a tool waits by the anvil; one getting a better one keeps working and is called over when it is ready.
 * Without a smith, or when the smith has nothing to make it from, a worker knocks a simple tool together himself.
 */
public final class Forge {
    private static final int HAMMER_SECONDS = 8;
    private static final long WAIT_LIMIT = 20 * 120;

    private static final class Job {
        final UUID who;
        final String name;
        final Chore chore;
        final int tier;
        final long since;
        int done;

        Job(VillagerEntityMCA v, Chore chore, int tier) {
            this.who = v.getUuid();
            this.name = v.getName().getString();
            this.chore = chore;
            this.tier = tier;
            this.since = v.getWorld().getTime();
        }
    }

    private static final Map<Integer, List<Job>> QUEUE = new HashMap<>();
    private static final Map<UUID, Long> REFUSED = new HashMap<>();

    private Forge() {
    }

    /** The smith of the village, if it has one and a smithy to work in. */
    static VillagerEntityMCA smith(Village village) {
        ServerWorld world = village.getWorld();
        if (!Staffing.SMITHS || world == null || village.smithyFurnace == null) return null;
        for (VillagerEntityMCA r : village.getResidents(world)) if (r.isAlive() && r.getProfession() == VillagerProfession.ARMORER) return r;
        return null;
    }

    /** Whether to send this worker to the smith for the tool rather than make do himself. */
    static boolean goesToSmith(VillagerEntityMCA v, Village village) {
        return village != null && smith(village) != null && v.getWorld().getTime() >= REFUSED.getOrDefault(v.getUuid(), 0L)
                && v.getProfession() != VillagerProfession.ARMORER;
    }

    /** Asks the smith for a tool (once; the request stands until it is made or given up). */
    static void order(VillagerEntityMCA v, Village village, Chore chore, int tier) {
        List<Job> q = QUEUE.computeIfAbsent(village.getId(), k -> new ArrayList<>());
        for (Job j : q) if (j.who.equals(v.getUuid())) return;
        q.add(new Job(v, chore, tier));
        Speech.say(v, "settlements.say.forge.ask", toolName(chore, tier));
    }

    private static Job jobOf(VillagerEntityMCA v, Village village) {
        List<Job> q = QUEUE.get(village.getId());
        if (q == null) return null;
        for (Job j : q) if (j.who.equals(v.getUuid())) return j;
        return null;
    }

    private static net.minecraft.text.Text toolName(Chore chore, int tier) {
        Item t = Gear.tool(chore, tier);
        return t == null ? net.minecraft.text.Text.empty() : new ItemStack(t).getName();
    }

    /**
     * Once a second for a worker: with a tool on order and none in hand, he goes and waits by the anvil. True while
     * he does (instead of working).
     */
    public static boolean waiting(VillagerEntityMCA v, Village village) {
        if (village == null) return false;
        Job j = jobOf(v, village);
        if (j == null) return false;
        Class<?> type = j.chore.getToolType();
        if (type != null && Gear.has(v, type)) return false; // he has one to work with meanwhile
        long now = v.getWorld().getTime();
        if (now - j.since > WAIT_LIMIT) { // too long: he makes do himself
            QUEUE.get(village.getId()).remove(j);
            REFUSED.put(v.getUuid(), now + 20 * 600);
            Gear.equipTool(v, village, j.chore);
            return false;
        }
        BlockPos anvil = village.smithyAnvil != null ? village.smithyAnvil : village.smithyFurnace;
        if (anvil == null) return false;
        Doing.set(v, "settlements.doing.forge.wait", toolName(j.chore, j.tier));
        if (v.squaredDistanceTo(anvil.getX() + 0.5, anvil.getY(), anvil.getZ() + 0.5) > 12) Move.to(v, anvil.getX() + 0.5, anvil.getY(), anvil.getZ() + 0.5, 0.5);
        else {
            v.getNavigation().stop();
            VillagerEntityMCA smith = smith(village);
            if (smith != null) v.getLookControl().lookAt(smith, 30f, 30f);
        }
        return true;
    }

    /** One second of the smith's day: tools first, if anybody is waiting. True while he is at it. */
    public static boolean work(VillagerEntityMCA smith, Village village, ServerWorld world) {
        List<Job> q = QUEUE.get(village.getId());
        if (q == null || q.isEmpty()) return false;
        Job j = q.get(0);
        Entity e = world.getEntity(j.who);
        if (!(e instanceof VillagerEntityMCA who) || !who.isAlive()) {
            q.remove(0);
            return true;
        }
        BlockPos anvil = village.smithyAnvil != null ? village.smithyAnvil : village.smithyFurnace;
        if (anvil == null) return false;
        Doing.set(smith, "settlements.doing.forge.make", toolName(j.chore, j.tier), j.name);
        if (smith.squaredDistanceTo(anvil.getX() + 0.5, anvil.getY(), anvil.getZ() + 0.5) > 6) {
            Move.to(smith, anvil.getX() + 0.5, anvil.getY(), anvil.getZ() + 0.5, 0.6);
            return true;
        }
        smith.getLookControl().lookAt(anvil.getX() + 0.5, anvil.getY() + 0.5, anvil.getZ() + 0.5);
        smith.swingHand(smith.getDominantHand());
        if (smith.age % 40 == 0) world.playSound(null, anvil, SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.6f, 1.1f);
        if (++j.done < HAMMER_SECONDS) return true;
        q.remove(0);
        // the material for it, from the stores; the best he can do with what there is
        int tier = j.tier;
        int units = Gear.toolUnits(j.chore);
        while (tier > 0 && !Gear.canPay(village, tier, units)) tier--;
        if (!Gear.canPay(village, tier, units)) {
            Speech.sayNow(smith, "settlements.say.forge.nothing", j.name);
            REFUSED.put(j.who, world.getTime() + 20 * 600);
            return true;
        }
        Gear.pay(village, tier, units);
        Item tool = Gear.tool(j.chore, tier);
        if (tool == null) return true;
        Gear.give(who, j.chore, new ItemStack(tool));
        Speech.sayNow(smith, "settlements.say.forge.done", j.name, new ItemStack(tool).getName());
        if (who.squaredDistanceTo(smith) < 64) {
            smith.getLookControl().lookAt(who, 30f, 30f);
            who.swingHand(who.getDominantHand());
            Speech.say(who, "settlements.say.forge.thanks", smith.getName());
        }
        return true;
    }
}
