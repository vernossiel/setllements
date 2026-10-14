package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Villagers cannot path over ladders, so the guards climb them by hand: walk to the foot of the ladder, then move up
 * (or down) the shaft tick by tick until they reach the deck. A timeout puts them on the deck, so a blocked tower never
 * strands anyone.
 */
public final class Climb {
    private static final int TIMEOUT_TICKS = 20 * 45;

    private Climb() {
    }

    public static void toDeck(VillagerEntityMCA g, BlockPos ladderBase, BlockPos deck) {
        if (g.climbPhase != 0) return;
        g.climbBase = ladderBase;
        g.climbTop = ladderBase.up(9);
        g.climbDeck = deck;
        g.climbPhase = 1;
        g.climbTimer = 0;
    }

    public static void toGround(VillagerEntityMCA g, BlockPos ladderBase, BlockPos deck) {
        if (g.climbPhase != 0) return;
        g.climbBase = ladderBase;
        g.climbTop = ladderBase.up(9);
        g.climbDeck = deck;
        // a small hop from the deck onto the top of the ladder, then down
        g.refreshPositionAndAngles(g.climbTop.getX() + 0.5, g.climbTop.getY(), g.climbTop.getZ() + 0.5, g.getYaw(), 0);
        g.climbPhase = 3;
        g.climbTimer = 0;
    }

    /** Every tick, server side. */
    public static void tick(VillagerEntityMCA g) {
        if (g.climbPhase == 0 || g.getWorld().isClient) return;
        if (++g.climbTimer > TIMEOUT_TICKS) {
            finish(g, g.climbPhase == 3 ? g.climbBase : g.climbDeck);
            return;
        }
        double cx = g.climbBase.getX() + 0.5, cz = g.climbBase.getZ() + 0.5;
        Vec3d v = g.getVelocity();
        switch (g.climbPhase) {
            case 1 -> {
                g.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(Vec3d.ofCenter(g.climbBase), 0.6f, 0));
                double dx = g.getX() - cx, dz = g.getZ() - cz;
                if (dx * dx + dz * dz < 0.5 && Math.abs(g.getY() - g.climbBase.getY()) < 1.5) g.climbPhase = 2;
            }
            case 2 -> {
                g.setVelocity((cx - g.getX()) * 0.3, 0.2, (cz - g.getZ()) * 0.3);
                g.fallDistance = 0;
                if (g.getY() >= g.climbDeck.getY() - 0.2) finish(g, g.climbDeck);
            }
            case 3 -> {
                g.setVelocity((cx - g.getX()) * 0.3, Math.min(v.y, -0.12), (cz - g.getZ()) * 0.3);
                g.fallDistance = 0;
                if (g.getY() <= g.climbBase.getY() + 0.2) finish(g, g.climbBase);
            }
            default -> g.climbPhase = 0;
        }
    }

    private static void finish(VillagerEntityMCA g, BlockPos at) {
        // down the ladder you step out in front of it, up the ladder you land on the deck
        BlockPos p = g.climbPhase == 3 ? at.south().south() : at;
        g.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, g.getYaw(), 0);
        g.setVelocity(Vec3d.ZERO);
        g.getNavigation().stop();
        g.climbPhase = 0;
    }
}
