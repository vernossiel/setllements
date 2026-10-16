package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.biome.Biome;

import java.util.Iterator;
import java.util.Set;

/**
 * Plenty does not last. What lies in the stores goes off - meat and fish in days, bread in a week or two, grain and
 * roots slowly - and without a granary the rats get at a big heap; so a village cannot sit on a mountain of food. And
 * the land is not the same everywhere nor every year: the desert, the dry savanna and the cold north yield less than
 * good plains, and every week the weather makes a harvest good or poor - now and then a bad one. So villages go
 * hungry in turn, and a hungry village has reasons: rogues, quarrels, raids.
 */
public final class Harvest {
    private static final Set<Item> RAW = Set.of(Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT, Items.COD, Items.SALMON,
            Items.TROPICAL_FISH, Items.ROTTEN_FLESH);
    private static final Set<Item> COOKED = Set.of(Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON,
            Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.RABBIT_STEW, Items.MUSHROOM_STEW, Items.BEETROOT_SOUP);
    private static final Set<Item> BAKED = Set.of(Items.BREAD, Items.BAKED_POTATO, Items.COOKIE, Items.PUMPKIN_PIE, Items.CAKE);
    private static final Set<Item> ROOTS = Set.of(Items.POTATO, Items.CARROT, Items.BEETROOT, Items.APPLE, Items.MELON_SLICE, Items.SWEET_BERRIES);

    private Harvest() {
    }

    /** How good the village's land is: good plains 1, dry or cold land less. */
    public static double fertility(ServerWorld world, Village village) {
        BlockPos c = new BlockPos(village.getCenter());
        Biome b = world.getBiome(c).value();
        float t = b.getTemperature();
        if (!b.hasPrecipitation() && t > 1.5f) return 0.55; // desert, badlands
        if (!b.hasPrecipitation()) return 0.75;             // savanna and the like
        if (t < 0.2f) return 0.7;                           // the cold north
        return 1.0;
    }

    /** This week's weather for the crops: mostly fair, sometimes good, now and then a bad week. Same all week. */
    public static double luck(Village village, long day) {
        Random r = Random.create(village.getId() * 0x9E3779B97F4A7C15L + (day / 7) * 0xC2B2AE3D27D4EB4FL);
        double roll = r.nextDouble();
        if (roll < 0.2) return 0.4 + r.nextDouble() * 0.3;   // a bad week: blight, drought, rain at the wrong time
        if (roll > 0.8) return 1.1 + r.nextDouble() * 0.15;  // a good one
        return 0.8 + r.nextDouble() * 0.3;
    }

    /** What a reaping yields against what it would in a good year on good land. */
    public static double factor(ServerWorld world, Village village) {
        long day = world.getTimeOfDay() / 24000L;
        return Math.min(1.25, fertility(world, village) * luck(village, day));
    }

    /** The reaped crop, cut down to the year and the land (the seed is always kept back). */
    public static void scale(ItemStack drop, double factor, Random random) {
        if (drop.isEmpty() || factor >= 1 || drop.isOf(Items.WHEAT_SEEDS) || drop.isOf(Items.BEETROOT_SEEDS)) return;
        double want = drop.getCount() * factor;
        int n = (int) want + (random.nextDouble() < want - (int) want ? 1 : 0);
        if (drop.isOf(Items.POTATO) || drop.isOf(Items.CARROT)) n = Math.max(1, n); // one kept to plant again
        drop.setCount(n);
    }

    /** A village's day: some of what lies in the stores goes off; a bad week begins, and the village says so. */
    public static void day(ServerWorld world, Village village) {
        boolean granary = Buildings.has(village, "granary");
        int food = 0;
        for (ItemStack s : village.storageBuffer) if (s.isFood()) food += s.getCount();
        boolean rats = !granary && food > Math.max(4, village.getPopulation()) * 12;
        Random r = world.random;
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (!s.isFood() && !s.isOf(Items.WHEAT)) continue;
            Item i = s.getItem();
            double rate = RAW.contains(i) ? 0.08 : COOKED.contains(i) ? 0.04 : BAKED.contains(i) ? 0.03 : ROOTS.contains(i) || i == Items.WHEAT ? 0.015 : 0.02;
            if (granary) rate /= 2;
            if (rats) rate += 0.02;
            double gone = s.getCount() * rate;
            int n = (int) gone + (r.nextDouble() < gone - (int) gone ? 1 : 0);
            s.decrement(n);
            if (s.isEmpty()) it.remove();
        }
        long day = world.getTimeOfDay() / 24000L;
        if (day % 7 == 0 && luck(village, day) < 0.7) Life.news(village, "harvest.bad");
        village.markDirty();
    }
}
