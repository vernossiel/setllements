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

    /** The line in the speaker's own grammar: a woman's lines are kept under the same key with ".f" (Russian verbs change). */
    static String voiced(VillagerEntityMCA v, String key) {
        if (!key.startsWith("settlements.say.") && !key.startsWith("settlements.talk.") && !key.startsWith("settlements.react.")) return key;
        if (v.getGenetics().getGender() != net.mca.entity.ai.relationship.Gender.FEMALE) return key;
        // (a line with no woman's form written falls back on the plain one: the key itself was said out loud)
        return net.minecraft.util.Language.getInstance().hasTranslation(key + ".f") ? key + ".f" : key;
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
        v.sendChatMessage(Text.translatable(voiced(v, key), args).formatted(Formatting.WHITE), p);
    }

    /** One of several ways to say the same thing: the base key or base.1 .. base.(n-1). */
    public static void sayAny(VillagerEntityMCA v, String base, int variants, Object... args) {
        int i = v.getRandom().nextInt(variants);
        String k = i == 0 ? base : base + "." + i;
        if (i > 0 && !net.minecraft.util.Language.getInstance().hasTranslation(k)) k = base; // fewer ways written than asked for
        say(v, k, args);
    }

    /** Conversation lines and replies: no gap, but still only when somebody can hear it. */
    public static void sayNow(VillagerEntityMCA v, String key, Object... args) {
        if (v.getWorld().isClient) return;
        PlayerEntity p = v.getWorld().getClosestPlayer(v, HEARING + 4);
        if (p == null) return;
        v.settlementsLastSay = v.getWorld().getTime();
        v.settlementsLastKey = key;
        v.sendChatMessage(Text.translatable(voiced(v, key), args == null ? new Object[0] : args).formatted(Formatting.WHITE), p);
    }

    public static void sayText(VillagerEntityMCA v, Text text) {
        if (v.getWorld().isClient) return;
        PlayerEntity p = v.getWorld().getClosestPlayer(v, HEARING + 6);
        if (p == null) return;
        v.settlementsLastSay = v.getWorld().getTime();
        v.sendChatMessage(text.copy().formatted(Formatting.WHITE), p);
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
