package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Villagers get around like a player would. Every errand goes through {@link #to}, which remembers where the villager
 * wants to be. When the normal path finder gives up or the villager stops making progress, he works his way there
 * by hand, one block a second: breaks natural blocks in the way, bridges gaps, builds a pillar up a cliff or digs
 * down to a lower goal, using blocks from his pockets or the village stores. Buildings are never broken through.
 */
public final class Move {
    private static final int GOAL_FRESH = 60;   // ticks an errand stays "wanted" after the last request
    private static final int HAND_AFTER = 3;    // seconds without progress before working by hand

    private Move() {
    }

    public static boolean to(VillagerEntityMCA v, double x, double y, double z, double speed) {
        remember(v, BlockPos.ofFloored(x, y, z));
        return v.getNavigation().startMovingTo(x, y, z, speed);
    }

    public static boolean to(VillagerEntityMCA v, Entity e, double speed) {
        remember(v, e.getBlockPos());
        return v.getNavigation().startMovingTo(e, speed);
    }

    private static void remember(VillagerEntityMCA v, BlockPos goal) {
        v.settlementsGoal = goal;
        v.settlementsGoalAt = v.getWorld().getTime();
    }

    /** The errand the villager is currently on, if any. */
    static BlockPos goal(VillagerEntityMCA v) {
        if (v.settlementsGoal == null || v.getWorld().getTime() - v.settlementsGoalAt > GOAL_FRESH) return null;
        return v.settlementsGoal;
    }

    static boolean passable(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        return s.getCollisionShape(world, p).isEmpty() && !s.isIn(BlockTags.FIRE) && !s.isOf(Blocks.LAVA) && !s.isOf(Blocks.POWDER_SNOW);
    }

    /** Natural terrain and plants that anybody may dig away; never something somebody built. */
    static boolean diggable(ServerWorld world, Village village, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getHardness(world, pos) < 0) return false;
        boolean natural = state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.SAND) || state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.REPLACEABLE) || state.isIn(BlockTags.SNOW) || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.CLAY) || state.isOf(Blocks.COBBLESTONE) && village != null && village.hallPos != null && !village.hallPos.isWithinDistance(pos, 12);
        if (!natural) return false;
        return village == null || Protection.severity(world, village, pos, state) == 0;
    }

    /** Breaks the block as a player would: the drops go into the villager's pockets. */
    static void dig(VillagerEntityMCA v, ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        for (ItemStack drop : Block.getDroppedStacks(s, world, p, world.getBlockEntity(p))) v.getInventory().addStack(drop);
        world.breakBlock(p, false, v);
        v.getLookControl().lookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        v.swingHand(v.getDominantHand());
    }

    /** Places a cheap block from the villager's pockets or the village stores. */
    static boolean place(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos p) {
        if (!passable(world, p) || !world.getBlockState(p).isReplaceable() && !world.getBlockState(p).isAir()) return false;
        BlockState state = null;
        var inv = v.getInventory();
        for (int i = 0; i < inv.size() && state == null; i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() instanceof BlockItem bi && (Scavenge.isFiller(s) || s.isIn(ItemTags.PLANKS))) {
                state = bi.getBlock().getDefaultState();
                s.decrement(1);
            }
        }
        if (state == null && village != null) {
            for (ItemStack s : village.storageBuffer) {
                if (Scavenge.isFiller(s)) {
                    state = Block.getBlockFromItem(s.getItem()).getDefaultState();
                    s.decrement(1);
                    break;
                }
            }
            if (state != null) village.storageBuffer.removeIf(ItemStack::isEmpty);
            else if (Builder.takeItem(village, s -> s.isIn(ItemTags.PLANKS), 1)) state = Blocks.OAK_PLANKS.getDefaultState();
        }
        if (state == null) return false;
        world.setBlockState(p, state);
        world.playSound(null, p, state.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 1.0f);
        v.swingHand(v.getDominantHand());
        if (village != null) village.markDirty();
        return true;
    }

    /** Once per second: true if the villager worked his way by hand this second. */
    public static boolean tick(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world) || v.hasVehicle() || v.isSleeping() || v.climbPhase != 0) return false;
        BlockPos goal = goal(v);
        Vec3d pos = v.getPos();
        if (goal == null) {
            v.settlementsHandSec = 0;
            return false;
        }
        double hx = goal.getX() + 0.5 - pos.x, hz = goal.getZ() + 0.5 - pos.z;
        double horiz = Math.hypot(hx, hz);
        int dy = goal.getY() - v.getBlockPos().getY();
        boolean there = horiz < 2.5 && Math.abs(dy) <= 1;
        boolean progressing = v.settlementsHandLast != null && pos.squaredDistanceTo(v.settlementsHandLast) > 0.5;
        v.settlementsHandLast = pos;
        if (there || progressing && v.getNavigation().isFollowingPath()) {
            v.settlementsHandSec = 0;
            return false;
        }
        if (++v.settlementsHandSec < HAND_AFTER) return false;

        v.getNavigation().stop();
        BlockPos feet = v.getBlockPos();

        // right above or below the goal: build up or dig down
        if (horiz < 1.5) {
            if (dy > 1 && dy <= 12) return pillar(v, village, world, feet);
            if (dy < -1) {
                BlockPos down = feet.down();
                if (diggable(world, village, down)) {
                    dig(v, world, down);
                    return true;
                }
                return false;
            }
        }

        Direction dir = Math.abs(hx) >= Math.abs(hz) ? (hx > 0 ? Direction.EAST : Direction.WEST) : (hz > 0 ? Direction.SOUTH : Direction.NORTH);
        if (v.settlementsSidestep > 0) { // the straight way was walled off: go round
            dir = dir.rotateYClockwise();
            v.settlementsSidestep--;
        }
        BlockPos ahead = feet.offset(dir);
        boolean feetFree = passable(world, ahead), headFree = passable(world, ahead.up());

        if (!feetFree || !headFree) {
            // a one block step up: hop on it
            if (!feetFree && headFree && passable(world, ahead.up(2)) && passable(world, feet.up(2))) {
                step(v, ahead.up());
                return true;
            }
            boolean dug = false;
            for (BlockPos b : new BlockPos[]{ahead.up(), ahead}) {
                if (!passable(world, b) && diggable(world, village, b)) {
                    dig(v, world, b);
                    dug = true;
                    break; // one block a second, like a player with a bad pickaxe
                }
            }
            if (dug) return true;
            if (dy > 0 && dy <= 12) return pillar(v, village, world, feet); // a wall nobody may break: climb over it
            v.settlementsSidestep = 3;
            return false;
        }

        BlockPos below = ahead.down();
        if (passable(world, below)) {
            int drop = 1;
            while (drop < 5 && passable(world, below.down(drop - 1).down())) drop++;
            boolean water = !world.getFluidState(below).isEmpty();
            if (!water && (drop > 3 || dy >= 0)) {
                // a hole or a ravine on the way: bridge it
                if (place(v, village, world, below)) {
                    step(v, ahead);
                    return true;
                }
                if (drop > 3) {
                    v.settlementsSidestep = 3;
                    return false;
                }
            }
        }
        step(v, ahead);
        return true;
    }

    private static boolean pillar(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos feet) {
        if (!passable(world, feet.up(2))) {
            if (diggable(world, village, feet.up(2))) {
                dig(v, world, feet.up(2));
                return true;
            }
            return false;
        }
        v.refreshPositionAndAngles(v.getX(), feet.getY() + 1.05, v.getZ(), v.getYaw(), v.getPitch());
        if (place(v, village, world, feet)) return true;
        v.refreshPositionAndAngles(v.getX(), feet.getY(), v.getZ(), v.getYaw(), v.getPitch()); // nothing to stand on
        return false;
    }

    private static void step(VillagerEntityMCA v, BlockPos cell) {
        if (cell.getY() > v.getBlockPos().getY()) v.getJumpControl().setActive();
        v.getMoveControl().moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0.6);
    }
}
