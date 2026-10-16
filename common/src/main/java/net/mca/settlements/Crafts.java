package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The trades of the village's workshops, each done in its own building and taking its time: the miller grinds the
 * grain and bakes the bread (more loaves from the same wheat than a housewife at home gets), the collier burns logs to
 * charcoal in the pit for the smith and the torches, the merchant keeps the market stall - sells what the village has
 * too much of and buys in what it lacks.
 */
public final class Crafts {
    private static final Map<UUID, Integer> BUSY = new HashMap<>(); // seconds into the batch at hand

    private Crafts() {
    }

    /** Where in a building its work is done: an open spot near the middle of its floor. */
    static BlockPos spot(ServerWorld world, Built b) {
        int[] fp = b.footprint();
        BlockPos c = b.point(fp[0] / 2, 0, fp[1] / 2);
        for (BlockPos p : BlockPos.iterateOutwards(c, 3, 2, 3)) {
            if (Move.passable(world, p) && Move.passable(world, p.up()) && world.getBlockState(p.down()).isSolidBlock(world, p.down())) return p.toImmutable();
        }
        return c;
    }

    /** At the bench: walks there first. True once he is there. */
    private static boolean at(VillagerEntityMCA v, BlockPos spot) {
        if (v.squaredDistanceTo(Vec3d.ofBottomCenter(spot)) > 2.5 * 2.5) {
            Move.to(v, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.55);
            return false;
        }
        v.getNavigation().stop();
        return true;
    }

    /** A batch of {@code seconds}: true when it is done this second. */
    private static boolean batch(VillagerEntityMCA v, int seconds) {
        int t = BUSY.merge(v.getUuid(), 1, Integer::sum);
        if (t < seconds) return false;
        BUSY.remove(v.getUuid());
        return true;
    }

    // ---------- the miller ----------

    /** Three wheat to two loaves, at the mill: ground on the stones, kneaded, baked. */
    public static boolean miller(VillagerEntityMCA v, Village village, ServerWorld world) {
        Built mill = Buildings.find(village, "mill");
        if (mill == null || count(village, s -> s.isOf(Items.WHEAT)) < 3) {
            BUSY.remove(v.getUuid());
            return false;
        }
        Doing.set(v, "settlements.doing.miller");
        BlockPos spot = spot(world, mill);
        if (!at(v, spot)) return true;
        int t = BUSY.getOrDefault(v.getUuid(), 0);
        Anim.set(v, t < 5 ? Anim.Act.REAP : Anim.Act.BUILD, 30); // the grain on the stones, then the dough
        if (t % 4 == 0) world.playSound(null, spot, t < 5 ? SoundEvents.BLOCK_GRINDSTONE_USE : SoundEvents.BLOCK_WOOL_HIT, SoundCategory.BLOCKS, 0.4f, 0.9f);
        if (!batch(v, 10)) return true;
        take(village, s -> s.isOf(Items.WHEAT), 3);
        Workforce.addToTreasury(village, new ItemStack(Items.BREAD, 2));
        world.spawnParticles(ParticleTypes.SMOKE, spot.getX() + 0.5, spot.getY() + 1.6, spot.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.01);
        if (world.random.nextInt(4) == 0) Speech.sayAny(v, "settlements.say.miller", 3);
        return true;
    }

    // ---------- the collier ----------

