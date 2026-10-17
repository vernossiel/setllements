package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A real lumberjack: finds a real tree, walks to it, fells the trunk log by log (every log is an item he carries
 * away), and plants a sapling where it stood. Villages keep a stand of trees: nothing is cut when fewer than six
 * remain around, or when the stores already hold plenty of logs.
 */
public final class Lumberjack {
    private static final int RADIUS = 72;
    private static final int MIN_TREES_LEFT = 6;
    private static final int LOG_QUOTA = 256;
    static final int QUOTA = LOG_QUOTA;
    private static final int LOGS_PER_SECOND = 2;
    /** Trees nobody could get to lately, by position, until (world time): passed over for a while. */
    private static final java.util.Map<Long, Long> UNREACHABLE = new java.util.HashMap<>();
    /** Per lumberjack: what he is heading for (a tree or a wood) and since when. */
    private static final java.util.Map<java.util.UUID, long[]> HEADING = new java.util.HashMap<>();
    /** The logs still standing of the tree a lumberjack has started on: once cut at the foot, it comes down whole. */
    private static final java.util.Map<java.util.UUID, List<BlockPos>> FELLING = new java.util.HashMap<>();
    /** Until when the lumberjack is picking up the logs of the tree he felled. */
    private static final java.util.Map<java.util.UUID, Long> GATHER = new java.util.HashMap<>();

    /** Ticks spent on the way to the same tree or wood; restarts when the goal changes. */
    private static long heading(VillagerEntityMCA v, BlockPos goal, long now) {
        long[] h = HEADING.get(v.getUuid());
        if (h == null || h[0] != goal.asLong()) {
            HEADING.put(v.getUuid(), new long[]{goal.asLong(), now});
            return 0;
        }
        return now - h[1];
    }

    private Lumberjack() {
    }

    public static boolean work(VillagerEntityMCA v, Village village) {
        return work(v, village, false);
    }

