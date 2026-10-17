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
    static final int QUOTA = STONE_QUOTA;
    private static final Map<Integer, List<Step>> LEVELS = new HashMap<>();
    private static final Map<java.util.UUID, long[]> SHED = new HashMap<>(); // miner -> {step, since}
    private static final Map<java.util.UUID, long[]> VEIN = new HashMap<>(); // miner -> {the ore he is cutting a way to, since}
    private static final java.util.Set<Long> GIVEN_UP = new java.util.HashSet<>(); // ores he could not get to

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
        reserve(village);
        Workforce.craftTorches(village); // the mine needs light: torches from charcoal burnt out of logs
        if (Dig.busy(v)) {
            Doing.count("m.digging");
            return true; // still at the rock
        }
        Dig.gather(v, 3); // what he broke goes into his pockets, then to the stores
        Orders.Order order = Orders.take(v, village, Needs.Need.COBBLE); // the builder's order for stone
        // the stone wanted, or ore: the miner's real work is the ore, and he goes on after it with stone enough in the stores
        if (village.mineLevel > MAX_LEVEL || order == null && Builder.stockCobble(village) >= STONE_QUOTA && !oreWanted(village)) return false;
        if (order != null) Doing.set(v, "settlements.doing.mine.order", order.amount(), order.forWhom());
        else Doing.set(v, "settlements.doing.mine", village.mineLevel + 1);

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
                && stand.getY() < o.getY() - 2 && v.getY() > stand.getY() + 2) {
            // down the stairs of the shaft, step by step; a mouth he cannot get into (the shed in the way, a hole
            // beside it) does not keep him there all day: after a minute he is set down at the working face
            Doing.count("m.shaft");
            if (v.settlementsStuck++ > 15 && world.isChunkLoaded(stand.getX() >> 4, stand.getZ() >> 4)) { // (a quarter of a minute; it was a minute at every trip down, a third of his day)
                Doing.count("m.put.shaft");
                if (!Move.putSafely(v, stand)) lostWay(village);
                v.settlementsStuck = 0;
                return false;
            }
            if (shaftWalk(v, o, false)) {
                Doing.count(inShaft(v, o) ? "m.shaft.in" : "m.shaft.mouth");
                return false;
            }
            Doing.count("m.shaft.nowalk");
        }
        if (v.squaredDistanceTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > 25) {
            // walk down; if the stairs do not work out, go straight to the face
            Doing.count("m.walk");
            if (v.settlementsStuck++ > 15 && world.isChunkLoaded(stand.getX() >> 4, stand.getZ() >> 4)) {
                Doing.count("m.put.face");
                // set down on the floor of the drift, never in the air or the rock; no floor down there to stand on
                // (the way down was never finished): the mine is gone over again from the top, the gaps dug out
                if (!Move.putSafely(v, stand)) lostWay(village);
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

        if (v.getY() < o.getY() - 3 && prospect(v, world)) {
            Doing.count("m.prospect");
            return true; // a vein: after it first
        }

        boolean worked = false;
        int ops = 0, skips = 0;
        int budget = Prosperity.ops(v, village, Builder.opsPerSecond);
        if (budget == 0) return true;
        while (ops < budget && skips < 60 && village.mineStep < steps.size()) {
            Step step = steps.get(village.mineStep);
            BlockPos target = o.add(step.dx(), step.dy(), step.dz());
            if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 20) {
                Doing.count("m.outofreach");
                if (step.dy() < 0) break; // within arm's reach (down in the rock: the way to the face is walked above)
                // the shed over the mouth: he goes round it to the block, as a builder would; the corner of its roof
                // was out of reach from the mouth, and there he stood, day after day, the mine not begun
                long[] sh = SHED.computeIfAbsent(v.getUuid(), k -> new long[]{-1, 0});
                if (sh[0] != village.mineStep) {
                    sh[0] = village.mineStep;
                    sh[1] = world.getTime();
                }
                if (world.getTime() - sh[1] < 400) {
                    if (!Scaffold.inReach(v, target)) {
                        Move.to(v, target.getX() + 0.5, o.getY(), target.getZ() + 0.5, 0.6);
                        break;
                    }
                } // twenty seconds and no nearer: laid from where he is
            }
            var here = world.getBlockState(target);
            if (step.kind() == Blueprints.Kind.DIG && (here.isIn(net.minecraft.registry.tag.BlockTags.IRON_ORES) || here.isIn(net.minecraft.registry.tag.BlockTags.COAL_ORES)
                    || here.isIn(net.minecraft.registry.tag.BlockTags.GOLD_ORES) || here.isIn(net.minecraft.registry.tag.BlockTags.DIAMOND_ORES)
                    || here.isIn(net.minecraft.registry.tag.BlockTags.COPPER_ORES))) {
                Speech.sayAny(v, "settlements.say.mine.ore", 3); // a find
            }
            // the mine's own steps and timbers are not dug out again when the plan is gone over from the top (each time it
            // was, he broke his own stair from under his feet)
            if (step.kind() == Blueprints.Kind.DIG && (here.getBlock() instanceof net.minecraft.block.StairsBlock || here.isIn(net.minecraft.registry.tag.BlockTags.PLANKS)
                    || here.isIn(net.minecraft.registry.tag.BlockTags.LOGS) || here.getBlock() instanceof net.minecraft.block.TorchBlock)) {
                village.mineStep++;
                continue;
            }
            if (step.kind() == Blueprints.Kind.DIG && !world.getBlockState(target).isAir()) {
                boolean wet = false;
                for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) if (!world.getFluidState(target.offset(dir)).isEmpty()) wet = true;
                if (wet && village.mineLevel == 0) { // water in the very shaft: no going on down there, a new site
                    village.mineOrigin = null;
                    village.mineStep = 0;
                    Speech.sayNow(v, "settlements.say.mine.flooded");
                    break;
                }
                if (wet) { // water or lava ahead in a gallery: this level is done
                    village.mineLevel++;
                    village.mineStep = 0;
                    break;
                }
                if (world.getBlockState(target).getBlock() instanceof net.minecraft.block.BlockWithEntity) {
                    village.mineStep++;
                    continue;
                }
                if (!Dig.sees(v, target)) { // not from here: to the face first
                    Doing.count("m.unseen");
                    Move.to(v, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
                    break;
                }
                if (!Gear.wearFor(v, world.getBlockState(target))) break; // the right tool for it (a shovel for earth and gravel, the pick for rock); broke: a new one first
                Gear.face(v, target);
                Move.clearFalling(world, target, drop -> v.getInventory().addStack(drop));
                Dig.start(v, target); // block by block, with the pick, as anyone would
                Doing.count("m.dig");
                worked = true;
                break;
            }
            // the stone for the steps and the floor is the stone he has just dug, from his own pockets, not from the
            // stores up top (empty of it: the steps were passed over, the shaft left a row of bare ledges)
            if (step.kind() != Blueprints.Kind.DIG) {
                var inv = v.getInventory();
                for (int i = 0; i < inv.size(); i++) {
                    net.minecraft.item.ItemStack st = inv.getStack(i);
                    if (st.isOf(net.minecraft.item.Items.COBBLESTONE) || st.isOf(net.minecraft.item.Items.COBBLED_DEEPSLATE)) {
                        Workforce.addToTreasury(village, st.split(Math.min(st.getCount(), 4)));
                        break;
                    }
                }
            }
            Builder.COLLECTOR = v;
            Builder.Result r;
            try {
                r = Builder.execute(world, village, step, target, 0);
            } finally {
                Builder.COLLECTOR = null;
            }
            if (r == Builder.Result.ABORT && village.mineLevel == 0) { // water or lava in the very shaft: a new site
                village.mineOrigin = null;
                village.mineStep = 0;
                Speech.sayNow(v, "settlements.say.mine.flooded");
                break;
            }
            if (r == Builder.Result.ABORT) { // water or lava ahead: this level is done
                village.mineLevel++;
                village.mineStep = 0;
                break;
            }
            if (r == Builder.Result.WAIT) {
                // no planks for the shed, a post or a beam: the digging goes on without it (the builders can add it later)
                if (step.kind() == Blueprints.Kind.DIG) break;
                if (world.random.nextInt(20) == 0) Speech.say(v, "settlements.say.mine.noplanks");
                Doing.set(v, "settlements.doing.mine.noplanks");
                Doing.count("m.noplanks");
                village.mineStep++;
                skips++;
                continue;
            }
            village.mineStep++;
            if (r == Builder.Result.PLACED) {
                Doing.count("m.placed");
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
        for (BlockPos p : BlockPos.iterate(feet.add(-8, -1, -8), feet.add(8, 3, 8))) {
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
        // what he saw he remembers; with nothing in view he goes back to an ore he noted nearby before
        if (v.getResidency().getHomeVillage().isPresent()) {
            java.util.Set<BlockPos> known = v.getResidency().getHomeVillage().get().knownOres; // kept with the village
            known.removeIf(p -> world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4) && !ore(world.getBlockState(p)));
            if (shown != null) known.add(shown);
            if (hidden != null && known.size() < 128) known.add(hidden);
            if (shown == null && hidden == null) {
                double kd = 12 * 12;
                for (BlockPos p : known) {
                    double d = p.getSquaredDistance(feet);
                    if (d < kd && Math.abs(p.getY() - feet.getY()) <= 1) {
                        kd = d;
                        hidden = p;
                    }
                }
                if (hidden != null) Doing.set(v, "settlements.doing.mine.remembered");
            }
        }
        BlockPos dig = shown;
        if (dig == null && hidden != null) {
            // one vein at a time, and not for ever: forty seconds after it with nothing to show, he leaves it be
            long now = world.getTime();
            long[] t = VEIN.computeIfAbsent(v.getUuid(), k -> new long[]{Long.MIN_VALUE, 0});
            if (t[0] != hidden.asLong()) {
                t[0] = hidden.asLong();
                t[1] = now;
            } else if (now - t[1] > 800) {
                if (v.getResidency().getHomeVillage().isPresent()) v.getResidency().getHomeVillage().get().knownOres.remove(hidden);
                GIVEN_UP.add(hidden.asLong());
                VEIN.remove(v.getUuid());
                return false;
            }
            if (GIVEN_UP.contains(hidden.asLong())) return false;
            // a side passage to it, two high, straight along one axis and then the other
            BlockPos c = feet, back = feet;
            while (!c.equals(hidden.withY(feet.getY())) && dig == null) {
                int dx = Integer.signum(hidden.getX() - c.getX()), dz = Integer.signum(hidden.getZ() - c.getZ());
                back = c;
                c = dx != 0 ? c.add(dx, 0, 0) : c.add(0, 0, dz);
                for (BlockPos cell : new BlockPos[]{c.up(), c}) {
                    if (world.getBlockState(cell).isAir()) continue;
                    if (v.squaredDistanceTo(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5) > 20 || !Dig.sees(v, cell)) {
                        // to the end of the passage cut so far (it was the rock itself he was sent to walk into, all day)
                        Move.to(v, back.getX() + 0.5, back.getY(), back.getZ() + 0.5, 0.55);
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
        if (!Gear.wearFor(v, s)) return false;
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
    /** The workings cannot be got to: back to the top of the plan, where what is dug already is passed over quickly. */
    /**
     * The whole run of the shaft and its landing kept free of building, not only the shed over its mouth: a house was
     * put up over the stair, its footing filled the shaft below, and the miner could not get down to his work.
     */
    static void reserve(Village village) {
        BlockPos o = village.mineOrigin;
        if (o == null) return;
        int x = o.getX() - 2, z = o.getZ() - 1, w = 7, d = Blueprints.MINE_DEPTH + 7;
        for (int[] p : village.plots) if (p[0] == x && p[1] == z && p[2] == w && p[3] == d) return;
        village.plots.add(new int[]{x, z, w, d});
        village.markDirty();
    }

    /** Something to stand on: a full block, a step or a slab. */
    static boolean floor(ServerWorld world, BlockPos p) {
        var st = world.getBlockState(p);
        return st.isSolidBlock(world, p) || st.getBlock() instanceof net.minecraft.block.StairsBlock || st.getBlock() instanceof net.minecraft.block.SlabBlock;
    }

    private static void lostWay(Village village) {
        village.mineLevel = 0;
        village.mineStep = 0;
        village.markDirty();
    }

    static boolean shaftWalk(VillagerEntityMCA v, BlockPos o, boolean up) {
        int depth = Blueprints.MINE_DEPTH;
        if (!inShaft(v, o)) {
            if (up) return false;
            double mx = o.getX() + 1.0 - v.getX(), mz = o.getZ() - 1.0 - v.getZ();
            if (mx * mx + mz * mz < 2.5 && Math.abs(v.getY() - o.getY()) < 1.5) {
                // at the mouth: onto the top stair, in under the shed roof
                v.getNavigation().stop();
                v.getMoveControl().moveTo(o.getX() + (v.getX() - o.getX() < 1 ? 0.5 : 1.5), o.getY(), o.getZ() + 0.5, 0.55);
                return true;
            }
            Move.to(v, o.getX() + 1.0, o.getY(), o.getZ() - 1.0, 0.55); // the mouth, in front of the shed
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

    /** The stores could do with coal or iron (for the torches, the tools, the smithy to come). */
    static boolean oreWanted(Village village) {
        int coal = 0, iron = 0;
        for (net.minecraft.item.ItemStack s : village.storageBuffer) {
            if (s.isOf(net.minecraft.item.Items.COAL)) coal += s.getCount();
            if (s.isOf(net.minecraft.item.Items.RAW_IRON) || s.isOf(net.minecraft.item.Items.IRON_INGOT)) iron += s.getCount();
        }
        return coal < 64 || iron < 64;
    }

    public static boolean hasPick(VillagerEntityMCA v) {
        for (int i = 0; i < v.getInventory().size(); i++) if (v.getInventory().getStack(i).getItem() instanceof net.minecraft.item.PickaxeItem) return true;
        return false;
    }

    /** An already dug cell next to the next block to dig. */
    private static BlockPos standFor(ServerWorld world, BlockPos o, List<Step> steps, Village village) {
        // the shed over the shaft and the first cut, on the surface: from the mouth of the mine (the cells behind him
        // are all posts and walls then, and he would be sent to stand inside the last wall he put up)
        if (village.mineLevel == 0 && village.mineStep < steps.size() && steps.get(village.mineStep).dy() >= 0) return o;
        if (village.mineStep > 0) {
            // the last cell behind the face that is open air with a floor, not a support beam or a torch
            for (int i = Math.min(village.mineStep, steps.size()) - 1; i >= Math.max(0, village.mineStep - 40); i--) {
                Step prev = steps.get(i);
                BlockPos p = o.add(prev.dx(), prev.dy(), prev.dz());
                // (a step is a floor too: taking only full blocks, the miner was sent to stand forty steps back up the
                // shaft, out of reach of the face, and walked up and down it all day)
                if (world.getBlockState(p).isAir() && world.getBlockState(p.up()).isAir() && floor(world, p.down())) return p;
            }
            Step prev = steps.get(Math.min(village.mineStep, steps.size()) - 1);
            return o.add(prev.dx(), prev.dy(), prev.dz());
        }
        if (village.mineLevel == 0) return o; // the entrance, on the surface
        int depth = Blueprints.MINE_DEPTH;
        return o.add(0, -depth + 1, depth + 4 + 8 * (village.mineLevel - 1)); // the end of the drift so far
    }
}
