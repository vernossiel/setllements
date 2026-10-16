package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The hay bales a village came with are grain put by, not ornaments: when the stores run short of wheat (for bread,
 * for the animals), the farmer with nothing growing to tend goes to the nearest bale about the village, opens it and
 * carries the sheaves - nine wheat a bale - to the stores.
 */
public final class Hay {
    private static final Map<UUID, BlockPos> TARGET = new HashMap<>();
    private static final Map<Integer, Long> NONE_UNTIL = new HashMap<>(); // village -> no bales found: not looked for again till then

    private Hay() {
    }

    /** One second of the farmer's spare time; true while he is at it. */
    public static boolean fetch(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null || Crafts.count(village, s -> s.isOf(Items.WHEAT)) >= 16) {
            TARGET.remove(v.getUuid());
            return false;
        }
        BlockPos bale = TARGET.get(v.getUuid());
        if (bale == null || !world.getBlockState(bale).isOf(Blocks.HAY_BLOCK)) {
            if (world.getTime() < NONE_UNTIL.getOrDefault(village.getId(), 0L)) return false;
            bale = nearest(world, village, v.getBlockPos());
            if (bale == null) {
                TARGET.remove(v.getUuid());
                NONE_UNTIL.put(village.getId(), world.getTime() + 20 * 120);
                return false;
            }
            TARGET.put(v.getUuid(), bale);
        }
        Doing.set(v, "settlements.doing.farm.hay");
        if (v.squaredDistanceTo(Vec3d.ofCenter(bale)) > 3 * 3) {
            Move.to(v, bale.getX() + 0.5, bale.getY(), bale.getZ() + 0.5, 0.55);
            return true;
        }
        v.getNavigation().stop();
        Gaze.at(v, bale.getX() + 0.5, bale.getY() + 0.5, bale.getZ() + 0.5);
        v.swingHand(v.getDominantHand());
        world.setBlockState(bale, Blocks.AIR.getDefaultState());
        world.playSound(null, bale, SoundEvents.BLOCK_GRASS_BREAK, SoundCategory.BLOCKS, 1f, 0.9f);
        Workforce.addToTreasury(village, new ItemStack(Items.WHEAT, 9));
        Anim.set(v, Anim.Act.CARRY, 60);
        TARGET.remove(v.getUuid());
        return true;
    }

    /** The nearest bale about the village (within forty blocks of the hall, on its level), the village's own first. */
    private static BlockPos nearest(ServerWorld world, Village village, BlockPos from) {
        BlockPos c = village.hallPos != null ? village.hallPos : new BlockPos(village.getCenter());
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(c.add(-40, -8, -40), c.add(40, 8, 40))) {
            if (!world.getBlockState(p).isOf(Blocks.HAY_BLOCK)) continue;
            if (village.playerChests.contains(p)) continue;
            double d = p.getSquaredDistance(from);
            if (d < bd) {
                bd = d;
                best = p.toImmutable();
            }
        }
        return best;
    }
}
