package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Sitting down: on a bench, a stair, a slab, a log, or simply on the ground. The villager rides an invisible marker
 * placed so that he sits on the surface; standing up removes it again.
 */
public final class Seat {
    public static final String TAG = "settlements_seat";

    private Seat() {
    }

    public static boolean seated(VillagerEntityMCA v) {
        Entity e = v.getVehicle();
        return e instanceof ArmorStandEntity && e.getCommandTags().contains(TAG);
    }

    /** The height a villager sits at on this block (the top of a step or slab), or NaN if it is no seat. */
    static double seatHeight(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (s.getBlock() instanceof StairsBlock && s.get(StairsBlock.HALF) == BlockHalf.BOTTOM) return p.getY() + 0.5;
        if (s.getBlock() instanceof SlabBlock && s.get(SlabBlock.TYPE) == SlabType.BOTTOM) return p.getY() + 0.5;
        return Double.NaN;
    }

    /** Sits down with the seat surface at {@code surfaceY}, facing {@code yaw}. */
    public static void sit(VillagerEntityMCA v, double x, double surfaceY, double z, float yaw) {
        if (!(v.getWorld() instanceof ServerWorld world) || v.hasVehicle()) return;
        ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        NbtCompound n = new NbtCompound();
        n.putBoolean("Marker", true);
        n.putBoolean("Invisible", true);
        n.putBoolean("NoGravity", true);
        n.putBoolean("Invulnerable", true);
        stand.readCustomDataFromNbt(n);
        // the rider is set 0.35 below his seat (VillagerEntityMCA.getHeightOffset) and his hips are 0.75 above his feet:
        // the seat a little under the surface puts them on it (at -0.55 he sat sunk into the block to the waist)
        stand.refreshPositionAndAngles(x, surfaceY - 0.28, z, yaw, 0);
        stand.addCommandTag(TAG);
        world.spawnEntity(stand);
        v.getNavigation().stop();
        v.startRiding(stand, true);
        v.setYaw(yaw);
        v.setBodyYaw(yaw);
        v.setHeadYaw(yaw);
    }

    /** Held standing in the stocks (riding an unseen seat at his feet), his head and hands through the board. */
    /** The name the stocks' seat carries, so that clients draw whoever is on it bent into the stocks. */
    public static final String STOCKS_NAME = "settlements_stocks";

    /** Whether this entity is locked in the stocks (works on the client: the seat's name is synced). */
    public static boolean inStocks(Entity e) {
        return e != null && e.getVehicle() instanceof ArmorStandEntity a && a.hasCustomName() && STOCKS_NAME.equals(a.getCustomName().getString());
    }

    public static void kneel(net.minecraft.entity.LivingEntity who, double x, double y, double z, float yaw) {
        if (!(who.getWorld() instanceof ServerWorld world)) return;
        if (who.hasVehicle()) who.stopRiding();
        ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        NbtCompound n = new NbtCompound();
        n.putBoolean("Marker", true);
        n.putBoolean("Invisible", true);
        n.putBoolean("NoGravity", true);
        n.putBoolean("Invulnerable", true);
        stand.readCustomDataFromNbt(n);
        stand.refreshPositionAndAngles(x, y + 0.35, z, yaw, 0); // the rider is set 0.35 below the seat: his feet on the platform, standing
        stand.addCommandTag(TAG);
        stand.addCommandTag("settlements_stocks");
        stand.setCustomName(net.minecraft.text.Text.literal(STOCKS_NAME)); // (not shown) how the client knows to draw him locked in the stocks
        stand.setCustomNameVisible(false);
        world.spawnEntity(stand);
        who.startRiding(stand, true);
        who.setYaw(yaw);
        who.setBodyYaw(yaw);
        who.setHeadYaw(yaw);
    }

    /** Up from the stocks' seat (and the seat gone). */
    public static void rise(net.minecraft.entity.LivingEntity who) {
        Entity e = who.getVehicle();
        if (!(e instanceof ArmorStandEntity) || !e.getCommandTags().contains(TAG)) return;
        RISING = true;
        try {
            who.stopRiding();
        } finally {
            RISING = false;
        }
        e.discard();
    }

    /** Set while the village itself lets somebody up: only then does getting off the stocks' seat go through. */
    public static boolean RISING = false;

    /** Sits on a seat block (stair, slab), or on the ground where he stands. */
    public static void sitHere(VillagerEntityMCA v, float yaw) {
        if (!(v.getWorld() instanceof ServerWorld world)) return;
        BlockPos p = v.getBlockPos();
        double h = seatHeight(world, p);
        if (Double.isNaN(h)) h = seatHeight(world, p.down());
        if (Double.isNaN(h)) h = v.getY();
        sit(v, p.getX() + 0.5, h, p.getZ() + 0.5, yaw);
    }

    public static void stand(VillagerEntityMCA v) {
        Entity e = v.getVehicle();
        if (!(e instanceof ArmorStandEntity) || !e.getCommandTags().contains(TAG)) return;
        v.stopRiding();
        e.discard();
        v.refreshPositionAndAngles(v.getX(), Math.ceil(v.getY()), v.getZ(), v.getYaw(), v.getPitch());
    }

    /** Leftover seats (somebody stood up some other way) are cleared away. */
    public static void cleanup(ServerWorld world) {
        for (Entity e : world.iterateEntities()) {
            if (e instanceof ArmorStandEntity && e.getCommandTags().contains(TAG) && !e.hasPassengers()) e.discard();
        }
    }

    /** A seat block near the villager (a bench, a stair, a slab) with nobody on it, or null. */
    public static BlockPos findNear(VillagerEntityMCA v, int radius) {
        if (!(v.getWorld() instanceof ServerWorld world)) return null;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(v.getBlockPos().add(-radius, -2, -radius), v.getBlockPos().add(radius, 2, radius))) {
            if (Double.isNaN(seatHeight(world, p)) || !world.getBlockState(p.up()).isAir() || !world.getBlockState(p.up(2)).isAir()) continue;
            if (!world.getEntitiesByClass(ArmorStandEntity.class, new net.minecraft.util.math.Box(p).expand(0.3, 1, 0.3), s -> s.getCommandTags().contains(TAG)).isEmpty()) continue;
            double d = p.getSquaredDistance(v.getBlockPos());
            if (d < bd) {
                bd = d;
                best = p.toImmutable();
            }
        }
        return best;
    }
}
