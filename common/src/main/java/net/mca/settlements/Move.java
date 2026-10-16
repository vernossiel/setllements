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

    /** An ordinary walking pace for every errand: nobody races about the village. */
    private static final double PACE = 0.6;

    private static final java.util.Map<java.util.UUID, long[]> TRYING = new java.util.HashMap<>(); // villager -> {goal, since}

    public static boolean to(VillagerEntityMCA v, double x, double y, double z, double speed) {
        speed = Math.min(speed, PACE) * Gaze.pace(v); // the tired drag their feet, the keen step out
        BlockPos goal = BlockPos.ofFloored(x, y, z);
        remember(v, goal);
        // the same spot asked for a dozen seconds and never reached, though he is close: the spot cannot be had (in a
        // wall, behind a house, taken) - he stands where he is, instead of running round and round it
        long now = v.getWorld().getTime();
        long[] tr = TRYING.get(v.getUuid());
        if (tr == null || BlockPos.fromLong(tr[0]).getSquaredDistance(goal) > 4) TRYING.put(v.getUuid(), new long[]{goal.asLong(), now});
        else if (now - tr[1] > 20 * 12 && v.squaredDistanceTo(x, y, z) < 8 * 8
                && (v.getNavigation().getCurrentPath() == null || !v.getNavigation().getCurrentPath().reachesTarget())) { // (a way that gets there, he keeps walking)
            v.getNavigation().stop();
            return true;
        }
        // already on his way there: he keeps walking the path he has, not stopping each second to work out a new one
        var nav = v.getNavigation();
        var path = nav.getCurrentPath();
        if (path != null && !path.isFinished() && nav.isFollowingPath() && path.getTarget().isWithinDistance(goal, 1.5)) return true;
        return nav.startMovingTo(x, y, z, speed);
    }

    public static boolean to(VillagerEntityMCA v, Entity e, double speed) {
        speed = Math.min(speed, PACE) * Gaze.pace(v);
        remember(v, e.getBlockPos());
        return v.getNavigation().startMovingTo(e, speed);
    }

    /**
     * Every tick, before the brain thinks: while he is on an errand of ours (asked for this last second), the old MCA
     * tasks (wandering, going into a building, back to the meeting place) do not steer him somewhere else. Two minds
     * pulling him two ways is what had him stepping to a door and back, or shuffling to and fro on a field.
     */
    public static void hold(VillagerEntityMCA v) {
        if (v.settlementsGoal == null || v.getWorld().getTime() - v.settlementsGoalAt > 25) return;
        var walk = v.getBrain().getOptionalMemory(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET).orElse(null);
        if (walk != null && walk.getLookTarget().getBlockPos().getSquaredDistance(v.settlementsGoal) > 3 * 3)
            v.getBrain().forget(net.minecraft.entity.ai.brain.MemoryModuleType.WALK_TARGET);
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
                || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.CLAY);
        if (!natural) return false;
        for (Direction d : Direction.values()) if (d != Direction.DOWN && !world.getFluidState(pos.offset(d)).isEmpty()) return false; // would let water in
        return village == null || Protection.severity(world, village, pos, state) == 0 && !Protection.insideStructure(village, pos);
    }

    /** Gravel and sand above a block about to be dug come down first, so nobody is buried. */
    static void clearFalling(ServerWorld world, BlockPos p, java.util.function.Consumer<ItemStack> drops) {
        java.util.List<BlockPos> column = new java.util.ArrayList<>();
        BlockPos q = p.up();
        while (column.size() < 8 && world.getBlockState(q).getBlock() instanceof net.minecraft.block.FallingBlock) {
            column.add(q);
            q = q.up();
        }
        for (int i = column.size() - 1; i >= 0; i--) {
            BlockPos c = column.get(i);
            for (ItemStack drop : Block.getDroppedStacks(world.getBlockState(c), world, c, null)) drops.accept(drop);
            world.breakBlock(c, false);
        }
    }

    /** Breaks the block as a player would: the drops go into the villager's pockets. */
    static void dig(VillagerEntityMCA v, ServerWorld world, BlockPos p) {
        clearFalling(world, p, d -> v.getInventory().addStack(d));
        BlockState s = world.getBlockState(p);
        for (ItemStack drop : Block.getDroppedStacks(s, world, p, world.getBlockEntity(p))) v.getInventory().addStack(drop);
        world.breakBlock(p, false, v);
        Gaze.at(v, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
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

    /** Doors and gates count as open: villagers use them. */
    private static boolean walkable(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        return passable(world, p) || s.getBlock() instanceof net.minecraft.block.DoorBlock || s.getBlock() instanceof net.minecraft.block.FenceGateBlock;
    }

    private static boolean standable(ServerWorld world, BlockPos p) {
        if (!walkable(world, p) || !walkable(world, p.up())) return false;
        return !world.getFluidState(p).isEmpty() || !passable(world, p.down());
    }

    /**
     * Fallen into a pit, a hole or a basin with no way out on foot: a short walk search finds no cell six blocks away.
     */
    static boolean trapped(ServerWorld world, BlockPos start) {
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < 500) {
            BlockPos p = queue.poll();
            if (Math.abs(p.getX() - start.getX()) + Math.abs(p.getZ() - start.getZ()) >= 6) return false;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos n = p.offset(dir);
                BlockPos next = null;
                if (standable(world, n)) next = n;
                else if (standable(world, n.up()) && walkable(world, p.up(2))) next = n.up(); // a one block step
                else if (walkable(world, n) && walkable(world, n.up())) {
                    for (int d = 1; d <= 3; d++) {
                        if (standable(world, n.down(d))) {
                            next = n.down(d);
                            break;
                        }
                        if (!walkable(world, n.down(d))) break;
                    }
                }
                if (next != null && seen.add(next)) queue.add(next);
            }
        }
        return seen.size() < 500;
    }

    /** The level the villager has to climb to: the highest ground around the pit, plus one to stand on it. */
    private static int rim(ServerWorld world, BlockPos feet) {
        int best = feet.getY();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos n = feet.offset(dir);
            int y = feet.getY();
            while (y < feet.getY() + 24 && !(walkable(world, new BlockPos(n.getX(), y, n.getZ())) && walkable(world, new BlockPos(n.getX(), y + 1, n.getZ())))) y++;
            best = Math.max(best, y);
        }
        return best;
    }

    /** Once per second: true if the villager worked his way by hand this second. */
    public static boolean tick(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world) || v.hasVehicle() || v.isSleeping() || v.climbPhase != 0) return false;
        BlockPos goal = goal(v);
        // stuck in a pit with nowhere to go: climb out like a player, building up under himself
        if (goal == null && (v.age / 20 + v.getId()) % 3 == 0 && (v.getNavigation().isIdle() || v.getVelocity().horizontalLengthSquared() < 0.001)
                && !(v.isGuard() && village != null && village.watchPoints.stream().anyMatch(w -> w.isWithinDistance(v.getBlockPos(), 4)))) {
            BlockPos feet = v.getBlockPos();
            if (trapped(world, feet)) {
                int top = rim(world, feet);
                if (top > feet.getY()) {
                    v.settlementsGoal = new BlockPos(feet.getX(), top + 1, feet.getZ());
                    v.settlementsGoalAt = world.getTime() + 200; // stays wanted while he climbs
                    v.settlementsHandSec = HAND_AFTER;
                    v.settlementsEscaping = true;
                    Speech.say(v, "settlements.react.trapped");
                    goal = v.settlementsGoal;
                }
            }
        }
        Vec3d pos = v.getPos();
        if (goal == null) {
            v.settlementsHandSec = 0;
            v.settlementsEscaping = false;
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
            if (there) Route.forget(v);
            return false;
        }
        if (++v.settlementsHandSec < HAND_AFTER) return false;
        // the ordinary path finder got him nowhere: he works out the way himself (see Route); the old straight-line
        // shoving below is only for climbing out of a pit
        if (!v.settlementsEscaping && Route.tick(v, village, world, goal)) return true;

        v.getNavigation().stop();
        BlockPos feet = v.getBlockPos();

        // right above or below the goal: build up or dig down, but only to get out of a pit; errands never tunnel or tower
        if (horiz < 1.5 && v.settlementsEscaping) {
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

        if (!feetFree && opens(world, ahead)) { // a gate or a door: open it, go through, shut it behind (see shutBehind)
            open(v, world, ahead);
            step(v, ahead);
            return true;
        }
        if (!feetFree || !headFree) {
            // a one block step up: hop on it
            if (!feetFree && headFree && passable(world, ahead.up(2)) && passable(world, feet.up(2))) {
                step(v, ahead.up());
                return true;
            }
            boolean dug = false;
            // only a thin obstacle near the surface is dug through: never a tunnel into a hill
            int surface = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ahead.getX(), ahead.getZ());
            BlockPos beyond = ahead.offset(dir);
            boolean thin = passable(world, beyond) && passable(world, beyond.up()) || passable(world, beyond.offset(dir)) && passable(world, beyond.offset(dir).up());
            boolean tunnel = !v.settlementsEscaping && (surface > feet.getY() + 3 || !thin);
            if (tunnel) {
                if (v.settlementsHandSec > HAND_AFTER + 30) {
                    // no way through on foot after half a minute: he finds his own way round (put down by the goal)
                    if (!putSafely(v, goal)) {
                        int gy = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, goal.getX(), goal.getZ());
                        putSafely(v, new BlockPos(goal.getX(), gy, goal.getZ()));
                    }
                    v.settlementsHandSec = 0;
                    return true;
                }
                v.settlementsSidestep = 3;
                return false;
            }
            for (BlockPos b : new BlockPos[]{ahead.up(), ahead}) {
                if (!passable(world, b) && diggable(world, village, b)) {
                    dig(v, world, b);
                    dug = true;
                    break; // one block a second, like a player with a bad pickaxe
                }
            }
            if (dug) return true;
            if (v.settlementsEscaping && dy > 0 && dy <= 12) return pillar(v, village, world, feet); // out of the pit
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

    /** Blocks put under his feet to climb out of somewhere: taken down again once nobody needs them. */
    private record Scaffold(BlockPos pos, long at, int village) {
    }

    private static final java.util.List<Scaffold> SCAFFOLD = new java.util.ArrayList<>();

    /** Once a second per world: a climbing column left standing a minute, with nobody on it, comes down; the block back to the stores. */
    public static void clearScaffold(ServerWorld world) {
        if (SCAFFOLD.isEmpty()) return;
        long now = world.getTime();
        for (java.util.Iterator<Scaffold> it = SCAFFOLD.iterator(); it.hasNext(); ) {
            Scaffold sc = it.next();
            if (now - sc.at() < 20 * 60) continue;
            BlockPos p = sc.pos();
            if (!world.isChunkLoaded(p)) continue;
            if (!world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, new net.minecraft.util.math.Box(p.up()).expand(0.3, 1, 0.3), e -> true).isEmpty()) continue;
            it.remove();
            BlockState st = world.getBlockState(p);
            if (st.isAir()) continue;
            world.setBlockState(p, Blocks.AIR.getDefaultState());
            Village vil = net.mca.server.world.data.VillageManager.get(world).getOrEmpty(sc.village()).orElse(null);
            if (vil != null && st.getBlock().asItem() != net.minecraft.item.Items.AIR) Workforce.addToTreasury(vil, new ItemStack(st.getBlock().asItem()));
        }
    }

    private static boolean pillar(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos feet) {
        // in his own mine he takes the stairs, he does not build himself a column out of it
        if (village != null && village.mineOrigin != null && Mining.inShaft(v, village.mineOrigin) && Mining.shaftWalk(v, village.mineOrigin, true)) return true;
        if (!passable(world, feet.up(2))) {
            if (diggable(world, village, feet.up(2))) {
                dig(v, world, feet.up(2));
                return true;
            }
            return false;
        }
        v.refreshPositionAndAngles(v.getX(), feet.getY() + 1.05, v.getZ(), v.getYaw(), v.getPitch());
        if (place(v, village, world, feet)) {
            if (village != null) SCAFFOLD.add(new Scaffold(feet.toImmutable(), world.getTime(), village.getId()));
            return true;
        }
        v.refreshPositionAndAngles(v.getX(), feet.getY(), v.getZ(), v.getYaw(), v.getPitch()); // nothing to stand on
        return false;
    }

    private static void step(VillagerEntityMCA v, BlockPos cell) {
        if (cell.getY() > v.getBlockPos().getY()) v.getJumpControl().setActive();
        v.getMoveControl().moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, PACE);
    }

    private static final java.util.Map<java.util.UUID, Integer> PRESSED = new java.util.HashMap<>();

    /** A step back from {@code from}, a little to the side. */
    public static void stepBack(VillagerEntityMCA v, net.minecraft.entity.Entity from) {
        double ax = v.getX() - from.getX(), az = v.getZ() - from.getZ(), len = Math.sqrt(ax * ax + az * az);
        if (len < 0.05) {
            double a = v.getRandom().nextDouble() * Math.PI * 2;
            ax = Math.cos(a);
            az = Math.sin(a);
            len = 1;
        }
        ax /= len;
        az /= len;
        double side = (v.getId() & 1) == 0 ? 0.8 : -0.8;
        v.getNavigation().startMovingTo(v.getX() + ax * 1.6 - az * side, v.getY(), v.getZ() + az * 1.6 + ax * side, 0.55);
        v.addVelocity(ax * 0.12, 0, az * 0.12);
    }

    /**
     * Once a second. Two people walking into each other and standing there pressed together: after a couple of
     * seconds each steps back and aside, as people do.
     */
    public static void elbowRoom(VillagerEntityMCA v) {
        if (!(v.getWorld() instanceof ServerWorld world) || v.isSleeping() || v.hasVehicle()) return;
        for (VillagerEntityMCA o : world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(0.25, 0.5, 0.25),
                o -> o != v && o.isAlive() && !o.isSleeping())) {
            int n = PRESSED.merge(v.getUuid(), 1, Integer::sum);
            if (n >= 2) {
                stepBack(v, o);
                PRESSED.remove(v.getUuid());
            }
            return;
        }
        PRESSED.remove(v.getUuid());
    }

    /**
     * Sets him down at {@code at} or the nearest spot around it where a man can stand: a floor under his feet and room
     * for his head. Never in the air, never in the rock; no fall to hurt him. False when there is no such spot near.
     */
    public static boolean putSafely(VillagerEntityMCA v, BlockPos at) {
        if (!(v.getWorld() instanceof ServerWorld world) || !world.isChunkLoaded(at.getX() >> 4, at.getZ() >> 4)) return false;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(at.add(-2, -2, -2), at.add(2, 2, 2))) {
            BlockState floor = world.getBlockState(p.down());
            if (!passable(world, p) || !passable(world, p.up()) || !world.getFluidState(p).isEmpty()) continue;
            if (!floor.isSideSolidFullSquare(world, p.down(), Direction.UP) && !floor.isOf(Blocks.SCAFFOLDING)) continue;
            double d = p.getSquaredDistance(at);
            if (d < bd) {
                bd = d;
                best = p.toImmutable();
            }
        }
        if (best == null) return false;
        v.refreshPositionAndAngles(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, v.getYaw(), 0);
        v.fallDistance = 0;
        v.setVelocity(Vec3d.ZERO);
        v.getNavigation().stop();
        return true;
    }

    private static final java.util.Map<java.util.UUID, BlockPos> OPENED = new java.util.HashMap<>();

    /** A wooden door or a fence gate, shut: a man opens it with his hand. */
    static boolean opens(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (s.getBlock() instanceof net.minecraft.block.FenceGateBlock) return !s.get(net.minecraft.block.FenceGateBlock.OPEN);
        return s.getBlock() instanceof net.minecraft.block.DoorBlock door && door.getBlockSetType().canOpenByHand() && !s.get(net.minecraft.block.DoorBlock.OPEN);
    }

    static void open(VillagerEntityMCA v, ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        v.swingHand(v.getDominantHand());
        if (s.getBlock() instanceof net.minecraft.block.FenceGateBlock) {
            Direction facing = v.getHorizontalFacing();
            BlockState o = s.with(net.minecraft.block.FenceGateBlock.OPEN, true);
            if (s.get(net.minecraft.block.FenceGateBlock.FACING) == facing.getOpposite()) o = o.with(net.minecraft.block.FenceGateBlock.FACING, facing); // swings away from him
            world.setBlockState(p, o, net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.REDRAW_ON_MAIN_THREAD);
            world.playSound(null, p, net.minecraft.sound.SoundEvents.BLOCK_FENCE_GATE_OPEN, SoundCategory.BLOCKS, 1f, 1f);
        } else if (s.getBlock() instanceof net.minecraft.block.DoorBlock door) {
            door.setOpen(v, world, s, p, true);
        }
        OPENED.put(v.getUuid(), p.toImmutable());
    }

    /** Once a second: the gate or door he went through is shut again once he is past it (the animals stay in). */
    public static void shutBehind(VillagerEntityMCA v) {
        BlockPos p = OPENED.get(v.getUuid());
        if (p == null || !(v.getWorld() instanceof ServerWorld world)) return;
        double d = v.squaredDistanceTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        if (d < 2.6) return; // still in it
        OPENED.remove(v.getUuid());
        if (d > 64) return; // long gone: somebody else may shut it
        BlockState s = world.getBlockState(p);
        if (!world.getOtherEntities(null, new net.minecraft.util.math.Box(p), e -> e instanceof net.minecraft.entity.LivingEntity).isEmpty()) return;
        if (s.getBlock() instanceof net.minecraft.block.FenceGateBlock && s.get(net.minecraft.block.FenceGateBlock.OPEN)) {
            world.setBlockState(p, s.with(net.minecraft.block.FenceGateBlock.OPEN, false), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.REDRAW_ON_MAIN_THREAD);
            world.playSound(null, p, net.minecraft.sound.SoundEvents.BLOCK_FENCE_GATE_CLOSE, SoundCategory.BLOCKS, 1f, 1f);
            v.swingHand(v.getDominantHand());
        } else if (s.getBlock() instanceof net.minecraft.block.DoorBlock door && s.get(net.minecraft.block.DoorBlock.OPEN)) {
            door.setOpen(v, world, s, p, false);
        }
    }
}
