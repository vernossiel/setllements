package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Villagers that cannot get anywhere clear the way themselves: a block that stands in their path is broken (unless it
 * belongs to the village's own buildings), and if that does not help they are put down next to where they were going.
 * A villager stuck inside a block is lifted out of it.
 */
public final class Unstick {
    private static final int CLEAR_AFTER = 4;     // seconds without progress before breaking blocks
    private static final int TELEPORT_AFTER = 14; // seconds before giving up on walking

    private Unstick() {
    }

    private static boolean breakable(ServerWorld world, Village village, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (state.getHardness(world, pos) < 0) return false;
        // only natural terrain and plants, never anything someone built
        boolean natural = state.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || state.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                || state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || state.isIn(net.minecraft.registry.tag.BlockTags.REPLACEABLE)
                || state.isIn(net.minecraft.registry.tag.BlockTags.SNOW) || state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)
                || state.isOf(net.minecraft.block.Blocks.GRAVEL) || state.isOf(net.minecraft.block.Blocks.CLAY);
        if (!natural) return false;
        return village == null || Protection.severity(world, village, pos, state) == 0;
    }

    private static Optional<BlockPos> target(VillagerEntityMCA v) {
        Optional<BlockPos> walk = v.getBrain().getOptionalMemory(MemoryModuleType.WALK_TARGET).map(w -> w.getLookTarget().getBlockPos());
        if (walk.isPresent()) return walk;
        return Optional.ofNullable(v.getNavigation().getTargetPos());
    }

    /** Once per second. */
    public static void tick(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world) || v.hasVehicle() || v.isSleeping() || v.climbPhase != 0 || v.settlementsEatTicks > 0) return;

        // inside a block: lift out of it
        BlockPos head = BlockPos.ofFloored(v.getX(), v.getEyeY(), v.getZ());
        if (world.getBlockState(head).shouldSuffocate(world, head)) {
            if (breakable(world, village, head)) {
                world.breakBlock(head, false, v);
            } else {
                BlockPos up = head;
                for (int i = 0; i < 6 && !world.getBlockState(up).isAir(); i++) up = up.up();
                v.refreshPositionAndAngles(up.getX() + 0.5, up.getY(), up.getZ() + 0.5, v.getYaw(), 0);
            }
            return;
        }

        if (Move.goal(v) != null) return; // errands are walked by Move, which works its way by hand
        Optional<BlockPos> goal = target(v);
        Vec3d pos = v.getPos();
        boolean wants = goal.isPresent() && goal.get().getSquaredDistance(v.getBlockPos()) > 6 && v.getNavigation().isFollowingPath();
        if (!wants) {
            v.settlementsStuckSec = 0;
            v.settlementsLastPos = pos;
            return;
        }
        if (v.settlementsLastPos != null && pos.squaredDistanceTo(v.settlementsLastPos) > 0.6) {
            v.settlementsStuckSec = 0;
            v.settlementsLastPos = pos;
            return;
        }
        if (v.settlementsLastPos == null) v.settlementsLastPos = pos;
        v.settlementsStuckSec++;

        BlockPos g = goal.get();
        if (v.settlementsStuckSec >= CLEAR_AFTER) {
            double dx = g.getX() + 0.5 - pos.x, dz = g.getZ() + 0.5 - pos.z;
            double len = Math.max(0.001, Math.hypot(dx, dz));
            int sx = (int) Math.round(dx / len), sz = (int) Math.round(dz / len);
            BlockPos feet = v.getBlockPos();
            for (int h = 0; h < 3; h++) {
                BlockPos ahead = feet.add(sx, h, sz);
                if (breakable(world, village, ahead) && !Dig.busy(v)) {
                    Dig.start(v, ahead); // a crack at a time, not by magic
                    break;
                }
            }
        }
        if (v.settlementsStuckSec >= TELEPORT_AFTER) {
            // still nowhere: he stops trusting the ordinary path finder and works out the way himself (Move -> Route),
            // which only as the very last resort sets him down near the goal
            v.settlementsStuckSec = 0;
            Move.to(v, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 0.5);
        }
    }
}
