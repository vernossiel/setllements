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

        // for now the village runs on its core trades: builders, lumberjacks, miners, and a farmer so that it eats
        List<Role> roles = new ArrayList<>();
        roles.add(new Role(VillagerProfession.CARTOGRAPHER, Math.max(1, p / 5)));
        roles.add(new Role(VillagerProfession.FLETCHER, Math.max(1, p / 4)));
        if (p >= 3) roles.add(new Role(VillagerProfession.MASON, Math.max(1, p / 5)));
        if (p >= 4) roles.add(new Role(VillagerProfession.FARMER, Math.max(1, p / 6)));

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

        // guards first when the village is big enough to need them
        int guards = (int) adults.stream().filter(VillagerEntityMCA::isGuard).count();
        int guardTarget = p >= 5 ? Math.max(1, p / 7) : 0;
        while (guards < guardTarget && !pool.isEmpty()) {
            assign(world, pool.remove(world.random.nextInt(pool.size())), ProfessionsMCA.GUARD.get());
            guards++;
        }
        for (Role role : roles) {
            int have = (int) adults.stream().filter(r -> r.getProfession() == role.profession()).count();
            while (have < role.target() && !pool.isEmpty()) {
                assign(world, pool.remove(world.random.nextInt(pool.size())), role.profession());
                have++;
            }
        }
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

    private static void assign(ServerWorld world, VillagerEntityMCA v, VillagerProfession prof) {
        v.setProfession(prof);
        v.setExperience(1); // keeps the vanilla "lost my job site" logic from taking the job away again
        v.reinitializeBrain(world);
        Speech.sayHired(v);
    }
}
