package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Building;
import net.mca.server.world.data.Village;
import net.minecraft.block.*;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Breaking village property wakes up the guards, digging plain ground does not.
 * Every offense adds to a per-player score that fades with time. Guards first warn, and attack only when the score
 * crosses a limit that depends on the player's standing: friends get more slack, known troublemakers much less.
 * A witness is required: a villager who sees it, or any guard close enough to hear it.
 */
public final class Protection {
    private static final double WITNESS_RANGE = 16;
    private static final double GUARD_HEARING = 8;
    private static final double DECAY_PER_SECOND = 0.1;

    private static final class Offense {
        double score;
        long lastUpdate;
        long lastWarn;
    }

    private static final Map<UUID, Offense> OFFENSES = new HashMap<>();

    private Protection() {
    }

    /** How serious it is to break this block here. 0 means nobody cares. */
    public static double severity(ServerWorld world, Village village, BlockPos pos, BlockState state) {
        if (state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.SAND) || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.REPLACEABLE) || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.COBBLESTONE) && !insideStructure(village, pos)) {
            return 0;
        }
        Block b = state.getBlock();
        String ns = Registries.BLOCK.getId(b).getNamespace();
        if (b instanceof ChestBlock || b instanceof BarrelBlock) return 10;
        if (b instanceof AnvilBlock || b instanceof AbstractFurnaceBlock || b instanceof GrindstoneBlock || b instanceof SmithingTableBlock) return 8;
        if (ns.equals("pyrotech") && !Registries.BLOCK.getId(b).getPath().startsWith("rock_")) return 8;
        if (b instanceof BedBlock) return 6;
        if (b instanceof DoorBlock || b instanceof CampfireBlock) return 4;
        if (b instanceof BlockWithEntity) return 5;
        if (insideStructure(village, pos)) return 2;
        return 0;
    }

    /** A ripe crop on the village's own tilled ground (or a melon or pumpkin by its stalk, sweet berries in its garden). */
    static boolean crop(ServerWorld world, Village village, BlockPos pos, BlockState state) {
        Block b = state.getBlock();
        // tilled ground of the village's own: its fields, or within its bounds (a player's plot out past them is his)
        boolean field = world.getBlockState(pos.down()).isOf(Blocks.FARMLAND) && (insideStructure(village, pos) || village.isWithinBorder(pos, 0));
        if (b instanceof net.minecraft.block.CropBlock c) return field && c.isMature(state);
        if (b == Blocks.MELON || b == Blocks.PUMPKIN) {
            for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL)
                if (world.getBlockState(pos.offset(d)).getBlock() instanceof net.minecraft.block.AttachedStemBlock) return true;
            return false;
        }
        if (b instanceof net.minecraft.block.SweetBerryBushBlock) return state.get(net.minecraft.block.SweetBerryBushBlock.AGE) >= 2 && insideStructure(village, pos);
        return false;
    }

    static boolean insideStructure(Village village, BlockPos pos) {
        for (Building b : village.getBuildings().values()) {
            if (b.containsPos(pos)) return true;
        }
        for (House h : village.houses) {
            BlockPos o = h.origin();
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + h.fw()
                    && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + h.fd()
                    && pos.getY() >= o.getY() && pos.getY() <= o.getY() + h.floors() * Blueprints.LEVEL_HEIGHT + 1) {
                return true;
            }
        }
        House hall = village.hall;
        if (hall != null) {
            BlockPos o = hall.origin();
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + hall.fw() && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + hall.fd()
                    && pos.getY() >= o.getY() && pos.getY() <= o.getY() + hall.floors() * Blueprints.LEVEL_HEIGHT + 8) return true;
        }
        for (BlockPos c : village.campfires) if (c.equals(pos)) return true;
        for (Built b : village.built) if (b.contains(pos)) return true;
        return false;
    }

    /** Called when a player breaks a block. */
    public static void onBreak(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (!(player.getWorld() instanceof ServerWorld world) || player.isCreative()) return;
        Village village = Village.findNearest(player).orElse(null);
        if (village == null || !village.isWithinBorder(pos, 8)) return;
        // the village's crop off its fields: that is theft, what he takes is what it yields
        if (crop(world, village, pos, state)) {
            Map<net.minecraft.item.Item, Integer> took = new HashMap<>();
            double value = 0;
            for (net.minecraft.item.ItemStack d : Block.getDroppedStacks(state, world, pos, null, player, player.getMainHandStack())) {
                if (d.isOf(net.minecraft.item.Items.WHEAT_SEEDS) || d.isOf(net.minecraft.item.Items.BEETROOT_SEEDS)) continue; // the seed is nothing
                took.merge(d.getItem(), d.getCount(), Integer::sum);
                value += Reputation.value(d);
            }
            if (!took.isEmpty()) Reputation.steal(player, village, value, took);
            return;
        }
        double sev = severity(world, village, pos, state);
        if (sev <= 0) return;

        List<VillagerEntityMCA> villagers = world.getEntitiesByClass(VillagerEntityMCA.class, player.getBoundingBox().expand(WITNESS_RANGE),
                v -> !v.isBaby() && v.isAlive() && !v.isSleeping());
        boolean seen = false;
        VillagerEntityMCA reporter = null;
        for (VillagerEntityMCA v : villagers) {
            double d = v.squaredDistanceTo(player);
            if (v.canSee(player) || (v.isGuard() && d < GUARD_HEARING * GUARD_HEARING)) {
                seen = true;
                reporter = v;
                break;
            }
        }
        if (!seen) {
            // nobody saw it, but a broken wall or a smashed bed is noticed sooner or later
            if (sev >= 2) Justice.suspect(world, village, player, Justice.Crime.VANDALISM, sev, null, pos);
            return;
        }

        if (reporter != null && !reporter.isGuard()) {
            Speech.say(reporter, "settlements.say.report.player");
            GuardIntel.alertNearestGuard(world, reporter, player);
        }
        Offense o = OFFENSES.computeIfAbsent(player.getUuid(), k -> new Offense());
        decay(o, world.getTime());
        o.score += sev;
        village.pushHearts(player, -(int) Math.ceil(sev));

        int standing = village.getReputation(player);
        double limit = standing >= 50 ? 25 : standing >= 0 ? 14 : standing >= -20 ? 8 : 5;
        if (o.score >= limit) {
            // enough is enough: a case for the guards (pay for the damage, or be arrested)
            Justice.report(world, village, player, Justice.Crime.VANDALISM, o.score, null, true);
            o.score = 0;
        } else if (o.score >= limit * 0.4 && world.getTime() - o.lastWarn > 200) {
            o.lastWarn = world.getTime();
            VillagerEntityMCA guard = world.getEntitiesByClass(VillagerEntityMCA.class, player.getBoundingBox().expand(40), VillagerEntityMCA::isGuard)
                    .stream().min(Comparator.comparingDouble(g -> g.squaredDistanceTo(player))).orElse(null);
            if (guard != null) {
                guard.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(player, 0.6f, 2));
                guard.sendChatMessage(player, "settlements.break.warn");
            } else if (!villagers.isEmpty()) {
                villagers.get(0).sendChatMessage(player, "settlements.break.warn");
            }
        }
    }

    private static void decay(Offense o, long now) {
        if (o.lastUpdate != 0) {
            o.score = Math.max(0, o.score - (now - o.lastUpdate) / 20.0 * DECAY_PER_SECOND);
        }
        o.lastUpdate = now;
    }
}
