package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The weather and the dark. In the rain those with nothing pressing go in (home, or under the nearest roof) and wait it
 * out; in a thunderstorm nearly everybody does. After dark whoever is out and about carries a lantern.
 */
public final class Weather {
    private static final Map<UUID, BlockPos> COVER = new HashMap<>();
    private static final Map<UUID, Long> SAID = new HashMap<>();

    private Weather() {
    }

    private static boolean dark(ServerWorld world, VillagerEntityMCA v) {
        long t = world.getTimeOfDay() % 24000L;
        return t >= 13000 && t <= 23000 || world.getLightLevel(v.getBlockPos()) < 6;
    }

    /** Every second: the lantern in his other hand while he walks about in the dark, put away by day or indoors. */
    public static void lantern(VillagerEntityMCA v, ServerWorld world) {
        boolean want = !v.isSleeping() && !v.isBaby() && dark(world, v) && world.isSkyVisible(v.getBlockPos().up())
                && v.getNavigation().isFollowingPath();
        ItemStack off = v.getStackInHand(Hand.OFF_HAND);
        if (want && off.isEmpty()) v.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.LANTERN));
        else if (!want && off.isOf(Items.LANTERN) && !v.getNavigation().isFollowingPath()) v.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
    }

    /**
     * Once a second; true while he is getting out of the rain or waiting it out under a roof. {@code free}: he has
     * nothing pressing (off work, or idle); a thunderstorm sends in all but the watch.
     */
    private static final java.util.Map<java.util.UUID, Long> STORM = new java.util.HashMap<>(); // worker -> when the storm sent him in

    public static boolean shelter(VillagerEntityMCA v, Village village, ServerWorld world, boolean free) {
        if (!world.isRaining() || v.isSleeping() || v.isGuard()) {
            COVER.remove(v.getUuid());
            STORM.remove(v.getUuid());
            return false;
        }
        if (!free && !world.isThundering()) return false;
        // a storm that does not let up: two minutes under a roof, then back to the work, wet or not (a thunderstorm
        // lasted the whole day, and the whole village sat it out with no food in the stores)
        if (!free) {
            long now = world.getTime();
            long since = STORM.computeIfAbsent(v.getUuid(), k -> now);
            if (now - since > 20 * 120) {
                if (now - since > 20 * 900) STORM.put(v.getUuid(), now); // (a quarter of an hour on: in again for a while)
                COVER.remove(v.getUuid());
                return false;
            }
        } else STORM.remove(v.getUuid());
        BlockPos at = COVER.get(v.getUuid());
        if (!world.hasRain(v.getBlockPos().up()) && at != null && (v.getBlockPos().isWithinDistance(at, 1.5) || v.isOnGround() && !v.isClimbing() && steady(world, v.getBlockPos()))) { // under cover: he waits there, looking out
            v.getNavigation().stop();
            Doing.set(v, "settlements.doing.weather.wait");
            if (world.random.nextInt(90) == 0) Speech.sayAny(v, "settlements.say.weather.wait", 3);
            return true;
        }
        if (!world.hasRain(v.getBlockPos().up()) && at == null) return false; // dry already, and not sent: let him be
        BlockPos to = COVER.get(v.getUuid());
        if (to == null) {
            to = cover(v, world);
            if (to == null) return false;
            COVER.put(v.getUuid(), to);
            if (world.getTime() - SAID.getOrDefault(v.getUuid(), -10000L) > 6000) {
                SAID.put(v.getUuid(), world.getTime());
                Speech.sayAny(v, world.isThundering() ? "settlements.say.weather.storm" : "settlements.say.weather.rain", 3);
            }
        }
        Doing.set(v, "settlements.doing.weather.run");
        Move.to(v, to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.7); // a quick step: it is wet
        return true;
    }

    /**
     * A spot one can stand out the rain on: not on a ladder or in a doorway, not on the brink of a drop, and under the
     * roof properly, not just under its eaves. (The spot under the eaves at the top of the ladder to a door was
     * chosen: they slid down the ladder into the rain, climbed up again, down again, all through the shower.)
     */
    private static boolean steady(ServerWorld world, BlockPos q) {
        for (BlockPos o : BlockPos.iterate(q.add(-1, -1, -1), q.add(1, 1, 1))) {
            var st = world.getBlockState(o);
            if (st.isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE) || st.getBlock() instanceof net.minecraft.block.DoorBlock) return false;
        }
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos n = q.offset(d);
            if (Move.passable(world, n) && Move.passable(world, n.down())) return false; // a drop beside him
            if (world.hasRain(n.up())) return false; // the rain a step off: the eaves, not the inside
        }
        return true;
    }

    /** Home, if it is near; or else the nearest spot under a roof. */
    private static BlockPos cover(VillagerEntityMCA v, ServerWorld world) {
        GlobalPos h = v.getResidency().getHome().orElse(null);
        if (h != null && h.getDimension() == world.getRegistryKey() && h.getPos().isWithinDistance(v.getBlockPos(), 40)) return h.getPos();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < 40; i++) {
            BlockPos p = v.getBlockPos().add(world.random.nextBetween(-14, 14), 0, world.random.nextBetween(-14, 14));
            int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, p.getX(), p.getZ());
            for (int y = p.getY() - 2; y <= p.getY() + 3 && y < top - 1; y++) {
                BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                if (!world.hasRain(q.up()) && Move.passable(world, q) && Move.passable(world, q.up()) && world.getBlockState(q.down()).isSolidBlock(world, q.down())
                        && steady(world, q)) {
                    double d = v.squaredDistanceTo(Vec3d.ofBottomCenter(q));
                    if (d < bd) {
                        bd = d;
                        best = q.toImmutable();
                    }
                    break;
                }
            }
        }
        return best;
    }
}
