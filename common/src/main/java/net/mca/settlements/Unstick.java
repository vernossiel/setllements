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

    /** Blocks a player has put down (the last few thousand): not the village's work, whatever they stand in. */
    private static final java.util.LinkedHashSet<Long> PLAYER_PLACED = new java.util.LinkedHashSet<>();

    public static void placedByPlayer(BlockPos pos) {
        PLAYER_PLACED.remove(pos.asLong());
        PLAYER_PLACED.add(pos.asLong());
        if (PLAYER_PLACED.size() > 4000) {
            var it = PLAYER_PLACED.iterator();
            it.next();
            it.remove();
        }
    }

    /** Put there by a player (a block across a doorway, a cobble wall round a man): fair to break when in the way. */
    public static boolean playerPut(BlockPos pos) {
        return PLAYER_PLACED.contains(pos.asLong());
    }

    private static boolean breakable(ServerWorld world, Village village, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (state.getHardness(world, pos) < 0) return false;
        if (playerPut(pos) && !(state.getBlock() instanceof net.minecraft.block.BlockWithEntity) && state.getHardness(world, pos) <= 3.0f) return true;
        // only natural terrain and plants, never anything someone built
        boolean natural = state.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || state.isIn(net.minecraft.registry.tag.BlockTags.SAND)
                || state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || state.isIn(net.minecraft.registry.tag.BlockTags.REPLACEABLE)
                || state.isIn(net.minecraft.registry.tag.BlockTags.SNOW) || state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)
                || state.isOf(net.minecraft.block.Blocks.GRAVEL) || state.isOf(net.minecraft.block.Blocks.CLAY);
        if (!natural) return false;
        return village == null || Protection.severity(world, village, pos, state) == 0;
    }

    /** Shut in: the open ground he can walk to from where he stands is a few cells only, and no sky over it. */
    private static boolean trapped(ServerWorld world, Village village, VillagerEntityMCA v) {
        BlockPos start = v.getBlockPos();
        if (village != null && village.mineOrigin != null && start.isWithinDistance(village.mineOrigin, 48)) return false; // a miner in his gallery is not shut in
        java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty()) {
            BlockPos p = open.poll();
            if (world.isSkyVisible(p) && p.getY() >= start.getY() - 1 && !world.getBlockState(p.down()).isAir() && seen.size() > 1) {
                // under the open sky: out in the open, unless it is the bottom of a pit
                if (Math.abs(p.getX() - start.getX()) + Math.abs(p.getZ() - start.getZ()) > 3) return false;
            }
            if (seen.size() > 40) return false;
            for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos q = p.offset(d).up(dy);
                    if (seen.contains(q) || Math.abs(q.getY() - start.getY()) > 2) continue;
                    if (Move.passable(world, q) && Move.passable(world, q.up()) && !world.getBlockState(q.down()).isAir()
                            || world.getBlockState(q).getBlock() instanceof net.minecraft.block.DoorBlock) {
                        seen.add(q);
                        open.add(q);
                    }
                }
            }
        }
        return true;
    }

    /** On the scaffolding, up on the work, or within a building site of the village going up. */
    private static boolean atWork(ServerWorld world, Village village, VillagerEntityMCA v) {
        if (world.getBlockState(v.getBlockPos().down()).isOf(net.minecraft.block.Blocks.SCAFFOLDING) || Builder.upOnWork(v)) return true;
        return village != null && village.buildOrigin != null && site(village).expand(3).contains(v.getPos());
    }

    /** The box of the village's building in hand. */
    private static net.minecraft.util.math.Box site(Village village) {
        int[] fp = Blueprints.footprint(village.buildType, village.buildW, village.buildD);
        int w = (village.buildRot & 1) == 0 ? fp[0] : fp[1], d = (village.buildRot & 1) == 0 ? fp[1] : fp[0];
        BlockPos o = village.buildOrigin;
        return new net.minecraft.util.math.Box(o.getX() - 1, o.getY() - 2, o.getZ() - 1, o.getX() + w + 1, o.getY() + 24, o.getZ() + d + 1);
    }

    /** The block of the wall to break through: before him at feet or head height, not the village's, nothing hard. */
    private static BlockPos wallBlock(ServerWorld world, Village village, VillagerEntityMCA v) {
        BlockPos feet = v.getBlockPos();
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            for (int h = 0; h <= 1; h++) {
                BlockPos q = feet.offset(d).up(h);
                BlockState st = world.getBlockState(q);
                if (st.isAir() || !st.getFluidState().isEmpty() || st.getBlock() instanceof net.minecraft.block.BlockWithEntity) continue;
                float hard = st.getHardness(world, q);
                if (hard < 0 || hard > 3.0f) continue; // no obsidian, no iron
                if (village != null && village.buildOrigin != null && site(village).contains(net.minecraft.util.math.Vec3d.ofCenter(q))) continue; // the house going up
                if (playerPut(q)) return q.toImmutable(); // the cobble put across his door: out with it, whatever house it stands in
                if (village != null && Protection.severity(world, village, q, st) > 0) continue; // the village's own
                if (village != null) for (Built b : village.built) if (b.contains(q)) { st = null; break; }
                if (st == null) continue;
                return q;
            }
        }
        return null;
    }

    /**
     * Out of a pit as a player gets out: a block of earth under his feet and up onto it, a block a time, till he stands
     * at the rim (the shaft's own earth: nothing taken from the stores).
     */
    private static void climbOut(ServerWorld world, VillagerEntityMCA v) {
        BlockPos feet = v.getBlockPos();
        if (!Move.passable(world, feet.up(2))) return; // a roof over him: not a pit
        if (!Layout.land(world.getBlockState(feet.down()))) return; // the floor of a pit is the earth: not a scaffold, a floor, a roof
        v.getNavigation().stop();
        world.setBlockState(feet, net.minecraft.block.Blocks.DIRT.getDefaultState());
        world.playSound(null, feet, net.minecraft.sound.SoundEvents.BLOCK_GRAVEL_PLACE, net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 1f);
        v.refreshPositionAndAngles(v.getX(), feet.getY() + 1, v.getZ(), v.getYaw(), v.getPitch());
        v.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        v.fallDistance = 0;
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
            } else if (!Move.putSafely(v, v.getBlockPos())) { // out beside, on his own level; up through it only when there is nothing beside
                BlockPos up = head;
                for (int i = 0; i < 6 && !world.getBlockState(up).isAir(); i++) up = up.up();
                v.refreshPositionAndAngles(up.getX() + 0.5, up.getY(), up.getZ() + 0.5, v.getYaw(), 0);
            }
            return;
        }

        // under water and out of breath (a pit of water, a flooded doorway): he swims for the nearest dry footing
        if (v.isSubmergedInWater() && v.getAir() < v.getMaxAir() / 2) {
            for (BlockPos p : BlockPos.iterateOutwards(v.getBlockPos(), 5, 4, 5)) {
                if (Move.passable(world, p) && Move.passable(world, p.up()) && world.getFluidState(p).isEmpty() && world.getFluidState(p.up()).isEmpty()
                        && world.getBlockState(p.down()).isSideSolidFullSquare(world, p.down(), net.minecraft.util.math.Direction.UP)) {
                    v.getNavigation().stop();
                    v.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, v.getYaw(), v.getPitch());
                    v.setAir(v.getMaxAir());
                    return;
                }
            }
        }

        // at work on a building (up on its scaffold, inside its walls going up): that is no trap. Taken for one, he broke
        // the walls he had just laid and piled earth under himself into pillars
        if (atWork(world, village, v)) {
            v.settlementsTrappedSec = 0;
            return;
        }
        // walled in (somebody shut him in a box of his own blocks): a shout for help, then he breaks his way out
        // through what is not the village's, a crack at a time, instead of pillaring up and running round in it
        if (trapped(world, village, v)) {
            v.settlementsTrappedSec++;
            if (v.settlementsTrappedSec == 2 || v.settlementsTrappedSec % 30 == 0) Speech.sayNow(v, "settlements.say.trapped");
            Doing.set(v, "settlements.doing.trapped");
            if (v.settlementsTrappedSec >= 8 && !Dig.busy(v)) {
                BlockPos out = wallBlock(world, village, v);
                if (out != null) Dig.start(v, out);
                else if (world.isSkyVisible(v.getBlockPos().up(2)) && v.settlementsTrappedSec % 2 == 0) climbOut(world, v); // the bottom of a pit: up out of it
            }
            return;
        }
        v.settlementsTrappedSec = 0;
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
