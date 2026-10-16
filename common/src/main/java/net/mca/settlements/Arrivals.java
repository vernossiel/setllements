package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.relationship.Gender;
import net.mca.resources.Names;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * People come and go. A village with beds to spare, bread enough and no war on its doorstep draws newcomers: now and
 * then of a morning somebody walks in along the road with a bundle (or a couple come together) and asks the elder for
 * a place; the better the village lives, the likelier. A village that goes hungry day after day loses people: one packs
 * up, says his piece and walks off down the road, and is gone. So the rich grow and the poor dwindle - and a hungry
 * village has every reason to look at its neighbour's granary.
 */
public final class Arrivals {
    private static final Map<Integer, Long> LAST_DAY = new HashMap<>();
    private static final Map<Integer, Integer> HUNGRY_DAYS = new HashMap<>();
    private static final Map<UUID, Long> LEAVING = new HashMap<>(); // until (world time): walked out by then, he is gone
    private static final Map<UUID, BlockPos> ROAD = new HashMap<>();

    private Arrivals() {
    }

    /** Every half minute: once a village's morning, who comes and who goes. */
    public static void tick(ServerWorld world) {
        long tod = world.getTimeOfDay(), day = tod / 24000L, t = tod % 24000L;
        if (t < 1000 || t > 4000) return;
        for (Village v : VillageManager.get(world)) {
            if (v.hall == null || LAST_DAY.getOrDefault(v.getId(), -1L) == day) continue;
            LAST_DAY.put(v.getId(), day);
            morning(world, v);
        }
    }

    private static void morning(ServerWorld world, Village v) {
        List<VillagerEntityMCA> folk = v.getResidents(world);
        int pop = folk.size();
        if (pop < 2) return;
        int food = 0;
        for (ItemStack s : v.storageBuffer) if (s.isFood()) food += s.getCount();
        double per = food / (double) pop;

        // hunger that lasts drives people away
        int hungry = per < 0.5 ? HUNGRY_DAYS.merge(v.getId(), 1, Integer::sum) : 0;
        if (per >= 0.5) HUNGRY_DAYS.put(v.getId(), 0);
        if (hungry >= 3 && pop > 4 && world.random.nextFloat() < 0.5f) {
            VillagerEntityMCA who = null;
            for (VillagerEntityMCA r : folk) {
                if (r.isBaby() || r.isElder() || r.isGuard() || !r.isAlive() || LEAVING.containsKey(r.getUuid())) continue;
                if (keeps(r, folk)) continue; // the ones who feed the village, and its only builder, stay and see it through
                if (r.getRelationships().getPartner().isPresent() && who != null) continue; // a single one goes first
                who = r;
            }
            if (who != null) leave(world, v, who);
            return;
        }

        // free beds, bread, peace: newcomers
        int free = v.getMaxPopulation() - pop;
        if (free < 1 || per < 1.5 || Prosperity.danger(v) >= 4 || Mood.afraid(v)) return;
        double chance = 0.25 + Math.min(0.35, (per - 1.5) * 0.08) + (Prosperity.factor(v) - 1.0) * 0.5;
        if (world.random.nextDouble() >= Math.max(0.05, Math.min(0.75, chance))) return;
        boolean couple = free >= 2 && world.random.nextFloat() < 0.4f;
        BlockPos road = edge(world, v);
        if (road == null) return;
        List<VillagerEntityMCA> came = new ArrayList<>();
        Gender first = world.random.nextBoolean() ? Gender.MALE : Gender.FEMALE;
        came.add(spawn(world, v, road, first));
        if (couple) came.add(spawn(world, v, road.east(), first == Gender.MALE ? Gender.FEMALE : Gender.MALE));
        came.removeIf(x -> x == null);
        if (came.isEmpty()) return;
        if (came.size() == 2) {
            came.get(0).getRelationships().marry(came.get(1));
            came.get(1).getRelationships().marry(came.get(0));
        }
        String names = came.size() == 2 ? came.get(0).getName().getString() + ", " + came.get(1).getName().getString() : came.get(0).getName().getString();
        Life.news(v, came.size() == 2 ? "arrival.couple" : "arrival", names);
        VillagerEntityMCA elder = Plan.elder(world, v);
        if (elder != null) elder.settlementsQueue.add(Text.translatable(came.size() == 2 ? "settlements.elder.welcome.couple" : "settlements.elder.welcome", names));
    }

