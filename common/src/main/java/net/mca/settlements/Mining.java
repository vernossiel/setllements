package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.settlements.Blueprints.Step;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Miners really dig the village mine, level after level: they walk to the mine, go down the stairs and break stone and
 * ore block by block; everything they break goes to the treasury. If the path down fails they are taken to the
 * working face after a while, so they never get stuck for good.
 */
public final class Mining {
    private static final int MAX_LEVEL = 8;
    private static final int STONE_QUOTA = 1200;
    private static final Map<Integer, List<Step>> LEVELS = new HashMap<>();

    private Mining() {
    }

    public static boolean work(VillagerEntityMCA v, Village village) {
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        if (village.mineOrigin == null) {
            // the miner opens the mine himself: he picks the spot and starts digging the stair shaft
            BlockPos site = Builder.chooseMineSite(world, village);
            if (site == null) return false;
            village.mineOrigin = site;
            village.mineLevel = 0;
            village.mineStep = 0;
            village.plots.add(new int[]{site.getX(), site.getZ(), 4, 4});
            village.markDirty();
            Speech.say(v, "settlements.say.build.mine");
        }
        Workforce.craftTorches(village); // the mine needs light: torches from charcoal burnt out of logs
        if (Dig.busy(v)) return true; // still at the rock
        Dig.gather(v, 3); // what he broke goes into his pockets, then to the stores
        if (village.mineLevel > MAX_LEVEL || Builder.stockCobble(village) >= STONE_QUOTA) return false;

        List<Step> steps = LEVELS.computeIfAbsent(village.mineLevel, k -> k == 0 ? Blueprints.mine() : Blueprints.mineExtension(k));
        if (village.mineStep >= steps.size()) {
            if (village.mineLevel == 0) village.mineBuilt = true; // the shaft and the first galleries are open
            village.mineLevel++;
            village.mineStep = 0;
            village.markDirty();
            return false;
        }
        BlockPos o = village.mineOrigin;
        BlockPos stand = standFor(world, o, steps, village);
        if (world.getBlockState(v.getBlockPos()).isSolidBlock(world, v.getBlockPos()) || world.getBlockState(v.getBlockPos().up()).isSolidBlock(world, v.getBlockPos().up())) {
            // caught inside the rock or a timber frame: out to the working face
            v.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, v.getYaw(), 0);
            v.getNavigation().stop();
        }
        if (v.squaredDistanceTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > 25) {
            // walk down; if the stairs do not work out, go straight to the face
            if (v.settlementsStuck++ > 25 && world.isChunkLoaded(stand.getX() >> 4, stand.getZ() >> 4)) {
                v.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, v.getYaw(), 0);
                v.settlementsStuck = 0;
            } else {
                Move.to(v, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.6);
            }
            return false;
        }
        v.settlementsStuck = 0;
        Speech.say(v, "settlements.say.mine.dig");
        for (int i = 0; i < v.getInventory().size(); i++) {
            if (v.getInventory().getStack(i).getItem() instanceof net.minecraft.item.PickaxeItem) {
                if (!(v.getMainHandStack().getItem() instanceof net.minecraft.item.PickaxeItem)) v.setStackInHand(v.getDominantHand(), v.getInventory().getStack(i).copy());
                break;
            }
        }

        boolean worked = false;
        int ops = 0, skips = 0;
        int budget = Prosperity.ops(v, village, Builder.opsPerSecond);
        if (budget == 0) return true;
        while (ops < budget && skips < 60 && village.mineStep < steps.size()) {
            Step step = steps.get(village.mineStep);
            BlockPos target = o.add(step.dx(), step.dy(), step.dz());
            if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 20) break; // within arm's reach
            var here = world.getBlockState(target);
            if (step.kind() == Blueprints.Kind.DIG && (here.isIn(net.minecraft.registry.tag.BlockTags.IRON_ORES) || here.isIn(net.minecraft.registry.tag.BlockTags.COAL_ORES)
                    || here.isIn(net.minecraft.registry.tag.BlockTags.GOLD_ORES) || here.isIn(net.minecraft.registry.tag.BlockTags.DIAMOND_ORES)
                    || here.isIn(net.minecraft.registry.tag.BlockTags.COPPER_ORES))) {
                Speech.sayAny(v, "settlements.say.mine.ore", 3); // a find
            }
            if (step.kind() == Blueprints.Kind.DIG && !world.getBlockState(target).isAir()) {
                boolean wet = false;
                for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) if (!world.getFluidState(target.offset(dir)).isEmpty()) wet = true;
                if (wet) { // water or lava ahead: this level is done
                    village.mineLevel++;
                    village.mineStep = 0;
                    break;
                }
                if (world.getBlockState(target).getBlock() instanceof net.minecraft.block.BlockWithEntity) {
                    village.mineStep++;
                    continue;
                }
                if (!Gear.wear(v, net.minecraft.item.PickaxeItem.class)) break; // the pick broke: a new one first
                Gear.face(v, target);
                Move.clearFalling(world, target, drop -> v.getInventory().addStack(drop));
                Dig.start(v, target); // block by block, with the pick, as anyone would
                worked = true;
                break;
            }
            Builder.COLLECTOR = v;
            Builder.Result r;
            try {
                r = Builder.execute(world, village, step, target, 0);
            } finally {
                Builder.COLLECTOR = null;
            }
            if (r == Builder.Result.ABORT) { // water or lava ahead: this level is done
                village.mineLevel++;
                village.mineStep = 0;
                break;
            }
            if (r == Builder.Result.WAIT) break;
            village.mineStep++;
            if (r == Builder.Result.PLACED) {
                ops++;
                worked = true;
                v.swingHand(v.getDominantHand());
            } else {
                skips++;
            }
        }
        village.markDirty();
        return worked;
    }

    public static boolean hasPick(VillagerEntityMCA v) {
        for (int i = 0; i < v.getInventory().size(); i++) if (v.getInventory().getStack(i).getItem() instanceof net.minecraft.item.PickaxeItem) return true;
        return false;
    }

    /** An already dug cell next to the next block to dig. */
    private static BlockPos standFor(ServerWorld world, BlockPos o, List<Step> steps, Village village) {
        if (village.mineStep > 0) {
            // the last cell behind the face that is open air with a floor, not a support beam or a torch
            for (int i = Math.min(village.mineStep, steps.size()) - 1; i >= Math.max(0, village.mineStep - 40); i--) {
                Step prev = steps.get(i);
                BlockPos p = o.add(prev.dx(), prev.dy(), prev.dz());
                if (world.getBlockState(p).isAir() && world.getBlockState(p.up()).isAir() && world.getBlockState(p.down()).isSolidBlock(world, p.down())) return p;
            }
            Step prev = steps.get(Math.min(village.mineStep, steps.size()) - 1);
            return o.add(prev.dx(), prev.dy(), prev.dz());
        }
        if (village.mineLevel == 0) return o; // the entrance, on the surface
        int depth = Blueprints.MINE_DEPTH;
        return o.add(0, -depth + 1, depth + 4 + 8 * (village.mineLevel - 1)); // the end of the drift so far
    }
}
