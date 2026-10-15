package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.settlements.Blueprints.Step;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
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
            Move.putSafely(v, stand);
            v.getNavigation().stop();
        }
        if (v.squaredDistanceTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > 25
                && stand.getY() < o.getY() - 2 && v.getY() > stand.getY() + 2 && shaftWalk(v, o, false)) {
            return false; // down the stairs of the shaft, step by step
        }
        if (v.squaredDistanceTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > 25) {
            // walk down; if the stairs do not work out, go straight to the face
            if (v.settlementsStuck++ > 45 && world.isChunkLoaded(stand.getX() >> 4, stand.getZ() >> 4)) {
                Move.putSafely(v, stand); // set down on the floor of the drift, never in the air or the rock
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

        if (v.getY() < o.getY() - 3 && prospect(v, world)) return true; // a vein: after it first

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
                if (!Dig.sees(v, target)) { // not from here: to the face first
                    Move.to(v, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
                    break;
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

    static boolean ore(BlockState s) {
        return s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.COPPER_ORES) || s.isIn(BlockTags.GOLD_ORES)
                || s.isIn(BlockTags.REDSTONE_ORES) || s.isIn(BlockTags.LAPIS_ORES) || s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.EMERALD_ORES);
    }

    private static boolean wet(ServerWorld world, BlockPos p) {
        for (Direction d : Direction.values()) if (!world.getFluidState(p.offset(d)).isEmpty()) return true;
        return false;
    }

    /**
     * A miner keeps his eyes open: ore showing in the walls around him he takes out, block after block along the
     * vein; ore a few blocks into the rock beside the drift (the colour in a crack, the sound of the rock) he cuts a
     * short side passage to. True while he is at it.
     */
    private static boolean prospect(VillagerEntityMCA v, ServerWorld world) {
        BlockPos feet = v.getBlockPos();
        BlockPos shown = null, hidden = null;
        double sd = Double.MAX_VALUE, hd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.iterate(feet.add(-5, -1, -5), feet.add(5, 3, 5))) {
            BlockState s = world.getBlockState(p);
            if (!ore(s)) continue;
            double d = p.getSquaredDistance(feet);
            boolean open = false;
            for (Direction dir : Direction.values()) if (world.getBlockState(p.offset(dir)).isAir()) open = true;
            if (open && d <= 20 && Dig.sees(v, p)) {
                if (d < sd) {
                    sd = d;
                    shown = p.toImmutable();
                }
            } else if (p.getY() >= feet.getY() && p.getY() <= feet.getY() + 1 && d < hd) {
                hd = d;
                hidden = p.toImmutable();
            }
        }
        BlockPos dig = shown;
        if (dig == null && hidden != null) {
            // a side passage to it, two high, straight along one axis and then the other
            BlockPos c = feet;
            while (!c.equals(hidden.withY(feet.getY())) && dig == null) {
                int dx = Integer.signum(hidden.getX() - c.getX()), dz = Integer.signum(hidden.getZ() - c.getZ());
                c = dx != 0 ? c.add(dx, 0, 0) : c.add(0, 0, dz);
                for (BlockPos cell : new BlockPos[]{c.up(), c}) {
                    if (world.getBlockState(cell).isAir()) continue;
                    if (v.squaredDistanceTo(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5) > 20 || !Dig.sees(v, cell)) {
                        Move.to(v, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0.55); // further in along the passage
                        return true;
                    }
                    dig = cell;
                    break;
                }
            }
        }
        if (dig == null) return false;
        BlockState s = world.getBlockState(dig);
        if (wet(world, dig) || s.getBlock() instanceof net.minecraft.block.BlockWithEntity || s.getHardness(world, dig) < 0) return false;
        if (!Gear.wear(v, net.minecraft.item.PickaxeItem.class)) return false;
        if (ore(s)) Speech.sayAny(v, "settlements.say.mine.ore", 3);
        Gear.face(v, dig);
        Move.clearFalling(world, dig, drop -> v.getInventory().addStack(drop));
        Dig.start(v, dig);
        return true;
    }

    /** Whether he is somewhere in the stair shaft of this mine. */
    static boolean inShaft(VillagerEntityMCA v, BlockPos o) {
        double rx = v.getX() - o.getX(), rz = v.getZ() - o.getZ();
        return rx >= -0.5 && rx <= 2.5 && rz >= -0.5 && rz <= Blueprints.MINE_DEPTH + 0.5 && v.getY() <= o.getY() + 1.5 && v.getY() >= o.getY() - Blueprints.MINE_DEPTH - 1;
    }

    /**
     * The way down (or up) the mine is known, so he walks it: to the mouth of the shaft under its shed, then from step
     * to step, as anyone would on a stair. True while on the way.
     */
    static boolean shaftWalk(VillagerEntityMCA v, BlockPos o, boolean up) {
        int depth = Blueprints.MINE_DEPTH;
        if (!inShaft(v, o)) {
            if (up) return false;
            Move.to(v, o.getX() + 1.0, o.getY(), o.getZ() - 0.5, 0.55); // the mouth, in front of the shed
            return true;
        }
        double rz = v.getZ() - o.getZ();
        int here = (int) Math.max(0, Math.min(depth - 1, Math.floor(rz + 0.5)));
        int i = up ? here - 1 : here + 1;
        if (i < 0) {
            Move.to(v, o.getX() + 1.0, o.getY(), o.getZ() - 1.5, 0.55); // out at the top
            return true;
        }
        if (i >= depth) return false; // at the bottom: the landing
        BlockPos next = o.add(v.getX() - o.getX() < 1 ? 0 : 1, -i, i);
        v.getNavigation().stop();
        v.getMoveControl().moveTo(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 0.5);
        if (up) v.getJumpControl().setActive();
        return true;
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
