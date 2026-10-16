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
        return BOOK.computeIfAbsent(village.getId(), k -> load(village));
    }

    /** The book as it stood when the world was saved (kept with the village). */
    private static EnumMap<Needs.Need, Order> load(Village village) {
        EnumMap<Needs.Need, Order> b = new EnumMap<>(Needs.Need.class);
        net.minecraft.nbt.NbtCompound nbt = village.orderBook;
        for (String key : nbt.getKeys()) {
            try {
                net.minecraft.nbt.NbtCompound o = nbt.getCompound(key);
                Needs.Need what = Needs.Need.valueOf(key);
                b.put(what, new Order(what, o.getInt("amount"), o.getString("for"), o.containsUuid("taker") ? o.getUuid("taker") : null,
                        o.contains("takerName") ? o.getString("takerName") : null, 0));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return b;
    }

    private static void save(Village village) {
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        for (Order o : book(village).values()) {
            net.minecraft.nbt.NbtCompound c = new net.minecraft.nbt.NbtCompound();
            c.putInt("amount", o.amount());
            c.putString("for", o.forWhom());
            if (o.taker() != null) c.putUuid("taker", o.taker());
            if (o.takerName() != null) c.putString("takerName", o.takerName());
            nbt.put(o.what().name(), c);
        }
        village.orderBook = nbt;
        village.markDirty();
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
        // takers who walked off it (a book read back from the save gives its takers a while to turn up)
        for (Map.Entry<Needs.Need, Order> e : b.entrySet()) {
            Order o = e.getValue();
            if (o.taker() == null) continue;
            if (o.takenAt() == 0) {
                e.setValue(new Order(o.what(), o.amount(), o.forWhom(), o.taker(), o.takerName(), now));
                continue;
            }
            Entity t = world.getEntity(o.taker());
            if (!(t instanceof VillagerEntityMCA tv) || !tv.isAlive() || now - o.takenAt() > STALE)
                e.setValue(new Order(o.what(), o.amount(), o.forWhom(), null, null, 0));
        }
        save(village);
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

    /** The elder gives the order to {@code v} (said at the morning gathering). */
    public static void assign(VillagerEntityMCA v, Village village, Needs.Need what) {
        Order o = book(village).get(what);
        if (o != null) book(village).put(what, o.taken(v, v.getWorld().getTime()));
    }

    /**
     * A player put things into the village stores. Whatever of it answers an open order is noticed: the one the
     * order was for (or whoever is near) thanks him, the village remembers it, and the order shrinks by as much.
     */
    public static void brought(net.minecraft.server.network.ServerPlayerEntity player, Village village,
                               Map<net.minecraft.item.Item, Integer> before, Map<net.minecraft.item.Item, Integer> after) {
        if (!(player.getWorld() instanceof ServerWorld world)) return;
        EnumMap<Needs.Need, Order> b = book(village);
        for (Order o : new ArrayList<>(b.values())) {
            int n = 0;
            for (Map.Entry<net.minecraft.item.Item, Integer> e : after.entrySet()) {
                int diff = e.getValue() - before.getOrDefault(e.getKey(), 0);
                if (diff > 0 && o.what().matches(new net.minecraft.item.ItemStack(e.getKey()))) n += diff;
            }
            if (n <= 0) continue;
            int left = o.amount() - n;
            if (left <= 0) b.remove(o.what());
            else b.put(o.what(), new Order(o.what(), left, o.forWhom(), o.taker(), o.takerName(), o.takenAt()));
            village.pushHearts(player, Math.min(10, 1 + n / 8));
            Remember.note(village, player, "brought");
            Life.news(village, "player_brought", player.getName().getString(), n, net.minecraft.text.Text.translatable("settlements.need.name." + o.what().name().toLowerCase()));
            VillagerEntityMCA thanker = null;
            double bd = 24 * 24;
            for (VillagerEntityMCA r : village.getResidents(world)) {
                if (!r.isAlive() || r.isSleeping()) continue;
                double d = r.squaredDistanceTo(player) / (r.getName().getString().equals(o.forWhom()) ? 4 : 1);
                if (d < bd) {
                    bd = d;
                    thanker = r;
                }
            }
            if (thanker != null) {
                Gaze.at(thanker, player, 30f, 30f);
                Speech.sayNow(thanker, "settlements.say.thanks.brought", player.getName(), n);
            }
        }
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
