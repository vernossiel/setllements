package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shepherd and his flock. In the morning he goes into the pen, puts a few of the animals on a lead and walks them
 * out to a pasture of good grass past the houses; there he lets them graze, keeps them together (one that wanders off
 * is fetched back on the lead) and shears the sheep that have their wool. Towards evening he leads them home and shuts
 * the gate. In the pen he feeds them to breed - the village's own herd, so many of each kind as it can keep - and
 * brings in any of the village's animals that got out. The animals he has had in the pen are the village's (they carry
 * its mark); what is born to them is too.
 */
public final class Herding {
    private static final int GROUP = 4;              // animals one man can lead at once
    private static final double PEN_RADIUS = 4.5;
    private static final int KEEP_TOGETHER = 12;     // further from the flock than this, an animal is fetched back

    enum Phase { PEN, OUT, GRAZE, IN }

    private static final class Flock {
        Phase phase = Phase.PEN;
        long day = -1;
        final List<UUID> led = new ArrayList<>();
        BlockPos pasture;
        long since;
    }

    private static final Map<UUID, Flock> FLOCKS = new HashMap<>();
    private static final Map<Integer, BlockPos> PASTURES = new HashMap<>();

    private Herding() {
    }

    static String mark(Village village) {
        return "settlements_herd_" + village.getId();
    }

    /** One of the village's own animals (as against a wild one, which the hunter may take). */
    public static boolean herd(AnimalEntity a, Village village) {
        return a.getCommandTags().contains(mark(village));
    }

    static boolean kind(AnimalEntity a) {
        return a instanceof SheepEntity || a instanceof CowEntity || a instanceof PigEntity || a instanceof ChickenEntity;
    }

    static boolean inPen(AnimalEntity a, BlockPos pen) {
        return pen != null && Math.abs(a.getX() - (pen.getX() + 0.5)) <= PEN_RADIUS && Math.abs(a.getZ() - (pen.getZ() + 0.5)) <= PEN_RADIUS
                && Math.abs(a.getY() - pen.getY()) < 3;
    }

    /** How many of a kind the village keeps: more as it grows. The butcher takes what is born beyond it. */
    public static int keep(Village village) {
        return 4 + 2 * Prosperity.tier(village);
    }

    /** The village's animals about the place (marked as its own, or in its pen, which makes them its own). */
    static List<AnimalEntity> animals(ServerWorld world, Village village) {
        BlockPos home = village.penCenter != null ? village.penCenter : new BlockPos(village.getCenter());
        List<AnimalEntity> out = new ArrayList<>();
        for (AnimalEntity a : world.getEntitiesByClass(AnimalEntity.class, new Box(home).expand(64, 16, 64), a -> a.isAlive() && kind(a))) {
            if (!herd(a, village) && inPen(a, village.penCenter)) a.addCommandTag(mark(village)); // in our pen: ours now
            if (herd(a, village)) out.add(a);
        }
        return out;
    }

