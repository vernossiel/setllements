package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The night watchman. At dusk he goes round the dark corners of the village and puts up lamps (a post and a lantern,
 * or a torch), so that nothing crawls out of the dark between the houses. At night he walks his round past the stores,
 * the granary and the smithy with a lantern; a thief does not try his luck while he is near. When he sees something
 * coming out of the dark he rings out the alarm: the village runs indoors and the guards come. He sleeps by day.
 */
public final class Watch {
    private static final Map<UUID, Integer> ROUND = new HashMap<>();
    private static final Map<UUID, BlockPos> LAMP = new HashMap<>();
    private static final Map<Integer, Long> ALARM = new HashMap<>();

    private Watch() {
    }

    static boolean night(long timeOfDay) {
        long t = timeOfDay % 24000L;
        return t >= 12000 || t < 300;
    }

    /** Whether a watchman is about near this spot right now (a rogue thinks twice). */
    public static boolean near(ServerWorld world, BlockPos p) {
        return !world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(p).expand(14, 6, 14),
                v -> v.isAlive() && !v.isSleeping() && v.getProfession() == net.mca.ProfessionsMCA.WATCHMAN.get()).isEmpty();
    }

    /** Once a second for the watchman: true while it is his time (dusk and night). */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world) {
        long tod = world.getTimeOfDay();
        if (!night(tod)) return false; // by day he sleeps and has his own time (see the routine)
        if (v.isSleeping()) v.wakeUp();
        Weather.lantern(v, world);
        if (alarm(v, village, world)) return true;
        long t = tod % 24000L;
        if (t >= 12000 && t < 14500 && lamps(v, village, world)) return true;
        return round(v, village, world);
    }

    /** Something out of the dark near the houses: the alarm, the village indoors, the guards on it. */
    private static boolean alarm(VillagerEntityMCA v, Village village, ServerWorld world) {
        HostileEntity foe = world.getEntitiesByClass(HostileEntity.class, v.getBoundingBox().expand(20, 6, 20), m -> m.isAlive() && v.canSee(m))
                .stream().findFirst().orElse(null);
        if (foe == null) return false;
        long now = world.getTime();
        Doing.set(v, "settlements.doing.watch.alarm");
        Gaze.at(v, foe, 30f, 30f);
        if (now - ALARM.getOrDefault(village.getId(), -10000L) > 20 * 30) {
            ALARM.put(village.getId(), now);
            Speech.sayNow(v, "settlements.say.watch.alarm");
            world.playSound(null, v.getBlockPos(), SoundEvents.BLOCK_BELL_USE, SoundCategory.NEUTRAL, 2f, 1f);
            for (VillagerEntityMCA g : world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(64, 12, 64), VillagerEntityMCA::isAlive)) {
                if (g.isGuard()) g.getBrain().remember(MemoryModuleType.ATTACK_TARGET, foe, 400);
            }
            Mood.event(village, Mood.Kind.FEAR, foe.getBlockPos()); // people keep in for a while
        }
        // he keeps his distance and keeps it in sight, calling out
        if (v.squaredDistanceTo(foe) < 36) {
            Vec3d away = v.getPos().subtract(foe.getPos()).normalize().multiply(6).add(v.getPos());
            Move.to(v, away.x, away.y, away.z, 0.7);
        } else v.getNavigation().stop();
        return true;
    }

    /** At dusk: a lamp on a post at the next dark corner, while there are torches in the stores. */
    static boolean lamps(VillagerEntityMCA v, Village village, ServerWorld world) {
        int torches = Crafts.count(village, s -> s.isOf(Items.TORCH));
        int lanterns = Crafts.count(village, s -> s.isOf(Items.LANTERN));
        if (torches + lanterns == 0) return false;
        BlockPos at = LAMP.get(v.getUuid());
        if (at == null || world.getBlockState(at).isOf(Blocks.OAK_FENCE)) {
            at = dark(world, village);
            if (at == null) return false;
            LAMP.put(v.getUuid(), at);
        }
        Doing.set(v, "settlements.doing.watch.lamps");
        if (v.squaredDistanceTo(Vec3d.ofBottomCenter(at)) > 3 * 3) {
            Move.to(v, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.6);
            return true;
        }
        if (!world.getBlockState(at).isReplaceable() || !world.getBlockState(at.up()).isReplaceable()) {
            LAMP.remove(v.getUuid());
            return true;
        }
        Crafts.take(village, s -> s.isOf(Items.STICK) || s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS), 1);
        world.setBlockState(at, Blocks.OAK_FENCE.getDefaultState());
        if (lanterns > 0) {
            Crafts.take(village, s -> s.isOf(Items.LANTERN), 1);
            world.setBlockState(at.up(), Blocks.LANTERN.getDefaultState());
        } else {
            Crafts.take(village, s -> s.isOf(Items.TORCH), 1);
            world.setBlockState(at.up(), Blocks.TORCH.getDefaultState());
        }
        Anim.set(v, Anim.Act.BUILD, 30);
        world.playSound(null, at, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.8f, 1f);
        if (world.random.nextInt(3) == 0) Speech.sayAny(v, "settlements.say.watch.lamp", 3);
        LAMP.remove(v.getUuid());
        return true;
    }

    /**
     * Where the next lamp goes. First the darkest stretch among the houses (dark enough for things to spawn), then, close
     * by, a sensible place for a post the way people set them: at the side of the street, not on it; not in a doorway,
     * a yard or a field; not plugging a passage; not right beside another light.
     */
    private static BlockPos dark(ServerWorld world, Village village) {
        BlockPos c = new BlockPos(village.getCenter());
        BlockPos zone = null;
        int bestLight = 8; // block light below this: monsters may come
        for (int dx = -40; dx <= 40; dx += 5) {
            for (int dz = -40; dz <= 40; dz += 5) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                BlockPos p = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!village.isWithinBorder(p, 0) || !world.getFluidState(p).isEmpty()) continue;
                if (!lived(world, village, p)) continue; // the empty land round the village is left dark
                int light = world.getLightLevel(LightType.BLOCK, p);
                if (light < bestLight) {
                    bestLight = light;
                    zone = p;
                }
            }
        }
        if (zone == null) return null;
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int x = zone.getX() + dx, z = zone.getZ() + dz;
                BlockPos p = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                int score = post(world, village, p);
                if (score == Integer.MIN_VALUE) continue;
                score -= Math.abs(dx) + Math.abs(dz); // near the dark spot
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        return best;
    }

    /** Within a few blocks of a street or a building: the part of the village people walk at night. */
    private static boolean lived(ServerWorld world, Village village, BlockPos p) {
        for (int[] pl : village.plots) {
            if (p.getX() >= pl[0] - 6 && p.getX() < pl[0] + pl[2] + 6 && p.getZ() >= pl[1] - 6 && p.getZ() < pl[1] + pl[3] + 6) return true;
        }
        for (BlockPos q : BlockPos.iterate(p.add(-5, -2, -5), p.add(5, 1, 5))) if (world.getBlockState(q).isOf(Blocks.DIRT_PATH)) return true;
        return false;
    }

    /** How good a place for a lamp post this is (MIN_VALUE: no place for one at all). */
    private static int post(ServerWorld world, Village village, BlockPos p) {
        BlockState ground = world.getBlockState(p.down());
        if (!world.getFluidState(p).isEmpty() || !world.getBlockState(p).isAir() || !world.getBlockState(p.up()).isAir()) return Integer.MIN_VALUE;
        if (ground.isOf(Blocks.DIRT_PATH) || ground.isOf(Blocks.FARMLAND) || !Layout.land(ground) || !ground.isSideSolidFullSquare(world, p.down(), net.minecraft.util.math.Direction.UP))
            return Integer.MIN_VALUE; // not on the street, not in a field
        for (int[] pl : village.plots) { // not in a yard or against a wall: a pace clear of every building
            if (p.getX() >= pl[0] - 1 && p.getX() < pl[0] + pl[2] + 1 && p.getZ() >= pl[1] - 1 && p.getZ() < pl[1] + pl[3] + 1) return Integer.MIN_VALUE;
        }
        int score = 0, open = 0;
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos q = p.offset(d);
            if (world.getBlockState(q).getCollisionShape(world, q).isEmpty()) open++;
            if (world.getBlockState(q.down()).isOf(Blocks.DIRT_PATH)) score += 4; // the side of the street: where lamps belong
        }
        if (open < 4) return Integer.MIN_VALUE; // never plugging a passage
        for (BlockPos q : BlockPos.iterate(p.add(-3, -1, -3), p.add(3, 2, 3))) {
            BlockState s = world.getBlockState(q);
            if (s.getBlock() instanceof net.minecraft.block.DoorBlock || s.isOf(Blocks.FARMLAND)) return Integer.MIN_VALUE; // a doorway, a field
        }
        for (BlockPos q : BlockPos.iterate(p.add(-5, -2, -5), p.add(5, 3, 5))) {
            BlockState s = world.getBlockState(q);
            if (s.isOf(Blocks.LANTERN) || s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH) || s.isOf(Blocks.CAMPFIRE)) return Integer.MIN_VALUE; // one is enough here
        }
        return score;
    }

    /** The round: the stores, the granary, the smithy, the hall, one after another, a while at each. */
    private static boolean round(VillagerEntityMCA v, Village village, ServerWorld world) {
        List<BlockPos> posts = new ArrayList<>();
        BlockPos store = Haul.store(v, village);
        if (store != null) posts.add(store);
        for (String t : new String[]{"granary", "smithy", "market", "storage"}) {
            Built b = Buildings.find(village, t);
            if (b != null) posts.add(Crafts.spot(world, b));
        }
        if (village.hallPos != null) posts.add(village.hallPos);
        if (posts.isEmpty()) return false;
        int i = ROUND.getOrDefault(v.getUuid(), 0) % posts.size();
        BlockPos post = posts.get(i);
        Doing.set(v, "settlements.doing.watch.round");
        if (v.squaredDistanceTo(Vec3d.ofBottomCenter(post)) > 4 * 4) {
            Move.to(v, post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 0.45);
            return true;
        }
        v.getNavigation().stop();
        if (world.random.nextInt(20) == 0) {
            ROUND.put(v.getUuid(), i + 1); // on to the next
            if (world.random.nextInt(3) == 0) Speech.sayAny(v, "settlements.say.watch.round", 3);
        }
        return true;
    }
}
