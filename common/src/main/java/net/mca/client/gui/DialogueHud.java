package net.mca.client.gui;

import net.mca.network.s2c.JusticeScreenRequest;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * A conversation face to face, drawn over the game like subtitles, the game going on behind it: the speaker's name and
 * words at the bottom of the screen; the answers show while the player looks at him, picked with the mouse wheel and
 * given with the use button (right click). Looking away, he can look about him; the words stay till the time is up.
 */
public final class DialogueHud {
    private static JusticeScreenRequest current;
    private static long opened;
    private static int selected;
    private static final int W = 300;

    private DialogueHud() {
    }

    public static void open(JusticeScreenRequest r) {
        current = r;
        opened = System.currentTimeMillis();
        selected = 0;
    }

    public static void close() {
        current = null;
    }

    private static long left() {
        if (current == null) return 0;
        // with no time limit, the words stay a while for reading and the answers with them
        int secs = current.getSeconds() > 0 ? current.getSeconds() : current.getOptions().length > 0 ? 40 : 5 + linesLength() / 25;
        return secs * 1000L - (System.currentTimeMillis() - opened);
    }

    private static int linesLength() {
        int n = 0;
        for (Text t : current.getLines()) n += t.getString().length();
        return n;
    }

    /** The speaker, if still in this world. */
    private static Entity speaker(MinecraftClient mc) {
        if (current == null || current.getEntity() < 0 || mc.world == null) return null;
        return mc.world.getEntityById(current.getEntity());
    }

    /** Looking at the one who speaks (or there is nobody to look at): the answers are up. */
    private static boolean facing(MinecraftClient mc) {
        if (current == null || mc.player == null) return false;
        Entity e = speaker(mc);
        if (e == null) return current.getEntity() < 0;
        if (mc.targetedEntity == e) return true;
        if (mc.player.squaredDistanceTo(e) > 10 * 10) return false;
        Vec3d to = e.getEyePos().subtract(mc.player.getEyePos()).normalize();
        return mc.player.getRotationVec(1f).dotProduct(to) > 0.94; // within some twenty degrees
    }

    private static boolean answering(MinecraftClient mc) {
        return current != null && mc.currentScreen == null && current.getOptions().length > 0 && facing(mc);
    }

    /** The mouse wheel, while the answers are up: the choice moves instead of the hotbar. */
    public static boolean scroll(double amount) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!answering(mc)) return false;
        int n = current.getOptions().length;
        selected = Math.floorMod(selected + (amount > 0 ? -1 : 1), n);
        return true;
    }

    /** The use button, while the answers are up: the chosen one is said. */
    public static boolean use() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!answering(mc) || mc.player == null) return false;
        String o = current.getOptions()[MathHelper.clamp(selected, 0, current.getOptions().length - 1)];
        mc.player.networkHandler.sendChatCommand("settle " + o);
        close();
        return true;
    }

    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (current == null || mc.options.hudHidden) return;
        if (left() <= 0) {
            close();
            return;
        }
        TextRenderer tr = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        List<OrderedText> lines = new ArrayList<>();
        for (Text l : current.getLines()) lines.addAll(l.getString().isEmpty() ? List.of(OrderedText.EMPTY) : tr.wrapLines(l, W - 16));
        int maxLines = Math.max(3, (sh - 140) / 10);
        if (lines.size() > maxLines) lines = lines.subList(0, maxLines);
        boolean answers = answering(mc);
        List<Text> labels = current.getLabels();
        int optH = current.getOptions().length == 0 ? 0 : answers ? labels.size() * 11 + 6 : 14;
        int h = 16 + lines.size() * 10 + optH + 6;
        int left = sw / 2 - W / 2, top = sh - 62 - h;
        ctx.fill(left, top, left + W, top + h, 0xA0101010);
        ctx.fill(left, top, left + W, top + 1, 0xC0B08A4A);
        ctx.drawTextWithShadow(tr, current.getSpeaker().copy().formatted(Formatting.GOLD), left + 8, top + 5, 0xFFFFFF);
        if (current.getSeconds() > 0) {
            // the time to answer, as a bar running down under the name
            float f = MathHelper.clamp(left() / (current.getSeconds() * 1000f), 0, 1);
            ctx.fill(left + 8, top + 15, left + 8 + (int) ((W - 16) * f), top + 16, f < 0.3f ? 0xFFD04040 : 0xFFB08A4A);
        }
        int y = top + 19;
        for (OrderedText line : lines) {
            ctx.drawTextWithShadow(tr, line, left + 8, y, 0xE8E0D0);
            y += 10;
        }
        if (current.getOptions().length == 0) return;
        y += 4;
        if (!answers) {
            Text hint = Text.translatable("settlements.dialogue.look", current.getSpeaker()).formatted(Formatting.GRAY, Formatting.ITALIC);
            ctx.drawTextWithShadow(tr, hint, left + 8, y, 0xAAAAAA);
            return;
        }
        for (int i = 0; i < labels.size(); i++) {
            boolean sel = i == selected;
            if (sel) ctx.fill(left + 4, y - 1, left + W - 4, y + 10, 0x40B08A4A);
            Text t = Text.literal((i + 1) + ". ").append(labels.get(i));
            ctx.drawTextWithShadow(tr, t, left + 10, y, sel ? 0xFFE8B0 : 0xA0A0A0);
            y += 11;
        }
        Text keys = Text.translatable("settlements.dialogue.keys").formatted(Formatting.DARK_GRAY);
        ctx.drawTextWithShadow(tr, keys, left + W - 8 - tr.getWidth(keys), top + 5, 0x808080);
    }
}
