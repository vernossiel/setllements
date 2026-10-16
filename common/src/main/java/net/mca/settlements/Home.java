package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Building;
import net.mca.server.world.data.Village;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A home of his own, and what is in it. Each evening, before he lies down, a villager sees to his house: he has a
 * chest of his own there (one standing in the house, or one he buys from the village carpenter once he can), where he
 * puts by what he does not need to carry about and takes out a little when his pockets are empty. And as he gets on,
 * the house fills: a rug by the bed, a flower in a pot, a lantern, a barrel, a shelf of books - one thing at a time,
 * when he has money to spare. So a look into a house tells you how its people live. A couple shares a chest.
 */
public final class Home {
    private static final Map<UUID, Long> DONE = new HashMap<>(); // villager -> the day he last saw to his house
    private static final int POCKET = 12;   // what he keeps on him; the rest goes into the chest
    private static final int CHEST_COST = 12;
    private static final int RESERVE = 15;  // never spends his last savings on furniture
    static final int COMMISSION = 60; // what a better house costs the household, to the village's builders

    private Home() {
    }

    private record Thing(int cost, boolean wall) {
    }

    /** What a house gets, in order, as its people get on. */
    private static final List<Thing> FURNITURE = List.of(
            new Thing(4, false),  // a rug by the bed
            new Thing(6, true),   // a flower in a pot
            new Thing(8, true),   // a lantern
            new Thing(10, true),  // a barrel
            new Thing(16, true)); // a shelf of books

    /** Once an evening, at his bed, before he lies down: true if he did something (he lies down a moment later). */
    public static boolean evening(VillagerEntityMCA v, Village village, ServerWorld world, BlockPos bed) {
        if (village == null || v.isBaby()) return false;
        long day = world.getTimeOfDay() / 24000L;
        if (DONE.getOrDefault(v.getUuid(), -1L) == day) return false;
        DONE.put(v.getUuid(), day);
        Building house = village.getBuildingAt(bed).orElse(null);
        if (house == null) return false;
        Inventory chest = chest(v, village, world, house, bed);
        if (chest == null) return false;
        boolean did = false;
        // savings: the rest of his money into the chest; a little out of it when his pockets are empty
        int pocket = Money.count(v.getInventory());
        if (pocket > POCKET) {
            int put = pocket - POCKET;
            if (Money.take(v.getInventory(), put)) {
                Money.give(chest, put);
                did = true;
            }
        } else if (pocket < 4) {
            int out = Math.min(POCKET - pocket, Money.count(chest));
            if (out > 0 && Money.take(chest, out)) {
                Money.give(v.getInventory(), out);
                did = true;
            }
        }
        // something new for the house, when there is money to spare
        if (v.settlementsFurnished < FURNITURE.size() && v.settlementsPurse >= 0) {
            Thing t = FURNITURE.get(v.settlementsFurnished);
            if (Money.count(chest) + Money.count(v.getInventory()) >= t.cost() + RESERVE) {
                BlockPos at = spot(world, house, bed, t.wall());
                if (at != null && spend(v, chest, village, t.cost())) {
                    world.setBlockState(at, furniture(v, v.settlementsFurnished), Block.NOTIFY_ALL);
                    v.settlementsFurnished++;
                    partnerFurnished(v, world);
                    Speech.say(v, "settlements.say.home.furnish");
                    Gaze.at(v, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
                    did = true;
                }
            }
        }
        // the house furnished and money still put by: they ask the elder for a better house
        if (!did && v.settlementsFurnished >= FURNITURE.size() && v.settlementsPurse >= 0 && commission(v, village, world, chest, bed)) did = true;
        if (did) v.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        return did;
    }

    /**
     * A household that has done well pays the village to have its house pulled down and a better one built in its
     * place (one station above the village's own); till then they sleep where they can, and the new house is theirs.
     * What they had put by in the old chest goes into their pockets first, so the builders do not empty it into the stores.
     */
    private static boolean commission(VillagerEntityMCA v, Village village, ServerWorld world, Inventory chest, BlockPos bed) {
        if (!village.commissions.isEmpty() || Money.count(chest) + Money.count(v.getInventory()) < COMMISSION + RESERVE) return false;
        Built house = null;
        for (Built b : village.built) if (b.contains(bed)) house = b;
        if (house == null || !Rebuild.canCommission(village, house)) return false;
        if (!spend(v, chest, village, COMMISSION)) return false;
        int savings = Money.count(chest);
        if (savings > 0 && Money.take(chest, savings)) Money.give(v.getInventory(), savings);
        village.commissions.put(house.origin().asLong(), v.getUuid());
        village.markDirty();
        v.settlementsChest = null;
        v.settlementsFurnished = 0;
        VillagerEntityMCA partner = partner(v, world);
        if (partner != null) {
            partner.settlementsChest = null;
            partner.settlementsFurnished = 0;
        }
        Speech.say(v, "settlements.say.home.commission");
        Life.news(village, "commission", v.getName().getString());
        return true;
    }

    /** His chest: the one he has, a free one in the house, his partner's, or a new one bought now. Null if none. */
    private static Inventory chest(VillagerEntityMCA v, Village village, ServerWorld world, Building house, BlockPos bed) {
        if (valid(world, house, v.settlementsChest)) return inv(world, v.settlementsChest);
        BlockPos old = v.settlementsChest;
        BlockPos mine = null;
        VillagerEntityMCA partner = partner(v, world);
        if (partner != null && valid(world, house, partner.settlementsChest)) mine = partner.settlementsChest;
        if (mine == null) mine = free(v, village, world, house);
        if (mine == null) {
            // none to be had: one from the carpenter, if he can pay for it
            int have = Money.count(v.getInventory());
            BlockPos at = have >= CHEST_COST ? spot(world, house, bed, true) : null;
            if (at == null || !Money.take(v.getInventory(), CHEST_COST)) return null;
            Money.deposit(village, CHEST_COST);
            world.setBlockState(at, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, facing(world, at)), Block.NOTIFY_ALL);
            mine = at;
            Speech.say(v, "settlements.say.home.chest");
        }
        v.settlementsChest = mine;
        if (partner != null && !valid(world, house, partner.settlementsChest)) partner.settlementsChest = mine;
        // a new house: what he had put by in the old one comes along, and the new house is furnished afresh
        if (old != null && !old.equals(mine)) {
            v.settlementsFurnished = 0;
            if (world.isChunkLoaded(old) && world.getBlockEntity(old) instanceof ChestBlockEntity c) {
                int n = Money.count(c);
                if (n > 0 && Money.take(c, n)) Money.give(inv(world, mine), n);
            }
        }
        return inv(world, mine);
    }

