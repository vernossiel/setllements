package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The small moves of the body, done as a player does them, tick by tick - not a villager set down a block further
 * on: a step onto the next cell (a hop when it is higher), a jump with a block put under his feet at the top of it,
 * a crouch to let himself down through scaffolding, a shove out of a block he is caught in, a fall from a scaffold
 * taken down under him landed softly. One move at a time; the work that wanted it waits while it lasts (see busy).
 */
public final class Moves {
    private Moves() {
    }

    private enum Kind { STEP, JUMP_PLACE, CROUCH_DOWN, SHOVE, SOFT_FALL }

    private static final class Move {
        Kind kind;
        BlockPos at;          // where to (a step, a shove), the cell to fill (a jump), or where he started (a crouch)
        BlockState block;     // what goes under his feet (a jump)
        Runnable placed;      // what the block's placing costs and records
        int ticks;
        int limit;
    }

    private static final Map<UUID, Move> MOVES = new HashMap<>();

    /** Whether he is in the middle of a move of the body: nothing else is to move him meanwhile. */
    public static boolean busy(VillagerEntityMCA v) {
        return MOVES.containsKey(v.getUuid());
    }

    /** A step onto the next cell (or two off): walked, a hop up when it is higher. */
    public static void step(VillagerEntityMCA v, BlockPos to) {
        start(v, Kind.STEP, to, null, null, 40);
    }

    /**
     * A jump, and at the top of it the block put under his feet where he stood: up a block as a player towers up.
     * {@code placed} runs when the block goes in (paying for it, recording it).
     */
    public static void jumpPlace(VillagerEntityMCA v, BlockState block, Runnable placed) {
        start(v, Kind.JUMP_PLACE, v.getBlockPos(), block, placed, 20);
        if (v.isOnGround()) v.setVelocity(v.getVelocity().x * 0.2, 0.42, v.getVelocity().z * 0.2);
        v.getJumpControl().setActive();
    }

    /** A crouch on the scaffolding: down through it a block, as a player holds the sneak key. */
    public static void crouchDown(VillagerEntityMCA v) {
        start(v, Kind.CROUCH_DOWN, v.getBlockPos(), null, null, 40);
        v.setSneaking(true);
    }

    /** Caught in a block: a shove out of it towards the open cell beside. */
    public static void shove(VillagerEntityMCA v, BlockPos to) {
        start(v, Kind.SHOVE, to, null, null, 15);
    }

    /** The scaffold he stood on taken away: he drops, and lands without harm. */
    public static void softFall(VillagerEntityMCA v) {
        start(v, Kind.SOFT_FALL, v.getBlockPos(), null, null, 80);
    }

    private static void start(VillagerEntityMCA v, Kind kind, BlockPos at, BlockState block, Runnable placed, int limit) {
        Move m = new Move();
        m.kind = kind;
        m.at = at.toImmutable();
        m.block = block;
        m.placed = placed;
        m.limit = limit;
        MOVES.put(v.getUuid(), m);
        v.getNavigation().stop();
    }

    /** Every tick, server side. */
    public static void tick(VillagerEntityMCA v) {
        if (v.getWorld().isClient) return;
        // asleep is asleep: nothing walks him about (an elder was seen running off still lying in her sleep)
        if (v.isSleeping()) {
            if (v.getNavigation().isFollowingPath()) v.getNavigation().stop();
            MOVES.remove(v.getUuid());
            return;
        }
        Move m = MOVES.get(v.getUuid());
        if (m == null) return;
        if (!(v.getWorld() instanceof ServerWorld world) || !v.isAlive() || ++m.ticks > m.limit) {
            end(v, m);
            return;
        }
        switch (m.kind) {
            case STEP -> {
                double dx = m.at.getX() + 0.5 - v.getX(), dz = m.at.getZ() + 0.5 - v.getZ();
                if (dx * dx + dz * dz < 0.09 && Math.abs(v.getY() - m.at.getY()) < 0.6) {
                    end(v, m);
                    return;
                }
                if (m.at.getY() > v.getY() + 0.5 && v.isOnGround()) v.getJumpControl().setActive();
                v.getMoveControl().moveTo(m.at.getX() + 0.5, m.at.getY(), m.at.getZ() + 0.5, 0.6);
                v.fallDistance = 0;
            }
            case JUMP_PLACE -> {
                v.fallDistance = 0;
                BlockPos cell = m.at;
                // at the top of the jump, his feet clear of the cell: the block in under them
                if (v.getY() >= cell.getY() + 1.0 && world.getBlockState(cell).isReplaceable()) {
                    world.setBlockState(cell, m.block);
                    Doing.event(v, "pillars up on", cell, m.block);
                    world.playSound(null, cell, m.block.getSoundGroup().getPlaceSound(), net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 1f);
                    v.swingHand(v.getDominantHand());
                    if (m.placed != null) m.placed.run();
                    end(v, m);
                    return;
                }
                if (v.isOnGround() && m.ticks > 3) { // came down without making it: once more
                    v.setVelocity(v.getVelocity().x * 0.2, 0.42, v.getVelocity().z * 0.2);
                    v.getJumpControl().setActive();
                }
                v.setVelocity(new Vec3d((cell.getX() + 0.5 - v.getX()) * 0.3, v.getVelocity().y, (cell.getZ() + 0.5 - v.getZ()) * 0.3));
            }
            case CROUCH_DOWN -> {
                v.fallDistance = 0;
                v.setSneaking(true);
                if (v.getY() <= m.at.getY() - 0.95) end(v, m); // down a block
            }
            case SHOVE -> {
                Vec3d to = Vec3d.ofBottomCenter(m.at);
                Vec3d d = to.subtract(v.getPos());
                if (d.horizontalLengthSquared() < 0.04 && Math.abs(d.y) < 0.5) {
                    end(v, m);
                    return;
                }
                v.setVelocity(d.x * 0.35, Math.max(v.getVelocity().y, d.y > 0.2 ? 0.2 : v.getVelocity().y), d.z * 0.35);
                v.fallDistance = 0;
            }
            case SOFT_FALL -> {
                v.fallDistance = 0;
                if (v.isOnGround() && m.ticks > 2) end(v, m);
            }
        }
    }

    private static void end(VillagerEntityMCA v, Move m) {
        MOVES.remove(v.getUuid());
        if (m.kind == Kind.CROUCH_DOWN) v.setSneaking(false);
        v.fallDistance = 0;
    }
}
