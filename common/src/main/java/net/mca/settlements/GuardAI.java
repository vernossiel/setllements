package net.mca.settlements;

import net.mca.ProfessionsMCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A tactical layer over MCA's guards. They pick targets by threat instead of by distance, do not pile onto one enemy,
 * keep away from creepers unless they are archers, call for backup, never chase far from the village, walk a real
 * patrol route (gates, well, tavern, campfires), keep night watch on the towers, and withdraw to rest when hurt.
 * The guards work in two shifts, the off-duty ones join the village's evening life.
 */
public final class GuardAI {
    private static final int SIGHT = 24;
    private static final int LEASH = 70;
    private static final int MAX_ON_ONE_TARGET = 2;

    private GuardAI() {
    }

    public static boolean isArcher(VillagerEntityMCA g) {
        return g.getProfession() == ProfessionsMCA.ARCHER.get();
    }

    /** Shift A guards the day, shift B the night; archers keep both. */
    public static boolean onDuty(VillagerEntityMCA g, long time) {
        if (isArcher(g)) return true;
        boolean night = time % 24000L >= 13000L;
        boolean shiftA = (g.getUuid().hashCode() & 1) == 0;
        return night != shiftA;
    }

    public static void tick(VillagerEntityMCA g, Village village, ServerWorld world, long time) {
        if (g.getVillagerBrain().isPanicking()) return;
        BlockPos center = new BlockPos(village.getCenter());
        var brain = g.getBrain();
        LivingEntity current = brain.getOptionalMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);

        // never chase far from home
        if (g.squaredDistanceTo(center.getX(), g.getY(), center.getZ()) > LEASH * LEASH) {
            brain.forget(MemoryModuleType.ATTACK_TARGET);
            brain.remember(MemoryModuleType.WALK_TARGET, new WalkTarget(center, 0.7f, 6));
            return;
        }

        List<LivingEntity> hostiles = world.getEntitiesByClass(LivingEntity.class, g.getBoundingBox().expand(SIGHT, 8, SIGHT),
                e -> e instanceof Monster && e.isAlive() && !(e instanceof VillagerEntityMCA));
        if (!hostiles.isEmpty()) {
            fight(g, village, world, hostiles, current);
            return;
        }