    private static boolean valid(ServerWorld world, Building house, BlockPos p) {
        return p != null && house.containsPos(p) && world.isChunkLoaded(p) && world.getBlockEntity(p) instanceof ChestBlockEntity;
    }

    private static Inventory inv(ServerWorld world, BlockPos p) {
        return world.getBlockEntity(p) instanceof ChestBlockEntity c ? c : null;
    }

    /** A chest in the house that nobody else calls his, and that is not the village's or a player's. */
    private static BlockPos free(VillagerEntityMCA v, Village village, ServerWorld world, Building house) {
        Set<BlockPos> claimed = new HashSet<>();
        for (VillagerEntityMCA r : village.getResidents(world)) if (r != v && r.settlementsChest != null) claimed.add(r.settlementsChest);
        BlockPos a = house.getPos0(), b = house.getPos1();
        for (BlockPos p : BlockPos.iterate(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))) {
            if (!(world.getBlockState(p).getBlock() instanceof ChestBlock) || !house.containsPos(p)) continue;
            BlockPos q = p.toImmutable();
            if (claimed.contains(q) || village.hallChests.contains(q) || village.playerChests.contains(q) || Haul.storeChest(village, q)) continue;
            return q;
        }
        return null;
    }

    /** He pays, from his pockets and then his chest, into the coffers (the carpenter's and the potter's work). */
    private static boolean spend(VillagerEntityMCA v, Inventory chest, Village village, int cost) {
        int fromPocket = Math.min(cost, Money.count(v.getInventory()));
        int fromChest = cost - fromPocket;
        if (Money.count(chest) < fromChest) return false;
        if (fromPocket > 0 && !Money.take(v.getInventory(), fromPocket)) return false;
        if (fromChest > 0 && !Money.take(chest, fromChest)) return false;
        Money.deposit(village, cost);
        return true;
    }

    private static BlockState furniture(VillagerEntityMCA v, int i) {
        int pick = Math.floorMod(v.getUuid().hashCode(), 4);
        return switch (i) {
            case 0 -> List.of(Blocks.RED_CARPET, Blocks.BROWN_CARPET, Blocks.GREEN_CARPET, Blocks.BLUE_CARPET).get(pick).getDefaultState();
            case 1 -> List.of(Blocks.POTTED_POPPY, Blocks.POTTED_DANDELION, Blocks.POTTED_CORNFLOWER, Blocks.POTTED_FERN).get(pick).getDefaultState();
            case 2 -> Blocks.LANTERN.getDefaultState();
            case 3 -> Blocks.BARREL.getDefaultState();
            default -> Blocks.BOOKSHELF.getDefaultState();
        };
    }

    private static VillagerEntityMCA partner(VillagerEntityMCA v, ServerWorld world) {
        return v.getRelationships().getPartnerUUID().map(world::getEntity).filter(e -> e instanceof VillagerEntityMCA).map(e -> (VillagerEntityMCA) e).orElse(null);
    }

    /** A couple furnish one house together. */
    private static void partnerFurnished(VillagerEntityMCA v, ServerWorld world) {
        VillagerEntityMCA p = partner(v, world);
        if (p != null && v.settlementsChest != null && v.settlementsChest.equals(p.settlementsChest)) p.settlementsFurnished = v.settlementsFurnished;
    }

    /**
     * A free place on the floor of the house near his bed: open above, on solid ground, not by a door, not plugging a
     * passage. {@code wall}: against a wall (furniture), otherwise anywhere open (a rug).
     */
    static BlockPos spot(ServerWorld world, Building house, BlockPos bed, boolean wall) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                BlockPos p = bed.add(dx, 0, dz);
                if (!house.containsPos(p) || !world.getBlockState(p).isAir() || !world.getBlockState(p.up()).isAir()) continue;
                if (!world.getBlockState(p.down()).isSideSolidFullSquare(world, p.down(), Direction.UP)) continue;
                int walls = 0;
                boolean door = false;
                boolean[] open = new boolean[4];
                int k = 0;
                for (Direction d : Direction.Type.HORIZONTAL) {
                    BlockPos q = p.offset(d);
                    BlockState s = world.getBlockState(q);
                    if (s.getBlock() instanceof DoorBlock || world.getBlockState(q.offset(d)).getBlock() instanceof DoorBlock) door = true;
                    if (s.isSolidBlock(world, q)) walls++;
                    open[k++] = s.getCollisionShape(world, q).isEmpty();
                }
                if (door) continue;
                if (wall && walls == 0) continue;
                // HORIZONTAL is N, E, S, W: open both ways along one line and closed across it is a passage
                if (wall && (open[0] && open[2] && !open[1] && !open[3] || open[1] && open[3] && !open[0] && !open[2])) continue;
                if (wall && !open[0] && !open[1] && !open[2] && !open[3]) continue;
                double d = p.getSquaredDistance(bed);
                if (d < bestD) {
                    bestD = d;
                    best = p.toImmutable();
                }
            }
        }
        return best;
    }

    /** A chest faces into the room: away from the wall it stands against. */
    private static Direction facing(ServerWorld world, BlockPos at) {
        for (Direction d : Direction.Type.HORIZONTAL) if (world.getBlockState(at.offset(d)).isSolidBlock(world, at.offset(d))) return d.getOpposite();
        return Direction.SOUTH;
    }

    /** What he has put by at home, in nuggets (when his house is in a loaded chunk; nought otherwise). */
    public static int savings(VillagerEntityMCA v) {
        BlockPos p = v.settlementsChest;
        if (p == null || !(v.getWorld() instanceof ServerWorld world) || !world.isChunkLoaded(p)) return 0;
        return world.getBlockEntity(p) instanceof ChestBlockEntity c ? Money.count(c) : 0;
    }
}
