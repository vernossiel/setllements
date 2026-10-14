package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/**
 * Shepherds work like a player would: sheep found out in the open are herded into the pen (the sheep follow the
 * shepherd's lead to the gate), inside the pen they are fed wheat to breed, up to what the pen holds, and sheared for
 * wool. Nothing happens without a pen, so the village builds one early.
 */
public final class Husbandry {
    private static final int HERD_CAP = 10;
    private static final int WOOL_QUOTA = 48;
    private static final double PEN_RADIUS = 4.8;

    private Husbandry() {
    }

    private static boolean inPen(SheepEntity s, BlockPos pen) {
        return Math.abs(s.getX() - (pen.getX() + 0.5)) <= PEN_RADIUS && Math.abs(s.getZ() - (pen.getZ() + 0.5)) <= PEN_RADIUS;
    }

    /** One shepherd second. Returns true when the shepherd actually worked. */
    public static boolean work(VillagerEntityMCA v, Village village) {
        if (village == null || village.penCenter == null) return false;
        BlockPos pen = village.penCenter;
        Box area = new Box(pen).expand(46, 10, 46);
        List<SheepEntity> sheep = v.getWorld().getEntitiesByClass(SheepEntity.class, area, s -> s.isAlive() && !s.isBaby());
        if (sheep.isEmpty()) return false;
        List<SheepEntity> inside = sheep.stream().filter(s -> inPen(s, pen)).toList();
        List<SheepEntity> outside = sheep.stream().filter(s -> !inPen(s, pen)).toList();

        // 1. herd the strays into the pen
        SheepEntity stray = outside.stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
        if (stray != null && inside.size() < HERD_CAP) {
            if (v.squaredDistanceTo(stray) > 12) {
                v.getNavigation().startMovingTo(stray, 0.65);
                return false;
            }
            // lead the sheep: it follows its own path to the pen while the shepherd walks along
            stray.getNavigation().startMovingTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 1.15);
            Vec3d toPen = new Vec3d(pen.getX() + 0.5 - v.getX(), 0, pen.getZ() + 0.5 - v.getZ()).normalize();
            v.getNavigation().startMovingTo(stray.getX() + toPen.x * 2, stray.getY(), stray.getZ() + toPen.z * 2, 0.6);
            Speech.say(v, "settlements.say.pen.herd");
            return true;
        }

        // 2. inside the pen: wool first
        int wool = village.storageBuffer.stream().filter(s -> s.isIn(net.minecraft.registry.tag.ItemTags.WOOL)).mapToInt(ItemStack::getCount).sum();
        if (wool < WOOL_QUOTA) {
            SheepEntity target = inside.stream().filter(s -> !s.isSheared()).min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
            if (target != null) {
                if (v.squaredDistanceTo(target) > 6) {
                    v.getNavigation().startMovingTo(target, 0.6);
                    return false;
                }
                target.setSheared(true);
                int n = 1 + v.getWorld().random.nextInt(3);
                Identifier id = new Identifier(target.getColor().getName() + "_wool");
                Workforce.addToTreasury(village, new ItemStack(Registries.ITEM.get(id), n));
                v.getWorld().playSoundFromEntity(null, target, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.PLAYERS, 1.0f, 1.0f);
                v.swingHand(v.getDominantHand());
                Speech.say(v, "settlements.say.pen.shear");
                return true;
            }
        }

        // 3. breeding, as many as the pen holds
        if (inside.size() < HERD_CAP) {
            int wheat = village.storageBuffer.stream().filter(s -> s.isOf(Items.WHEAT)).mapToInt(ItemStack::getCount).sum();
            if (wheat >= 2) {
                List<SheepEntity> ready = inside.stream().filter(s -> !s.isInLove() && s.getBreedingAge() == 0).toList();
                for (SheepEntity a : ready) {
                    SheepEntity b = ready.stream().filter(o -> o != a && o.squaredDistanceTo(a) < 64).findFirst().orElse(null);
                    if (b == null) continue;
                    if (v.squaredDistanceTo(a) > 6) {
                        v.getNavigation().startMovingTo(a, 0.6);
                        return false;
                    }
                    int need = 2;
                    for (var it = village.storageBuffer.iterator(); it.hasNext() && need > 0; ) {
                        ItemStack s = it.next();
                        if (!s.isOf(Items.WHEAT)) continue;
                        int d = Math.min(need, s.getCount());
                        s.decrement(d);
                        need -= d;
                        if (s.isEmpty()) it.remove();
                    }
                    village.markDirty();
                    a.setLoveTicks(600);
                    b.setLoveTicks(600);
                    v.swingHand(v.getDominantHand());
                    Speech.say(v, "settlements.say.pen.feed");
                    return true;
                }
            }
        }
        // nothing to do: wait by the pen, within reach of the flock
        if (v.squaredDistanceTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5) > 100) {
            v.getNavigation().startMovingTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.6);
        }
        return false;
    }
}
