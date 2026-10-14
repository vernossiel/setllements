package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * How well the village is doing, and so how fast its people work: a poor land (desert, ice), frequent attacks and an
 * empty larder all slow the village down. On top of that every worker slows down as he tires.
 */
public final class Prosperity {
    private static final Map<Integer, double[]> DANGER = new HashMap<>(); // {value, last update tick}
    private static final Map<Integer, double[]> CACHE = new HashMap<>();  // {factor, computed at tick}
    private static final double DANGER_HALF_LIFE = 24000.0 * 2;

    private Prosperity() {
    }

    public static void danger(VillagerEntityMCA v, double amount) {
        Village.findNearest(v).ifPresent(village -> danger(village, amount));
    }

    /** Something attacked the village: a monster bit somebody, a raid came through. Fades over a couple of days. */
    public static void danger(Village village, double amount) {
        ServerWorld w = village.getWorld();
        if (w == null) return;
        double now = w.getTime();
        double[] d = DANGER.computeIfAbsent(village.getId(), k -> new double[]{0, now});
        d[0] = danger(village) + amount;
        d[1] = now;
    }

    public static double danger(Village village) {
        double[] d = DANGER.get(village.getId());
        ServerWorld w = village.getWorld();
        if (d == null || w == null) return 0;
        return d[0] * Math.pow(0.5, (w.getTime() - d[1]) / DANGER_HALF_LIFE);
    }

    private static double land(Village village) {
        ServerWorld w = village.getWorld();
        if (w == null) return 1;
        String b = w.getBiome(new BlockPos(village.getCenter())).getKey().map(k -> k.getValue().getPath()).orElse("plains");
        if (b.contains("desert") || b.contains("badlands") || b.contains("ice") || b.contains("frozen") || b.equals("snowy_plains") || b.contains("slopes")) return 0.7;
        if (b.contains("taiga") || b.contains("savanna") || b.contains("swamp") || b.contains("snowy") || b.contains("windswept")) return 0.9;
        return 1.0;
    }

    /** The village's working pace, 0.35 (struggling) .. 1.2 (thriving). */
    public static double factor(Village village) {
        ServerWorld w = village.getWorld();
        long now = w == null ? 0 : w.getTime();
        double[] c = CACHE.get(village.getId());
        if (c != null && now - c[1] < 200) return c[0];
        double f = land(village);
        f *= 1 - Math.min(0.45, danger(village) * 0.04);
        int food = 0;
        for (ItemStack s : village.storageBuffer) if (s.isFood()) food += s.getCount();
        if (Buildings.has(village, "granary")) food = food * 3 / 2; // stored well, food goes further
        int pop = Math.max(1, village.getPopulation());
        if (food < pop) f *= 0.75;
        else if (food < pop * 3) f *= 0.9;
        else if (food > pop * 10) f *= 1.1;
        if (village.hall != null && village.hall.floors() >= 2) f *= 1.05;
        if (Buildings.has(village, "market")) f *= 1.05;
        f = Math.max(0.35, Math.min(1.2, f));
        CACHE.put(village.getId(), new double[]{f, now});
        return f;
    }

    public static Text describe(Village village) {
        double f = factor(village);
        if (f >= 1.0) return Text.translatable("settlements.prosperity.good").formatted(Formatting.DARK_GREEN);
        if (f >= 0.8) return Text.translatable("settlements.prosperity.ok").formatted(Formatting.GRAY);
        if (f >= 0.6) return Text.translatable("settlements.prosperity.hard").formatted(Formatting.GOLD);
        return Text.translatable("settlements.prosperity.dire").formatted(Formatting.RED);
    }

    /** A tired worker is slow: full pace when rested, half when worn out. */
    public static double fatigue(VillagerEntityMCA v) {
        if (v.settlementsEnergy > 600) return 1.0;
        if (v.settlementsEnergy > 350) return 0.75;
        return 0.5;
    }

    /** How many blocks this worker gets done this second out of {@code base}, carrying fractions over. */
    public static int ops(VillagerEntityMCA v, Village village, double base) {
        double f = base * fatigue(v) * (village == null ? 1 : factor(village));
        v.settlementsPace += f;
        int n = (int) v.settlementsPace;
        v.settlementsPace -= n;
        return n;
    }
}
