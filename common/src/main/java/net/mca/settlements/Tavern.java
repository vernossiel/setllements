package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * The village tavern: a smoker and a barrel in the kitchen, tables with chairs in the hall. The cook really works the
 * smoker and fills the barrel with meals; hungry villagers walk to a free chair, take a meal from the barrel and eat
 * it sitting at the table.
 */
public record Tavern(BlockPos smoker, BlockPos barrel, BlockPos craft, List<BlockPos> seats) {
    private static final double MAX_TRIP = 90;

    public NbtCompound save() {
        NbtCompound n = new NbtCompound();
        n.putLong("smoker", smoker.asLong());
        n.putLong("barrel", barrel.asLong());
        n.putLong("craft", craft.asLong());
        n.putLongArray("seats", seats.stream().mapToLong(BlockPos::asLong).toArray());
        return n;
    }

    public static Tavern load(NbtCompound n) {
        List<BlockPos> seats = new ArrayList<>();
        for (long l : n.getLongArray("seats")) seats.add(BlockPos.fromLong(l));
        return new Tavern(BlockPos.fromLong(n.getLong("smoker")), BlockPos.fromLong(n.getLong("barrel")), BlockPos.fromLong(n.getLong("craft")), seats);
    }

    public static int mealsIn(Inventory inv) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (Workforce.eatable(s)) n += s.getCount();
        }
        return n;
    }

    /** Inserts as much as fits, returns what is left. */
    public static ItemStack insert(Inventory inv, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int i = 0; i < inv.size() && !rest.isEmpty(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                inv.setStack(i, rest.copy());
                rest.setCount(0);
            } else if (ItemStack.canCombine(slot, rest)) {
                int move = Math.min(rest.getCount(), slot.getMaxCount() - slot.getCount());
                slot.increment(move);
                rest.decrement(move);
            }
        }
        return rest;
    }

    private static ItemStack takeMeal(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (Workforce.eatable(s)) return s.split(1);
        }
        return ItemStack.EMPTY;
    }

    private static Inventory barrelOf(ServerWorld world, Tavern t) {
        return world.isChunkLoaded(t.barrel().getX() >> 4, t.barrel().getZ() >> 4) && world.getBlockEntity(t.barrel()) instanceof Inventory inv ? inv : null;
    }

    /**
     * One second of a diner heading for the tavern. Returns true while the villager is busy with the trip or the meal.
     */
    public static boolean visit(VillagerEntityMCA v, Village village) {
        Tavern t = village.tavern;
        if (t == null || !(v.getWorld() instanceof ServerWorld world)) return false;
        if (v.squaredDistanceTo(t.barrel().getX(), t.barrel().getY(), t.barrel().getZ()) > MAX_TRIP * MAX_TRIP) return false;
        Inventory barrel = barrelOf(world, t);
        if (barrel == null || mealsIn(barrel) == 0) return false;

        BlockPos seat = freeSeat(world, v, t);
        if (seat == null) return false;
        if (v.squaredDistanceTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5) > 2.5 * 2.5) {
            Move.to(v, seat.getX() + 0.5, seat.getY() + 0.5, seat.getZ() + 0.5, 0.6);
            Speech.say(v, "settlements.say.tavern.go");
            return true;
        }
        ItemStack meal = takeMeal(barrel);
        if (meal.isEmpty()) return false;
        BlockState state = world.getBlockState(seat);
        float yaw = 0;
        if (state.getBlock() instanceof StairsBlock) {
            Direction facing = state.get(StairsBlock.FACING); // the back of the chair; the sitter looks the other way
            yaw = facing.getOpposite().asRotation();
        }
        v.refreshPositionAndAngles(seat.getX() + 0.5, seat.getY() + 0.5, seat.getZ() + 0.5, yaw, 0);
        v.setHeadYaw(yaw);
        Workforce.beginMeal(v, meal, 6);
        Speech.say(v, "settlements.say.tavern.eat");
        return true;
    }

    private static BlockPos freeSeat(ServerWorld world, VillagerEntityMCA v, Tavern t) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos seat : t.seats()) {
            if (!world.getBlockState(seat).isOf(world.getBlockState(seat).getBlock()) || !(world.getBlockState(seat).getBlock() instanceof StairsBlock)) continue;
            boolean taken = !world.getEntitiesByClass(VillagerEntityMCA.class, new net.minecraft.util.math.Box(seat).expand(0.4), o -> o != v).isEmpty();
            if (taken) continue;
            double d = v.squaredDistanceTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = seat;
            }
        }
        return best;
    }
}
