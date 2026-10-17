package net.mca.settlements;

import net.mca.ProfessionsMCA;
import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.List;

/**
 * The village elder hands out jobs. Without this nothing would start: villagers only get a profession from a vanilla
 * workstation, and no village has a cartography table for its builder. Roles are filled by priority from the
 * unemployed, according to what the village has built and how many people live there.
 */
public final class Staffing {
    private record Role(VillagerProfession profession, int target) {
    }

    private static final java.util.Set<Integer> LIT = new java.util.HashSet<>();

    private Staffing() {
    }

    private static boolean isOurRole(VillagerProfession p) {
        return p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.FARMER || p == VillagerProfession.FLETCHER
                || p == VillagerProfession.TOOLSMITH || p == VillagerProfession.MASON || p == VillagerProfession.BUTCHER
                || p == VillagerProfession.SHEPHERD || p == VillagerProfession.ARMORER || p == VillagerProfession.FISHERMAN
                || p == VillagerProfession.LEATHERWORKER || p == VillagerProfession.CLERIC || p == ProfessionsMCA.INNKEEPER.get()
                || p == ProfessionsMCA.MILLER.get() || p == ProfessionsMCA.COLLIER.get() || p == ProfessionsMCA.MERCHANT.get()
                || p == ProfessionsMCA.GLAZIER.get() || p == ProfessionsMCA.PRIEST.get() || p == ProfessionsMCA.WATCHMAN.get();
    }

    /** The smith's trade is switched off for now (workers make their own tools); true brings it back. */
    static final boolean SMITHS = true;

    private static final java.util.Map<Integer, long[]> FOREST = new java.util.HashMap<>();

    private static final java.util.Map<Integer, long[]> WATER = new java.util.HashMap<>();