    /** One shepherd second; true while at work. */
    public static boolean work(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null) return false;
        BlockPos pen = village.penCenter;
        List<AnimalEntity> herd = animals(world, village);
        Flock f = FLOCKS.computeIfAbsent(v.getUuid(), k -> new Flock());
        long t = world.getTimeOfDay() % 24000L, day = world.getTimeOfDay() / 24000L;
        // a stray of the village's own (or a wild one nearby, when the village has hardly any) into the pen first
        if (pen == null) return false; // no pen yet: nowhere to keep a herd (the builders put one up first)
        if (f.day != day) { // a new day: the flock is in the pen
            f.day = day;
            f.phase = Phase.PEN;
            f.led.clear();
        }
        long evening = Persona.workEnd(v) - 2000;
        switch (f.phase) {
            case PEN -> {
                if (t < evening - 3000 && !herd.isEmpty() && !world.isRaining()) {
                    // out to the pasture with a group of them
                    BlockPos pasture = pasture(world, village);
                    if (pasture != null) {
                        f.pasture = pasture;
                        f.phase = Phase.OUT;
                        f.since = world.getTime();
                    }
                }
                return penWork(v, village, world, herd, pen);
            }
            case OUT -> {
                Doing.set(v, "settlements.doing.herd.out");
                if (f.led.size() < Math.min(GROUP, herd.size()) && world.getTime() - f.since < 20 * 60) {
                    // gathering the group at the pen: a lead on each, one after another
                    AnimalEntity next = herd.stream().filter(a -> inPen(a, pen) && !a.isLeashed() && !a.isBaby())
                            .min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
                    if (next != null) {
                        if (v.squaredDistanceTo(next) > 6) {
                            Move.to(v, next.getX(), next.getY(), next.getZ(), 0.5);
                            return true;
                        }
                        next.attachLeash(v, true);
                        f.led.add(next.getUuid());
                        v.swingHand(v.getDominantHand());
                        world.playSoundFromEntity(null, next, SoundEvents.ENTITY_LEASH_KNOT_PLACE, SoundCategory.NEUTRAL, 0.6f, 1f);
                        return true;
                    }
                }
                if (f.led.isEmpty()) {
                    f.phase = Phase.GRAZE; // nothing to take out: the day is spent with the pen
                    f.pasture = null;
                    return penWork(v, village, world, herd, pen);
                }
                if (walkWith(v, world, f, f.pasture, 0.45)) return true;
                letGo(world, f); // there: off the leads, to graze
                f.phase = Phase.GRAZE;
                f.since = world.getTime();
                Speech.say(v, "settlements.say.herd.pasture");
                return true;
            }
            case GRAZE -> {
                if (f.pasture == null) {
                    if (t >= evening) f.phase = Phase.IN;
                    return penWork(v, village, world, herd, pen);
                }
                Doing.set(v, "settlements.doing.herd.graze");
                List<AnimalEntity> out = herd.stream().filter(a -> !inPen(a, pen)).toList();
                if (t >= evening || world.isRaining() || world.isThundering()) {
                    f.phase = Phase.IN;
                    return true;
                }
                // one wandering off: fetched back on the lead
                for (AnimalEntity a : out) {
                    if (a.squaredDistanceTo(f.pasture.getX() + 0.5, a.getY(), f.pasture.getZ() + 0.5) <= KEEP_TOGETHER * KEEP_TOGETHER) continue;
                    if (v.squaredDistanceTo(a) > 6) {
                        Move.to(v, a.getX(), a.getY(), a.getZ(), 0.55);
                        return true;
                    }
                    if (!a.isLeashed()) a.attachLeash(v, true);
                    a.getNavigation().startMovingTo(f.pasture.getX() + 0.5, f.pasture.getY(), f.pasture.getZ() + 0.5, 1.0);
                    Move.to(v, f.pasture.getX() + 0.5, f.pasture.getY(), f.pasture.getZ() + 0.5, 0.45);
                    if (a.squaredDistanceTo(f.pasture.getX() + 0.5, a.getY(), f.pasture.getZ() + 0.5) < 36) a.detachLeash(true, false);
                    return true;
                }
                for (AnimalEntity a : out) if (a.isLeashed() && a.getHoldingEntity() == v) a.detachLeash(true, false);
                // the sheep that have their wool back are sheared out here
                if (shear(v, village, world, out)) return true;
                // and he stands by them, looks them over, moves about a little
                if (v.squaredDistanceTo(f.pasture.getX() + 0.5, f.pasture.getY(), f.pasture.getZ() + 0.5) > 25) {
                    Move.to(v, f.pasture.getX() + 0.5, f.pasture.getY(), f.pasture.getZ() + 0.5, 0.4);
                } else if (world.random.nextInt(12) == 0 && !out.isEmpty()) {
                    v.getLookControl().lookAt(out.get(world.random.nextInt(out.size())), 30f, 30f);
                }
                return true;
            }
            case IN -> {
                Doing.set(v, "settlements.doing.herd.in");
                // all of them back on the leads (a group at a time), then home
                List<AnimalEntity> out = herd.stream().filter(a -> !inPen(a, pen) && !a.isBaby()).toList();
                if (f.led.size() < GROUP) {
                    AnimalEntity next = out.stream().filter(a -> !a.isLeashed()).min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
                    if (next != null && v.squaredDistanceTo(next) < 24 * 24) {
                        if (v.squaredDistanceTo(next) > 6) {
                            Move.to(v, next.getX(), next.getY(), next.getZ(), 0.5);
                            return true;
                        }
                        next.attachLeash(v, true);
                        f.led.add(next.getUuid());
                        world.playSoundFromEntity(null, next, SoundEvents.ENTITY_LEASH_KNOT_PLACE, SoundCategory.NEUTRAL, 0.6f, 1f);
                        return true;
                    }
                }
                if (!f.led.isEmpty()) {
                    if (walkWith(v, world, f, pen, 0.45)) return true;
                    letGo(world, f);
                    Speech.say(v, "settlements.say.herd.home");
                    return true;
                }
                if (out.isEmpty()) {
                    f.phase = Phase.PEN; // all in: the gate is shut behind him (see Move.shutBehind)
                    f.pasture = null;
                    f.day = day + (t >= evening ? 0 : 0);
                    f.since = Long.MAX_VALUE; // not out again today
                    return penWork(v, village, world, herd, pen);
                }
                // the rest are too far off for now: fetched on the next round
                AnimalEntity far = out.stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).get();
                Move.to(v, far.getX(), far.getY(), far.getZ(), 0.55);
                return true;
            }
        }
        return false;
    }

    /** Walks to {@code to} with the animals on his leads following; true while still on the way. */
    private static boolean walkWith(VillagerEntityMCA v, ServerWorld world, Flock f, BlockPos to, double speed) {
        f.led.removeIf(id -> !(world.getEntity(id) instanceof AnimalEntity a) || !a.isAlive() || !a.isLeashed());
        if (f.led.isEmpty()) return false;
        if (v.squaredDistanceTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5) <= 6) return false;
        // he waits for the slow ones, not dragging them
        for (UUID id : f.led) {
            if (world.getEntity(id) instanceof AnimalEntity a && a.squaredDistanceTo(v) > 36) {
                v.getNavigation().stop();
                v.getLookControl().lookAt(a, 30f, 30f);
                a.getNavigation().startMovingTo(v, 1.1);
                return true;
            }
        }
        Move.to(v, to.getX() + 0.5, to.getY(), to.getZ() + 0.5, speed);
        for (UUID id : f.led) if (world.getEntity(id) instanceof AnimalEntity a) a.getNavigation().startMovingTo(v, 1.0);
        return true;
    }

    private static void letGo(ServerWorld world, Flock f) {
        for (UUID id : f.led) if (world.getEntity(id) instanceof AnimalEntity a && a.isLeashed()) a.detachLeash(true, false);
        f.led.clear();
    }

    /** The work in and about the pen: strays brought in, the animals fed to breed, the sheep sheared. */
    private static boolean penWork(VillagerEntityMCA v, Village village, ServerWorld world, List<AnimalEntity> herd, BlockPos pen) {
        Doing.set(v, "settlements.doing.herd.pen");
        // one of ours outside the pen, not on a pasture with him: brought in on a lead
        for (AnimalEntity a : herd) {
            if (inPen(a, pen) || a.isBaby()) continue;
            if (v.squaredDistanceTo(a) > 6) {
                Move.to(v, a.getX(), a.getY(), a.getZ(), 0.55);
                Doing.set(v, "settlements.doing.herd.stray");
                return true;
            }
            if (!a.isLeashed()) a.attachLeash(v, true);
            Move.to(v, pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.45);
            a.getNavigation().startMovingTo(v, 1.0);
            if (inPen(v.getBlockPos(), pen)) a.detachLeash(true, false);
            Doing.set(v, "settlements.doing.herd.stray");
            return true;
        }
        List<AnimalEntity> inside = herd.stream().filter(a -> inPen(a, pen)).toList();
        if (shear(v, village, world, inside)) return true;
        if (breed(v, village, inside)) return true;
        if (herd.size() < 2) return tame(v, village, world, herd); // hardly any animals: he brings in wild ones for the herd
        // with no butcher in the village he takes the surplus himself, and roasts it
        if (!Staffing.has(world, village, net.minecraft.village.VillagerProfession.BUTCHER) && Butchery.work(v, village, world)) return true;
        // nothing to do: he leans on the fence and watches them
        if (v.squaredDistanceTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5) > 64) Move.to(v, pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.45);
        else if (!inside.isEmpty() && world.random.nextInt(10) == 0) v.getLookControl().lookAt(inside.get(world.random.nextInt(inside.size())), 30f, 30f);
        Doing.set(v, "settlements.doing.herd.watch");
        return true;
    }

    private static boolean inPen(BlockPos p, BlockPos pen) {
        return Math.abs(p.getX() - pen.getX()) <= 3 && Math.abs(p.getZ() - pen.getZ()) <= 3;
    }

    /** A wild sheep, cow, pig or chicken near the village led into the pen (or kept near, with no pen): the herd starts so. */
    private static boolean tame(VillagerEntityMCA v, Village village, ServerWorld world, List<AnimalEntity> herd) {
        BlockPos home = village.penCenter != null ? village.penCenter : new BlockPos(village.getCenter());
        if (herd.size() >= keep(village) * 2) return false;
        AnimalEntity wild = world.getEntitiesByClass(AnimalEntity.class, new Box(home).expand(48, 12, 48),
                        a -> a.isAlive() && kind(a) && !a.isBaby() && !herd(a, village) && !a.isLeashed())
                .stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
        if (wild == null) {
            Doing.set(v, "settlements.doing.herd.none");
            return false;
        }
        Doing.set(v, "settlements.doing.herd.tame");
        if (v.squaredDistanceTo(wild) > 6) {
            Move.to(v, wild.getX(), wild.getY(), wild.getZ(), 0.55);
            return true;
        }
        wild.attachLeash(v, true);
        wild.addCommandTag(mark(village));
        Speech.say(v, "settlements.say.herd.tame");
        return true;
    }

    private static boolean shear(VillagerEntityMCA v, Village village, ServerWorld world, List<AnimalEntity> among) {
        int wool = 0;
        for (ItemStack s : village.storageBuffer) if (s.isIn(net.minecraft.registry.tag.ItemTags.WOOL)) wool += s.getCount();
        if (wool >= 64) return false;
        SheepEntity sheep = among.stream().filter(a -> a instanceof SheepEntity s && s.isShearable()).map(a -> (SheepEntity) a)
                .min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
        if (sheep == null) return false;
        if (!Gear.has(v, net.minecraft.item.ShearsItem.class)) v.getInventory().addStack(new ItemStack(Items.SHEARS)); // shears from the stores' scrap iron
        Doing.set(v, "settlements.doing.herd.shear");
        if (v.squaredDistanceTo(sheep) > 5) {
            Move.to(v, sheep.getX(), sheep.getY(), sheep.getZ(), 0.5);
            return true;
        }
        Gear.wear(v, net.minecraft.item.ShearsItem.class);
        Gear.face(v, sheep.getBlockPos());
        sheep.setSheared(true);
        int n = 1 + world.random.nextInt(3);
        Workforce.addToTreasury(village, new ItemStack(Registries.ITEM.get(new Identifier(sheep.getColor().getName() + "_wool")), n));
        world.playSoundFromEntity(null, sheep, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.NEUTRAL, 1f, 1f);
        v.swingHand(v.getDominantHand());
        Speech.say(v, "settlements.say.pen.shear");
        return true;
    }

    /** Each kind fed to breed, up to what the village keeps and two more for the butcher. */
    private static boolean breed(VillagerEntityMCA v, Village village, List<AnimalEntity> inside) {
        for (Class<? extends AnimalEntity> k : List.of(SheepEntity.class, CowEntity.class, PigEntity.class, ChickenEntity.class)) {
            List<AnimalEntity> same = inside.stream().filter(k::isInstance).toList();
            if (same.size() < 2 || same.size() >= keep(village) + 2) continue;
            List<AnimalEntity> ready = same.stream().filter(a -> !a.isBaby() && !a.isInLove() && a.getBreedingAge() == 0).toList();
            if (ready.size() < 2) continue;
            ItemStack food = null;
            for (ItemStack s : village.storageBuffer) if (s.getCount() >= 1 && ready.get(0).isBreedingItem(s)) food = s;
            if (food == null) continue;
            AnimalEntity a = ready.stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).get();
            Doing.set(v, "settlements.doing.herd.feed");
            if (v.squaredDistanceTo(a) > 5) {
                Move.to(v, a.getX(), a.getY(), a.getZ(), 0.5);
                return true;
            }
            v.setStackInHand(v.getDominantHand(), new ItemStack(food.getItem()));
            food.decrement(1);
            village.storageBuffer.removeIf(ItemStack::isEmpty);
            village.markDirty();
            a.lovePlayer(null);
            v.getLookControl().lookAt(a, 30f, 30f);
            v.swingHand(v.getDominantHand());
            Speech.say(v, "settlements.say.pen.feed");
            return true;
        }
        return false;
    }

    /** A meadow of good grass a walk from the pen, clear of the houses: chosen once and kept. */
    static BlockPos pasture(ServerWorld world, Village village) {
        BlockPos have = PASTURES.get(village.getId());
        if (have != null) return have;
        BlockPos from = village.penCenter != null ? village.penCenter : new BlockPos(village.getCenter());
        BlockPos best = null;
        int bestGrass = 30;
        for (int r = 18; r <= 42; r += 8) {
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16;
                int x = from.getX() + (int) (Math.cos(a) * r), z = from.getZ() + (int) (Math.sin(a) * r);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int grass = 0;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x + dx, 0, z + dz));
                        if (world.getBlockState(top.down()).isOf(Blocks.GRASS_BLOCK)) grass++;
                    }
                }
                BlockPos c = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                if (grass > bestGrass && !Layout.onStreet(village, x, z)) {
                    bestGrass = grass;
                    best = c;
                }
            }
            if (best != null) break;
        }
        if (best != null) PASTURES.put(village.getId(), best);
        return best;
    }

    /** The gate of the pen, if it can be found. */
    static BlockPos gate(ServerWorld world, BlockPos pen) {
        for (BlockPos p : BlockPos.iterate(pen.add(-6, -1, -6), pen.add(6, 1, 6))) {
            if (world.getBlockState(p).getBlock() instanceof FenceGateBlock) return p.toImmutable();
        }
        return null;
    }
}
