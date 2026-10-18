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
    private static final Map<java.util.UUID, long[]> FACE = new HashMap<>(); // miner -> {level<<32|step out of reach, since}
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
        if (climbOut(v, world)) return true;
        if (village.mineOrigin != null && keepStairs(v, village, world)) return true; // a step of the shaft gone: put back first
        if (village.mineOrigin != null && seal(v, village, world)) return true; // the shaft broken through to the open: walled up
        // up top by the mouth: the shed whole and its doors hung, before he goes down (it was looked to only at the
        // face, where he never is up top, and the shed stood half built for good)
        if (village.mineOrigin != null && mouth(v, village, world, village.mineOrigin)) return true;
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
            // caught inside the rock or a timber frame: out beside it (to the working face only where nobody sees)
            if (!Move.shoveOut(v) && Bed.unseen(v, world)) Move.putSafely(v, stand);
            v.getNavigation().stop();
        }
        if (v.squaredDistanceTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > 25) {
            // down the stairs of the shaft and along the drift to the face, as anyone walks (his own route takes the
            // stairs); only when that has got him nowhere, and nobody sees, is he set down at the face
            Doing.count("m.walk");
            boolean onWay = Route.active(v, stand) || v.getNavigation().isFollowingPath();
            if (onWay) v.settlementsStuck = 0;
            if (!onWay && v.settlementsStuck++ > 20 && Bed.unseen(v, world) && world.isChunkLoaded(stand.getX() >> 4, stand.getZ() >> 4)) {
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

        if (redo(v, village, world, o)) return true; // the shed or a step left for want of planks or stone, now there is some

        boolean worked = false;
        int ops = 0, skips = 0;
        int budget = Prosperity.ops(v, village, Builder.opsPerSecond);
        if (budget == 0) return true;
        while (ops < budget && skips < 60 && village.mineStep < steps.size()) {
            Step step = steps.get(village.mineStep);
            BlockPos target = o.add(step.dx(), step.dy(), step.dz());
            if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 20) {
                Doing.count("m.outofreach");
                if (step.dy() < 0) {
                    // down in the rock: on to it along what is dug (he stood out of reach of the face for days, the mine
                    // stopped); a block not to be got near in a minute is passed over, the next one taken
                    long[] fr = FACE.computeIfAbsent(v.getUuid(), k -> new long[]{-1, 0});
                    long key = ((long) village.mineLevel << 32) | village.mineStep;
                    if (fr[0] != key) {
                        fr[0] = key;
                        fr[1] = world.getTime();
                    }
                    if (world.getTime() - fr[1] > 20 * 20) {
                        village.mineStep++;
                        skips++;
                        continue;
                    }
                    // (no walking off to it: the way to a block inside the rock is through the rock, and his route
                    // cut a long tunnel of its own through it, off the plan)
                    break;
                }
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
                SKIPPED.computeIfAbsent(village.getId(), k -> new java.util.LinkedHashSet<>()).add(((long) village.mineLevel << 32) | village.mineStep); // to come back to
                Doing.set(v, "settlements.doing.mine.noplanks");
                Doing.count("m.noplanks");
                village.mineStep++;
                skips++;
                continue;
            }
            village.mineStep++;
            if (r == Builder.Result.PLACED) {
                Doing.count("m.placed"); Doing.laid(v);
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


    /** Per village: the steps of the shed and the shaft passed over for want of planks or stone ({level, step} packed). */
    private static final Map<Integer, java.util.LinkedHashSet<Long>> SKIPPED = new HashMap<>();

    /**
     * The shed and the steps he had to leave go in when the stuff for them is there: the shed stood half built for
     * good, its door open, and the stair a row of bare ledges. True while at it.
     */
    private static boolean redo(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos o) {
        var left = SKIPPED.get(village.getId());
        if (left == null || left.isEmpty() || world.getTime() % 20 != 0 && v.getRandom().nextInt(3) != 0) return false;
        var it = left.iterator();
        while (it.hasNext()) {
            long k = it.next();
            int level = (int) (k >>> 32), idx = (int) k;
            List<Step> steps = LEVELS.computeIfAbsent(level, l -> l == 0 ? Blueprints.mine() : Blueprints.mineExtension(l));
            if (idx >= steps.size()) {
                it.remove();
                continue;
            }
            Step step = steps.get(idx);
            BlockPos target = o.add(step.dx(), step.dy(), step.dz());
            if (!world.getBlockState(target).isAir() && !world.getBlockState(target).isReplaceable()) {
                it.remove(); // put in meanwhile (the builders, a player)
                continue;
            }
            if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 20) {
                if (Math.abs(target.getY() - v.getBlockY()) > 3) return false; // not on his way: when he passes it
                Move.to(v, target.getX() + 0.5, v.getY(), target.getZ() + 0.5, 0.6);
                return true;
            }
            Builder.COLLECTOR = v;
            Builder.Result r;
            try {
                r = Builder.execute(world, village, step, target, 0);
            } finally {
                Builder.COLLECTOR = null;
            }
            if (r == Builder.Result.WAIT) return false; // still none: on with the digging
            it.remove();
            if (r == Builder.Result.PLACED) {
                Gear.face(v, target);
                v.swingHand(v.getDominantHand());
                Doing.count("m.redo");
                return true;
            }
        }
        return false;
    }

    /**
     * A step of the stair shaft gone (dug out by mistake, blown, taken by a player): put back as he passes it, from the
     * stone in his pockets or the stores - the way out of the mine is kept whole. True while at it.
     */
    public static boolean keepStairs(VillagerEntityMCA v, Village village, ServerWorld world) {
        BlockPos o = village.mineOrigin;
        if (o == null || !inShaft(v, o) && v.squaredDistanceTo(o.getX() + 1, o.getY(), o.getZ()) > 6 * 6) return false;
        List<Step> steps = LEVELS.computeIfAbsent(0, k -> Blueprints.mine());
        int done = village.mineLevel > 0 ? steps.size() : village.mineStep;
        for (int i = 0; i < Math.min(done, steps.size()); i++) {
            Step step = steps.get(i);
            if (step.kind() != Blueprints.Kind.STAIRS_N) continue;
            BlockPos target = o.add(step.dx(), step.dy(), step.dz());
            if (v.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 20) continue;
            BlockState here = world.getBlockState(target);
            if (!here.isAir() && !here.isReplaceable()) continue; // standing (or rock: not dug out yet)
            if (v.getBlockPos().equals(target)) continue; // (not into the cell he stands in)
            var inv = v.getInventory(); // the stone for it from his pockets first, as for the steps when they were cut
            for (int k = 0; k < inv.size(); k++) {
                net.minecraft.item.ItemStack st = inv.getStack(k);
                if (st.isOf(net.minecraft.item.Items.COBBLESTONE) || st.isOf(net.minecraft.item.Items.COBBLED_DEEPSLATE)) {
                    Workforce.addToTreasury(village, st.split(1));
                    break;
                }
            }
            Builder.COLLECTOR = v;
            Builder.Result r;
            try {
                r = Builder.execute(world, village, step, target, 0);
            } finally {
                Builder.COLLECTOR = null;
            }
            if (r == Builder.Result.PLACED) {
                Gear.face(v, target);
                v.swingHand(v.getDominantHand());
                Doing.count("m.stair");
                Speech.sayAny(v, "settlements.say.mine.stair", 3);
                return true;
            }
        }
        return false;
    }

    /**
     * The shaft kept shut: where its side or its roof has broken through to the open (the stair cut out of a hillside,
     * into a cave, past the end of the shed), the gap is walled up with stone, as he passes it. The shaft had a hole
     * to the grass beside the shed that anything could fall or walk into. True while at it.
     */
    static boolean seal(VillagerEntityMCA v, Village village, ServerWorld world) {
        BlockPos o = village.mineOrigin;
        if (o == null || !inShaft(v, o) && v.squaredDistanceTo(o.getX() + 1, o.getY(), o.getZ()) > 6 * 6) return false;
        for (int i = 0; i < Blueprints.MINE_DEPTH; i++) {
            if (!world.getBlockState(o.add(0, -i, i)).isAir()) continue; // not cut down to here yet
            java.util.List<BlockPos> wall = new java.util.ArrayList<>();
            for (int y = -i; y <= -i + 2; y++) {
                if (y >= 0 && i <= 3) continue; // up there the shed's own walls
                wall.add(o.add(-1, y, i));
                wall.add(o.add(2, y, i));
            }
            if (i >= 4) for (int x = 0; x <= 1; x++) wall.add(o.add(x, -i + 3, i)); // the roof of the cut, past the shed
            for (BlockPos g : wall) {
                BlockState st = world.getBlockState(g);
                if (!st.isAir() && !(st.isReplaceable() && st.getFluidState().isEmpty())) continue;
                if (v.squaredDistanceTo(g.getX() + 0.5, g.getY() + 0.5, g.getZ() + 0.5) > 20) continue;
                if (v.getBoundingBox().intersects(new net.minecraft.util.math.Box(g))) continue;
                BlockState put = null;
                var inv = v.getInventory();
                for (int k = 0; k < inv.size() && put == null; k++) {
                    net.minecraft.item.ItemStack it = inv.getStack(k);
                    if (it.isOf(net.minecraft.item.Items.COBBLESTONE) || it.isOf(net.minecraft.item.Items.COBBLED_DEEPSLATE) || it.isOf(net.minecraft.item.Items.DIRT)) {
                        put = net.minecraft.block.Block.getBlockFromItem(it.getItem()).getDefaultState();
                        it.decrement(1);
                    }
                }
                if (put == null && Builder.takeItem(village, it -> it.isOf(net.minecraft.item.Items.COBBLESTONE), 1)) put = net.minecraft.block.Blocks.COBBLESTONE.getDefaultState();
                if (put == null) return false; // nothing to wall it up with now
                Gear.face(v, g);
                world.setBlockState(g, put);
                world.playSound(null, g, put.getSoundGroup().getPlaceSound(), net.minecraft.sound.SoundCategory.BLOCKS, 0.8f, 1f);
                v.swingHand(v.getDominantHand());
                Doing.count("m.seal"); Doing.event(v, "walls up the shaft with", g, put);
                return true;
            }
        }
        return false;
    }

    private static List<Step> MOUTH;
    private static final Map<Integer, Long> MOUTH_WAIT = new HashMap<>(); // village -> no stuff for it till then
    private static final Map<java.util.UUID, long[]> MOUTH_TRY = new HashMap<>(); // miner -> {the block he is trying for, since}
    private static final Map<Long, Long> MOUTH_SKIP = new HashMap<>(); // blocks of the shed not to be got at: left till then
    private static final Map<Integer, java.util.LinkedHashSet<Long>> ASKED = new HashMap<>(); // village -> shed blocks the builder was asked to put in

    /**
     * The builder's turn at the mine's shed: the blocks the miner could not get at and asked him for, put in from his
     * scaffolding as on any building. True while he is at it.
     */
    public static boolean helpAtShed(VillagerEntityMCA v, Village village, ServerWorld world) {
        var asked = ASKED.get(village.getId());
        BlockPos o = village.mineOrigin;
        if (asked == null || asked.isEmpty() || o == null) return false;
        if (MOUTH == null) MOUTH = Blueprints.mineMouth();
        Built site = new Built("shed", o.add(-1, 0, -1), 4, 5, 1, 0);
        java.util.Set<Long> planned = new java.util.HashSet<>(asked);
        for (java.util.Iterator<Long> it = asked.iterator(); it.hasNext(); ) {
            long key = it.next();
            BlockPos target = BlockPos.fromLong(key);
            Step step = null;
            for (Step st : MOUTH) if (o.add(st.dx(), st.dy(), st.dz()).equals(target)) step = st;
            BlockState here = world.getBlockState(target);
            if (step == null || !here.isAir() && !here.isReplaceable()) { // put in meanwhile
                it.remove();
                continue;
            }
            Doing.set(v, "settlements.doing.mine.helpshed");
            if (!Scaffold.inReach(v, target)) {
                if (Scaffold.approach(v, village, world, target, site, planned)) return true;
                if (!Scaffold.inReach(v, target)) {
                    Move.to(v, target.getX() + 0.5, o.getY(), target.getZ() - 1.5, 0.6);
                    return true;
                }
            }
            Gear.face(v, target);
            Builder.Result r = Builder.execute(world, village, step, target, 0);
            if (r == Builder.Result.WAIT) return false; // nothing to make it of yet
            it.remove();
            if (r == Builder.Result.PLACED) {
                v.swingHand(v.getDominantHand());
                Doing.laid(v);
                if (asked.isEmpty()) {
                    Speech.sayAny(v, "settlements.say.mine.shedhelped", 3);
                    Scaffold.clearAround(village, world, o.add(-1, 0, -1), 4, 5); // his scaffolding down again
                }
                return true;
            }
        }
        return false;
    }

    /** Up by the mouth of the mine, with the shed or its doors not whole: what is missing put in. True while at it. */
    private static boolean mouth(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos o) {
        if (village.mineLevel == 0 && village.mineStep < 60) return false; // the shed is being put up the first time
        if (Math.abs(v.getY() - o.getY()) > 2 || v.squaredDistanceTo(o.getX() + 1, o.getY(), o.getZ()) > 12 * 12) return false;
        if (world.getTime() < MOUTH_WAIT.getOrDefault(village.getId(), 0L)) return false;
        if (MOUTH == null) MOUTH = Blueprints.mineMouth();
        for (Step step : MOUTH) {
            BlockPos target = o.add(step.dx(), step.dy(), step.dz());
            BlockState here = world.getBlockState(target);
            // the shed of the old plan had a post at each front corner: boarded over now like the walls
            if (step.kind() == Blueprints.Kind.DIG) { // the doorway cut into the ground
                boolean earth = here.isIn(net.minecraft.registry.tag.BlockTags.DIRT) || here.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)
                        || here.isIn(net.minecraft.registry.tag.BlockTags.SAND) || here.isOf(net.minecraft.block.Blocks.GRAVEL) || here.isReplaceable();
                if (here.isAir() || !earth || !here.getFluidState().isEmpty()) continue;
                if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 16) {
                    Doing.set(v, "settlements.doing.mine.shed");
                    Move.to(v, target.getX() + 0.5, o.getY() + 1, target.getZ() - 1.5, 0.6);
                    return true;
                }
                Gear.face(v, target);
                Gear.toolFor(v, village, here);
                Dig.start(v, target);
                Doing.count("m.mouth");
                return true;
            }
            boolean oldPost = step.kind() == Blueprints.Kind.WALL && step.dz() == 0 && here.isIn(net.minecraft.registry.tag.BlockTags.LOGS);
            if (!here.isAir() && !here.isReplaceable() && !oldPost) continue; // standing
            if (step.kind() == Blueprints.Kind.DOOR && here.getBlock() instanceof net.minecraft.block.DoorBlock) {
                // the pair hung as a double door (both leaves were hung the same way)
                if (here.get(net.minecraft.block.DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.LOWER) {
                    BlockState hung = Builder.pairDoor(world, target, here);
                    if (hung != here) {
                        world.setBlockState(target, hung, net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                        world.setBlockState(target.up(), hung.with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER), net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                    }
                }
                continue;
            }
            long key = target.asLong();
            if (MOUTH_SKIP.getOrDefault(key, 0L) > world.getTime()) continue; // could not be got at a while ago: the rest first
            if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 16) {
                // a corner of the roof he cannot get at from the ground: twenty seconds, then laid from where he
                // stands if it is near, else left for later - he walked at the shed all day for one roof block, five
                // days running, and the mine stood still
                long[] tr = MOUTH_TRY.computeIfAbsent(v.getUuid(), k -> new long[]{key, world.getTime()});
                if (tr[0] != key) {
                    tr[0] = key;
                    tr[1] = world.getTime();
                }
                if (world.getTime() - tr[1] > 20 * 20) {
                    if (v.squaredDistanceTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 6.5 * 6.5) {
                        // out of his reach: he asks the builder, who has the scaffolding for it, and goes back to his digging
                        MOUTH_SKIP.put(key, world.getTime() + 20 * 600);
                        if (ASKED.computeIfAbsent(village.getId(), k -> new java.util.LinkedHashSet<>()).add(key)) {
                            Speech.sayAny(v, "settlements.say.mine.askbuilder", 3);
                            Doing.event(v, "asks the builder for", target, null);
                        }
                        continue;
                    }
                } else {
                    Doing.set(v, "settlements.doing.mine.shed");
                    Move.to(v, target.getX() + 0.5, o.getY(), target.getZ() - 1.5, 0.6); // from in front of the shed
                    return true;
                }
            }
            if (oldPost) {
                world.breakBlock(target, false, v);
                Workforce.addToTreasury(village, new net.minecraft.item.ItemStack(here.getBlock().asItem()));
            }
            Builder.COLLECTOR = v;
            Builder.Result r;
            try {
                r = Builder.execute(world, village, step, target, 0);
            } finally {
                Builder.COLLECTOR = null;
            }
            if (r == Builder.Result.WAIT || r == Builder.Result.ABORT) {
                MOUTH_WAIT.put(village.getId(), world.getTime() + 1200); // nothing to make it of: again in a minute
                return false;
            }
            Doing.set(v, "settlements.doing.mine.shed");
            if (r == Builder.Result.PLACED) {
                Gear.face(v, target);
                v.swingHand(v.getDominantHand());
                Doing.count("m.mouth");
                return true;
            }
        }
        MOUTH_WAIT.put(village.getId(), world.getTime() + 2400); // all standing: looked at again in a while
        return false;
    }

    /**
     * Down a hole with rock all round him and the sky (or the drift) open above: up out of it as a player would, a
     * jump and a block of the stone in his pockets under his feet, block by block.
     */
    private static boolean climbOut(VillagerEntityMCA v, ServerWorld world) {
        if (!v.isOnGround()) return false;
        BlockPos feet = v.getBlockPos();
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (!world.getBlockState(feet.offset(d)).isSolidBlock(world, feet.offset(d))) return false;
            if (!world.getBlockState(feet.offset(d).up()).isSolidBlock(world, feet.offset(d).up())) return false;
        }
        if (!world.getBlockState(feet.up(2)).isAir()) return false;
        // a way out within a few blocks up: the floor of the drift beside the hole
        boolean way = false;
        for (int h = 2; h <= 5 && !way; h++) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos q = feet.up(h).offset(d);
                if (world.getBlockState(q).isAir() && world.getBlockState(q.up()).isAir() && floor(world, q.down())) way = true;
            }
            if (!world.getBlockState(feet.up(h)).isAir()) break;
        }
        if (!way) return false;
        var inv = v.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack st = inv.getStack(i);
            if (!st.isOf(net.minecraft.item.Items.COBBLESTONE) && !st.isOf(net.minecraft.item.Items.COBBLED_DEEPSLATE) && !st.isOf(net.minecraft.item.Items.DIRT)) continue;
            Moves.jumpPlace(v, net.minecraft.block.Block.getBlockFromItem(st.getItem()).getDefaultState(), null); // the jump, the stone under him
            st.decrement(1);
            Doing.count("m.climbout");
            return true;
        }
        return false;
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
        for (BlockPos p : BlockPos.iterate(feet.add(-8, 0, -8), feet.add(8, 3, 8))) {
            BlockState s = world.getBlockState(p);
            if (!ore(s)) continue;
            double d = p.getSquaredDistance(feet);
            boolean open = false;
            for (Direction dir : Direction.values()) if (world.getBlockState(p.offset(dir)).isAir()) open = true;
            if (p.getY() < feet.getY()) continue; // not the floor under him: a vein going down took him down after it, block by block, into a pit with no way up
            if (open && d <= 20 && Dig.sees(v, p)) {
                if (d < sd) {
                    sd = d;
                    shown = p.toImmutable();
                }
            } else if (p.getY() >= feet.getY() && p.getY() <= feet.getY() + 1 && d < hd
                    && Math.abs(p.getX() - feet.getX()) + Math.abs(p.getZ() - feet.getZ()) <= 4) { // a short way in after it, no more (passages ran off all over)
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
                    if (d < kd && Math.abs(p.getY() - feet.getY()) <= 1 && Math.abs(p.getX() - feet.getX()) + Math.abs(p.getZ() - feet.getZ()) <= 4) {
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
