package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * The village's herdsman: sheep, cows, pigs and chickens. Strays are led into the pen, inside it the animals are fed to
 * breed (up to a few of each kind), sheep are sheared, and when the village is short of food he slaughters
 * animals, wild ones first, and carries the drops home. Without a pen he still hunts, the village has to eat.
 */
public final class Husbandry {
    private static final int PER_SPECIES_CAP = 6;
    private static final int KEEP_BREEDERS = 2;
    private static final int WOOL_QUOTA = 48;
    private static final double PEN_RADIUS = 4.8;

    private Husbandry() {
    }

    private static boolean ours(AnimalEntity a) {
        return a instanceof SheepEntity || a instanceof CowEntity || a instanceof PigEntity || a instanceof ChickenEntity;
    }

    private static boolean inPen(AnimalEntity s, BlockPos pen) {
        return pen != null && Math.abs(s.getX() - (pen.getX() + 0.5)) <= PEN_RADIUS && Math.abs(s.getZ() - (pen.getZ() + 0.5)) <= PEN_RADIUS;
    }

    private static int foodStock(Village village) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (s.isFood() && Workforce.eatable(s)) n += s.getCount();
        return n;
    }

    /** One herdsman second. Returns true when he actually worked. */
    public static boolean work(VillagerEntityMCA v, Village village) {
        Doing.set(v, "settlements.doing.herd");
        if (village == null || !(v.getWorld() instanceof ServerWorld world)) return false;
        BlockPos pen = village.penCenter;
        BlockPos home = pen != null ? pen : new BlockPos(village.getCenter());

        if (collectDrops(v, village, world)) return true;

        List<AnimalEntity> animals = world.getEntitiesByClass(AnimalEntity.class, new Box(home).expand(46, 10, 46),
                a -> a.isAlive() && !a.isBaby() && ours(a));
        if (animals.isEmpty()) {
            Speech.say(v, "settlements.say.pen.noanimals");
            return false;
        }
        List<AnimalEntity> inside = animals.stream().filter(a -> inPen(a, pen)).toList();
        List<AnimalEntity> outside = animals.stream().filter(a -> !inPen(a, pen)).toList();

        // 1. the village is hungry: slaughter, wild animals first, then the surplus in the pen
        int hungerLine = 20 + village.getPopulation() * 3;
        if (foodStock(village) < hungerLine) {
            AnimalEntity prey = outside.stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
            if (prey == null) {
                prey = inside.stream().filter(a -> surplus(a, inside)).min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
            }
            if (prey != null) return slaughter(v, world, prey);
        }

        // 2. lead strays into the pen
        if (pen != null) {
            AnimalEntity stray = outside.stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
            if (stray != null && inside.size() < PER_SPECIES_CAP * 3) {
                if (v.squaredDistanceTo(stray) > 12) {
                    Move.to(v, stray, 0.65);
                    return false;
                }
                stray.getNavigation().startMovingTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 1.15);
                Vec3d toPen = new Vec3d(pen.getX() + 0.5 - v.getX(), 0, pen.getZ() + 0.5 - v.getZ()).normalize();
                Move.to(v, stray.getX() + toPen.x * 2, stray.getY(), stray.getZ() + toPen.z * 2, 0.6);
                Speech.say(v, "settlements.say.pen.herd");
                return true;
            }
        }

        // 3. wool from the sheep in the pen
        int wool = village.storageBuffer.stream().filter(s -> s.isIn(net.minecraft.registry.tag.ItemTags.WOOL)).mapToInt(ItemStack::getCount).sum();
        if (wool < WOOL_QUOTA) {
            SheepEntity target = inside.stream().filter(a -> a instanceof SheepEntity s && !s.isSheared()).map(a -> (SheepEntity) a)
                    .min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
            if (target != null) {
                if (v.squaredDistanceTo(target) > 6) {
                    Move.to(v, target, 0.6);
                    return false;
                }
                target.setSheared(true);
                int n = 1 + world.random.nextInt(3);
                Workforce.addToTreasury(village, new ItemStack(Registries.ITEM.get(new Identifier(target.getColor().getName() + "_wool")), n));
                world.playSoundFromEntity(null, target, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.PLAYERS, 1.0f, 1.0f);
                v.swingHand(v.getDominantHand());
                Speech.say(v, "settlements.say.pen.shear");
                return true;
            }
        }

        // 4. breed each kind up to its cap, with whatever food the stores hold for it
        if (breed(v, village, inside)) return true;

        if (pen != null && v.squaredDistanceTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5) > 100) {
            Move.to(v, pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.6);
        }
        return false;
    }

    /** More than the breeders we keep, of the same kind. */
    private static boolean surplus(AnimalEntity a, List<AnimalEntity> inside) {
        return inside.stream().filter(o -> o.getClass() == a.getClass()).count() > KEEP_BREEDERS;
    }

    private static boolean slaughter(VillagerEntityMCA v, ServerWorld world, AnimalEntity prey) {
        if (v.squaredDistanceTo(prey) > 5) {
            Move.to(v, prey, 0.7);
            return false;
        }
        v.getLookControl().lookAt(prey, 30f, 30f);
        v.swingHand(v.getDominantHand());
        prey.damage(world.getDamageSources().mobAttack(v), 6f);
        v.settlementsKillPos = prey.getBlockPos();
        v.settlementsKillUntil = world.getTime() + 400;
        Speech.say(v, "settlements.say.pen.slaughter");
        return true;
    }

    /** What the animals leave behind goes to the stores. */
    private static boolean collectDrops(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (v.settlementsKillPos == null || world.getTime() > v.settlementsKillUntil) return false;
        BlockPos at = v.settlementsKillPos;
        List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, new Box(at).expand(6, 3, 6), e -> e.isAlive() && e.getItemAge() > 4);
        if (drops.isEmpty()) return false;
        ItemEntity e = drops.stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).get();
        if (v.squaredDistanceTo(e) > 4) {
            Move.to(v, e, 0.7);
            return true;
        }
        Workforce.addToTreasury(village, e.getStack().copy());
        e.discard();
        return true;
    }

    private static boolean breed(VillagerEntityMCA v, Village village, List<AnimalEntity> inside) {
        for (Class<? extends AnimalEntity> kind : List.of(SheepEntity.class, CowEntity.class, PigEntity.class, ChickenEntity.class)) {
            List<AnimalEntity> same = inside.stream().filter(a -> kind.isInstance(a)).toList();
            if (same.size() < 2 || same.size() >= PER_SPECIES_CAP) continue;
            List<AnimalEntity> ready = same.stream().filter(a -> !a.isInLove() && a.getBreedingAge() == 0).toList();
            ItemStack food = null;
            for (ItemStack s : village.storageBuffer) {
                if (s.getCount() >= 2 && same.get(0).isBreedingItem(s)) {
                    food = s;
                    break;
                }
            }
            if (food == null) continue;
            for (AnimalEntity a : ready) {
                AnimalEntity b = ready.stream().filter(o -> o != a && o.squaredDistanceTo(a) < 64).findFirst().orElse(null);
                if (b == null) continue;
                if (v.squaredDistanceTo(a) > 6) {
                    Move.to(v, a, 0.6);
                    return false;
                }
                food.decrement(2);
                village.storageBuffer.removeIf(ItemStack::isEmpty);
                village.markDirty();
                a.setLoveTicks(600);
                b.setLoveTicks(600);
                v.swingHand(v.getDominantHand());
                Speech.say(v, "settlements.say.pen.feed");
                return true;
            }
        }
        return false;
    }
}