    /** Open water round the village (surface water in a 48 block radius, sampled every 4 blocks), looked at every ten minutes. */
    static int water(ServerWorld world, net.mca.server.world.data.Village village) {
        long[] w = WATER.get(village.getId());
        if (w != null && world.getTime() - w[1] < 12000) return (int) w[0];
        net.minecraft.util.math.BlockPos c = new net.minecraft.util.math.BlockPos(village.getCenter());
        int n = 0;
        for (int dx = -48; dx <= 48; dx += 4) {
            for (int dz = -48; dz <= 48; dz += 4) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
                if (world.getFluidState(new net.minecraft.util.math.BlockPos(x, y, z)).isIn(net.minecraft.registry.tag.FluidTags.WATER)) n++;
            }
        }
        WATER.put(village.getId(), new long[]{n, world.getTime()});
        return n;
    }

    /** Trees round the village (tree tops in a 56 block radius, sampled every 4 blocks), looked at every ten minutes. */
    static int forest(ServerWorld world, net.mca.server.world.data.Village village) {
        long[] f = FOREST.get(village.getId());
        long now = world.getTime();
        if (f != null && now - f[1] < 12000) return (int) f[0];
        net.minecraft.util.math.BlockPos c = new net.minecraft.util.math.BlockPos(village.getCenter());
        int n = 0;
        for (int dx = -56; dx <= 56; dx += 4) {
            for (int dz = -56; dz <= 56; dz += 4) {
                int x = c.getX() + dx, z = c.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
                var s = world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
                if (s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || s.isIn(net.minecraft.registry.tag.BlockTags.LOGS)) n++;
            }
        }
        FOREST.put(village.getId(), new long[]{n, now});
        return n;
    }

    private static boolean isCore(VillagerProfession p) {
        return p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH
                || p == VillagerProfession.MASON || p == VillagerProfession.FARMER || p == VillagerProfession.SHEPHERD || p == VillagerProfession.BUTCHER
                || p == VillagerProfession.LEATHERWORKER || p == ProfessionsMCA.INNKEEPER.get() || p == VillagerProfession.FISHERMAN
                || p == VillagerProfession.CLERIC || p == ProfessionsMCA.MILLER.get() || p == ProfessionsMCA.COLLIER.get() || p == ProfessionsMCA.MERCHANT.get()
                || p == ProfessionsMCA.GLAZIER.get() || p == ProfessionsMCA.PRIEST.get() || p == ProfessionsMCA.WATCHMAN.get();
    }

    /** Somebody in the village works at this trade. */
    static boolean has(net.minecraft.server.world.ServerWorld world, Village village, VillagerProfession p) {
        for (VillagerEntityMCA r : village.getResidents(world)) if (r.isAlive() && !r.isBaby() && r.getProfession() == p) return true;
        return false;
    }

    /** True for jobs nobody depends on, so the person can be reassigned. */
    private static boolean isFree(VillagerProfession p) {
        if (p == VillagerProfession.NONE || p == VillagerProfession.NITWIT) return true;
        if (isOurRole(p)) return false;
        return Registries.VILLAGER_PROFESSION.getId(p).getNamespace().equals("minecraft"); // librarian, weaponsmith ...
    }

    /** Every thirty seconds per world. */
    public static void tick(ServerWorld world) {
        for (Village v : VillageManager.get(world)) staff(world, v);
    }

    private static void staff(ServerWorld world, Village v) {
        List<VillagerEntityMCA> adults = v.getResidents(world).stream()
                .filter(r -> !r.isBaby() && r.isAlive() && !Hire.isHired(r) && r.getProfession() != ProfessionsMCA.MERCENARY.get())
                .toList();
        int p = adults.size();
        if (v.hall == null) openHall(world, v);
        Housing.assign(world, v); // a house to each household
        if (p < 2) return;

        // once the hall stands, lamps round it straight away: the first nights are the deadliest
        if (v.hallPos != null && LIT.add(v.getId())) Watch.lightUp(world, v, 8);

        // the founders' supplies: a new village starts with enough to begin, the rest it has to earn
        if (!v.seeded) {
            v.seeded = true;
            int pop = Math.max(p, 4);
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_PLANKS, 60 + pop * 10));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_LOG, 12));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.COBBLESTONE, 24));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.BREAD, pop * 6)); // to see them to the first harvest
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.WHITE_WOOL, 6));
            Money.deposit(v, 60); // the coffers' first money, for the wages
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.TORCH, 16)); // lamps for the first nights
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.WHEAT_SEEDS, 16));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.POTATO, 8));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.CARROT, 8));
            v.markDirty();
        }

        // the elder: the first civilian by id, always the same one, and he keeps no other job
        VillagerEntityMCA elder = Plan.elder(world, v);
        for (VillagerEntityMCA r : adults) r.setElder(r == elder);
        // the elder governs; he does not go down the mine or into the woods
        if (elder != null && isOurRole(elder.getProfession())) {
            elder.setProfession(VillagerProfession.NONE);
            elder.reinitializeBrain(world);
        }

        // the elder decides who does what, after what the village needs right now
        Builder.Shortage short_ = Builder.shortage(v);
        int food = 0;
        for (net.minecraft.item.ItemStack s : v.storageBuffer) if (s.isFood()) food += s.getCount();
        boolean hungry = food < p * 3;
        List<Role> roles = new ArrayList<>(); // in order of importance
        roles.add(new Role(VillagerProfession.CARTOGRAPHER, Math.max(1, Math.min(4, p / 3)))); // a second pair of hands from six people on: one builder alone took days over a house
        if (p >= 3) roles.add(new Role(VillagerProfession.FARMER, Math.max(1, p / 6) + (hungry ? 1 : 0)));
        // lumberjacks after the woods there are: a desert or a steppe village keeps one, who goes far for wood
        int woods = forest(world, v);
        int lumber = woods >= 40 ? Math.max(1, p / 4) : woods >= 12 ? Math.max(1, p / 8) : 1;
        roles.add(new Role(VillagerProfession.FLETCHER, lumber + (woods >= 12 && short_ != null && short_.need() == Needs.Need.PLANKS ? 1 : 0)));
        // a shepherd once there is a pen (before it, the farmer keeps what animals there are), then the miner, then the butcher
        if (p >= 4 && v.penCenter != null) roles.add(new Role(VillagerProfession.SHEPHERD, 1));
        if (p >= 3) roles.add(new Role(VillagerProfession.MASON, Math.max(1, p / 5) + (short_ != null && short_.need() == Needs.Need.COBBLE ? 1 : 0)));
        // the hunter when the larder runs low or the village is big enough to keep one; the butcher once there is a herd
        // to take from; the innkeeper once there is a tavern to keep
        if (p >= 5 && (hungry || p >= 8)) roles.add(new Role(VillagerProfession.LEATHERWORKER, 1));
        if (p >= 5 && v.penCenter != null) roles.add(new Role(VillagerProfession.BUTCHER, 1));
        if (p >= 6 && v.tavern != null) roles.add(new Role(ProfessionsMCA.INNKEEPER.get(), 1));
        // a smith only once there is a smithy to work in: tools, ingots
        boolean smithy = SMITHS && v.smithyBuilt && v.smithyFurnace != null;
        if (smithy && p >= 4) roles.add(new Role(VillagerProfession.ARMORER, 1));
        // the workshops' own people, once the workshop stands: the miller, the collier, the merchant; the fisherman by
        // the water; the healer once the village is big enough or has a chapel
        if (p >= 4 && water(world, v) >= 24) roles.add(new Role(VillagerProfession.FISHERMAN, 1 + (hungry && p >= 8 ? 1 : 0)));
        if (p >= 5 && Buildings.has(v, "mill")) roles.add(new Role(ProfessionsMCA.MILLER.get(), 1));
        if (p >= 6 && Buildings.has(v, "charcoal")) roles.add(new Role(ProfessionsMCA.COLLIER.get(), 1));
        if (p >= 8 && (Buildings.has(v, "chapel") || p >= 12)) roles.add(new Role(VillagerProfession.CLERIC, 1));
        if (p >= 8 && Buildings.has(v, "market")) roles.add(new Role(ProfessionsMCA.MERCHANT.get(), 1));
        if (p >= 10 && Buildings.has(v, "chapel")) roles.add(new Role(ProfessionsMCA.PRIEST.get(), 1));
        if (p >= 6) roles.add(new Role(ProfessionsMCA.WATCHMAN.get(), 1));
        if (p >= 7 && (Buildings.has(v, "mason") || v.smithyBuilt)) roles.add(new Role(ProfessionsMCA.GLAZIER.get(), 1));

        // trades that are switched off for now go back to the pool
        for (VillagerEntityMCA r : adults) {
            VillagerProfession rp = r.getProfession();
            if (isOurRole(rp) && !isCore(rp) && !(rp == VillagerProfession.ARMORER && smithy)) {
                r.setProfession(VillagerProfession.NONE);
                r.reinitializeBrain(world);
            }
        }

        // too many lumberjacks for the woods there are (a desert village): the extra ones go back to the pool
        int jacks = 0;
        for (VillagerEntityMCA r : adults) {
            if (r.getProfession() != VillagerProfession.FLETCHER && r.getProfession() != VillagerProfession.TOOLSMITH) continue;
            if (++jacks > lumber) {
                r.setProfession(VillagerProfession.NONE);
                r.reinitializeBrain(world);
            }
        }

        // guards when the village is big enough to need them (more with barracks, more when it is attacked often)
        int guards = (int) adults.stream().filter(VillagerEntityMCA::isGuard).count();
        int guardTarget = p >= 6 ? Math.max(1, p / 7) : 0; // a hamlet of five needs a mason more than a guard
        if (Buildings.has(v, "barracks")) guardTarget = Math.max(2, p / 4);
        if (p >= 4 && Prosperity.danger(v) >= 4) guardTarget++;
        // more guards than a village this size needs (it has lost people): the extra ones go back to work
        if (guards > guardTarget) {
            for (VillagerEntityMCA r : adults) {
                if (guards <= guardTarget) break;
                if (!r.isGuard() || r.getBrain().getOptionalMemory(net.minecraft.entity.ai.brain.MemoryModuleType.ATTACK_TARGET).isPresent()) continue;
                r.setProfession(VillagerProfession.NONE);
                r.reinitializeBrain(world);
                guards--;
            }
        }

        List<VillagerEntityMCA> pool = new ArrayList<>();
        for (VillagerEntityMCA r : adults) {
            if (!r.isGuard() && !r.isElder() && isFree(r.getProfession())) pool.add(r);
        }
        while (guards < guardTarget && !pool.isEmpty()) {
            announce(elder, assign(world, pool.remove(world.random.nextInt(pool.size())), ProfessionsMCA.GUARD.get()), ProfessionsMCA.GUARD.get());
            guards++;
        }
        // first one of every trade (a village with no miner gets no stone, however many farmers it has), then the extra hands
        List<Role> firsts = new ArrayList<>();
        for (Role role : roles) firsts.add(new Role(role.profession(), 1));
        fill(world, elder, adults, pool, firsts, true);
        fill(world, elder, adults, pool, roles, false);
        // nobody stands about with no trade while there is work: a spare hand goes where the village is shortest - the
        // fields when food runs low, the woods or the quarry when the builders wait on wood or stone, else the fields
        while (!pool.isEmpty()) {
            VillagerEntityMCA who = pool.remove(0);
            VillagerProfession prof = hungry ? VillagerProfession.FARMER
                    : short_ != null && short_.need() == Needs.Need.PLANKS ? VillagerProfession.FLETCHER
                    : short_ != null && short_.need() == Needs.Need.COBBLE ? VillagerProfession.MASON
                    : VillagerProfession.FARMER;
            announce(elder, assign(world, who, prof), prof);
        }
    }

    /** At once, not at the next half minute: a newcomer is taken on the moment he arrives. */
    static void staffNow(ServerWorld world, Village v) {
        staff(world, v);
    }

    private static void fill(ServerWorld world, VillagerEntityMCA elder, List<VillagerEntityMCA> adults, List<VillagerEntityMCA> pool, List<Role> roles, boolean firsts) {
        for (Role role : roles) {
            int have = (int) adults.stream().filter(r -> r.getProfession() == role.profession()).count();
            while (have < role.target()) {
                VillagerEntityMCA who = null;
                if (!pool.isEmpty()) {
                    who = pool.remove(world.random.nextInt(pool.size()));
                } else {
                    // nobody free: someone from a trade that has more hands than it needs right now
                    for (Role other : roles) {
                        if (other == role) continue;
                        List<VillagerEntityMCA> in = adults.stream().filter(r -> r.getProfession() == other.profession() && !r.isElder() && !r.isGuard()).toList();
                        boolean lesser = roles.indexOf(other) > roles.indexOf(role);
                        // a trade with hands to spare gives one up; and in the first round a more important trade with
                        // nobody at all (the builder, the farmer) takes the hand of a less important one
                        if ((in.size() > other.target() || firsts && lesser && !in.isEmpty()) && (firsts || lesser)) {
                            who = in.get(in.size() - 1);
                            break;
                        }
                    }
                }
                if (who == null) break;
                announce(elder, assign(world, who, role.profession()), role.profession());
                have++;
            }
        }
    }

    /** The elder says it out loud, the next time he speaks. */
    private static void announce(VillagerEntityMCA elder, VillagerEntityMCA who, VillagerProfession prof) {
        if (elder == null || who == null) return;
        String role = Registries.VILLAGER_PROFESSION.getId(prof).getPath();
        elder.settlementsQueue.add(net.minecraft.text.Text.translatable("settlements.elder.assign", who.getName(),
                net.minecraft.text.Text.translatable("settlements.role." + role)));
    }

    private static final java.util.Map<Integer, Long> HALL_RETRY = new java.util.HashMap<>();

    /** Every two seconds: a village without a town hall gets one at once. */
    public static void foundHalls(ServerWorld world) {
        for (Village v : VillageManager.get(world)) if (v.hall == null) openHall(world, v);
    }

    private static void openHall(ServerWorld world, Village v) {
        if (world.getTime() < HALL_RETRY.getOrDefault(v.getId(), 0L)) return;
        if (!Builder.foundHall(world, v)) {
            HALL_RETRY.put(v.getId(), world.getTime() + 600); // no flat ground loaded yet: try again later
            return;
        }
        Camp.ensureHearth(v, world);
        VillagerEntityMCA elder = Plan.elder(world, v);
        if (elder != null && v.hallPos != null) {
            elder.setElder(true);
            net.minecraft.util.math.BlockPos spot = v.hallPos.south();
            for (net.minecraft.util.math.BlockPos q : net.minecraft.util.math.BlockPos.iterate(v.hallPos.add(-2, 0, -2), v.hallPos.add(2, 1, 2))) {
                if (world.getBlockState(q).isAir() && world.getBlockState(q.up()).isAir() && world.getBlockState(q.down()).isSolidBlock(world, q.down())) {
                    spot = q.toImmutable();
                    break;
                }
            }
            elder.refreshPositionAndAngles(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, elder.getYaw(), 0);
        }
    }

    static VillagerEntityMCA assign(ServerWorld world, VillagerEntityMCA v, VillagerProfession prof) {
        v.setProfession(prof);
        if (Haul.load(v) > 0) v.settlementsDeliverNow = true; // what he gathered at his old work goes to the stores first (see Workforce)
        v.setExperience(1); // keeps the vanilla "lost my job site" logic from taking the job away again
        v.reinitializeBrain(world);
        Speech.sayHired(v);
        return v;
    }
}
