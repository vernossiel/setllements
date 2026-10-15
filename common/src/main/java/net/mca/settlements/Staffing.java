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

    private Staffing() {
    }

    private static boolean isOurRole(VillagerProfession p) {
        return p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.FARMER || p == VillagerProfession.FLETCHER
                || p == VillagerProfession.TOOLSMITH || p == VillagerProfession.MASON || p == VillagerProfession.BUTCHER
                || p == VillagerProfession.SHEPHERD || p == VillagerProfession.ARMORER || p == VillagerProfession.FISHERMAN
                || p == VillagerProfession.LEATHERWORKER || p == VillagerProfession.CLERIC;
    }

    private static boolean isCore(VillagerProfession p) {
        return p == VillagerProfession.CARTOGRAPHER || p == VillagerProfession.FLETCHER || p == VillagerProfession.TOOLSMITH
                || p == VillagerProfession.MASON || p == VillagerProfession.FARMER;
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
        if (p < 2) return;

        // the founders' supplies: a new village starts with enough to begin, the rest it has to earn
        if (!v.seeded) {
            v.seeded = true;
            int pop = Math.max(p, 4);
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_PLANKS, 60 + pop * 10));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.OAK_LOG, 12));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.COBBLESTONE, 24));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.BREAD, pop * 2));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.WHITE_WOOL, 6));
            Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.WHEAT_SEEDS, 8));
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
        roles.add(new Role(VillagerProfession.CARTOGRAPHER, Math.max(1, p / 5)));
        if (p >= 3) roles.add(new Role(VillagerProfession.FARMER, Math.max(1, p / 6) + (hungry ? 1 : 0)));
        roles.add(new Role(VillagerProfession.FLETCHER, Math.max(1, p / 4) + (short_ != null && short_.need() == Needs.Need.PLANKS ? 1 : 0)));
        if (p >= 3) roles.add(new Role(VillagerProfession.MASON, Math.max(1, p / 5) + (short_ != null && short_.need() == Needs.Need.COBBLE ? 1 : 0)));

        // trades that are switched off for now go back to the pool
        for (VillagerEntityMCA r : adults) {
            VillagerProfession rp = r.getProfession();
            if (isOurRole(rp) && !isCore(rp)) {
                r.setProfession(VillagerProfession.NONE);
                r.reinitializeBrain(world);
            }
        }

        List<VillagerEntityMCA> pool = new ArrayList<>();
        for (VillagerEntityMCA r : adults) {
            if (!r.isGuard() && !r.isElder() && isFree(r.getProfession())) pool.add(r);
        }

        // guards when the village is big enough to need them (more with barracks, more when it is attacked often)
        int guards = (int) adults.stream().filter(VillagerEntityMCA::isGuard).count();
        int guardTarget = p >= 5 ? Math.max(1, p / 7) : 0;
        if (Buildings.has(v, "barracks")) guardTarget = Math.max(2, p / 4);
        if (p >= 4 && Prosperity.danger(v) >= 4) guardTarget++;
        while (guards < guardTarget && !pool.isEmpty()) {
            announce(elder, assign(world, pool.remove(world.random.nextInt(pool.size())), ProfessionsMCA.GUARD.get()), ProfessionsMCA.GUARD.get());
            guards++;
        }
        // first one of every trade (a village with no miner gets no stone, however many farmers it has), then the extra hands
        List<Role> firsts = new ArrayList<>();
        for (Role role : roles) firsts.add(new Role(role.profession(), 1));
        fill(world, elder, adults, pool, firsts, true);
        fill(world, elder, adults, pool, roles, false);
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
                        if (in.size() > other.target() && (firsts || roles.indexOf(other) > roles.indexOf(role))) {
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
        v.setExperience(1); // keeps the vanilla "lost my job site" logic from taking the job away again
        v.reinitializeBrain(world);
        Speech.sayHired(v);
        return v;
    }
}
