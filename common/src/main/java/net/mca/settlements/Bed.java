package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.block.BedBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bedtime. At night a villager goes to his own bed in his own house and lies down in it, rather than leaving it to the
 * vanilla routine (which loses its way, or finds the bed taken, and leaves him out in the dark all night). One whose
 * bed cannot be reached spends the night in the town hall.
 */
public final class Bed {
    private static final Map<UUID, Long> SINCE = new HashMap<>();

    private Bed() {
    }

    /** Once a second at bedtime; true while he is on his way to bed or in it. */
    public static boolean tick(VillagerEntityMCA v, net.mca.server.world.data.Village village, ServerWorld world) {
        if (v.isSleeping()) {
            SINCE.remove(v.getUuid());
            return true;
        }
        if (v.hasVehicle()) v.stopRiding();
        GlobalPos home = v.getResidency().getHome().orElse(null);
        BlockPos bed = home != null && home.getDimension() == world.getRegistryKey() && world.getBlockState(home.getPos()).getBlock() instanceof BedBlock ? home.getPos() : null;
        long now = world.getTime();
        long since = SINCE.computeIfAbsent(v.getUuid(), k -> now);
        Doing.set(v, "settlements.doing.bed");
        if (bed != null && now - since < 20 * 90) {
            if (v.squaredDistanceTo(Vec3d.ofCenter(bed)) > 2.2 * 2.2) {
                Move.to(v, bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, 0.55);
                return true;
            }
            if (Home.evening(v, village, world, bed)) return true; // first he sees to his house: his savings, something new for it
            if (world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(bed), o -> o != v && o.isSleeping()).isEmpty()) {
                v.sleep(bed);
                SINCE.remove(v.getUuid());
                return true;
            }
        }
        // no bed to be had or reached: the night under the town hall's roof
        BlockPos hall = village != null ? village.hallPos : null;
        if (hall == null) return false;
        if (v.squaredDistanceTo(Vec3d.ofCenter(hall)) > 3 * 3) Move.to(v, hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5, 0.55);
        else v.getNavigation().stop();
        return true;
    }
}
