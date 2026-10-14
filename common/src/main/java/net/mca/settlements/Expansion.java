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

    /** Once per day. */
    public static void daily(ServerWorld world) {
        VillageManager vm = VillageManager.get(world);
        int count = 0;
        for (Village ignored : vm) count++;
        if (count >= MAX_VILLAGES || world.random.nextFloat() > 0.2f) return;
        for (Village v : vm) {
            if (!v.storageBuilt || v.houses.size() < 3 || v.getPopulation() < 8) continue;
            int planks = 0, wool = 0, food = 0;
            for (ItemStack s : v.storageBuffer) {
                if (s.isIn(ItemTags.PLANKS)) planks += s.getCount();
                else if (s.isIn(ItemTags.LOGS)) planks += 4 * s.getCount();
                else if (s.isIn(ItemTags.WOOL)) wool += s.getCount();
                else if (s.isFood()) food += s.getCount();
            }
            if (planks < 200 || wool < 3 || food < 40) continue;
            if (found(world, vm, v)) return; // one founding per day
        }
    }

    private static boolean found(ServerWorld world, VillageManager vm, Village parent) {
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

            if (!Builder.buildInstantly(world, parent, origin, Blueprints.house(6, 7, 1))) continue;

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