    /** Another man is already swinging at this log. */
    private static boolean mateAt(ServerWorld world, VillagerEntityMCA v, BlockPos p) {
        return !world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(8), o -> o != v && p.equals(o.settlementsDigPos)).isEmpty();
    }

    /** {@code force}: a builder short of wood fells a tree even when the stores are full or the forest is thin. */
    public static boolean work(VillagerEntityMCA v, Village village, boolean force) {
        if (!(v.getWorld() instanceof ServerWorld world)) return false;
        Orders.Order order = Orders.take(v, village, Needs.Need.PLANKS); // the builder's order for wood, if nobody has it
        if (order != null) force = true;
        if (!force && village != null && stockLogs(village) >= LOG_QUOTA) return false;
        if (order != null) Doing.set(v, "settlements.doing.lumber.order", order.amount(), order.forWhom());
        else Doing.set(v, "settlements.doing.lumber");

        if (Dig.busy(v)) return true; // still swinging at a log
        Dig.gather(v, 2.5);
        Long gathering = GATHER.get(v.getUuid());
        if (gathering != null) {
            if (world.getTime() < gathering && Dig.collect(v, 12)) return true;
            GATHER.remove(v.getUuid());
            // the logs gathered: the axe back in his hand, not an empty hand
            ItemStack axe = ItemStack.EMPTY;
            for (int i = 0; i < v.getInventory().size(); i++) if (v.getInventory().getStack(i).getItem() instanceof net.minecraft.item.AxeItem) axe = v.getInventory().getStack(i).copy();
            v.setStackInHand(v.getDominantHand(), axe);
        }
        if (world.getTime() < v.settlementsNoWorkUntil) return false; // no trees around, look again later

        // out looking for a wood further away
        if (v.settlementsExplore != null && village != null) v.settlementsExplore = null; // (an old far-off target from before: forgotten)
        if (v.settlementsExplore != null && v.lumberTarget == null) {
            BlockPos e = v.settlementsExplore;
            // a wood he cannot get to in a minute and a half is given up: he looks around where he is
            if (v.squaredDistanceTo(e.getX() + 0.5, v.getY(), e.getZ() + 0.5) > 64 && heading(v, e, world.getTime()) < 1800) {
                if (Move.to(v, e.getX() + 0.5, e.getY(), e.getZ() + 0.5, 0.6)) return false;
            }
            v.settlementsExplore = null; // arrived: look around here
        }
        List<BlockPos> felling = FELLING.get(v.getUuid());
        if (felling != null) {
            felling.removeIf(p -> !world.getBlockState(p).isIn(BlockTags.LOGS));
            if (felling.isEmpty() && v.lumberTarget != null) {
                // the last log is down: a sapling in its place, then pick up the logs
                Speech.sayAny(v, "settlements.say.lumber.timber", 3);
                loose(world, v.lumberTarget, v); // and no branch left hanging in the air
                plant(v, world, v.lumberTarget);
                if (village != null) plantGrove(v, village, world);
                v.lumberTarget = null;
                FELLING.remove(v.getUuid());
                GATHER.put(v.getUuid(), world.getTime() + 600);
                return true;
            }
            if (felling.isEmpty() || v.lumberTarget == null) {
                FELLING.remove(v.getUuid());
                felling = null;
            }
        }
        if (felling == null && (v.lumberTarget == null || !world.getBlockState(v.lumberTarget).isIn(BlockTags.LOGS))) {
            v.lumberTarget = null;
            // a big tree a mate is at: he goes and helps with it
            for (VillagerEntityMCA o : world.getEntitiesByClass(VillagerEntityMCA.class, v.getBoundingBox().expand(24, 8, 24),
                    o -> o != v && o.isAlive() && o.lumberTarget != null && FELLING.containsKey(o.getUuid()) && FELLING.get(o.getUuid()).size() >= 14)) {
                long mates = world.getEntitiesByClass(VillagerEntityMCA.class, o.getBoundingBox().expand(24, 8, 24), m -> m != o && o.lumberTarget.equals(m.lumberTarget)).size();
                if (mates >= 1) continue;
                v.lumberTarget = o.lumberTarget;
                Speech.sayNow(v, "settlements.say.lumber.join", o.getName());
                Doing.set(v, "settlements.doing.lumber.join", o.getName());
                break;
            }
        }
        if (felling == null && (v.lumberTarget == null || !world.getBlockState(v.lumberTarget).isIn(BlockTags.LOGS))) {
            v.lumberTarget = null;
            // round the village, not round wherever he stands: each tree looked for from the last one, he walked off a tree
            // at a time a hundred blocks into the wild and down into the valley, and was not seen again
            BlockPos heart = village == null ? v.getBlockPos() : village.hallPos != null ? village.hallPos : new BlockPos(village.getCenter());
            List<BlockPos> trees = trees(world, heart, village);
            if (village != null) trees.removeIf(t -> Math.abs(t.getY() - heart.getY()) > 16);
            // the stand of trees round the village is spared, unless the builders are short of wood
            Builder.Shortage sh = village == null ? null : Builder.shortage(village);
            if (order != null || village != null && stockLogs(village) < 16) force = true;
            // the grove he planted: grown trees in it come first; still saplings, and nothing else about - wait for it
            if (village != null && trees.size() <= (force ? 0 : MIN_TREES_LEFT)) {
                List<BlockPos> grown = Grove.grown(world, village);
                if (!grown.isEmpty()) trees = grown;
                else if (Grove.growing(world, village) > 3 && !force) {
                    Doing.set(v, "settlements.doing.lumber.grove", Grove.growing(world, village));
                    if (world.random.nextInt(30) == 0) Speech.say(v, "settlements.say.lumber.grove");
                    v.settlementsNoWorkUntil = world.getTime() + 600;
                    return false;
                }
            }
            // (no going off further and further into the wild to look for a wood: two woodsmen walked a hundred and fifty
            // blocks out and were never seen again. Trees are looked for round the hall; none there, the grove is planted)
            if (trees.size() <= (force ? 0 : MIN_TREES_LEFT)) {
                v.settlementsExploreTries = 0;
                // leave the forest alone; with no forest at all the village has to ask for wood
                v.settlementsNoWorkUntil = world.getTime() + 1200;
                if (village != null && Grove.growing(world, village) < 12) { // the wood the village will need: planted by the hall
                    plantGrove(v, village, world);
                    plantGrove(v, village, world);
                }
                if (!force) {
                    Speech.say(v, trees.isEmpty() ? "settlements.say.lumber.none" : "settlements.say.lumber.sparse");
                    if (trees.isEmpty()) Needs.set(v, Needs.Need.PLANKS);
                }
                return false;
            }
            // the nearest tree to him first; once the village has planted a grove, trees close to the village are preferred
            BlockPos center = village == null ? v.getBlockPos() : new BlockPos(village.getCenter());
            double near = village != null && village.groveSaplings >= 6 ? 1.0 : 0.0;
            trees.sort((a, b) -> Double.compare(
                    a.getSquaredDistance(v.getBlockPos()) + near * 3 * a.getSquaredDistance(center),
                    b.getSquaredDistance(v.getBlockPos()) + near * 3 * b.getSquaredDistance(center)));
            v.lumberTarget = trees.get(0);
            v.settlementsExploreTries = 0;
        }
        BlockPos base = v.lumberTarget;
        double hx = base.getX() + 0.5 - v.getX(), hz = base.getZ() + 0.5 - v.getZ(), h2 = hx * hx + hz * hz;
        double dy = v.getY() - base.getY();
        // close enough to swing at the trunk: at its foot, or (having wandered up into it) right by the trunk
        boolean reach = h2 <= 16 && dy >= -2 && dy <= 4 || h2 <= 6 && dy > 4 && dy <= 10;
        if (!reach) {
            // walk to open ground beside the trunk, not to the log itself (that path ends up in the crown);
            // a tree he cannot get to (on a cliff, in a thicket, across water) is left for another
            BlockPos foot = footOf(world, base, v.getPos());
            boolean moving = foot != null && Move.to(v, foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 0.6);
            if (!moving || heading(v, base, world.getTime()) > 900) {
                UNREACHABLE.put(base.asLong(), world.getTime() + 12000);
                v.lumberTarget = null;
            }
            return false;
        }

        // fell the trunk: the connected logs, lowest first; a tree already cut at the foot is finished off
        List<BlockPos> trunk = felling != null ? felling : trunk(world, base);
        if (trunk.isEmpty()) {
            v.lumberTarget = null;
            return false;
        }
        Gaze.at(v, base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5);
        for (int i = 0; i < v.getInventory().size(); i++) {
            if (v.getInventory().getStack(i).getItem() instanceof net.minecraft.item.AxeItem) {
                v.setStackInHand(v.getDominantHand(), v.getInventory().getStack(i).copy()); // shown in hand only while chopping
                break;
            }
        }
        if (!Gear.has(v, net.minecraft.item.AxeItem.class)) {
            // no axe (lost, broken, or no room for one among the logs): make one before swinging at nothing
            Gear.equipTool(v, village, net.mca.entity.ai.Chore.CHOP);
            if (!Gear.has(v, net.minecraft.item.AxeItem.class)) return false;
        }
        Gear.face(v, base);
        // log by log, like a man with an axe: the lowest log he can reach from where he stands
        BlockPos next = null;
        for (BlockPos p : trunk) {
            double lx = p.getX() + 0.5 - v.getX(), lz = p.getZ() + 0.5 - v.getZ(), ly = p.getY() - v.getY();
            if (lx * lx + lz * lz <= 20 && ly >= -2 && ly <= 4 && Dig.sees(v, p) && !mateAt(world, v, p)) {
                next = p;
                break;
            }
        }
        if (next != null) {
            if (!Gear.wear(v, net.minecraft.item.AxeItem.class)) return false; // the axe broke: a new one first
            Dig.start(v, next);
            FELLING.put(v.getUuid(), new ArrayList<>(trunk));
            Speech.say(v, "settlements.say.lumber.fell");
            return true;
        }
        if (felling == null) {
            // nothing of it within reach from here: closer
            BlockPos foot = footOf(world, trunk.get(0), v.getPos());
            if (foot != null) Move.to(v, foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 0.6);
            return true;
        }
        // cut through at the foot: what is left up there comes down
        Speech.sayAny(v, "settlements.say.lumber.timber", 3);
        for (BlockPos p : trunk) world.breakBlock(p, true, v);
        loose(world, base, v);
        FELLING.remove(v.getUuid());
        plant(v, world, base);
        if (village != null) plantGrove(v, village, world);
        v.lumberTarget = null;
        GATHER.put(v.getUuid(), world.getTime() + 600); // and now the logs lying about
        return true;
    }

    static int stockLogs(Village village) {
        int n = 0;
        for (ItemStack s : village.storageBuffer) if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) n += s.getCount();
        return n;
    }

    /** Bases of real trees nearby: a log on the ground with leaves above it, not part of a village building. */
    private static List<BlockPos> trees(ServerWorld world, BlockPos center, Village village) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx += 1) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += 1) {
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                for (int y = top - 1; y >= top - 40 && y > world.getBottomY(); y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState here = world.getBlockState(p);
                    if (!here.isIn(BlockTags.LOGS)) {
                        if (here.isIn(BlockTags.LEAVES) || !here.isSolidBlock(world, p)) continue; // crown, snow, air
                        break; // the ground: no tree in this column
                    }
                    BlockPos below = p.down();
                    BlockState bs = world.getBlockState(below);
                    if (bs.isIn(BlockTags.LOGS)) continue; // still inside the trunk, go down to its base
                    if (!bs.isSolidBlock(world, below)) break;
                    if (village != null && planted(village, p)) break; // the village's own timber, not a tree
                    Long bad = UNREACHABLE.get(p.asLong());
                    if (bad != null && bad > world.getTime()) break;
                    if (leavesAbove(world, p)) out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    /** Open ground beside a trunk where a man can stand and swing: air for his body, something solid underfoot. */
    private static BlockPos footOf(ServerWorld world, BlockPos base, net.minecraft.util.math.Vec3d from) {
        // on his own side of the trunk: the nearest good spot to where he comes from, a flat one before a step up or down
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = 1; r <= 2 && best == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = base.add(dx, dy, dz);
                        if (Move.passable(world, p) && Move.passable(world, p.up()) && world.getBlockState(p.down()).isSolidBlock(world, p.down())
                                && world.getFluidState(p).isEmpty()) {
                            double score = from == null ? 0 : from.squaredDistanceTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                            score += Math.abs(dy) * 6 + (dx != 0 && dz != 0 ? 2 : 0);
                            if (score < bestScore) {
                                bestScore = score;
                                best = p;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean planted(Village village, BlockPos p) {
        for (Built b : village.built) if (b.contains(p)) return true;
        for (House h : village.houses) {
            BlockPos o = h.origin();
            if (p.getX() >= o.getX() - 1 && p.getX() <= o.getX() + h.fw() && p.getZ() >= o.getZ() - 1 && p.getZ() <= o.getZ() + h.fd()) return true;
        }
        return false;
    }

    /** Logs left hanging round a felled tree, with nothing under them that reaches the ground: a branch the felling missed. */
    private static void loose(ServerWorld world, BlockPos base, VillagerEntityMCA v) {
        for (BlockPos p : BlockPos.iterate(base.add(-4, 0, -4), base.add(4, 18, 4))) {
            if (!world.getBlockState(p).isIn(BlockTags.LOGS) || grounded(world, p.toImmutable())) continue;
            world.breakBlock(p, true, v);
        }
    }

    /** Whether this log stands on something: a chain of logs down to solid ground (not leaves). */
    private static boolean grounded(ServerWorld world, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < 200) {
            BlockPos p = queue.poll();
            BlockState below = world.getBlockState(p.down());
            if (!below.isIn(BlockTags.LOGS) && !below.isIn(BlockTags.LEAVES) && below.isSolidBlock(world, p.down())) return true;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos n = p.add(dx, dy, dz);
                if (world.getBlockState(n).isIn(BlockTags.LOGS) && seen.add(n)) queue.add(n);
            }
        }
        return seen.size() >= 200; // a great tree we did not finish looking at: leave it be
    }

    /** A crown on this trunk: leaves next to any of its logs, up the bends and branches (acacia, dark oak) too. */
    private static boolean leavesAbove(ServerWorld world, BlockPos base) {
        if (!world.getBlockState(base.up()).isIn(BlockTags.LOGS) && !world.getBlockState(base.up()).isIn(BlockTags.LEAVES)) {
            boolean bend = false; // an acacia leans off its stump at once
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (world.getBlockState(base.add(dx, 1, dz)).isIn(BlockTags.LOGS)) bend = true;
            if (!bend) return false;
        }
        List<BlockPos> logs = trunk(world, base);
        if (logs.size() < 3) return false;
        for (BlockPos p : logs) {
            for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                if (world.getBlockState(p.offset(d)).isIn(BlockTags.LEAVES)) return true;
            }
        }
        return false;
    }

    /** Connected logs of this tree, bottom first (flood fill over the trunk and its branches, at most 64). */
    private static List<BlockPos> trunk(ServerWorld world, BlockPos base) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        seen.add(base);
        while (!queue.isEmpty() && out.size() < 400) { // a giant spruce is a hundred logs and more
            BlockPos p = queue.poll();
            if (!world.getBlockState(p).isIn(BlockTags.LOGS)) continue;
            out.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.add(dx, dy, dz);
                        if (seen.add(n)) queue.add(n);
                    }
                }
            }
        }
        out.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        return out;
    }

    /** A new tree for the one that was cut: the sapling matching the log. */
    private static void plant(VillagerEntityMCA v, ServerWorld world, BlockPos base) {
        BlockPos ground = base.down();
        BlockState g = world.getBlockState(ground);
        if (!(g.isOf(Blocks.DIRT) || g.isOf(Blocks.GRASS_BLOCK) || g.isOf(Blocks.PODZOL) || g.isOf(Blocks.COARSE_DIRT) || g.isOf(Blocks.ROOTED_DIRT))) return;
        if (!world.getBlockState(base).isAir()) return;
        String path = Registries.BLOCK.getId(world.getBlockState(base).getBlock()).getPath();
        // the log is already gone, so look at what we carried away
        String wood = "oak";
        for (int i = 0; i < v.getInventory().size(); i++) {
            ItemStack s = v.getInventory().getStack(i);
            if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) {
                String id = Registries.ITEM.getId(s.getItem()).getPath();
                for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
                    if (id.endsWith(suffix)) {
                        wood = id.replace("stripped_", "").replace(suffix, "");
                        break;
                    }
                }
            }
        }
        Block sapling = Registries.BLOCK.get(new Identifier(wood + "_sapling"));
        if (sapling == Blocks.AIR) sapling = Blocks.OAK_SAPLING;
        Village home = v.getResidency().getHomeVillage().orElse(null);
        // a tree felled out in the wild is not planted again out there: its sapling goes into the village's own grove
        // by the hall, where the next one will be at hand (see plantGrove)
        if (home != null && base.getSquaredDistance(heart(home)) > 40 * 40) {
            plantGrove(v, home, world, sapling);
            return;
        }
        world.setBlockState(base, sapling.getDefaultState());
        if (home != null) Grove.remember(home, base);
        Speech.say(v, "settlements.say.lumber.plant");
    }

    private static BlockPos heart(Village village) {
        return village.hallPos != null ? village.hallPos : new BlockPos(village.getCenter());
    }

    /** Saplings from the branches go into the village's grove, so that wood grows where it is needed. */
    private static void plantGrove(VillagerEntityMCA v, Village village, ServerWorld world) {
        plantGrove(v, village, world, Blocks.OAK_SAPLING);
    }

    /**
     * The village's grove: one stand of trees of its own near the hall, planted in rows three blocks apart, not
     * saplings dotted about all round the village. Its place is chosen with the first sapling (open grass 16 to 30
     * blocks from the hall, on its level, off the plots and buildings); each sapling after goes into the next free
     * spot in the rows, the grove growing outward from the middle.
     */
    private static void plantGrove(VillagerEntityMCA v, Village village, ServerWorld world, Block sapling) {
        BlockPos c = heart(village);
        BlockPos anchor = null;
        double ad = Double.MAX_VALUE;
        for (BlockPos g : village.grove) { // the grove there is: its spot nearest the hall
            double d = g.getSquaredDistance(c);
            if (d > 12 * 12 && d < 40 * 40 && d < ad) {
                ad = d;
                anchor = g;
            }
        }
        if (anchor == null) {
            for (int attempt = 0; attempt < 40 && anchor == null; attempt++) {
                int dist = 16 + world.random.nextInt(15);
                double angle = world.random.nextDouble() * Math.PI * 2;
                int x = c.getX() + (int) (Math.cos(angle) * dist), z = c.getZ() + (int) (Math.sin(angle) * dist);
                BlockPos p = groveSpot(world, village, x, z, c);
                if (p == null) continue;
                // room for a stand: most of the ground about it fit for trees too
                int fit = 0;
                for (int dx = -6; dx <= 6; dx += 3) for (int dz = -6; dz <= 6; dz += 3) if (groveSpot(world, village, x + dx, z + dz, c) != null) fit++;
                if (fit >= 12) anchor = p;
            }
        }
        if (anchor == null) return;
        // the free spot in the rows nearest the middle of the grove
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = groveSpot(world, village, anchor.getX() + dx * 3, anchor.getZ() + dz * 3, c);
                    if (p == null || !world.getBlockState(p).isAir()) continue;
                    world.setBlockState(p, sapling.getDefaultState());
                    Grove.remember(village, p);
                    village.groveSaplings++;
                    village.markDirty();
                    Speech.say(v, "settlements.say.lumber.plant");
                    return;
                }
            }
        }
    }

    /** Ground at x, z a sapling can go into for the grove, or null: grass or earth, open sky, on the hall's level, nothing built or laid out there. */
    private static BlockPos groveSpot(ServerWorld world, Village village, int x, int z, BlockPos c) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        if (Math.abs(y - c.getY()) > 6) return null;
        BlockState ground = world.getBlockState(p.down());
        if (!(ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.PODZOL) || ground.isOf(Blocks.COARSE_DIRT))) return null;
        BlockState here = world.getBlockState(p);
        if (!here.isAir() && !(here.getBlock() instanceof net.minecraft.block.SaplingBlock) && !here.isIn(BlockTags.LOGS)) return null;
        for (int[] plot : village.plots) {
            if (x >= plot[0] - 2 && x <= plot[0] + plot[2] + 2 && z >= plot[1] - 2 && z <= plot[1] + plot[3] + 2) return null;
        }
        for (Built b : village.built) if (b.contains(p) || b.contains(p.add(2, 0, 0)) || b.contains(p.add(-2, 0, 0)) || b.contains(p.add(0, 0, 2)) || b.contains(p.add(0, 0, -2))) return null;
        if (village.getBuildingAt(p).isPresent()) return null;
        return p;
    }
}
