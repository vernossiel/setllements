package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a villager is up to and why, in his own words: every trade writes it down as it goes ("felling a tree - the
 * builder is 41 planks short"). A player who turns on {@code /settle why} sees it for whoever he looks at.
 */
public final class Doing {
    private record Note(String key, Object[] args, long at) {
    }

    private static final Map<UUID, Note> NOTES = new HashMap<>();
    private static final Set<UUID> WATCHING = new HashSet<>();

    private Doing() {
    }

    /** He is now doing this ({@code settlements.doing.*}). */
    public static void set(VillagerEntityMCA v, String key, Object... args) {
        Note n = NOTES.get(v.getUuid());
        if (n != null && n.key().equals(key) && java.util.Arrays.equals(n.args(), args)) return;
        NOTES.put(v.getUuid(), new Note(key, args, v.getWorld().getTime()));
    }

    public static Text describe(VillagerEntityMCA v) {
        Note n = NOTES.get(v.getUuid());
        long now = v.getWorld().getTime();
        if (v.isSleeping()) return Text.translatable("settlements.doing.sleep");
        if (n == null || now - n.at() > 200) return Text.translatable("settlements.doing.idle");
        return Text.translatable(n.key(), n.args());
    }

    /** /settle why: on or off for this player. */
    public static int toggle(ServerPlayerEntity p) {
        boolean on = WATCHING.add(p.getUuid());
        if (!on) WATCHING.remove(p.getUuid());
        p.sendMessage(Text.translatable(on ? "settlements.why.on" : "settlements.why.off"), false);
        return 1;
    }

    /** Once a second: whoever a watching player looks at, what he is doing, over the hotbar. */
    public static void tick(ServerWorld world) {
        if (WATCHING.isEmpty()) return;
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (!WATCHING.contains(p.getUuid())) continue;
            VillagerEntityMCA v = lookedAt(p, 24);
            if (v == null) continue;
            MutableText line = Text.empty().append(v.getName().copy().formatted(Formatting.GOLD)).append(Text.literal(" - ").formatted(Formatting.GRAY))
                    .append(describe(v).copy().formatted(Formatting.WHITE));
            p.sendMessage(line, true);
        }
    }

    private static VillagerEntityMCA lookedAt(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos(), dir = p.getRotationVec(1f), end = eye.add(dir.multiply(range));
        Box box = p.getBoundingBox().stretch(dir.multiply(range)).expand(1);
        EntityHitResult hit = net.minecraft.entity.projectile.ProjectileUtil.raycast(p, eye, end, box, e -> e instanceof VillagerEntityMCA, range * range);
        Entity e = hit == null ? null : hit.getEntity();
        return e instanceof VillagerEntityMCA v ? v : null;
    }
}
