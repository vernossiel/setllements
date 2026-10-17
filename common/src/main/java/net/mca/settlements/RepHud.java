package net.mca.settlements;

import net.mca.server.world.data.Village;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The player sees his standing with a village change as it changes: a line over the hotbar, the new figure and by how much. */
public final class RepHud {
    private static final Map<UUID, int[]> LAST = new HashMap<>(); // player -> {village id, reputation}

    private RepHud() {
    }

    /** Once a second. */
    public static void tick(ServerWorld world) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            Village v = Village.findNearest(p).orElse(null);
            if (v == null || !v.isWithinBorder(p)) continue;
            int rep = v.getReputation(p);
            int[] last = LAST.get(p.getUuid());
            LAST.put(p.getUuid(), new int[]{v.getId(), rep});
            if (last == null || last[0] != v.getId() || last[1] == rep) continue;
            int d = rep - last[1];
            p.sendMessage(Text.translatable("settlements.rep.change", v.getName(), rep,
                    Text.literal((d > 0 ? "+" : "") + d).formatted(d > 0 ? Formatting.GREEN : Formatting.RED)), true);
        }
    }
}
