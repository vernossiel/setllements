package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The village's order book. What the village is really short of - the builder's planks and stone for the house
 * going up, food when the stores run low - is written up as an order with the amount and who it is for. A worker
 * who can supply it takes it on (so two do not chase the same thing while the rest stand idle), says so, and the
 * order is struck off when the stores hold enough. The notice board and the villagers' talk show what is open.
 */
public final class Orders {
    public record Order(Needs.Need what, int amount, String forWhom, UUID taker, String takerName, long takenAt) {
        Order taken(VillagerEntityMCA v, long now) {
            return new Order(what, amount, forWhom, v.getUuid(), v.getName().getString(), now);
        }
    }

    private static final Map<Integer, EnumMap<Needs.Need, Order>> BOOK = new HashMap<>();
    private static final long STALE = 20 * 120; // a taker not seen at it for two minutes loses the order

    private Orders() {
    }

    private static EnumMap<Needs.Need, Order> book(Village village) {
        return BOOK.computeIfAbsent(village.getId(), k -> new EnumMap<>(Needs.Need.class));
    }

    public static List<Order> open(Village village) {
        return new ArrayList<>(book(village).values());
    }

    static int stock(Village village, Needs.Need what) {
        return switch (what) {
            case PLANKS -> Builder.stockPlanks(village);
            case COBBLE -> Builder.stockCobble(village);
            case IRON -> Builder.stockIron(village);
            case WOOL -> Builder.stockWool(village);
            case FOOD -> Forage.food(village);
            default -> 0;
        };
    }

    private static final Map<Integer, Long> REFRESHED = new HashMap<>();

    /** Called by everyone every second; does the work every five seconds per village. */
    public static void refreshDue(Village village, ServerWorld world) {
        long now = world.getTime();
        if (now - REFRESHED.getOrDefault(village.getId(), -1000L) < 100) return;
        REFRESHED.put(village.getId(), now);
        refresh(village, world);
    }

    /** Every few seconds per village: writes up what is short, strikes off what is no longer. */
    public static void refresh(Village village, ServerWorld world) {
        EnumMap<Needs.Need, Order> b = book(village);
        long now = world.getTime();
        Builder.Shortage s = Builder.shortage(village);
        if (s != null && s.need() != Needs.Need.NONE && s.need() != Needs.Need.FOOD) {
            int want = Math.max(8, Builder.stillNeeded(village, s.need()) - stock(village, s.need()));
            Order o = b.get(s.need());
            String who = Builder.builderName(village, world);
            b.put(s.need(), o == null ? new Order(s.need(), want, who, null, null, 0) : new Order(s.need(), want, who, o.taker(), o.takerName(), o.takenAt()));
        }
        if (Forage.hungry(village)) {
            int want = village.getPopulation() * 4 - stock(village, Needs.Need.FOOD);
            Order o = b.get(Needs.Need.FOOD);
            if (want > 0) b.put(Needs.Need.FOOD, o == null ? new Order(Needs.Need.FOOD, want, "", null, null, 0)
                    : new Order(Needs.Need.FOOD, want, "", o.taker(), o.takerName(), o.takenAt()));
        }
        // struck off: the stores hold enough, or nobody wants it any more
        b.values().removeIf(o -> o.what() == Needs.Need.FOOD ? !Forage.hungry(village)
                : (s == null || s.need() != o.what()) && stock(village, o.what()) >= o.amount() / 2 || stock(village, o.what()) >= o.amount() + 8);
        // takers who walked off it
        for (Map.Entry<Needs.Need, Order> e : b.entrySet()) {
            Order o = e.getValue();
            if (o.taker() == null) continue;
            Entity t = world.getEntity(o.taker());
            if (!(t instanceof VillagerEntityMCA tv) || !tv.isAlive() || now - o.takenAt() > STALE)
                e.setValue(new Order(o.what(), o.amount(), o.forWhom(), null, null, 0));
        }
    }

    /**
     * {@code v} takes on the order for {@code what} if there is one and nobody else has it (or he has it already).
     * Returns the order, or null. Taking it is said aloud once.
     */
    public static Order take(VillagerEntityMCA v, Village village, Needs.Need what) {
        if (village == null) return null;
        EnumMap<Needs.Need, Order> b = book(village);
        Order o = b.get(what);
        if (o == null) return null;
        long now = v.getWorld().getTime();
        if (o.taker() != null && !o.taker().equals(v.getUuid())) return null;
        boolean fresh = o.taker() == null;
        o = o.taken(v, now);
        b.put(what, o);
        if (fresh) Speech.sayNow(v, "settlements.say.order.take." + what.name().toLowerCase(), o.amount(), o.forWhom());
        return o;
    }

    /** The order he holds, if any. */
    public static Order held(VillagerEntityMCA v, Village village) {
        if (village == null) return null;
        for (Order o : book(village).values()) if (v.getUuid().equals(o.taker())) return o;
        return null;
    }

    /** An open order nobody holds, among those {@code v} could supply. */
    public static Order free(Village village, Needs.Need... can) {
        if (village == null) return null;
        EnumMap<Needs.Need, Order> b = book(village);
        for (Needs.Need n : can) {
            Order o = b.get(n);
            if (o != null && o.taker() == null) return o;
        }
        return null;
    }
}