    /** Logs into charcoal in the pit: slow, smoky work. Two logs, a while under the turf, two charcoal. */
    public static boolean collier(VillagerEntityMCA v, Village village, ServerWorld world) {
        Built pit = Buildings.find(village, "charcoal");
        int coal = count(village, s -> s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL));
        if (pit == null || coal >= 64 || count(village, s -> s.isIn(ItemTags.LOGS)) < 8) { // leave the builders their timber
            BUSY.remove(v.getUuid());
            return false;
        }
        Doing.set(v, "settlements.doing.collier");
        BlockPos spot = spot(world, pit);
        if (!at(v, spot)) return true;
        Anim.set(v, Anim.Act.DIG, 30); // tending the stack, the turf on it
        world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, 1, 0.4, 0.2, 0.4, 0.01);
        if (!batch(v, 15)) return true;
        take(village, s -> s.isIn(ItemTags.LOGS), 2);
        Workforce.addToTreasury(village, new ItemStack(Items.CHARCOAL, 2));
        if (world.random.nextInt(4) == 0) Speech.sayAny(v, "settlements.say.collier", 3);
        return true;
    }

    // ---------- the merchant ----------

    private record Ware(Predicate<ItemStack> what, Item item, int keep, int lot, int price) {
    }

    // what the village sells when it has too much of it (keep: what it holds back), a lot at a time, for emeralds
    private static final Ware[] SELL = {
            new Ware(s -> s.isIn(ItemTags.WOOL), null, 32, 8, 1),
            new Ware(s -> s.isOf(Items.BREAD), Items.BREAD, 0, 12, 1),
            new Ware(s -> s.isIn(ItemTags.LOGS), null, 256, 16, 1),
            new Ware(s -> s.isOf(Items.COBBLESTONE), Items.COBBLESTONE, 600, 64, 1),
            new Ware(s -> s.isOf(Items.LEATHER), Items.LEATHER, 8, 6, 1),
    };

    /** At the market stall: calling his wares; now and then a deal with the carters on the road. */
    public static boolean merchant(VillagerEntityMCA v, Village village, ServerWorld world) {
        Built market = Buildings.find(village, "market");
        if (market == null) return false;
        Doing.set(v, "settlements.doing.merchant");
        BlockPos spot = spot(world, market);
        if (!at(v, spot)) return true;
        if (world.getClosestPlayer(v, 10) != null && world.random.nextInt(20) == 0) {
            Anim.set(v, Anim.Act.WAVE, 30);
            Speech.sayAny(v, "settlements.say.merchant.call", 4);
        }
        if (!batch(v, 60)) return true; // a deal a minute, at most
        int pop = Math.max(1, village.getPopulation());
        // buy what the village needs first: food when the larder is low, iron for the smith
        int emeralds = count(village, s -> s.isOf(Items.EMERALD));
        if (Forage.food(village) < pop * 2 && emeralds >= 2) {
            take(village, s -> s.isOf(Items.EMERALD), 2);
            Workforce.addToTreasury(village, new ItemStack(Items.BREAD, 10));
            Life.news(village, "merchant.bought.food");
            return true;
        }
        if (count(village, s -> s.isOf(Items.IRON_INGOT)) < 8 && emeralds >= 4) {
            take(village, s -> s.isOf(Items.EMERALD), 4);
            Workforce.addToTreasury(village, new ItemStack(Items.IRON_INGOT, 4));
            return true;
        }
        // then sell a lot of whatever the village has too much of
        for (Ware w : SELL) {
            int keep = w.item() == Items.BREAD ? pop * 10 : w.keep();
            if (count(village, w.what()) < keep + w.lot()) continue;
            take(village, w.what(), w.lot());
            Workforce.addToTreasury(village, new ItemStack(Items.EMERALD, w.price()));
            Anim.set(v, Anim.Act.FEED, 30); // handing it over
            return true;
        }
        return true;
    }

    // ---------- the glazier ----------

    private static final Map<UUID, BlockPos> SAND = new HashMap<>();

    /**
     * Glass for the windows: he digs sand on the shore or the dunes, carries it to the workshop's furnace and melts it,
     * and hands the panes in. Three sand make eight panes. A village with a glazier has real windows, not lattices.
     */
    public static boolean glazier(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (count(village, s -> s.isOf(Items.GLASS_PANE)) >= 96) return false; // enough in stock
        Built shop = Buildings.find(village, "mason");
        if (shop == null) shop = Buildings.find(village, "smithy");
        if (shop == null) return false;
        int sand = 0;
        for (int i = 0; i < v.getInventory().size(); i++) if (v.getInventory().getStack(i).isIn(ItemTags.SAND)) sand += v.getInventory().getStack(i).getCount();
        sand += count(village, s -> s.isIn(ItemTags.SAND));
        if (sand >= 3) { // to the furnace with it
            Doing.set(v, "settlements.doing.glazier.melt");
            BlockPos spot = spot(world, shop);
            if (!at(v, spot)) return true;
            Anim.set(v, Anim.Act.FEED, 30); // the pipe in the fire, turning
            world.spawnParticles(ParticleTypes.FLAME, spot.getX() + 0.5, spot.getY() + 1.2, spot.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.01);
            if (!batch(v, 12)) return true;
            int need = 3;
            var inv = v.getInventory();
            for (int i = 0; i < inv.size() && need > 0; i++) {
                ItemStack s = inv.getStack(i);
                if (!s.isIn(ItemTags.SAND)) continue;
                int d = Math.min(need, s.getCount());
                s.decrement(d);
                need -= d;
            }
            if (need > 0) take(village, s -> s.isIn(ItemTags.SAND), need);
            Workforce.addToTreasury(village, new ItemStack(Items.GLASS_PANE, 8));
            world.playSound(null, spot, SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.BLOCKS, 0.6f, 1.2f);
            if (world.random.nextInt(3) == 0) Speech.sayAny(v, "settlements.say.glazier", 3);
            return true;
        }
        // out for sand: the nearest bare sand within reach
        Doing.set(v, "settlements.doing.glazier.sand");
        if (Dig.busy(v) || Dig.collect(v, 4)) return true; // digging, or picking up what he dug
        BlockPos at = SAND.get(v.getUuid());
        if (at == null || !world.getBlockState(at).isIn(net.minecraft.registry.tag.BlockTags.SAND)) {
            at = findSand(world, v.getBlockPos());
            if (at == null) return false;
            SAND.put(v.getUuid(), at);
        }
        if (v.squaredDistanceTo(Vec3d.ofCenter(at)) > 3.5 * 3.5) {
            Move.to(v, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 0.6);
            return true;
        }
        Gear.equipShovel(v, village);
        Dig.start(v, at);
        return true;
    }

    private static BlockPos findSand(ServerWorld world, BlockPos from) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -48; dx <= 48; dx += 2) {
            for (int dz = -48; dz <= 48; dz += 2) {
                int x = from.getX() + dx, z = from.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                BlockPos top = new BlockPos(x, world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                if (!world.getBlockState(top).isIn(net.minecraft.registry.tag.BlockTags.SAND) || !world.getFluidState(top.up()).isEmpty()) continue;
                double d = top.getSquaredDistance(from);
                if (d < bd) {
                    bd = d;
                    best = top;
                }
            }
        }
        return best;
    }

    // ---------- the stores ----------

    static int count(Village village, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (what.test(s)) n += s.getCount();
        return n;
    }

    static void take(Village village, Predicate<ItemStack> what, int n) {
        for (Iterator<ItemStack> it = village.storageBuffer.iterator(); it.hasNext() && n > 0; ) {
            ItemStack s = it.next();
            if (!what.test(s)) continue;
            int d = Math.min(n, s.getCount());
            s.decrement(d);
            n -= d;
            if (s.isEmpty()) it.remove();
        }
        village.markDirty();
    }
}
