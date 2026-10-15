package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.RabbitEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A hungry village does not sit and starve: while the stores are short of food, whoever has nothing more pressing
 * goes out for it. They gather what has been dropped, pick the ripe berries in the woods and hunt the wild beasts
 * about - never the village's own pen, and never the last of a herd.
 */
public final class Forage {
    private static final int RANGE = 32;
    private static final Map<UUID, BlockPos> BUSH = new HashMap<>();
    private static final Map<UUID, Long> LOOKED = new HashMap<>();

    private Forage() {
    }

    /** Less than three meals a head in the stores. */
    public static boolean hungry(Village village) {
        if (village == null) return false;
        int food = 0;
        for (ItemStack s : village.storageBuffer) if (s.isFood()) food += s.getCount();
        return food < Math.max(1, village.getPopulation()) * 3;
    }

    /** Meals in the stores. */
    public static int food(Village village) {
        int food = 0;
        for (ItemStack s : village.storageBuffer) if (s.isFood()) food += s.getCount();
        return food;
    }

    /** One second of foraging. True while he is at it. */
    public static boolean work(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (v.isBaby() || v.getVillagerBrain().isPanicking()) return false;
        long now = world.getTime();
        Doing.set(v, "settlements.doing.forage");

        // 1. food lying about: a kill's meat, dropped crops
        ItemEntity food = null;
        double fd = Double.MAX_VALUE;
        for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, v.getBoundingBox().expand(12, 4, 12), e -> e.isAlive() && e.getStack().isFood())) {
            if (world.getClosestPlayer(e, 5) != null) continue; // a player's, or somebody is handling it
            double d = e.squaredDistanceTo(v);
            if (d < fd) {
                fd = d;
                food = e;
            }
        }
        if (food != null) {
            if (fd <= 5) {
                Workforce.addToTreasury(village, food.getStack().copy());
                food.discard();
                v.swingHand(v.getDominantHand());
            } else {
                Move.to(v, food, 0.6);
            }
            return true;
        }

        // 2. ripe berries
        BlockPos bush = BUSH.get(v.getUuid());
        if (bush != null && !ripe(world.getBlockState(bush))) bush = null;
        if (bush == null && now - LOOKED.getOrDefault(v.getUuid(), -1000L) >= 200) {
            LOOKED.put(v.getUuid(), now);
            bush = findBush(world, v.getBlockPos());
        }
        if (bush != null) {
            BUSH.put(v.getUuid(), bush);
            if (v.squaredDistanceTo(bush.getX() + 0.5, bush.getY(), bush.getZ() + 0.5) > 6) {
                Move.to(v, bush.getX() + 0.5, bush.getY(), bush.getZ() + 0.5, 0.55);
            } else {
                BlockState s = world.getBlockState(bush);
                int age = s.get(SweetBerryBushBlock.AGE);
                Workforce.addToTreasury(village, new ItemStack(Items.SWEET_BERRIES, 1 + world.random.nextInt(2) + (age == 3 ? 1 : 0)));
                world.setBlockState(bush, s.with(SweetBerryBushBlock.AGE, 1), 2);
                v.getLookControl().lookAt(bush.getX() + 0.5, bush.getY() + 0.5, bush.getZ() + 0.5);
                v.swingHand(v.getDominantHand());
                Speech.sayAny(v, "settlements.say.forage.berries", 3);
                BUSH.remove(v.getUuid());
            }
            return true;
        }

        // 3. a hunt: wild beasts, not the pen's, and never the last few of their kind about
        AnimalEntity prey = null;
        double pd = Double.MAX_VALUE;
        Box area = v.getBoundingBox().expand(RANGE, 8, RANGE);
        for (AnimalEntity a : world.getEntitiesByClass(AnimalEntity.class, area, a -> a.isAlive() && !a.isBaby() && !a.isLeashed() && game(a))) {
            if (village.penCenter != null && a.getBlockPos().isWithinDistance(village.penCenter, 12)) continue;
            Class<?> kind = a.getClass();
            int herd = world.getEntitiesByClass(AnimalEntity.class, a.getBoundingBox().expand(24, 6, 24), o -> o.getClass() == kind && o.isAlive()).size();
            if (herd < 3) continue;
            double d = a.squaredDistanceTo(v);
            if (d < pd) {
                pd = d;
                prey = a;
            }
        }
        if (prey == null) return false;
        if (pd > 6) {
            Move.to(v, prey, 0.62);
            if (pd < 200) Speech.sayAny(v, "settlements.say.forage.hunt", 3);
        } else {
            v.getLookControl().lookAt(prey, 30f, 30f);
            v.swingHand(v.getDominantHand());
            prey.damage(world.getDamageSources().mobAttack(v), 6f);
        }
        return true;
    }

    private static boolean game(AnimalEntity a) {
        return a instanceof CowEntity || a instanceof PigEntity || a instanceof SheepEntity || a instanceof ChickenEntity || a instanceof RabbitEntity;
    }

    private static boolean ripe(BlockState s) {
        return s.isOf(Blocks.SWEET_BERRY_BUSH) && s.get(SweetBerryBushBlock.AGE) >= 2;
    }

    private static BlockPos findBush(ServerWorld world, BlockPos from) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(from.add(-24, -4, -24), from.add(24, 4, 24))) {
            if (!ripe(world.getBlockState(p))) continue;
            double d = p.getSquaredDistance(from);
            if (d < bd) {
                bd = d;
                best = p.toImmutable();
            }
        }
        return best;
    }
}