        // peace: heal up, then work the shift
        if (g.getHealth() < g.getMaxHealth() * 0.6f && rest(g, village, world)) return;
        if (!onDuty(g, time)) return;
        patrol(g, village, world, time, center);
    }

    // ---------- combat ----------

    private static void fight(VillagerEntityMCA g, Village village, ServerWorld world, List<LivingEntity> hostiles, LivingEntity current) {
        boolean archer = isArcher(g);
        List<VillagerEntityMCA> guards = world.getEntitiesByClass(VillagerEntityMCA.class, g.getBoundingBox().expand(40),
                o -> o != g && o.isGuard() && o.isAlive());

        LivingEntity best = null;
        double bestScore = -1;
        for (LivingEntity h : hostiles) {
            double score = Defense.rating(h) * 8 / (1 + g.distanceTo(h));
            if (h instanceof MobEntity m && (m.getTarget() instanceof VillagerEntity || m.getTarget() instanceof PlayerEntity)) score += 5;
            if (isRanged(h)) score += archer ? 4 : 1;
            if (h instanceof CreeperEntity) score = archer ? score + 2 : -1;
            int crowd = 0;
            for (VillagerEntityMCA o : guards) if (o.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == h) crowd++;
            if (crowd >= MAX_ON_ONE_TARGET && Defense.rating(h) < 20) score -= 4 * crowd;
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }

        // melee guards keep clear of creepers
        if (!archer) {
            CreeperEntity creeper = hostiles.stream().filter(CreeperEntity.class::isInstance).map(CreeperEntity.class::cast)
                    .filter(c -> g.squaredDistanceTo(c) < 49).min(Comparator.comparingDouble(g::squaredDistanceTo)).orElse(null);
            if (creeper != null && (best == null || best == creeper || bestScore < 0)) {
                g.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
                Vec3d away = g.getPos().subtract(creeper.getPos()).normalize().multiply(9).add(g.getPos());
                g.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(away, 0.8f, 1));
                return;
            }
        }
        if (best == null || bestScore <= 0) return;

        double currentScore = current == null ? -1 : Defense.rating(current) * 8 / (1 + g.distanceTo(current));
        if (current == null || !current.isAlive() || bestScore > currentScore + 3) {
            g.getBrain().remember(MemoryModuleType.ATTACK_TARGET, best, 300);
            // call for backup: up to two idle guards join in
            final LivingEntity target = best;
            guards.stream()
                    .filter(o -> o.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isEmpty() && !o.getVillagerBrain().isPanicking())
                    .sorted(Comparator.comparingDouble(o -> o.squaredDistanceTo(target)))
                    .limit(2)
                    .forEach(o -> o.getBrain().remember(MemoryModuleType.ATTACK_TARGET, target, 300));
        }
    }

    private static boolean isRanged(LivingEntity e) {
        String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        return id.contains("skeleton") || id.contains("stray") || id.contains("pillager") || id.contains("blaze")
                || id.contains("ghast") || id.contains("witch") || id.contains("evoker") || id.contains("drowned");
    }

    // ---------- recovery ----------

    private static boolean rest(VillagerEntityMCA g, Village village, ServerWorld world) {
        VillagerEntityMCA healer = world.getEntitiesByClass(VillagerEntityMCA.class, g.getBoundingBox().expand(50, 12, 50),
                        o -> o.getProfession() == net.minecraft.village.VillagerProfession.CLERIC && o.isAlive())
                .stream().min(Comparator.comparingDouble(g::squaredDistanceTo)).orElse(null);
        BlockPos spot = healer != null ? healer.getBlockPos() : Camp.nearest(village, world, g);
        if (spot == null) return false;
        if (g.squaredDistanceTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 16) {
            g.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, 0.6f, 2));
        } else if (g.age % 100 == 0) {
            g.heal(1f); // the healer does the real work, sitting by a fire only helps a little
        }
        return true;
    }

    // ---------- patrol and night watch ----------

    private static void patrol(VillagerEntityMCA g, Village village, ServerWorld world, long time, BlockPos center) {
        if (g.getBrain().getOptionalMemory(MemoryModuleType.ATTACK_TARGET).isPresent()) return;
        boolean night = time % 24000L >= 13000L;

        // archers keep the night watch on the towers, climbing the ladder
        if (!village.watchPoints.isEmpty() && village.towerLadders.size() == village.watchPoints.size()) {
            int tower = Math.floorMod(g.getUuid().hashCode(), village.watchPoints.size());
            BlockPos deck = village.watchPoints.get(tower), ladder = village.towerLadders.get(tower);
            if (g.climbPhase != 0) return; // busy on the ladder
            boolean onDeck = g.getY() > ladder.getY() + 5;
            if (night && isArcher(g)) {
                if (!onDeck) {
                    Climb.toDeck(g, ladder, deck);
                    return;
                }
                g.getNavigation().stop();
                return;
            }
            if (onDeck) { // dawn, or not an archer's post: back down
                Climb.toGround(g, ladder, deck);
                return;
            }
        }
        if (g.getBrain().getOptionalMemory(MemoryModuleType.WALK_TARGET).isPresent()) return;

        List<BlockPos> route = route(village, world, center, night);
        if (route.isEmpty()) return;
        int index = Math.floorMod((int) (time / 800) + g.getUuid().hashCode(), route.size());
        BlockPos stop = route.get(index);
        if (g.squaredDistanceTo(stop.getX() + 0.5, stop.getY(), stop.getZ() + 0.5) > 16) {
            g.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(stop, 0.5f, 2));
        }
    }

    /** Patrol stops: the well, the gates, the tavern door, campfires and the foot of every tower. */
    private static List<BlockPos> route(Village village, ServerWorld world, BlockPos center, boolean night) {
        List<BlockPos> stops = new ArrayList<>();
        stops.add(ground(world, center.getX(), center.getZ()));
        if (village.wallBuilt) {
            int half = 46;
            stops.add(ground(world, center.getX() + half, center.getZ()));
            stops.add(ground(world, center.getX() - half, center.getZ()));
            stops.add(ground(world, center.getX(), center.getZ() + half));
            stops.add(ground(world, center.getX(), center.getZ() - half));
        }
        if (village.tavern != null && !night) stops.add(village.tavern.barrel());
        if (night) stops.addAll(village.campfires);
        for (BlockPos w : village.watchPoints) stops.add(ground(world, w.getX(), w.getZ()));
        return stops;
    }

    private static BlockPos ground(ServerWorld world, int x, int z) {
        return new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }
}
