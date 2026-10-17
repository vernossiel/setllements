package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.relationship.Gender;
import net.mca.resources.Names;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

/**
 * A prosperous village sends settlers to found a new one. They get a starter house built on the spot (paid from the
 * parent's treasury) and a few supplies; the normal village detection picks the house up and the new village takes over.
 */
public final class Expansion {
    private static final int MIN_DIST = 90, MAX_DIST = 150;
    private static final int MAX_VILLAGES = 8;

    private Expansion() {
    }

    private static final java.util.Map<Integer, long[]> HEMMED = new java.util.HashMap<>(); // village -> {first day without room, last day}
    private static final java.util.Map<Integer, Long> FOUNDED = new java.util.HashMap<>(); // village -> day it last sent settlers

    /** The builder found no plot for a home today: the village is hemmed in by its land. */
    public static void noRoom(Village v, ServerWorld world) {
        long day = world.getTimeOfDay() / 24000L;
        long[] h = HEMMED.get(v.getId());
        if (h == null || day - h[1] > 1) HEMMED.put(v.getId(), new long[]{day, day});
        else h[1] = day;
    }

    /** Days on end with no room to build a home (a village on a ravine's lip, a mountainside). */
    static int hemmedDays(Village v, long day) {
        long[] h = HEMMED.get(v.getId());
        return h == null || day - h[1] > 1 ? 0 : (int) (h[1] - h[0] + 1);
    }

    /**
     * Once per day. A village sends settlers out to found a hamlet of their own: a prosperous one when it can spare
     * them, and one hemmed in by its land (no room for another home three days running) when it has people enough -
     * there the village stays what it is, a hill village, and its young go down to level ground.
     */
    public static void daily(ServerWorld world) {
        VillageManager vm = VillageManager.get(world);
        int count = 0;
        for (Village ignored : vm) count++;
        if (count >= MAX_VILLAGES) return;
        long day = world.getTimeOfDay() / 24000L;
        for (Village v : vm) {
            if (day - FOUNDED.getOrDefault(v.getId(), -100L) < 10) continue; // one going out at a time
            boolean hemmed = hemmedDays(v, day) >= 3 && v.getPopulation() >= 6 && !Diplomacy.starving(v);
            if (hemmed) {
                if (found(world, vm, v, true)) {
                    FOUNDED.put(v.getId(), day);
                    return;
                }
                continue;
            }
            if (world.random.nextFloat() > 0.2f) continue;
            if (!v.storageBuilt || v.houses.size() < 3 || v.getPopulation() < 8) continue;
            int planks = 0, wool = 0, food = 0;
            for (ItemStack s : v.storageBuffer) {
                if (s.isIn(ItemTags.PLANKS)) planks += s.getCount();
                else if (s.isIn(ItemTags.LOGS)) planks += 4 * s.getCount();
                else if (s.isIn(ItemTags.WOOL)) wool += s.getCount();
                else if (s.isFood()) food += s.getCount();
            }
            if (planks < 200 || wool < 3 || food < 40) continue;
            if (found(world, vm, v, false)) { // one founding per day
                FOUNDED.put(v.getId(), day);
                return;
            }
        }
    }

    /** Two grown villagers of the parent who can be spared (not the elder, a guard, or the only builder or farmer), unseen. */
    private static java.util.List<VillagerEntityMCA> settlers(ServerWorld world, Village parent) {
        java.util.List<VillagerEntityMCA> folk = parent.getResidents(world);
        java.util.List<VillagerEntityMCA> out = new java.util.ArrayList<>();
        for (VillagerEntityMCA r : folk) {
            if (r.isBaby() || r.isElder() || r.isGuard() || !r.isAlive() || r.isSleeping() || !Bed.unseen(r, world)) continue;
            var p = r.getProfession();
            if ((p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.FARMER) && folk.stream().filter(o -> o.getProfession() == p).count() <= 1) continue;
            out.add(r);
            // a married couple go together
            r.getRelationships().getPartnerUUID().map(world::getEntity).filter(e -> e instanceof VillagerEntityMCA && !out.contains(e))
                    .ifPresent(e -> out.add((VillagerEntityMCA) e));
            if (out.size() >= 2) break;
        }
        return out.size() >= 2 ? out.subList(0, 2) : java.util.List.of();
    }

