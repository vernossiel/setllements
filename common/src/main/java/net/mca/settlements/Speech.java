package net.mca.settlements;

import net.mca.entity.VillagerEntityMCA;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.village.VillagerProfession;
import net.minecraft.registry.Registries;

/**
 * Villagers say what they are doing, when a player is close enough to hear. A line is only spoken at the moment the
 * action really starts, never repeats within three minutes, and a villager speaks at most once every twenty seconds.
 */
public final class Speech {
    private static final double HEARING = 10;
    private static final long MIN_GAP = 400;
    private static final long REPEAT_GAP = 3600;

    private Speech() {
    }

    public static void say(VillagerEntityMCA v, String key, Object... args) {
        if (v.getWorld().isClient) return;
        long now = v.getWorld().getTime();
        if (now - v.settlementsLastSay < MIN_GAP) return;
        if (key.equals(v.settlementsLastKey) && now - v.settlementsLastSay < REPEAT_GAP) return;
        PlayerEntity p = v.getWorld().getClosestPlayer(v, HEARING);
        if (p == null) return;
        v.settlementsLastSay = now;
        v.settlementsLastKey = key;
        v.sendChatMessage(Text.translatable(key, args).formatted(Formatting.WHITE), p);
    }

    /** "I am a lumberjack now" style line keyed by profession. */
    public static void sayWork(VillagerEntityMCA v) {
        VillagerProfession p = v.getProfession();
        say(v, "settlements.say.work." + Registries.VILLAGER_PROFESSION.getId(p).getPath());
    }

    public static void sayHired(VillagerEntityMCA v) {
        VillagerProfession p = v.getProfession();
        say(v, "settlements.say.hired." + Registries.VILLAGER_PROFESSION.getId(p).getPath());
    }
}
