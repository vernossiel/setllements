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
 * Getting about like a player: the villager's own way over the ground as it is, worked out for every errand that is
 * more than a few steps on the level (the ordinary path finder is left the short walks). Each move has its price -
 * a step, a step up, a drop of up to three blocks, a ladder or vine up or down, a door or gate opened and shut behind
 * him, water waded or swum - and where there is no way on foot, the work that makes one, dearer still: earth or rock
 * dug out of the way, a step cut into a slope, a block laid over a gap, a block put under his feet to climb, the
 * ground dug out under him to get down. The search takes the cheapest: round, rather than through. Edges with a drop
 * of more than three below cost more, so the way keeps off the brink of roofs, scaffolds and cliffs; a drop that would
 * hurt him is never a step. Shut in (a house with no way to the goal, a box, a pit), he first looks for the way out.
 * When even that fails he tries harder, step by step, the way MineColonies' stuck handler does
 * (https://github.com/ldtteam/minecolonies, GPL-3.0, whose escalation this follows): look again, look again allowing
 * work, walk off a little and come at it from elsewhere; and at the very end, where nobody sees, he is set down near
 * the goal - before a player's eyes he says there is no way and leaves it.
 */
public final class Route {
    private static final int MAX_NODES = 6000;
    private static final int RANGE = 56;

    private Route() {
    }

    /** What has to be done to enter a cell of the route. */
    enum Act { WALK, DIG, BRIDGE, PILLAR, DIGDOWN }

    record Hop(BlockPos pos, Act act) {
    }

    private static final class State {
        BlockPos goal;
        List<Hop> path;
        int i;
        int level;        // how hard he is trying (0 plain, 1 again, 2 with work, 3 from elsewhere, 4 last resort)
        int still;        // seconds without getting anywhere
        Vec3d last;
        BlockPos segmentEnd;
        int awaySec;
        long touched;     // the last second he was on it
        boolean out;      // the route is the way out (of a house, a box, a pit); the goal after it
        double setOut;    // how far from the goal he was when he set out on this route
        final List<BlockPos> laid = new ArrayList<>(); // the steps he set down the side of a wall, to take away behind him
    }

    private static final Map<UUID, State> STATE = new HashMap<>();
    private static final java.util.Set<UUID> CROUCHED = new java.util.HashSet<>(); // let down through scaffolding by climbTick

    public static void forget(VillagerEntityMCA v) {
        STATE.remove(v.getUuid());
    }

    /** Whether he is on his own route to (about) this goal now: nothing else is to set him another way meanwhile. */
    public static boolean active(VillagerEntityMCA v, BlockPos goal) {
        State s = STATE.get(v.getUuid());
        return s != null && s.goal != null && s.goal.isWithinDistance(goal, 2.5) && (s.path != null || s.awaySec > 0)
                && v.getWorld().getTime() - s.touched < 200;
    }

    /**
     * Every tick: on a ladder (or vine, or scaffolding) with the route going on up it, he climbs - holds on and pulls
     * himself up, as a player holds the jump key; going down he lets himself down it. The path finder's walking alone
     * stood him at the foot of it.
     */
    public static void climbTick(VillagerEntityMCA v) {
        if (v.getWorld().isClient) return;
        BlockPos feet = v.getBlockPos();
        // standing on top of a scaffold column counts too: down into it is a crouch (no other way through its top)
        boolean onScaffold = v.getWorld().getBlockState(feet).isOf(Blocks.SCAFFOLDING) || v.getWorld().getBlockState(feet.down()).isOf(Blocks.SCAFFOLDING);
        if (!v.isClimbing() && !onScaffold) {
            if (CROUCHED.remove(v.getUuid())) v.setSneaking(false);
            return;
        }
        State s = STATE.get(v.getUuid());
        if (s == null || s.path == null) {
            if (CROUCHED.remove(v.getUuid())) v.setSneaking(false);
            return;
        }
        // where on the route he hangs (the hop at his feet), and which way the next one goes: up he pulls himself, down
        // he lets go. (He used to look back two hops too: going down, the hop he had just left above him had him climb
        // back up it, and he bobbed at the top of the ladder all night.)
        int at = -1;
        for (int j = Math.max(0, s.i - 3); j < Math.min(s.path.size(), s.i + 8); j++) {
            if (s.path.get(j).pos().equals(feet)) {
                at = j;
                break;
            }
        }
        BlockPos next = at >= 0 && at + 1 < s.path.size() ? s.path.get(at + 1).pos() : at < 0 && s.i < s.path.size() ? s.path.get(s.i).pos() : null;
        boolean down = next != null && next.getX() == feet.getX() && next.getZ() == feet.getZ() && next.getY() < feet.getY();
        // through scaffolding a body goes down only crouched, as a player holds sneak; up it, or off it, he stands
        if (down && onScaffold) {
            v.setSneaking(true);
            CROUCHED.add(v.getUuid());
        } else if (CROUCHED.remove(v.getUuid())) v.setSneaking(false);
        if (next == null || next.getX() != feet.getX() || next.getZ() != feet.getZ()) return;
        v.fallDistance = 0;
        if (next.getY() > feet.getY()) v.getJumpControl().setActive();
        else if (next.getY() < feet.getY()) {
            // down: no pushing on (pressed against the ladder he would climb it), straight down it at a ladder's pace
            v.setJumping(false);
            v.getNavigation().stop();
            v.setVelocity(0, Math.min(v.getVelocity().y, -0.15), 0);
            v.velocityModified = true;
        }
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

    /** What one search learns of the ground, once for each block: the same cells are looked at from every side. */
    private static final class Ground {
        final ServerWorld world;
        private final Map<Long, Byte> seen = new HashMap<>();

        Ground(ServerWorld world) {
            this.world = world;
        }

        private static final byte OPEN = 1, STAND = 2, WATER = 4, CLIMB = 8, DOOR = 16, EDGE = 32, KNOWN = 64;

        private byte of(BlockPos p) {
            long k = p.asLong();
            Byte b = seen.get(k);
            if (b != null) return b;
            byte v = KNOWN;
            BlockState s = world.getBlockState(p);
            if (Route.open(world, p)) v |= OPEN;
            if (Route.stand(world, p)) v |= STAND;
            if (Route.water(world, p)) v |= WATER;
            if (s.isIn(BlockTags.CLIMBABLE)) v |= CLIMB;
            if (s.getBlock() instanceof net.minecraft.block.DoorBlock || s.getBlock() instanceof net.minecraft.block.FenceGateBlock) v |= DOOR;
            seen.put(k, v);
            return v;
        }

        boolean open(BlockPos p) { return (of(p) & OPEN) != 0; }

        boolean stand(BlockPos p) { return (of(p) & STAND) != 0; }

        boolean water(BlockPos p) { return (of(p) & WATER) != 0; }

        boolean climb(BlockPos p) { return (of(p) & CLIMB) != 0; }

        boolean door(BlockPos p) { return (of(p) & DOOR) != 0; }

        boolean solid(BlockPos p) { return !world.getBlockState(p).getCollisionShape(world, p).isEmpty(); }

        private final Map<Long, Boolean> edges = new HashMap<>();

        /** By the brink of a drop of more than three blocks: the edge of a roof, a scaffold, a cliff, a shaft. */
        boolean edge(BlockPos p) {
            return edges.computeIfAbsent(p.asLong(), k -> {
                for (Direction d : Direction.Type.HORIZONTAL) {
                    BlockPos n = p.offset(d);
                    if (!open(n) || !open(n.down()) || water(n.down())) continue;
                    int drop = 2;
                    while (drop <= 4 && open(n.down(drop)) && !water(n.down(drop))) drop++;
                    if (drop > 4) return true;
                }
                return false;
            });
        }
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
        if (b == null) return 0; // looking for the way out: no goal to make for, the nearest one found
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.2;
    }

    /**
     * The best route from {@code start} to within a step of {@code goal}; when the goal cannot be reached, the route
     * to the nearest point he can get to (or null when that is no nearer than where he is). {@code work} allows the
     * moves that change the ground (digging, bridging, climbing on blocks of his own).
     */
    static List<Hop> find(ServerWorld world, Village village, BlockPos start, BlockPos goal, boolean work) {
        return search(world, village, start, goal, null, work);
    }

    /**
     * The way out from where he is shut in - a house with no way to where he must go, a box, a pit: the nearest spot
     * under the open sky with ground to stand on, a few steps off.
     */
    static List<Hop> findOut(ServerWorld world, Village village, BlockPos start, boolean work) {
        return search(world, village, start, null, p -> Math.abs(p.getX() - start.getX()) + Math.abs(p.getZ() - start.getZ()) >= 4
                && world.isSkyVisible(p.up()), work);
    }

    private static List<Hop> search(ServerWorld world, Village village, BlockPos start, BlockPos goal, java.util.function.Predicate<BlockPos> done, boolean work) {
        Ground gr = new Ground(world);
        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        Node first = new Node(start, 0, h(start, goal), null, Act.WALK);
        open.add(first);
        best.put(start.asLong(), 0.0);
        Node closest = first;
        double closestH = h(start, goal);
        boolean found = false;
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node c = open.poll();
            if (c.g > best.getOrDefault(c.pos.asLong(), Double.MAX_VALUE)) continue;
            if (goal != null) {
                double hc = h(c.pos, goal);
                if (hc < closestH) {
                    closestH = hc;
                    closest = c;
                }
                if (Math.abs(c.pos.getX() - goal.getX()) + Math.abs(c.pos.getZ() - goal.getZ()) <= 1 && Math.abs(c.pos.getY() - goal.getY()) <= 1) {
                    closest = c;
                    found = true;
                    break;
                }
            } else if (c != first && gr.stand(c.pos) && done.test(c.pos)) {
                closest = c;
                found = true;
                break;
            }
            for (Node n : neighbours(gr, village, c, goal, work)) {
                if (Math.abs(n.pos.getX() - start.getX()) > RANGE || Math.abs(n.pos.getZ() - start.getZ()) > RANGE || Math.abs(n.pos.getY() - start.getY()) > 24) continue;
                if (!world.isChunkLoaded(n.pos.getX() >> 4, n.pos.getZ() >> 4)) continue;
                long k = n.pos.asLong();
                if (n.g < best.getOrDefault(k, Double.MAX_VALUE)) {
                    best.put(k, n.g);
                    open.add(n);
                }
            }
        }
        if (goal == null && !found) return null;
        if (goal != null && !found && (closest == first || closestH > h(start, goal) - 2)) return null;
        List<Hop> out = new ArrayList<>();
        for (Node n = closest; n != null && n != first; n = n.parent) out.add(0, new Hop(n.pos, n.act));
        return out;
    }

    // the prices of the moves (a step on the level is one)
    private static final double STEP_UP = 1.6, LADDER_UP = 1.5, LADDER_DOWN = 1.2, WADE = 3, DOOR = 1, EDGE = 2.5;
    private static final double DIG = 4, PER_BLOCK = 3, BRIDGE = 9, PILLAR = 7, DIG_DOWN = 6;

    private static List<Node> neighbours(Ground gr, Village village, Node c, BlockPos goal, boolean work) {
        ServerWorld world = gr.world;
        List<Node> out = new ArrayList<>(8);
        BlockPos p = c.pos;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos n = p.offset(dir);
            double door = gr.door(n) ? DOOR : 0; // opened, gone through, shut behind him (see Move.doorsAhead, shutBehind)
            if (gr.stand(n)) {
                add(out, c, n, (gr.water(n) ? WADE : 1) + door + (gr.edge(n) ? EDGE : 0), goal, Act.WALK);
                continue;
            }
            if (gr.stand(n.up()) && gr.open(p.up(2))) { // a step up (a block, a stair)
                add(out, c, n.up(), STEP_UP + (gr.edge(n.up()) ? EDGE : 0), goal, Act.WALK);
                continue;
            }
            if (gr.open(n) && gr.open(n.up())) {
                boolean landed = false;
                for (int d = 1; d <= 3; d++) { // a drop he can take without hurting himself; more is never a step
                    BlockPos low = n.down(d);
                    if (gr.stand(low)) {
                        add(out, c, low, 1 + 0.4 * d + (gr.edge(low) ? EDGE : 0), goal, Act.WALK);
                        landed = true;
                        break;
                    }
                    if (!gr.open(low)) break;
                }
                if (!landed && work && !gr.water(n.down()) && world.getBlockState(n.down()).isReplaceable()) {
                    add(out, c, n, BRIDGE, goal, Act.BRIDGE); // a gap: a block laid over it
                }
                // high up with no way down (a roof, the top of a wall, the scaffold taken down from under him): a step
                // down out over the edge, a block set against the wall under it, as a player stairs his way down -
                // the blocks taken away again behind him. (A builder walked the roof up and down all evening, 'off to
                // the stores for material', with no way off it.)
                if (!landed && work && (goal == null || goal.getY() < p.getY()) && gr.open(n.down()) && !gr.water(n.down()) && !gr.water(n.down(2))
                        && world.getBlockState(n.down(2)).isReplaceable() && !dangerous(world.getBlockState(n.down(2))) && propped(gr, n.down(2))) {
                    add(out, c, n.down(), BRIDGE + 2, goal, Act.BRIDGE);
                }
                continue;
            }
            if (work) {
                // earth or rock in the way, a floor beyond: dug through, as a man with a shovel would
                int blocks = 0;
                boolean ok = gr.solid(n.down());
                for (BlockPos b : new BlockPos[]{n, n.up()}) {
                    if (gr.open(b)) continue;
                    if (!Move.diggable(world, village, b)) ok = false;
                    blocks++;
                }
                if (ok && blocks > 0) add(out, c, n, DIG + PER_BLOCK * blocks, goal, Act.DIG);
                // or a step cut into the slope: up onto the block ahead, the earth over it (and over his head) dug out
                if (gr.solid(n) && !gr.door(n)) {
                    int cut = 0;
                    boolean ok2 = true;
                    for (BlockPos b : new BlockPos[]{n.up(), n.up(2), p.up(2)}) {
                        if (gr.open(b)) continue;
                        if (!Move.diggable(world, village, b)) ok2 = false;
                        cut++;
                    }
                    if (ok2 && cut > 0) add(out, c, n.up(), DIG + PER_BLOCK * cut, goal, Act.DIG);
                }
            }
        }
        // up and down ladders, vines and scaffolding
        if ((gr.climb(p) || gr.climb(p.up())) && gr.open(p.up()) && gr.open(p.up(2))) add(out, c, p.up(), LADDER_UP, goal, Act.WALK);
        if (gr.climb(p.down()) && gr.open(p.down())) add(out, c, p.down(), LADDER_DOWN, goal, Act.WALK);
        if (work && !gr.climb(p) && !gr.water(p)) {
            // up where there is no other way, as a player does: a jump and a block put under his feet (taken down after)
            BlockPos under = p.down();
            if ((goal == null || goal.getY() > p.getY() + 1) && gr.open(p.up(2)) && gr.solid(under))
                add(out, c, p.up(), PILLAR, goal, Act.PILLAR);
            // and down where there is no other way: the earth under him dug out, a block at a time, into what lies below
            BlockPos floor2 = p.down(2);
            if (goal != null && goal.getY() < p.getY() - 1 && Move.diggable(world, village, under) && !gr.water(floor2) && !wetNear(world, under)
                    && gr.solid(floor2) && !dangerous(world.getBlockState(floor2)))
                add(out, c, under, DIG_DOWN, goal, Act.DIGDOWN);
        }
        return out;
    }

    /** Something beside or under a cell to set a block against. */
    private static boolean propped(Ground gr, BlockPos p) {
        if (gr.solid(p.down())) return true;
        for (Direction d : Direction.Type.HORIZONTAL) if (gr.solid(p.offset(d))) return true;
        return false;
    }

    /** Water or lava beside a block: dug out, it would let it in. */
    private static boolean wetNear(ServerWorld world, BlockPos p) {
        for (Direction d : Direction.values()) if (!world.getFluidState(p.offset(d)).isEmpty()) return true;
        return false;
    }

    private static void add(List<Node> out, Node c, BlockPos n, double cost, BlockPos goal, Act act) {
        double g = c.g + cost;
        out.add(new Node(n.toImmutable(), g, g + h(n, goal), c, act));
    }

    /** A goal down in the village's mine (under its mouth, along the run of the shaft and the drifts). */
    private static boolean inMine(Village village, BlockPos goal) {
        if (village == null || village.mineOrigin == null) return false;
        BlockPos o = village.mineOrigin;
        return goal.getY() < o.getY() - 2 && Math.abs(goal.getX() - o.getX()) <= 64 && Math.abs(goal.getZ() - o.getZ()) <= 96;
    }

    /** Under a roof or shut in by walls: where a way to anywhere may first have to be a way out. */
    private static boolean shutIn(ServerWorld world, VillagerEntityMCA v) {
        BlockPos feet = v.getBlockPos();
        if (!world.isSkyVisible(feet.up())) return true;
        int walls = 0;
        for (Direction d : Direction.Type.HORIZONTAL) if (!open(world, feet.offset(d)) || !open(world, feet.offset(d).up())) walls++;
        return walls >= 3;
    }

    // ---------- walking it ----------

    /** One second of getting to {@code goal} on his own route. True while he is at it. */
    public static boolean tick(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos goal) {
        State s = STATE.computeIfAbsent(v.getUuid(), k -> new State());
        s.touched = world.getTime();
        if (s.goal == null || !s.goal.isWithinDistance(goal, 2.5)) { // (a goal that moves a little - someone he follows - keeps the route)
            s.goal = goal.toImmutable();
            s.path = null;
            s.level = 0;
            s.still = 0;
            s.awaySec = 0;
            s.out = false;
        } else s.goal = goal.toImmutable();
        // the steps he set down the side of the wall, taken away once he is down and off them
        for (java.util.Iterator<BlockPos> it = s.laid.iterator(); it.hasNext(); ) {
            BlockPos b = it.next();
            if (v.getBlockPos().getSquaredDistance(b) < 3 * 3 && v.getY() > b.getY() - 3) continue;
            if (world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, new net.minecraft.util.math.Box(b.up()), e -> true).isEmpty()) {
                BlockState was = world.getBlockState(b);
                if (!was.isAir() && was.getHardness(world, b) >= 0) {
                    world.breakBlock(b, false, v);
                    if (village != null) Workforce.addToTreasury(village, new net.minecraft.item.ItemStack(was.getBlock().asItem()));
                }
            }
            it.remove();
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
            s.out = false;
            s.level++;
            if (s.level == 3) {
                Vec3d away = net.minecraft.entity.ai.NoPenaltyTargeting.find(v, 10, 4);
                if (away != null) {
                    Doing.count("nav.away");
                    v.getNavigation().startMovingTo(away.x, away.y, away.z, 0.5);
                    s.awaySec = 8;
                    return true;
                }
                s.level++;
            }
            if (s.level >= 4) {
                // the very last resort: set down by the goal (never in the air or the rock), where nobody sees; before a
                // player's eyes he says there is no way and leaves it, as a man does
                if (v.getBlockPos().getSquaredDistance(goal) < 64 * 64 && Bed.unseen(v, world) && Move.putSafely(v, goal)) {
                    Speech.sayAny(v, "settlements.say.way.round", 3);
                    Doing.count("nav.put");
                } else {
                    Speech.sayAny(v, "settlements.say.way.none", 3);
                    Doing.count("nav.giveup");
                    Move.giveUp(v, goal);
                }
                STATE.remove(v.getUuid());
                return true;
            }
        }
        if (s.path == null) {
            // no digging a way of his own down in the mine: the workings are dug to their plan (the miner's route cut
            // a long tunnel off through the rock to a spot of the drift not reached yet); up top and elsewhere, yes
            boolean work = s.level >= 2 && !inMine(village, goal);
            s.path = find(world, village, v.getBlockPos(), goal, work);
            s.out = false;
            if (s.path == null && shutIn(world, v)) {
                // no way from in here: first the way out of the house, the box, the pit; the goal from outside
                s.path = findOut(world, village, v.getBlockPos(), work);
                s.out = s.path != null;
                if (s.out) Doing.count("nav.out");
            }
            s.i = 0;
            s.segmentEnd = null;
            s.setOut = Math.sqrt(v.getBlockPos().getSquaredDistance(goal));
            if (!s.out) Doing.count(s.path == null ? "nav.none" : "nav.route" + (work ? ".work" : ""));
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
        if (s.i >= s.path.size()) {
            // at the end of it: there; or out of the house, on to the goal now; or as near as the ground allowed (a long
            // way, a stretch at a time: a stretch that got him nearer is no failure) - look again from here
            s.path = null;
            double left = Math.sqrt(v.getBlockPos().getSquaredDistance(goal));
            if (s.out) s.out = false;
            else if (left > 2 && left > s.setOut - 4) s.level++;
            return true;
        }
        Hop next = s.path.get(s.i);
        switch (next.act()) {
            case DIG -> {
                // what stands in the way: the block ahead and the one over it, and going up a cut step, the one over his head
                java.util.List<BlockPos> cut = new java.util.ArrayList<>();
                if (next.pos().getY() > feet.getY()) cut.add(feet.up(2));
                cut.add(next.pos().up());
                cut.add(next.pos());
                for (BlockPos b : cut) {
                    if (open(world, b)) continue;
                    if (!Move.diggable(world, village, b)) { // someone built here meanwhile
                        s.path = null;
                        return true;
                    }
                    if (v.squaredDistanceTo(Vec3d.ofCenter(b)) > 16 || !Dig.sees(v, b)) break;
                    Gear.face(v, b);
                    Gear.toolFor(v, village, world.getBlockState(b));
                    Dig.start(v, b); // a crack at a time, like anyone with a shovel
                    Doing.count("nav.dig");
                    return true;
                }
                if (open(world, next.pos()) && open(world, next.pos().up())) {
                    if (next.pos().getY() > feet.getY()) Move.step(v, next.pos()); // up the step he cut
                    else walk(v, s, s.i);
                }
                return true;
            }
            case BRIDGE -> {
                BlockPos under = next.pos().down();
                if (world.getBlockState(under).isReplaceable() && v.squaredDistanceTo(Vec3d.ofCenter(under)) <= 16) {
                    Doing.count("nav.bridge");
                    if (!Move.place(v, village, world, under)) { // nothing to lay: no bridge this way
                        s.path = null;
                        s.level = Math.max(s.level, 3);
                        return true;
                    }
                    if (next.pos().getY() < feet.getY()) s.laid.add(under.toImmutable()); // a step down: taken away after
                }
                walk(v, s, s.i);
                return true;
            }
            case PILLAR -> {
                BlockPos base = next.pos().down();
                if (feet.equals(base)) {
                    v.getNavigation().stop();
                    if (Move.pillar(v, village, world, feet)) Doing.count("nav.pillar");
                    else { // nothing to put under him, or no room overhead: not this way
                        s.path = null;
                        s.level = Math.max(s.level, 3);
                    }
                } else Move.step(v, base);
                return true;
            }
            case DIGDOWN -> {
                BlockPos base = next.pos().up();
                if (open(world, next.pos())) { // dug: down into it
                    Move.step(v, next.pos());
                    return true;
                }
                if (!Move.diggable(world, village, next.pos())) {
                    s.path = null;
                    return true;
                }
                if (feet.equals(base)) {
                    v.getNavigation().stop();
                    Gear.face(v, next.pos());
                    Gear.toolFor(v, village, world.getBlockState(next.pos()));
                    Dig.start(v, next.pos());
                    Doing.count("nav.digdown");
                } else Move.step(v, base);
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