    private static boolean found(ServerWorld world, VillageManager vm, Village parent, boolean own) {
        java.util.List<VillagerEntityMCA> going = own ? settlers(world, parent) : java.util.List.of();
        if (own && going.isEmpty()) return false; // nobody to send unseen today
        BlockPos c = new BlockPos(parent.getCenter());
        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2;
            int dist = MIN_DIST + world.random.nextInt(MAX_DIST - MIN_DIST);
            int x = c.getX() + (int) (Math.cos(angle) * dist), z = c.getZ() + (int) (Math.sin(angle) * dist);
            BlockPos origin = flatSite(world, x, z, 6, 7);
            if (origin == null) continue;
            boolean crowded = false;
            for (Village other : vm) {
                if (Math.hypot(other.getCenter().getX() - origin.getX(), other.getCenter().getZ() - origin.getZ()) < MIN_DIST * 0.8) crowded = true;
            }
            if (crowded) continue;

            if (!Builder.buildInstantly(world, parent, origin, Blueprints.house(7, 7, 1))) continue;

            if (own) { // the parent's own people move out: gone from it, at the new house by the time anyone looks
                for (int i = 0; i < going.size(); i++) {
                    VillagerEntityMCA s = going.get(i);
                    s.getResidency().leaveHome();
                    s.getBrain().forget(net.minecraft.entity.ai.brain.MemoryModuleType.HOME);
                    s.setProfession(i == 0 ? VillagerProfession.CARTOGRAPHER : VillagerProfession.FARMER);
                    s.getInventory().addStack(new ItemStack(Items.BREAD, 12));
                    s.requestTeleport(origin.getX() + 3 + i, origin.getY() + 1, origin.getZ() + 8);
                }
                Life.news(parent, "settlers", going.get(0).getName().getString(), going.get(1).getName().getString());
                vm.reportBuilding(origin.add(1, 1, 2));
                Diplomacy.announceNear(world, parent, Text.translatable("settlements.expansion", parent.getName()));
                return true;
            }
            for (int i = 0; i < 2; i++) {
                Gender g = i == 0 ? Gender.MALE : Gender.FEMALE;
                VillagerEntityMCA settler = g.getVillagerType().spawn(world, origin.add(3, 1, 8), SpawnReason.EVENT);
                if (settler == null) continue;
                settler.setCustomName(Text.literal(Names.pickCitizenName(g)));
                settler.setProfession(i == 0 ? VillagerProfession.CARTOGRAPHER : VillagerProfession.FARMER);
                settler.getInventory().addStack(new ItemStack(Items.OAK_PLANKS, 64));
                settler.getInventory().addStack(new ItemStack(Items.BREAD, 12));
            }
            vm.reportBuilding(origin.add(1, 1, 2));
            Diplomacy.announceNear(world, parent, Text.translatable("settlements.expansion", parent.getName()));
            return true;
        }
        return false;
    }

    private static BlockPos flatSite(ServerWorld world, int x, int z, int w, int d) {
        world.getChunk(x >> 4, z >> 4); // make sure the chunk exists
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int dx = -1; dx <= w; dx++) {
            for (int dz = -1; dz <= d; dz++) {
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                BlockPos g = new BlockPos(x + dx, y - 1, z + dz);
                BlockState ground = world.getBlockState(g);
                if (!ground.isSolidBlock(world, g) || !ground.getFluidState().isEmpty()) return null;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        return maxY - minY <= 1 ? new BlockPos(x, maxY, z) : null;
    }
}
