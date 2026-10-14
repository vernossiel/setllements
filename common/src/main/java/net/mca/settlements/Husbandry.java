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
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.List;

/** Shepherds shear sheep for wool (needed for beds) and breed them with wheat from the treasury, up to a herd cap. */
public final class Husbandry {
    private static final int HERD_CAP = 10;
    private static final int WOOL_QUOTA = 48;

    private Husbandry() {
    }

    /** One shepherd second. Returns true when the shepherd actually worked. */
    public static boolean work(VillagerEntityMCA v, Village village) {
        if (village == null) return false;
        Box area = v.getBoundingBox().expand(28, 8, 28);
        List<SheepEntity> sheep = v.getWorld().getEntitiesByClass(SheepEntity.class, area, s -> s.isAlive() && !s.isBaby());
        if (sheep.isEmpty()) return false;

        int wool = village.storageBuffer.stream().filter(s -> s.isIn(net.minecraft.registry.tag.ItemTags.WOOL)).mapToInt(ItemStack::getCount).sum();
        if (wool < WOOL_QUOTA) {
            SheepEntity target = sheep.stream().filter(s -> !s.isSheared()).min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
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
                return true;
            }
        }

        if (sheep.size() < HERD_CAP) {
            int wheat = village.storageBuffer.stream().filter(s -> s.isOf(Items.WHEAT)).mapToInt(ItemStack::getCount).sum();
            if (wheat >= 2) {
                List<SheepEntity> ready = sheep.stream().filter(s -> !s.isInLove() && s.getBreedingAge() == 0).toList();
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
                    return true;
                }
            }
        }
        return false;
    }
}
