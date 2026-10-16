package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.Chore;
import net.mca.server.world.data.Village;
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

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The butcher, who is the village's hunter and cook as well. From the herd he takes only what is born beyond what the
 * village keeps (the shepherd breeds two more of each kind for him), never the last ones; when the stores run short of
 * food he goes out after wild game - rabbits, pigs, sheep and cows not of the village. What he brings in is cooked: in
 * the tavern's smoker if there is a tavern, else roasted over the village fire.
 */
public final class Butchery {
    private static final Map<net.minecraft.item.Item, net.minecraft.item.Item> ROAST = Map.of(
            Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP, Items.MUTTON, Items.COOKED_MUTTON,
            Items.CHICKEN, Items.COOKED_CHICKEN, Items.RABBIT, Items.COOKED_RABBIT, Items.COD, Items.COOKED_COD,
            Items.SALMON, Items.COOKED_SALMON, Items.POTATO, Items.BAKED_POTATO);

    private Butchery() {
    }

    static int food(Village village) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (s.isFood() && Workforce.eatable(s)) n += s.getCount();
        return n;
    }

    static int raw(Village village) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (ROAST.containsKey(s.getItem())) n += s.getCount();
        return n;
    }

    /** One butcher second; true while at work. */
    public static boolean work(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null) return false;
        if (collect(v, village, world)) return true;
        int people = Math.max(1, village.getPopulation());
        boolean shortOfFood = food(village) < people * 6;
        // 1. the herd beyond what the village keeps
        List<AnimalEntity> herd = Herding.animals(world, village);
        for (Class<? extends AnimalEntity> k : List.of(SheepEntity.class, CowEntity.class, PigEntity.class, ChickenEntity.class)) {
            List<AnimalEntity> same = herd.stream().filter(k::isInstance).filter(a -> !a.isBaby()).toList();
            // in hunger, down to a breeding pair; otherwise only what is born beyond what the village keeps
            int limit = food(village) == 0 ? 2 : Herding.keep(village);
            if (same.size() <= limit) continue;
            AnimalEntity take = same.stream().filter(a -> !a.isInLove() && !a.isLeashed()).min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
            if (take != null) {
                Doing.set(v, "settlements.doing.butcher.herd");
                return kill(v, village, world, take);
            }
        }
        // 2. short of food: a hunt for wild game round the village
        if (shortOfFood) {
            BlockPos home = new BlockPos(village.getCenter());
            int range = food(village) == 0 ? 96 : 56; // nothing at all in the larder: he goes further out after game
            AnimalEntity game = world.getEntitiesByClass(AnimalEntity.class, new Box(home).expand(range, 24, range),
                            a -> a.isAlive() && !a.isBaby() && !Herding.herd(a, village) && !a.isLeashed()
                                    && (a instanceof RabbitEntity || Herding.kind(a)) && !Herding.inPen(a, village.penCenter))
                    .stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).orElse(null);
            if (game != null) {
                Doing.set(v, "settlements.doing.butcher.hunt");
                return kill(v, village, world, game);
            }
        }
        // 3. what was brought in is cooked
        if (village.tavern != null) {
            boolean cooked = Cooking.work(v, village);
            if (cooked) return true;
        }
        if (raw(village) > 0) return roast(v, village, world);
        if (shortOfFood) Doing.set(v, "settlements.doing.butcher.nothing");
        else Doing.set(v, "settlements.doing.butcher.idle");
        return false;
    }

    private static boolean kill(VillagerEntityMCA v, Village village, ServerWorld world, AnimalEntity prey) {
        Gear.equipTool(v, village, Chore.HUNT);
        Gear.wear(v, net.minecraft.item.SwordItem.class);
        if (v.squaredDistanceTo(prey) > 5) {
            Move.to(v, prey.getX(), prey.getY(), prey.getZ(), 0.6);
            return true;
        }
        v.getLookControl().lookAt(prey, 30f, 30f);
        v.swingHand(v.getDominantHand());
        prey.damage(world.getDamageSources().mobAttack(v), 7f);
        v.settlementsKillPos = prey.getBlockPos();
        v.settlementsKillUntil = world.getTime() + 400;
        if (!prey.isAlive()) Speech.say(v, "settlements.say.pen.slaughter");
        return true;
    }

    /** What the animal leaves goes into his bag and from there to the stores. */
    private static boolean collect(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (v.settlementsKillPos == null || world.getTime() > v.settlementsKillUntil) return false;
        List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, new Box(v.settlementsKillPos).expand(6, 3, 6), e -> e.isAlive() && e.getItemAge() > 4);
        if (drops.isEmpty()) return false;
        ItemEntity e = drops.stream().min(Comparator.comparingDouble(v::squaredDistanceTo)).get();
        Doing.set(v, "settlements.doing.butcher.collect");
        if (v.squaredDistanceTo(e) > 4) {
            Move.to(v, e.getX(), e.getY(), e.getZ(), 0.6);
            return true;
        }
        Workforce.addToTreasury(village, e.getStack().copy());
        e.discard();
        return true;
    }

    private static final Map<java.util.UUID, Integer> TURNING = new java.util.HashMap<>();

    /** Meat over the village fire (or a fire of his own, with no hearth yet): a few pieces at a time, turned till done. */
    private static boolean roast(VillagerEntityMCA v, Village village, ServerWorld world) {
        Doing.set(v, "settlements.doing.butcher.roast");
        BlockPos fire = Camp.hearth(village, world);
        if (fire != null && v.squaredDistanceTo(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5) > 9) {
            Move.to(v, fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5, 0.5);
            return true;
        }
        if (fire != null) v.getLookControl().lookAt(fire.getX() + 0.5, fire.getY() + 0.5, fire.getZ() + 0.5);
        v.getNavigation().stop();
        int t = TURNING.merge(v.getUuid(), 1, Integer::sum);
        if (t % 4 == 0) v.swingHand(v.getDominantHand());
        if (t < 10) return true; // ten seconds a batch
        TURNING.remove(v.getUuid());
        ItemStack cooked = ItemStack.EMPTY;
        for (var it = village.storageBuffer.iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            var done = ROAST.get(s.getItem());
            if (done == null) continue;
            int n = Math.min(4, s.getCount());
            s.decrement(n);
            if (s.isEmpty()) it.remove();
            cooked = new ItemStack(done, n);
            break;
        }
        if (!cooked.isEmpty()) Workforce.addToTreasury(village, cooked);
        village.markDirty();
        Speech.say(v, "settlements.say.cook.ready");
        return true;
    }
}
