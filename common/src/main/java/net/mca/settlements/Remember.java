package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The village remembers people: who brought materials when the builders were short, who stole, who struck one of
 * them, who gave back what he took. A villager meeting that person brings it up - warmly or not - and the memory
 * fades over the days (a good turn lasts a week or so, a theft or a blow longer). Kept with the village.
 */
public final class Remember {
    private static final Map<String, Integer> LASTS = Map.of("brought", 7, "returned", 5, "stole", 12, "struck", 15, "helped", 7);
    private static final Map<UUID, Long> GREETED = new HashMap<>();

    private Remember() {
    }

    /** Notes what {@code p} did (one of brought, returned, stole, struck, helped). */
    public static void note(Village village, PlayerEntity p, String what) {
        ServerWorld world = village.getWorld();
        if (world == null) return;
        NbtCompound all = village.memories;
        String key = p.getUuidAsString();
        NbtList list = all.getList(key, NbtElement.COMPOUND_TYPE);
        NbtCompound n = new NbtCompound();
        n.putString("what", what);
        n.putLong("day", world.getTimeOfDay() / 24000L);
        list.add(n);
        while (list.size() > 6) list.remove(0);
        all.put(key, list);
        village.markDirty();
    }

    /** The freshest thing still remembered about {@code p}, or null. */
    static String recall(Village village, PlayerEntity p, long day) {
        NbtList list = village.memories.getList(p.getUuidAsString(), NbtElement.COMPOUND_TYPE);
        for (int i = list.size() - 1; i >= 0; i--) {
            NbtCompound n = list.getCompound(i);
            String what = n.getString("what");
            if (day - n.getLong("day") <= LASTS.getOrDefault(what, 5)) return what;
        }
        return null;
    }

    /** Once a second: a villager who meets a remembered player brings it up, once a day. */
    public static void tick(VillagerEntityMCA v, Village village, ServerWorld world) {
        if (village == null || v.isBaby() || v.isSleeping() || Talk.talking(v)) return;
        PlayerEntity p = world.getClosestPlayer(v, 6);
        if (p == null || !v.canSee(p)) return;
        long day = world.getTimeOfDay() / 24000L;
        UUID key = new UUID(v.getUuid().getMostSignificantBits() ^ p.getUuid().getLeastSignificantBits(), day);
        if (GREETED.containsKey(key)) return;
        String what = recall(village, p, day);
        if (what == null) return;
        GREETED.put(key, day);
        if (GREETED.size() > 2000) GREETED.clear();
        Gaze.at(v, p, 30f, 30f);
        Speech.say(v, "settlements.recall." + what + "." + v.getRandom().nextInt(3), p.getName());
    }
}