    /** Those a hungry village cannot do without: whoever brings in food, and the one builder it has. */
    private static boolean keeps(VillagerEntityMCA r, List<VillagerEntityMCA> folk) {
        var p = r.getProfession();
        if (p == net.minecraft.village.VillagerProfession.FARMER || p == net.minecraft.village.VillagerProfession.SHEPHERD
                || p == net.minecraft.village.VillagerProfession.BUTCHER || p == net.minecraft.village.VillagerProfession.LEATHERWORKER) return true;
        if (p == net.minecraft.village.VillagerProfession.CARTOGRAPHER) return folk.stream().filter(o -> o.getProfession() == p).count() <= 1;
        return false;
    }

    /** A newcomer (or a couple) walks in now, if the village has room: for the days caught up (see Ledger). */
    static int newcomers(ServerWorld world, Village v) {
        int free = v.getMaxPopulation() - v.getPopulation();
        if (free < 1) return 0;
        BlockPos road = edge(world, v);
        if (road == null) return 0;
        Gender g = world.random.nextBoolean() ? Gender.MALE : Gender.FEMALE;
        VillagerEntityMCA a = spawn(world, v, road, g);
        if (a == null) return 0;
        if (free >= 2 && world.random.nextFloat() < 0.4f) {
            VillagerEntityMCA b = spawn(world, v, road.east(), g == Gender.MALE ? Gender.FEMALE : Gender.MALE);
            if (b != null) {
                a.getRelationships().marry(b);
                b.getRelationships().marry(a);
                return 2;
            }
        }
        return 1;
    }

    private static VillagerEntityMCA spawn(ServerWorld world, Village v, BlockPos at, Gender g) {
        VillagerEntityMCA n = g.getVillagerType().spawn(world, at, SpawnReason.EVENT);
        if (n == null) return null;
        n.setCustomName(Text.literal(Names.pickCitizenName(g)));
        n.getInventory().addStack(new ItemStack(Items.BREAD, 2));
        BlockPos to = v.hallPos != null ? v.hallPos : new BlockPos(v.getCenter());
        n.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(to, 0.5f, 3));
        Speech.sayNow(n, "settlements.say.arrive");
        return n;
    }

    /** Somewhere on the village's edge, on dry open ground: where the road comes in. */
    private static BlockPos edge(ServerWorld world, Village v) {
        BlockPos c = new BlockPos(v.getCenter());
        for (int i = 0; i < 16; i++) {
            double a = world.random.nextDouble() * Math.PI * 2;
            int r = 30 + world.random.nextInt(12);
            int x = c.getX() + (int) (Math.cos(a) * r), z = c.getZ() + (int) (Math.sin(a) * r);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (world.getFluidState(p.down()).isEmpty() && world.getBlockState(p).isAir() && world.getBlockState(p.up()).isAir()
                    && world.getBlockState(p.down()).isSolidBlock(world, p.down())) return p;
        }
        return null;
    }

    private static void leave(ServerWorld world, Village v, VillagerEntityMCA who) {
        BlockPos road = edge(world, v);
        if (road == null) return;
        LEAVING.put(who.getUuid(), world.getTime() + 20 * 120);
        ROAD.put(who.getUuid(), road);
        Speech.sayNow(who, "settlements.say.leave");
        Life.news(v, "left", who.getName().getString());
    }

    /** Once a second for a villager: true while he is on his way out of a hungry village (and then he is gone). */
    public static boolean leaving(VillagerEntityMCA v, ServerWorld world) {
        Long until = LEAVING.get(v.getUuid());
        if (until == null) return false;
        BlockPos road = ROAD.get(v.getUuid());
        Doing.set(v, "settlements.doing.leave");
        boolean out = road == null || v.squaredDistanceTo(Vec3d.ofBottomCenter(road)) < 4;
        if (out || world.getTime() >= until) {
            // gone down the road, unless somebody is right there watching him go (then he walks on a little)
            if (world.getClosestPlayer(v, 12) == null || world.getTime() >= until + 20 * 60) {
                LEAVING.remove(v.getUuid());
                ROAD.remove(v.getUuid());
                v.discard();
                return true;
            }
        }
        if (road != null) Move.to(v, road.getX() + 0.5, road.getY(), road.getZ() + 0.5, 0.55);
        return true;
    }
}
