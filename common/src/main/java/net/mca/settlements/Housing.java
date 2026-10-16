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
        // a bed somebody took for himself the vanilla way, off in the valley or the woods: let go of it
        for (VillagerEntityMCA m : folk) {
            GlobalPos home = m.getResidency().getHome().orElse(null);
            if (home != null && !allowed(v, home.getPos())) {
                m.releaseTicketFor(MemoryModuleType.HOME);
                m.getBrain().forget(MemoryModuleType.HOME);
            }
        }
        List<List<VillagerEntityMCA>> households = households(folk);
        // the houses: buildings with beds in them
        Map<Building, List<BlockPos>> houses = new HashMap<>();
        PointOfInterestStorage poi = world.getPointOfInterestStorage();
        BlockPos heart = v.hallPos != null ? v.hallPos : new BlockPos(v.getCenter());
        int reach = reach(v, heart);
        for (Building b : v.getBuildings().values()) {
            if (b.getBuildingType().grouped()) continue;
            // a house the village counts but that stands off in the woods is no home: nobody walks there at nightfall.
            // Whatever the village built itself is home wherever it stands; anything else only within the village as it
            // has grown (its own houses' reach, never less than 48 blocks from the hall)
            if (!ours(v, b) && (b.getCenter().getSquaredDistance(heart) > Math.min(reach, 56) * Math.min(reach, 56)
                    || Math.abs(b.getCenter().getY() - heart.getY()) > 14) && !byBell(v, b.getCenter())) continue; // nor one down in the valley below
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
            boolean moving = promised(v, h, houses, taken, false) != null; // their new house stands ready: they move
            if (together && !moving && at != null && !taken.contains(at) && houses.containsKey(at) && houses.get(at).size() >= h.size()) taken.add(at);
            else unhoused.add(h);
        }
        // the others into the smallest free house that fits them, the biggest households first
        unhoused.sort(Comparator.comparingInt((List<VillagerEntityMCA> h) -> h.size()).reversed());
        int homeless = 0;
        Built hall = null;
        for (Built b : v.built) if (Buildings.base(b.type()).equals("townhall")) hall = b;
        boolean bedMoved = false;
        for (List<VillagerEntityMCA> h : unhoused) {
            Building best = promised(v, h, houses, taken, true);
            for (Map.Entry<Building, List<BlockPos>> e : houses.entrySet()) {
                if (best != null) break;
                if (taken.contains(e.getKey()) || e.getValue().size() < h.size() || promisedToOther(v, e.getKey(), h)) continue;
                if (best == null || e.getValue().size() < houses.get(best).size()) best = e.getKey();
            }
            if (best == null) {
                // no house for them: they sleep in the town hall, on a bed carried in from a house too far off to be
                // a home (or one made from the stores' wool and planks), one bed each half minute
                final Built hb = hall;
                if (hb != null && h.stream().allMatch(m -> inHall(world, hb, m))) continue; // bedded down in the hall already
                if (hb != null && !bedMoved) {
                    VillagerEntityMCA m = h.stream().filter(x -> !inHall(world, hb, x)).findFirst().orElse(null);
                    BlockPos nb = m == null ? null : hallBed(world, v, hb, heart);
                    if (nb != null) {
                        move(world, m, nb);
                        bedMoved = true;
                        if (h.stream().allMatch(x -> inHall(world, hb, x))) continue;
                    }
                }
                homeless++;
                // a bed far off is no home either: better the town hall's floor than a walk through the woods at night
                for (VillagerEntityMCA m : h) {
                    GlobalPos home = m.getResidency().getHome().orElse(null);
                    if (home != null && home.getPos().getSquaredDistance(heart) > reach * reach && v.getBuildingAt(home.getPos()).map(b -> !ours(v, b)).orElse(true)) {
                        m.releaseTicketFor(MemoryModuleType.HOME);
                        m.getBrain().forget(MemoryModuleType.HOME);
                    }
                }
                continue;
            }
            taken.add(best);
            List<BlockPos> beds = houses.get(best);
            for (int i = 0; i < h.size(); i++) move(world, h.get(i), beds.get(i));
        }
        HOMELESS.put(v.getId(), homeless);
        EMPTY.put(v.getId(), (int) houses.keySet().stream().filter(b -> !taken.contains(b)).count());
    }

    /** Whether a bed here can be a home: in a house the village built, or in another one near the hall and on its level. */
    public static boolean allowed(Village v, BlockPos bed) {
        BlockPos heart = v.hallPos != null ? v.hallPos : new BlockPos(v.getCenter());
        Building b = v.getBuildingAt(bed).orElse(null);
        if (b != null && ours(v, b)) return true;
        for (Built x : v.built) if (x.contains(bed)) return true;
        int reach = Math.min(reach(v, heart), 56); // (an old village is wider than forty blocks: its far houses stood empty and their people slept out)
        if (bed.getSquaredDistance(heart) <= reach * reach && Math.abs(bed.getY() - heart.getY()) <= 14) return true;
        // the houses the village stood in before its hall was raised: the hall may have gone up well off on lower
        // ground (no level site among them), and the old houses about the bell are homes all the same
        return b != null && byBell(v, bed);
    }

    /** Among the old houses about the village's bell, on its level. */
    private static boolean byBell(Village v, BlockPos p) {
        BlockPos bell = bell(v);
        return bell != null && p.getSquaredDistance(bell) <= 56 * 56 && Math.abs(p.getY() - bell.getY()) <= 14;
    }

    /** The village's bell (its meeting place, where it stood before there was a hall); null if it has none. */
    private static BlockPos bell(Village v) {
        for (Building x : v.getBuildings().values()) if ("town_center".equals(x.getType())) return x.getCenter();
        return null;
    }

    /** Built by the village itself (one of its own plots). */
    private static boolean ours(Village v, Building b) {
        for (Built x : v.built) if (x.contains(b.getCenter())) return true;
        return false;
    }

    /** How far the village reaches from its hall: its farthest own building and a little more, at least 48 blocks. */
    private static int reach(Village v, BlockPos heart) {
        double far = 48;
        for (Built x : v.built) far = Math.max(far, Math.sqrt(x.origin().getSquaredDistance(heart)) + 12);
        return (int) Math.min(160, far);
    }

    /** The new house this household paid for, once it stands with its beds in it. */
    private static Building promised(Village v, List<VillagerEntityMCA> h, Map<Building, List<BlockPos>> houses, Set<Building> taken, boolean claim) {
        for (Map.Entry<Long, UUID> r : v.reserved.entrySet()) {
            if (h.stream().noneMatch(m -> m.getUuid().equals(r.getValue()))) continue;
            BlockPos at = BlockPos.fromLong(r.getKey());
            for (Map.Entry<Building, List<BlockPos>> e : houses.entrySet()) {
                if (taken.contains(e.getKey()) || !e.getKey().containsPos(at)) continue;
                if (e.getValue().size() < h.size()) continue;
                if (!claim) return e.getKey();
                v.reserved.remove(r.getKey()); // theirs now: from here on it is their house like any other
                v.markDirty();
                return e.getKey();
            }
        }
        return null;
    }

    /** A new house somebody else paid for, not yet moved into. */
    private static boolean promisedToOther(Village v, Building b, List<VillagerEntityMCA> h) {
        for (Map.Entry<Long, UUID> r : v.reserved.entrySet()) {
            if (b.containsPos(BlockPos.fromLong(r.getKey())) && h.stream().noneMatch(m -> m.getUuid().equals(r.getValue()))) return true;
        }
        return false;
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
    /** His bed is in the town hall. */
    private static boolean inHall(ServerWorld world, Built hall, VillagerEntityMCA m) {
        GlobalPos g = m.getResidency().getHome().orElse(null);
        return g != null && hall.contains(g.getPos()) && world.getBlockState(g.getPos()).getBlock() instanceof net.minecraft.block.BedBlock;
    }

    /**
     * A bed put down on the hall's floor, out of the way of its doors: the head end's place, or null if there is no
     * room or no bed to be had. The bed is one carried in from an empty house too far off to be lived in, or else
     * one made from three wool and three planks out of the stores.
     */
    private static BlockPos hallBed(ServerWorld world, Village v, Built hall, BlockPos heart) {
        BlockPos foot = null;
        net.minecraft.util.math.Direction facing = null;
        BlockPos c = v.hallPos != null ? v.hallPos : hall.point(hall.fw() / 2, 0, hall.fd() / 2);
        search:
        for (BlockPos q : BlockPos.iterateOutwards(c, 8, 3, 8)) {
            if (!hall.contains(q) || !free(world, q)) continue;
            for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                BlockPos head = q.offset(d);
                if (hall.contains(head) && free(world, head)) {
                    foot = q.toImmutable();
                    facing = d;
                    break search;
                }
            }
        }
        if (foot == null) return null;
        // where it comes from: an empty bed off in the woods or the valley first
        net.minecraft.block.BlockState bed = null;
        BlockPos far = world.getPointOfInterestStorage().getInSquare(t -> t.matchesKey(PointOfInterestTypes.HOME), heart, 96, PointOfInterestStorage.OccupationStatus.HAS_SPACE)
                .map(net.minecraft.world.poi.PointOfInterest::getPos).filter(p -> !allowed(v, p) && !hall.contains(p)).findFirst().orElse(null);
        if (far != null && world.getBlockState(far).getBlock() instanceof net.minecraft.block.BedBlock b) {
            bed = b.getDefaultState();
            world.breakBlock(far, false); // (both halves go)
        } else if (Builder.takeItem(v, st -> st.isIn(net.minecraft.registry.tag.ItemTags.WOOL), 3)) {
            if (!Builder.takeItem(v, st -> st.isIn(net.minecraft.registry.tag.ItemTags.PLANKS), 3)) {
                net.mca.settlements.Workforce.addToTreasury(v, new net.minecraft.item.ItemStack(net.minecraft.item.Items.WHITE_WOOL, 3)); // (the wool back: no planks)
                return null;
            }
            bed = net.minecraft.block.Blocks.WHITE_BED.getDefaultState();
        }
        if (bed == null) return null;
        BlockPos head = foot.offset(facing);
        world.setBlockState(foot, bed.with(net.minecraft.block.BedBlock.FACING, facing).with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.FOOT));
        world.setBlockState(head, bed.with(net.minecraft.block.BedBlock.FACING, facing).with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD));
        return head;
    }

    /** Floor to lie on, room over it, under the roof, not in a doorway. */
    private static boolean free(ServerWorld world, BlockPos p) {
        if (!world.getBlockState(p).isAir() || !world.getBlockState(p.up()).isAir() || world.isSkyVisible(p)) return false;
        if (!world.getBlockState(p.down()).isSolidBlock(world, p.down())) return false;
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
            if (world.getBlockState(p.offset(d)).getBlock() instanceof net.minecraft.block.DoorBlock) return false;
            if (world.getBlockState(p.offset(d)).getBlock() instanceof net.minecraft.block.LecternBlock) return false;
        }
        return true;
    }

    private static void move(ServerWorld world, VillagerEntityMCA m, BlockPos bed) {
        GlobalPos now = m.getResidency().getHome().orElse(null);
        if (now != null && now.getPos().equals(bed)) return;
        if (now != null) m.releaseTicketFor(MemoryModuleType.HOME);
        world.getPointOfInterestStorage().getPosition(t -> t.matchesKey(PointOfInterestTypes.HOME), (t, p) -> p.equals(bed), bed, 1);
        m.getBrain().remember(MemoryModuleType.HOME, GlobalPos.create(world.getRegistryKey(), bed));
        m.getBrain().remember(MemoryModuleTypeMCA.FORCED_HOME.get(), true);
    }
}
