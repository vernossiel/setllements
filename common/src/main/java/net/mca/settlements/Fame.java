package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.mca.server.world.data.Village;
import net.mca.server.world.data.VillageManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.Map;
import java.util.UUID;

/**
 * What a village thinks of a player - not one number but how he is known: honest or a thief, peaceable or dangerous,
 * open-handed or a stranger who never gave anything. And besides the village, every villager has his own opinion of him
 * (MCA's hearts, per villager): the one he struck remembers it, his family takes it to heart, those who saw it think the
 * less of him; the one he helped speaks well of him. Opinions travel: two villagers who talk pass on what they think of
 * him (see Talk), and what one village knows its neighbours hear in a few days (see Ledger).
 *
 * <p>Nothing of it is shown in numbers. It shows in how people take him: a wave or a turned back, a word of greeting
 * or a cold one, a guard who wants to see his pockets sooner, the elder's way of speaking of him.
 *
 * <p>All of it happens when something happens - a deed, the end of a conversation, a village's day - never every tick.
 */
public final class Fame {
    public enum Axis { HONEST, PEACE, GENEROUS }

    public enum Deed {
        THEFT(Axis.HONEST, -6), VANDALISM(Axis.HONEST, -3), REFUSED_SEARCH(Axis.HONEST, -3), FREED_ROGUE(Axis.HONEST, -5),
        CLEARED(Axis.HONEST, 3), SERVED(Axis.HONEST, 4), PAID(Axis.HONEST, 2),
        HARASS(Axis.PEACE, -3), ASSAULT(Axis.PEACE, -12), MURDER(Axis.PEACE, -40), INTRUDE(Axis.PEACE, -3), DEFENDED(Axis.PEACE, 6),
        GIFT(Axis.GENEROUS, 3), HELP(Axis.GENEROUS, 4);

        final Axis axis;
        final int amount;

        Deed(Axis axis, int amount) {
            this.axis = axis;
            this.amount = amount;
        }
    }

    private static final int MAX = 100;

    private Fame() {
    }

    private static int[] of(Village village, UUID p) {
        return village.fame.computeIfAbsent(p, k -> new int[Axis.values().length]);
    }

    public static int get(Village village, PlayerEntity p, Axis a) {
        int[] f = village.fame.get(p.getUuid());
        return f == null ? 0 : f[a.ordinal()];
    }

    /**
     * Something he did. {@code who}: the one it was done to or for (may be null); {@code scale}: how much of it (1 for
     * an ordinary case). The village's view moves; the one it touched, his family and whoever saw it change their own
     * opinion of him.
     */
    public static void deed(ServerWorld world, Village village, PlayerEntity p, Deed d, VillagerEntityMCA who, double scale) {
        if (village == null || p == null || p.isSpectator()) return;
        int rounded = (int) Math.round(d.amount * Math.max(0.25, scale));
        // however big the deed, one deed moves the village's view and a man's opinion only so far
        final int n = Math.max(-25, Math.min(25, rounded == 0 ? Integer.signum(d.amount) : rounded));
        int[] f = of(village, p.getUuid());
        f[d.axis.ordinal()] = Math.max(-MAX, Math.min(MAX, f[d.axis.ordinal()] + n));
        village.markDirty();
        if (who != null) {
            opinion(who, p, Math.max(-40, Math.min(40, n * 3))); // done to him: he feels it most
            who.getRelationships().getPartnerUUID().map(world::getEntity).filter(e -> e instanceof VillagerEntityMCA)
                    .ifPresent(e -> opinion((VillagerEntityMCA) e, p, n * 2));
        }
        // those who saw it
        for (VillagerEntityMCA o : world.getEntitiesByClass(VillagerEntityMCA.class, p.getBoundingBox().expand(16, 6, 16),
                o -> o != who && o.isAlive() && !o.isSleeping() && o.canSee(p))) {
            opinion(o, p, n);
        }
    }

    private static void opinion(VillagerEntityMCA v, PlayerEntity p, int by) {
        if (by != 0) v.getVillagerBrain().getMemoriesForPlayer(p).modHearts(by);
    }

    /** His own opinion of the player (MCA hearts). */
    public static int opinion(VillagerEntityMCA v, PlayerEntity p) {
        return v.getVillagerBrain().getMemoriesForPlayer(p).getHearts();
    }

    /** End of a conversation between two villagers: what each thinks of the players they know rubs off on the other a little. */
    public static void gossip(VillagerEntityMCA a, VillagerEntityMCA b) {
        if (!(a.getWorld() instanceof ServerWorld world)) return;
        for (PlayerEntity p : world.getPlayers()) {
            int ha = opinion(a, p), hb = opinion(b, p);
            if (Math.abs(ha) >= 10 && Math.abs(ha) > Math.abs(hb)) opinion(b, p, Integer.signum(ha) * 2);
            else if (Math.abs(hb) >= 10 && Math.abs(hb) > Math.abs(ha)) opinion(a, p, Integer.signum(hb) * 2);
        }
    }

    /**
     * A village's day: what it holds against a player (or for him) fades a little, and what it knows reaches its
     * neighbours within a thousand blocks, weaker, by the road.
     */
    public static void day(ServerWorld world, Village village) {
        if (village.fame.isEmpty()) return;
        for (int[] f : village.fame.values()) {
            for (int i = 0; i < f.length; i++) if (f[i] != 0) f[i] -= Integer.signum(f[i]) * Math.max(1, Math.abs(f[i]) / 30);
        }
        village.fame.values().removeIf(f -> f[0] == 0 && f[1] == 0 && f[2] == 0);
        for (Village other : VillageManager.get(world)) {
            if (other == village || other.getCenter().getSquaredDistance(village.getCenter()) > 1000 * 1000) continue;
            for (Map.Entry<UUID, int[]> e : village.fame.entrySet()) {
                int[] mine = e.getValue(), theirs = of(other, e.getKey());
                for (int i = 0; i < mine.length; i++) {
                    if (Math.abs(mine[i]) > Math.abs(theirs[i]) + 10) theirs[i] += (mine[i] - theirs[i]) / 10;
                }
            }
            other.markDirty();
        }
        village.markDirty();
    }

    /** How the village knows him, in a word for the elder: the strongest thing about him, or null if nothing stands out. */
    public static String word(Village village, PlayerEntity p) {
        int[] f = village.fame.get(p.getUuid());
        if (f == null) return null;
        int best = -1, bv = 0;
        for (int i = 0; i < f.length; i++) {
            if (Math.abs(f[i]) > Math.abs(bv)) {
                bv = f[i];
                best = i;
            }
        }
        if (best < 0 || Math.abs(bv) < 10) return null;
        return "settlements.fame." + Axis.values()[best].name().toLowerCase() + (bv > 0 ? ".good" : ".bad") + (Math.abs(bv) >= 40 ? ".strong" : "");
    }
}
