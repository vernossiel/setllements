package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;

/**
 * Breaking a block the way a player does: he stands by it, swings at it, it cracks a stage at a time, and when it
 * gives it drops what it drops on the ground for him to pick up. How long it takes is the player's rule: the block's
 * hardness against the tool in his hand (a log falls to an axe, stone needs a pick), and how good he is at his trade.
 */
public final class Dig {
    private static final double REACH = 5.0; // a man's reach, and a little for the swing

    private Dig() {
    }

    /**
     * Whether he can touch the block at {@code pos} from where he stands, as a player could: nothing solid between his
     * eyes and it (for an empty spot, nothing in the way of putting a block there).
     */
    public static boolean sees(VillagerEntityMCA v, BlockPos pos) {
        net.minecraft.util.math.Vec3d eye = v.getEyePos(), c = net.minecraft.util.math.Vec3d.ofCenter(pos);
        net.minecraft.util.hit.BlockHitResult hit = v.getWorld().raycast(new net.minecraft.world.RaycastContext(eye, c,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, v));
        if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return true;
        return hit.getBlockPos().equals(pos) || hit.getPos().squaredDistanceTo(c) <= 0.76; // struck on the block itself
    }

    /** True while he is still at a block. */
    public static boolean busy(VillagerEntityMCA v) {
        return v.settlementsDigPos != null;
    }

    /** Sets him to break this block from where he stands (the work goes on tick by tick, see {@link #tick}). */
    public static void start(VillagerEntityMCA v, BlockPos pos) {
        if (pos.equals(v.settlementsDigPos)) return;
        stop(v);
        // the right tool in hand first: nobody breaks stone with his fists
        Gear.toolFor(v, net.mca.server.world.data.Village.findNearest(v).or(() -> v.getResidency().getHomeVillage()).orElse(null), v.getWorld().getBlockState(pos));
        v.settlementsDigPos = pos.toImmutable();
        v.settlementsDigProgress = 0;
        v.getNavigation().stop();
    }

    public static void stop(VillagerEntityMCA v) {
        if (v.settlementsDigPos != null && v.getWorld() instanceof ServerWorld world) world.setBlockBreakingInfo(v.getId(), v.settlementsDigPos, -1);
        v.settlementsDigPos = null;
        v.settlementsDigProgress = 0;
    }

    /** Every tick. */
    public static void tick(VillagerEntityMCA v) {
        BlockPos p = v.settlementsDigPos;
        if (p == null || !(v.getWorld() instanceof ServerWorld world)) return;
        BlockState s = world.getBlockState(p);
        float hardness = s.getHardness(world, p);
        if (s.isAir() || hardness < 0 || !v.isAlive() || v.isSleeping()
                || v.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) > REACH * REACH || v.age % 10 == 0 && !sees(v, p)) {
            stop(v);
            return;
        }
        ItemStack tool = v.getMainHandStack();
        boolean right = !s.isToolRequired() || tool.isSuitableFor(s);
        float speed = Math.max(1f, tool.getMiningSpeedMultiplier(s));
        float delta = hardness == 0 ? 1f : speed / hardness / (right ? 30f : 100f);
        delta *= (float) Math.max(0.6, Math.min(1.6, Trade.pace(v)));
        v.settlementsDigProgress += delta;
        v.getLookControl().lookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        if (v.age % 5 == 0) {
            v.swingHand(v.getDominantHand());
            world.playSound(null, p, s.getSoundGroup().getHitSound(), SoundCategory.BLOCKS, 0.25f, 0.5f);
        }
        if (v.settlementsDigProgress >= 1f) {
            world.setBlockBreakingInfo(v.getId(), p, -1);
            world.breakBlock(p, true, v); // the drops fall where the block stood, as for anyone
            v.settlementsDigPos = null;
            v.settlementsDigProgress = 0;
            return;
        }
        world.setBlockBreakingInfo(v.getId(), p, Math.min(9, (int) (v.settlementsDigProgress * 10)));
    }

    /** Picks up what lies within arm's reach into his pockets. True when he picked something up. */
    public static boolean gather(VillagerEntityMCA v, double radius) {
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        boolean any = false;
        for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, v.getBoundingBox().expand(radius, 2, radius), e -> e.isAlive() && !e.cannotPickup())) {
            if (world.getClosestPlayer(e, 4) != null) continue; // a player's things are his own
            ItemStack rest = v.getInventory().addStack(e.getStack().copy());
            if (rest.getCount() == e.getStack().getCount()) continue;
            v.sendPickup(e, e.getStack().getCount() - rest.getCount());
            if (rest.isEmpty()) e.discard();
            else e.setStack(rest);
            any = true;
        }
        return any;
    }

    /** Walks over to the nearest thing he dropped while working, within {@code radius}. True while there is any. */
    public static boolean collect(VillagerEntityMCA v, double radius) {
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        gather(v, 2.5);
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ItemEntity e : world.getEntitiesByClass(ItemEntity.class, v.getBoundingBox().expand(radius, 6, radius), e -> e.isAlive() && !e.cannotPickup())) {
            if (world.getClosestPlayer(e, 4) != null) continue;
            double d = e.squaredDistanceTo(v);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        if (best == null) return false;
        Move.to(v, best, 0.6);
        return true;
    }
}
