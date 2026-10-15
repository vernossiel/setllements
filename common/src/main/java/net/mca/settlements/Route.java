package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Finding the way like somebody who knows how to get about: when the ordinary path finder gives up, the villager
 * works out a route of his own over the ground as it is - steps up and drops, ladders, vines and scaffolding, doors
 * and gates, water to wade or swim through - and, if there is no way on foot, the least work that makes one: a bit of
 * earth or rock dug out of the way, a block laid over a gap. He then walks it, doing the work where it comes.
 * When even that fails he tries again harder, step by step, the way MineColonies' stuck handler does
 * (https://github.com/ldtteam/minecolonies, GPL-3.0, whose escalation this follows): look again, look again allowing
 * work, walk off a little and come at it from elsewhere, and only at the very end is he set down near the goal.
 */
public final class Route {
    private static final int MAX_NODES = 4000;
    private static final int RANGE = 48;

    private Route() {
    }

    /** What has to be done to enter a cell of the route. */
    enum Act { WALK, DIG, BRIDGE }

    record Hop(BlockPos pos, Act act) {
    }

    private static final class State {
        BlockPos goal;
        List<Hop> path;
        int i;
        int level;      // how hard he is trying (0 plain, 1 again, 2 with work, 3 from elsewhere, 4 last resort)
        int still;      // seconds without getting anywhere
        Vec3d last;
        BlockPos segmentEnd;
        int awaySec;
    }

    private static final Map<UUID, State> STATE = new HashMap<>();

    public static void forget(VillagerEntityMCA v) {
        STATE.remove(v.getUuid());
    }

    // ---------- the ground ----------

    private static boolean climbable(ServerWorld world, BlockPos p) {
        return world.getBlockState(p).isIn(BlockTags.CLIMBABLE);
    }

    private static boolean dangerous(BlockState s) {
        return s.isOf(Blocks.LAVA) || s.isIn(BlockTags.FIRE) || s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS) || s.isOf(Blocks.SWEET_BERRY_BUSH)
                || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE);
    }

    /** A body fits through: open, or a door or gate he opens, or something to climb. */
    static boolean open(ServerWorld world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (dangerous(s)) return false;
        return Move.passable(world, p) || s.getBlock() instanceof net.minecraft.block.DoorBlock || s.getBlock() instanceof net.minecraft.block.FenceGateBlock
                || s.isIn(BlockTags.CLIMBABLE);
    }

    private static boolean water(ServerWorld world, BlockPos p) {
        return world.getFluidState(p).isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }

    /** Somewhere to stand: room for feet and head, and a floor under the feet (or a ladder to hold, or water). */
    static boolean stand(ServerWorld world, BlockPos p) {
        if (!open(world, p) || !open(world, p.up())) return false;
        if (water(world, p) || climbable(world, p)) return true;
        BlockState below = world.getBlockState(p.down());
        if (dangerous(below)) return false;
        return !below.getCollisionShape(world, p.down()).isEmpty() || below.isOf(Blocks.SCAFFOLDING);
    }

    // ---------- the search ----------

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final double g, f;
        final Node parent;
        final Act act;

        Node(BlockPos pos, double g, double f, Node parent, Act act) {
            this.pos = pos;
            this.g = g;
            this.f = f;
            this.parent = parent;
            this.act = act;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private static double h(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.2;
    }

    /**
     * The best route from {@code start} to within a step of {@code goal}; when the goal cannot be reached, the route
     * to the nearest point he can get to (or null when that is no nearer than where he is). {@code work} allows digging
     * natural ground and bridging gaps.
     */
    static List<Hop> find(ServerWorld world, Village village, BlockPos start, BlockPos goal, boolean work) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        Node first = new Node(start, 0, h(start, goal), null, Act.WALK);
        open.add(first);
        best.put(start.asLong(), 0.0);
        Node closest = first;
        double closestH = h(start, goal);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node c = open.poll();
            if (c.g > best.getOrDefault(c.pos.asLong(), Double.MAX_VALUE)) continue;
            double hc = h(c.pos, goal);
            if (hc < closestH) {
                closestH = hc;
                closest = c;
            }
            if (Math.abs(c.pos.getX() - goal.getX()) + Math.abs(c.pos.getZ() - goal.getZ()) <= 1 && Math.abs(c.pos.getY() - goal.getY()) <= 1) {
                closest = c;
                break;
            }
            for (Node n : neighbours(world, village, c, goal, work)) {
                if (Math.abs(n.pos.getX() - start.getX()) > RANGE || Math.abs(n.pos.getZ() - start.getZ()) > RANGE || Math.abs(n.pos.getY() - start.getY()) > 24) continue;
                if (!world.isChunkLoaded(n.pos.getX() >> 4, n.pos.getZ() >> 4)) continue;
                long k = n.pos.asLong();
                if (n.g < best.getOrDefault(k, Double.MAX_VALUE)) {
                    best.put(k, n.g);
                    open.add(n);
                }
            }
        }
        if (closest == first || closestH > h(start, goal) - 2) return null;
        List<Hop> out = new ArrayList<>();
        for (Node n = closest; n != null && n != first; n = n.parent) out.add(0, new Hop(n.pos, n.act));
        return out;
    }

    private static List<Node> neighbours(ServerWorld world, Village village, Node c, BlockPos goal, boolean work) {
        List<Node> out = new ArrayList<>(8);
        BlockPos p = c.pos;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos n = p.offset(dir);
            if (stand(world, n)) {
                add(out, c, n, water(world, n) ? 3 : 1, goal, Act.WALK);
                continue;
            }
            if (stand(world, n.up()) && open(world, p.up(2))) { // a step up (a block, a stair)
                add(out, c, n.up(), 1.6, goal, Act.WALK);
                continue;
            }
            if (open(world, n) && open(world, n.up())) {
                boolean landed = false;
                for (int d = 1; d <= 3; d++) { // a drop he can take without hurting himself
                    BlockPos low = n.down(d);
                    if (stand(world, low)) {
                        add(out, c, low, 1 + 0.4 * d, goal, Act.WALK);
                        landed = true;
                        break;
                    }
                    if (!open(world, low)) break;
                }
                if (!landed && work && !water(world, n.down()) && world.getBlockState(n.down()).isReplaceable()) {
                    add(out, c, n, 9, goal, Act.BRIDGE); // a gap: lay a block over it
                }
                continue;
            }
            if (work) {
                // earth or rock in the way, a floor beyond: dig through, as a man with a shovel would
                int blocks = 0;
                boolean ok = !world.getBlockState(n.down()).getCollisionShape(world, n.down()).isEmpty();
                for (BlockPos b : new BlockPos[]{n, n.up()}) {
                    if (open(world, b)) continue;
                    if (!Move.diggable(world, village, b)) ok = false;
                    blocks++;
                }
                if (ok && blocks > 0) add(out, c, n, 3 + 3 * blocks, goal, Act.DIG);
            }
        }
        // up and down ladders, vines and scaffolding
        if ((climbable(world, p) || climbable(world, p.up())) && open(world, p.up()) && open(world, p.up(2))) add(out, c, p.up(), 1.5, goal, Act.WALK);
        if (climbable(world, p.down()) && open(world, p.down())) add(out, c, p.down(), 1.2, goal, Act.WALK);
        return out;
    }

    private static void add(List<Node> out, Node c, BlockPos n, double cost, BlockPos goal, Act act) {
        double g = c.g + cost;
        out.add(new Node(n.toImmutable(), g, g + h(n, goal), c, act));
    }

    // ---------- walking it ----------

    /**
     * One second of getting to {@code goal} the hard way (called once the ordinary path finder got him nowhere).
     * True while he is at it.
     */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos goal) {
        State s = STATE.computeIfAbsent(v.getUuid(), k -> new State());
        if (!goal.equals(s.goal)) {
            s.goal = goal.toImmutable();
            s.path = null;
            s.level = 0;
            s.still = 0;
            s.awaySec = 0;
        }
        Vec3d pos = v.getPos();
        boolean moved = s.last != null && pos.squaredDistanceTo(s.last) > 0.3;
        s.last = pos;
        if (moved || Dig.busy(v)) s.still = 0;
        else s.still++;

        // walking off a little, to come at it from elsewhere
        if (s.awaySec > 0) {
            s.awaySec--;
            if (s.awaySec == 0) s.path = null;
            return true;
        }
        if (s.still >= 6) { // no good: try harder
            s.still = 0;
            s.path = null;
            s.level++;
            if (s.level == 3) {
                Vec3d away = net.minecraft.entity.ai.NoPenaltyTargeting.find(v, 10, 4);
                if (away != null) {
                    v.getNavigation().startMovingTo(away.x, away.y, away.z, 0.5);
                    s.awaySec = 8;
                    return true;
                }
                s.level++;
            }
            if (s.level >= 4) {
                // the very last resort: set down by the goal (never in the air or the rock), or the errand is given up
                if (v.getBlockPos().getSquaredDistance(goal) < 64 * 64 && Move.putSafely(v, goal)) Speech.sayAny(v, "settlements.say.way.round", 3);
                else v.settlementsGoal = null;
                STATE.remove(v.getUuid());
                return true;
            }
        }
        if (s.path == null) {
            s.path = find(world, village, v.getBlockPos(), goal, s.level >= 2);
            s.i = 0;
            s.segmentEnd = null;
            if (s.path == null) {
                s.still = 6; // nothing at this level: straight to the next one
                return true;
            }
        }
        if (Dig.busy(v)) return true;

        // where on the route he is: the furthest hop he stands at
        BlockPos feet = v.getBlockPos();
        for (int j = s.path.size() - 1; j >= s.i; j--) {
            BlockPos hp = s.path.get(j).pos();
            if (Math.abs(hp.getX() - feet.getX()) + Math.abs(hp.getZ() - feet.getZ()) <= 0 && Math.abs(hp.getY() - feet.getY()) <= 1) {
                s.i = j + 1;
                break;
            }
        }
        if (s.i >= s.path.size()) { // at the end of it: there, or as near as the ground allowed - look again
            s.path = null;
            if (v.getBlockPos().getSquaredDistance(goal) > 4) s.level++;
            return true;
        }
        Hop next = s.path.get(s.i);
        switch (next.act()) {
            case DIG -> {
                for (BlockPos b : new BlockPos[]{next.pos().up(), next.pos()}) {
                    if (open(world, b)) continue;
                    if (!Move.diggable(world, village, b)) { // someone built here meanwhile
                        s.path = null;
                        return true;
                    }
                    if (v.squaredDistanceTo(Vec3d.ofCenter(b)) > 16 || !Dig.sees(v, b)) break;
                    Gear.face(v, b);
                    Dig.start(v, b); // a crack at a time, like anyone with a shovel
                    return true;
                }
                if (open(world, next.pos()) && open(world, next.pos().up())) walk(v, s, s.i);
                return true;
            }
            case BRIDGE -> {
                BlockPos under = next.pos().down();
                if (world.getBlockState(under).isReplaceable() && v.squaredDistanceTo(Vec3d.ofCenter(under)) <= 16) {
                    if (!Move.place(v, village, world, under)) { // nothing to lay: no bridge this way
                        s.path = null;
                        s.level = Math.max(s.level, 3);
                        return true;
                    }
                }
                walk(v, s, s.i);
                return true;
            }
            default -> {
                walk(v, s, s.i);
                return true;
            }
        }
    }

    /** Hands the plain stretch of the route from {@code from} up to the next bit of work to the legs. */
    private static void walk(VillagerEntityMCA v, State s, int from) {
        int to = from;
        while (to + 1 < s.path.size() && s.path.get(to + 1).act() == Act.WALK) to++;
        BlockPos end = s.path.get(to).pos();
        if (end.equals(s.segmentEnd) && v.getNavigation().isFollowingPath()) return;
        List<PathNode> nodes = new ArrayList<>();
        nodes.add(new PathNode(v.getBlockX(), v.getBlockY(), v.getBlockZ()));
        for (int j = from; j <= to; j++) {
            BlockPos p = s.path.get(j).pos();
            nodes.add(new PathNode(p.getX(), p.getY(), p.getZ()));
        }
        v.getNavigation().startMovingAlong(new Path(nodes, end, true), 0.5);
        s.segmentEnd = end;
    }
}
