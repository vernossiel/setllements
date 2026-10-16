package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.entity.ai.MemoryModuleTypeMCA;
import net.mca.server.world.data.Building;
import net.mca.server.world.data.FamilyTreeNode;
import net.mca.server.world.data.Village;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A house to each household. A household is a grown villager on his own, or a married couple with their children; it
 * lives in a house of its own, with a bed for each of them, and sleeps there. The children stay with their parents
 * till they marry. A household with no house of its own is what the builders build for next, and newcomers only come
 * where there is an empty house waiting (see Plan and Arrivals).
 */
public final class Housing {
    private static final Map<Integer, Integer> HOMELESS = new HashMap<>();
    private static final Map<Integer, Integer> EMPTY = new HashMap<>();

    private Housing() {
    }

    /** Households of the village with no house of their own, as last reckoned. */
    public static int homeless(Village v) {
        return HOMELESS.getOrDefault(v.getId(), 0);
    }

    /** Houses standing empty, as last reckoned. */
    public static int empty(Village v) {
        return EMPTY.getOrDefault(v.getId(), 0);
    }

    /** Every half minute for a village in view: the households sorted into the houses. */
    public static void assign(ServerWorld world, Village v) {
        List<VillagerEntityMCA> folk = v.getResidents(world).stream().filter(VillagerEntityMCA::isAlive).toList();
        List<List<VillagerEntityMCA>> households = households(folk);
        // the houses: buildings with beds in them
        Map<Building, List<BlockPos>> houses = new HashMap<>();
        PointOfInterestStorage poi = world.getPointOfInterestStorage();
        for (Building b : v.getBuildings().values()) {
            if (b.getBuildingType().grouped()) continue;
            int r = Math.max(4, b.getSize());
            List<BlockPos> beds = poi.getInSquare(t -> t.matchesKey(PointOfInterestTypes.HOME), b.getCenter(), r, PointOfInterestStorage.OccupationStatus.ANY)
                    .map(p -> p.getPos()).filter(b::containsPos).sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
            if (!beds.isEmpty()) houses.put(b, beds);
        }
        Set<Building> taken = new HashSet<>();
        List<List<VillagerEntityMCA>> unhoused = new ArrayList<>();
        // households already together in one house with beds enough keep it
        for (List<VillagerEntityMCA> h : households) {
            Building at = null;
            boolean together = true;
            for (VillagerEntityMCA m : h) {
                Building b = homeOf(v, m);
                if (b == null || at != null && b != at) together = false;
                if (at == null) at = b;
            }
            if (together && at != null && !taken.contains(at) && houses.containsKey(at) && houses.get(at).size() >= h.size()) taken.add(at);
            else unhoused.add(h);
        }
        // the others into the smallest free house that fits them, the biggest households first
        unhoused.sort(Comparator.comparingInt((List<VillagerEntityMCA> h) -> h.size()).reversed());
        int homeless = 0;
        for (List<VillagerEntityMCA> h : unhoused) {
            Building best = null;
            for (Map.Entry<Building, List<BlockPos>> e : houses.entrySet()) {
                if (taken.contains(e.getKey()) || e.getValue().size() < h.size()) continue;
                if (best == null || e.getValue().size() < houses.get(best).size()) best = e.getKey();
            }
            if (best == null) {
                homeless++;
                continue;
            }
            taken.add(best);
            List<BlockPos> beds = houses.get(best);
            for (int i = 0; i < h.size(); i++) move(world, h.get(i), beds.get(i));
        }
        HOMELESS.put(v.getId(), homeless);
        EMPTY.put(v.getId(), (int) houses.keySet().stream().filter(b -> !taken.contains(b)).count());
    }

    /** The household each villager belongs to: a couple and their children together, everyone else on his own. */
    private static List<List<VillagerEntityMCA>> households(List<VillagerEntityMCA> folk) {
        Map<UUID, VillagerEntityMCA> byId = new HashMap<>();
        for (VillagerEntityMCA f : folk) byId.put(f.getUuid(), f);
        Set<UUID> placed = new HashSet<>();
        List<List<VillagerEntityMCA>> out = new ArrayList<>();
        for (VillagerEntityMCA a : folk) {
            if (a.isBaby() || placed.contains(a.getUuid())) continue;
            List<VillagerEntityMCA> h = new ArrayList<>(List.of(a));
            placed.add(a.getUuid());
            a.getRelationships().getPartnerUUID().map(byId::get).filter(p -> !placed.contains(p.getUuid())).ifPresent(p -> {
                h.add(p);
                placed.add(p.getUuid());
            });
            out.add(h);
        }
        // the children with their parents (or with whoever is left of them)
        for (VillagerEntityMCA c : folk) {
            if (!c.isBaby() || placed.contains(c.getUuid())) continue;
            FamilyTreeNode node = c.getRelationships().getFamilyEntry();
            List<VillagerEntityMCA> home = null;
            for (List<VillagerEntityMCA> h : out) {
                for (VillagerEntityMCA m : h) if (m.getUuid().equals(node.mother()) || m.getUuid().equals(node.father())) home = h;
            }
            if (home == null) home = out.isEmpty() ? null : out.get(0); // an orphan: taken in by the first household
            if (home == null) {
                home = new ArrayList<>();
                out.add(home);
            }
            home.add(c);
            placed.add(c.getUuid());
        }
        return out;
    }

    private static Building homeOf(Village v, VillagerEntityMCA m) {
        GlobalPos h = m.getResidency().getHome().orElse(null);
        if (h == null) return null;
        return v.getBuildingAt(h.getPos()).orElse(null);
    }

    /** His bed now in the household's house. */
    private static void move(ServerWorld world, VillagerEntityMCA m, BlockPos bed) {
        GlobalPos now = m.getResidency().getHome().orElse(null);
        if (now != null && now.getPos().equals(bed)) return;
        if (now != null) m.releaseTicketFor(MemoryModuleType.HOME);
        world.getPointOfInterestStorage().getPosition(t -> t.matchesKey(PointOfInterestTypes.HOME), (t, p) -> p.equals(bed), bed, 1);
        m.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(world.getRegistryKey(), bed));
        m.getBrain().remember(MemoryModuleTypeMCA.FORCED_HOME.get(), true);
    }
}
