package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The churchyard: the dead get a headstone with their name, and in the evenings those who knew them come by.
 */
public final class Chapel {
    private Chapel() {
    }

    private static Direction front(Built b) {
        return Direction.fromHorizontal(b.rot() & 3); // the plan's south, turned with the building
    }

    /** A headstone with the name on the first free plot. Nothing happens without a churchyard. */
    public static void bury(ServerWorld world, Village village, String name) {
        Built chapel = Buildings.find(village, "chapel");
        if (chapel == null) return;
        Direction facing = front(chapel);
        for (int[] g : Buildings.GRAVES) {
            BlockPos stone = chapel.point(g[0], 0, g[1]);
            if (!world.isChunkLoaded(stone.getX() >> 4, stone.getZ() >> 4)) return;
            if (!world.getBlockState(stone).isAir()) continue;
            BlockPos sign = stone.offset(facing);
            world.setBlockState(stone, (Buildings.has(village, "mason") ? Blocks.STONE_BRICK_WALL : Blocks.COBBLESTONE_WALL).getDefaultState());
            BlockPos mound = sign.down();
            if (world.getBlockState(mound).isIn(net.minecraft.registry.tag.BlockTags.DIRT)) world.setBlockState(mound, Blocks.PODZOL.getDefaultState());
            if (world.getBlockState(sign).isReplaceable()) {
                world.setBlockState(sign, Blocks.SPRUCE_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, facing));
                if (world.getBlockEntity(sign) instanceof SignBlockEntity be) {
                    SignText text = new SignText().withMessage(1, Text.literal(name)).withMessage(2, Text.literal("† " + (world.getTimeOfDay() / 24000L)));
                    be.setText(text, true);
                    be.setWaxed(true);
                    be.markDirty();
                    BlockState st = world.getBlockState(sign);
                    world.updateListeners(sign, st, st, 3);
                }
            }
            return;
        }
    }

    /** Somebody died lately: in the evening a villager now and then visits the graves. True while on the visit. */
    public static boolean visit(VillagerEntityMCA v, Village village) {
        Built chapel = Buildings.find(village, "chapel");
        if (chapel == null) return false;
        boolean mourning = Life.news(village).stream().anyMatch(n -> n.key().equals("death") || n.key().equals("murder"));
        if (!mourning || (v.age / 20 + v.getUuid().hashCode()) % 7 != 0) return false;
        BlockPos gate = chapel.point(10, 0, 7);
        if (v.squaredDistanceTo(gate.getX() + 0.5, gate.getY(), gate.getZ() + 0.5) > 9) {
            Move.to(v, gate.getX() + 0.5, gate.getY(), gate.getZ() + 0.5, 0.45);
            return true;
        }
        v.getNavigation().stop();
        Speech.sayAny(v, "settlements.say.mourn", 3);
        return true;
    }
}
